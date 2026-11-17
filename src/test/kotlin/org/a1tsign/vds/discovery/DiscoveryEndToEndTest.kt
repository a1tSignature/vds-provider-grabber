package org.a1tsign.vds.discovery

import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.a1tsign.vds.db.LocationTable
import org.a1tsign.vds.db.ProviderLegalInfoTable
import org.a1tsign.vds.db.ProviderPaymentMethodTable
import org.a1tsign.vds.db.ProviderRepository
import org.a1tsign.vds.db.ProviderTable
import org.a1tsign.vds.db.TariffTable
import org.a1tsign.vds.discovery.model.DiscoveryPassSummary
import org.a1tsign.vds.discovery.model.PassFailureReason
import org.a1tsign.vds.discovery.model.PassStatus
import org.a1tsign.vds.discovery.model.RejectionReason
import org.a1tsign.vds.discovery.support.DiscoveryAppHarness
import org.a1tsign.vds.discovery.support.TavilyFixtureServer
import org.a1tsign.vds.discovery.support.TavilyReply
import org.a1tsign.vds.discovery.support.tavilyResults
import org.a1tsign.vds.domain.ProviderStatus
import org.a1tsign.vds.domain.Role
import org.a1tsign.vds.domain.SystemStatus
import org.a1tsign.vds.support.AbstractDbTest
import org.a1tsign.vds.support.capturingLogs
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A pass through the production wiring: the real `TavilySearchProvider` against a loopback
 * Tavily-shaped fixture, the real `CatalogApiClient` against the real application on a real port.
 */
class DiscoveryEndToEndTest : AbstractDbTest() {

