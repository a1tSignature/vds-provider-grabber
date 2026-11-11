package org.a1tsign.vds.api

import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.a1tsign.vds.db.ProviderRepository
import org.a1tsign.vds.domain.BillingPeriod
import org.a1tsign.vds.domain.SystemStatus
import org.a1tsign.vds.support.AbstractDbTest
import org.a1tsign.vds.support.CatalogFixture
import org.a1tsign.vds.support.PostgresSupport
import org.a1tsign.vds.support.SearchFixture
import org.a1tsign.vds.support.loginForToken
import org.a1tsign.vds.support.testAppDb
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.math.BigDecimal
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TariffsByProviderApiTest : AbstractDbTest() {
    private val root = "/api/v1"

    private suspend fun ApplicationTestBuilder.getJson(token: String, pathAndQuery: String): JsonObject {
        val response = client.get("$root$pathAndQuery") { bearerAuth(token) }
        assertEquals(HttpStatusCode.OK, response.status, "$pathAndQuery: ${response.bodyAsText()}")
        return Json.parseToJsonElement(response.bodyAsText()).jsonObject
    }

    private suspend fun ApplicationTestBuilder.byProvider(token: String, query: String = ""): JsonObject =
        getJson(token, "/tariffs/by-provider?$query")

    private fun JsonObject.content(): List<JsonObject> = getValue("content").jsonArray.map { it.jsonObject }
    private fun JsonObject.total(): Long = getValue("totalElements").jsonPrimitive.long
    private fun JsonObject.slug(): String = getValue("provider").jsonObject.getValue("slug").jsonPrimitive.content
    private fun JsonObject.slugs(): List<String> = content().map { it.slug() }

    /** `slug: headline tariff name xN`, in response order. */
    private fun JsonObject.headlines(): List<String> = content().map {
        val tariff = it.getValue("cheapestTariff").jsonObject.getValue("name").jsonPrimitive.content
        "${it.slug()}: $tariff x${it.getValue("matchingTariffCount").jsonPrimitive.int}"
    }

    @Test
    fun `each provider is headlined by its cheapest matching current MONTHLY tariff`() = runBlocking {
        testAppDb(clock) {
            val token = loginForToken("bootstrap-admin", "bootstrap-password-1")
            val fixture = SearchFixture(PostgresSupport.database)

            assertEquals(HttpStatusCode.Unauthorized, client.get("$root/tariffs/by-provider").status)

            // wayfarer (ANNUAL-only and unpriced tariffs) and dormant (inactive) match nothing;
            // nimbus's closed 20.00 row and ANNUAL-only tariff do not count; vertex's cheaper
            // tariff without amountUsd matches but yields the headline to the USD-priced one.
            val unfiltered = byProvider(token)
            assertEquals(
                listOf(
                    "search-zephyr: Zephyr Prime x6",
                    "nimbus-cloud: Nimbus Pro x2",
                    "polaris-quasar-net: Polaris Standard x1",
                    "search-vertex: Vertex Core x2",
                ),
                unfiltered.headlines(),
            )
            assertEquals(4L, unfiltered.total(), "providers, not their 11 matching tariffs")
            val zephyrPrice = unfiltered.content().first().getValue("cheapestPrice").jsonObject
            assertEquals("5.0000", zephyrPrice.getValue("amountUsd").jsonPrimitive.content)
            assertEquals("MONTHLY", zephyrPrice.getValue("billingPeriod").jsonPrimitive.content)
            assertTrue("lastConfirmedAt" in zephyrPrice)
            assertEquals(unfiltered, byProvider(token, "billingPeriod=ANNUAL&hasPrice=false"), "undeclared here, so ignored")

            mapOf(
                "providerId=${fixture.polaris.id}" to listOf("polaris-quasar-net: Polaris Standard x1"),
                "country=JP" to listOf("search-zephyr: Zeta Gamma x2"),
                "minVcpu=3" to listOf("nimbus-cloud: Nimbus Pro x1", "search-vertex: Vertex Core x1", "search-zephyr: Zeta Gamma x1"),
                "minRamMb=4096" to listOf(
                    "nimbus-cloud: Nimbus Pro x1", "polaris-quasar-net: Polaris Standard x1",
                    "search-vertex: Vertex Core x1", "search-zephyr: Zeta Gamma x1",
                ),
                "minDiskGb=100" to listOf("nimbus-cloud: Nimbus Pro x1", "search-zephyr: Zeta Beta x2", "search-vertex: Vertex Core x1"),
                "diskType=NVME" to listOf("nimbus-cloud: Nimbus Pro x1", "search-vertex: Vertex Core x1", "search-zephyr: Zeta History x1"),
                "maxPriceUsd=9" to listOf("search-zephyr: Zephyr Prime x1", "nimbus-cloud: Nimbus Pro x1"),
                "paymentMethod=SBP" to listOf("search-vertex: Vertex Core x2"),
                "unmeteredTraffic=false" to listOf("search-zephyr: Zeta Beta x1"),
                "inStock=false" to listOf("polaris-quasar-net: Polaris Standard x1"),
                "activeOnly=false" to listOf(
                    "polaris-quasar-net: Polaris Mini x2", "search-zephyr: Zephyr Prime x6", "search-dormant: Dormant Plan x1",
                    "nimbus-cloud: Nimbus Pro x2", "search-vertex: Vertex Core x2",
                ),
            ).forEach { (query, expected) -> assertEquals(expected, byProvider(token, query).headlines(), query) }

            // diskType=SSD leaves vertex only its tariff without amountUsd.
            val ssdAscending = byProvider(token, "diskType=SSD")
            assertEquals(
                listOf(
                    "search-zephyr: Zephyr Prime x3", "nimbus-cloud: Nimbus Basic x1",
                    "polaris-quasar-net: Polaris Standard x1", "search-vertex: Vertex Edge x1",
                ),
                ssdAscending.headlines(),
            )
            assertFalse("amountUsd" in ssdAscending.content().last().getValue("cheapestPrice").jsonObject)
            assertEquals(
                listOf("polaris-quasar-net", "nimbus-cloud", "search-zephyr", "search-vertex"),
                byProvider(token, "diskType=SSD&sort=priceUsd,desc").slugs(),
                "an absent amountUsd is last in both directions",
            )
            assertEquals(
                listOf("search-zephyr", "nimbus-cloud", "polaris-quasar-net"),
                byProvider(token, "diskType=SSD&maxPriceUsd=1000").slugs(),
            )

            val invalid = "minVcpu=x&diskType=FLOPPY&maxPriceUsd=-1&activeOnly=maybe&country=germany"
            val flat = client.get("$root/tariffs?$invalid") { bearerAuth(token) }
            val grouped = client.get("$root/tariffs/by-provider?$invalid") { bearerAuth(token) }
            assertEquals(HttpStatusCode.BadRequest, grouped.status)
            fun details(body: String) = Json.parseToJsonElement(body).jsonObject.getValue("details").jsonArray
            assertEquals(5, details(grouped.bodyAsText()).size)
            assertEquals(details(flat.bodyAsText()), details(grouped.bodyAsText()))
        }
    }

    @Test
    fun `ties, hidden providers, sorting and paging`() = runBlocking {
        testAppDb(clock) {
            val token = loginForToken("bootstrap-admin", "bootstrap-password-1")
            val catalog = CatalogFixture(PostgresSupport.database)
            val at = Instant.parse("2024-01-01T00:00:00Z")
            fun price(tariffId: Long, usd: String?) = catalog.createTariffPrice(
                tariffId, BigDecimal("4.00"), "USD", BillingPeriod.MONTHLY, at, amountUsd = usd?.let(::BigDecimal),
            )
            fun provider(slug: String, usd: String?, systemStatus: SystemStatus = SystemStatus.CONFIGURED): Long {
                val id = catalog.createProvider(slug, at).id
                price(catalog.createTariff(id, "Only", at).id, usd)
                transaction(PostgresSupport.database) { ProviderRepository().updateSystemStatus(id, systemStatus, at) }
                return id
            }

            // Slugs run against id order so that sort=name and sort=id disagree.
            val tied = catalog.createProvider("delta", at).id
            val first = catalog.createTariff(tied, "First", at)
            val second = catalog.createTariff(tied, "Second", at)
            price(second.id, "4.0000")
            price(first.id, "4.0000")
            provider("charlie", "4.0000")
            provider("bravo", null)
            provider("alpha", "9.0000")
            provider("hidden-candidate", "1.0000", SystemStatus.CANDIDATE)
            provider("hidden-rejected", "1.0000", SystemStatus.REJECTED)

            val byPrice = byProvider(token)
            assertEquals(listOf("delta: First x2", "charlie: Only x1", "alpha: Only x1", "bravo: Only x1"), byPrice.headlines())
            assertEquals(4L, byPrice.total())
            assertEquals(byPrice, byProvider(token, "sort=priceUsd,asc"))
            assertEquals(listOf("alpha", "delta", "charlie", "bravo"), byProvider(token, "sort=priceUsd,desc").slugs())
            assertEquals(listOf("alpha", "bravo", "charlie", "delta"), byProvider(token, "sort=name").slugs())
            assertEquals(listOf("alpha", "bravo", "charlie", "delta"), byProvider(token, "sort=id,desc").slugs())

            val pages = (0..2).map { byProvider(token, "size=2&page=$it") }
            assertEquals(byPrice.slugs(), pages.flatMap { it.slugs() })
            assertEquals(listOf(4L, 4L, 4L), pages.map { it.total() })
            assertEquals(2, pages[0].getValue("totalPages").jsonPrimitive.int)

            val unknownSort = client.get("$root/tariffs/by-provider?sort=ramMb") { bearerAuth(token) }
            assertEquals(HttpStatusCode.BadRequest, unknownSort.status)
            assertTrue("allowed: id, name, priceUsd" in unknownSort.bodyAsText(), unknownSort.bodyAsText())
            assertEquals(HttpStatusCode.BadRequest, client.get("$root/tariffs/by-provider?size=101") { bearerAuth(token) }.status)
        }
    }

    @Test
    fun `expanding a provider row lists exactly its matching tariffs, headline first`() = runBlocking {
        testAppDb(clock) {
            val token = loginForToken("bootstrap-admin", "bootstrap-password-1")
            SearchFixture(PostgresSupport.database)

            var checked = 0
            listOf("", "diskType=SSD", "minRamMb=2048", "country=BR", "activeOnly=false", "maxPriceUsd=12&minVcpu=2").forEach { filters ->
                byProvider(token, filters).content().forEach { element ->
                    val providerId = element.getValue("provider").jsonObject.getValue("id").jsonPrimitive.long
                    val expansion = getJson(
                        token,
                        "/tariffs?providerId=$providerId&$filters&billingPeriod=MONTHLY&hasPrice=true&sort=priceUsd&size=100",
                    )
                    val context = "'$filters' provider ${element.slug()}"
                    assertEquals(element.getValue("matchingTariffCount").jsonPrimitive.long, expansion.total(), context)
                    assertEquals(element.getValue("cheapestTariff"), expansion.content().first().getValue("tariff"), context)
                    assertEquals(element.getValue("cheapestPrice"), expansion.content().first().getValue("currentPrice"), context)
                    checked++
                }
            }
            assertTrue(checked >= 15, "the fixture must exercise several providers per filter set, got $checked")
        }
    }

    @Test
    fun `hasPrice on the flat search splits tariffs by a current price of the requested period`() = runBlocking {
        testAppDb(clock) {
            val token = loginForToken("bootstrap-admin", "bootstrap-password-1")
            val zephyr = SearchFixture(PostgresSupport.database).zephyr.id
            suspend fun names(query: String): List<String> = getJson(token, "/tariffs?providerId=$zephyr&size=50&$query").content()
                .map { it.getValue("tariff").jsonObject.getValue("name").jsonPrimitive.content }

            val all = names("sort=name")
            assertEquals(
                listOf("Zephyr Prime", "Zeta Alpha", "Zeta Beta", "Zeta Delta", "Zeta Empty", "Zeta Epsilon", "Zeta Gamma", "Zeta History"),
                all,
                "an absent hasPrice filters nothing",
            )
            assertEquals(listOf("Zeta Delta", "Zeta Empty"), names("sort=name&hasPrice=false"))
            assertEquals(all - listOf("Zeta Delta", "Zeta Empty"), names("sort=name&hasPrice=true"))
            assertEquals(listOf("Zeta History"), names("billingPeriod=ANNUAL&hasPrice=true"))
            val invalid = client.get("$root/tariffs?hasPrice=yes") { bearerAuth(token) }
            assertEquals(HttpStatusCode.BadRequest, invalid.status)
        }
    }
}