    @Test
    fun `search hits become public CANDIDATE rows, deduplicated against every status and filtered into buckets`(
        @TempDir dir: Path,
    ) {
        val longDomain = "a".repeat(60) + ".example"
        TavilyFixtureServer().use { tavily ->
            tavily.onUsage(TavilyReply(status = 500))
            tavily.onSearch(
                TavilyReply(
                    body = tavilyResults(
                        // a valid hit with a very low score: the score is not a threshold
                        Triple("https://newhost.example/vps/plans?ref=x", "NewHost VPS", "Cheap NVMe VPS from 3 EUR"),
                        Triple("https://www.example.test/vps", "Example VPS", "vps"), // websiteUrl host of a non-domain slug
                        Triple("https://cand.example/x", "Cand VPS", "vps"), // CANDIDATE
                        Triple("https://rej.example/", "Rej VPS", "vps"), // REJECTED
                        Triple("https://old.reddit.com/r/vps", "vps thread", "vps"),
                        Triple("https://blog.example/", "Cooking", "recipes"),
                        Triple("not a url", "vps", "vps"),
                        Triple("https://$longDomain/", "Long VPS", "vps"),
                        Triple("https://second.example/", null, "a vps provider"),
                        score = 0.01,
                    ),
                ),
            )
            DiscoveryAppHarness(clock, tavily.url(), dir.resolve("state.json")).use { app ->
                app.seedProvider("example-static", "https://example.test", SystemStatus.CONFIGURED)
                app.seedProvider("cand-example", "https://cand.example", SystemStatus.CANDIDATE)
                app.seedProvider("rej-example", "https://rej.example/", SystemStatus.REJECTED)
                val runner = assertNotNull(app.components.discoveryRunner)

                lateinit var summary: DiscoveryPassSummary
                val logs = capturingLogs { summary = runBlocking { runner.runOnce() } }

                assertEquals(PassStatus.COMPLETED, summary.status, summary.message)
                assertNull(summary.failureReason, summary.message)
                assertEquals(3, summary.knownProviders)
                assertEquals(9, summary.resultsSeen)
                assertEquals(
                    mapOf(
                        RejectionReason.DENYLIST to 1,
                        RejectionReason.KEYWORD to 1,
                        RejectionReason.MALFORMED_URL to 1,
                        RejectionReason.SLUG_TOO_LONG to 1,
                        RejectionReason.ALREADY_KNOWN to 3,
                        RejectionReason.LISTICLE to 0,
                    ),
                    summary.resultsRejected,
                )
                assertEquals(2, summary.candidatesCreated)
                assertEquals(1, summary.creditsSpent)
                assertNull(summary.vendorCreditsRemaining, "a failed /usage leaves it absent")

                val (newHost, second) = transaction(app.components.database) {
                    val repo = ProviderRepository()
                    listOf("newhost-example", "second-example").map { slug ->
                        val id = ProviderTable.selectAll().where { ProviderTable.slug eq slug }.singleOrNull()?.get(ProviderTable.id)
                        assertNotNull(id?.let(repo::find), "$slug was created")
                    }
                }
                assertEquals("NewHost VPS", newHost.name)
                assertEquals("https://newhost.example/", newHost.websiteUrl)
                assertEquals(ProviderStatus.UNKNOWN, newHost.status)
                assertEquals(SystemStatus.CANDIDATE, newHost.systemStatus)
                val description = assertNotNull(newHost.description)
                assertTrue(description.length <= 1000)
                assertTrue(description.contains("\"vps hosting\""), description)
                assertTrue(description.contains("https://newhost.example/vps/plans?ref=x"), description)
                assertTrue(description.contains("Cheap NVMe VPS from 3 EUR"), description)
                assertEquals("second.example", second.name, "an absent title falls back to the domain")

                val ids = listOf(newHost.id, second.id)
                transaction(app.components.database) {
                    for (table in listOf(ProviderLegalInfoTable.providerId, ProviderPaymentMethodTable.providerId, LocationTable.providerId, TariffTable.providerId)) {
                        for (id in ids) assertEquals(0L, table.table.selectAll().where { table eq id }.count(), "${table.table.tableName} for $id")
                    }
                }

                runBlocking {
                    val viewer = app.token(Role.VIEWER)
                    val default = slugs(app.get("/api/v1/providers?size=100", viewer).bodyAsText())
                    assertTrue(default.containsAll(listOf("newhost-example", "second-example")), "visible by default: $default")
                    val candidates = slugs(app.get("/api/v1/providers?size=100&systemStatus=CANDIDATE", viewer).bodyAsText())
                    assertTrue(candidates.containsAll(listOf("newhost-example", "second-example")), "$candidates")
                }

                // The key travels only in the Authorization header, and never reaches a log line.
                assertTrue(tavily.requests().all { it.query == null && !it.body.contains(DiscoveryAppHarness.API_KEY) })
                assertTrue(logs.messages().none { it.contains(DiscoveryAppHarness.API_KEY) })
                assertTrue(logs.messages().any { it.contains("discovery pass") && it.contains("finished") })
            }
        }
    }

    @Test
    fun `exceeding MAX_KNOWN_PROVIDERS over the real catalog fails the pass without a search`(@TempDir dir: Path) {
        TavilyFixtureServer().use { tavily ->
            tavily.onSearch(TavilyReply(body = tavilyResults(Triple("https://x.example/", "vps", "vps"))))
            DiscoveryAppHarness(
                clock, tavily.url(), dir.resolve("state.json"),
                // page size 100, so 3 rows over a bound of 2 is caught on the first page
                mapOf("VDS_DISCOVERY_MAX_KNOWN_PROVIDERS" to "2"),
            ).use { app ->
                listOf("p1", "p2", "p3").forEach { app.seedProvider(it, "https://$it.example", SystemStatus.CONFIGURED) }

                val summary = runBlocking { app.components.discoveryRunner!!.runOnce() }

                assertEquals(PassFailureReason.KNOWN_SET_TOO_LARGE, summary.failureReason)
                assertEquals(0, summary.creditsSpent)
                assertTrue(tavily.requests().none { it.path == "/search" })
            }
        }
    }

    private fun slugs(page: String): List<String> =
        Json.parseToJsonElement(page).jsonObject.getValue("content").jsonArray.map { it.jsonObject.getValue("slug").jsonPrimitive.content }
}
