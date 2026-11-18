package org.a1tsign.vds.crawler

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.a1tsign.vds.config.crawlerAdapterConfig
import org.a1tsign.vds.crawler.adapters.ExampleFailingAdapter
import org.a1tsign.vds.crawler.adapters.ExampleStaticAdapter
import org.a1tsign.vds.crawler.fetch.FetchLimits
import org.a1tsign.vds.crawler.fetch.KtorPageFetcher
import org.a1tsign.vds.crawler.fetch.crawlerHttpClient
import org.a1tsign.vds.crawler.model.AdapterRunResult
import org.a1tsign.vds.crawler.model.AdapterRunStatus
import org.a1tsign.vds.crawler.model.CrawlRunSummary
import org.a1tsign.vds.crawler.model.FailureReason
import org.a1tsign.vds.crawler.model.RunStatus
import org.a1tsign.vds.crawler.support.CrawlerAppHarness
import org.a1tsign.vds.crawler.support.FixtureHttpServer
import org.a1tsign.vds.crawler.support.StubProviderAdapter
import org.a1tsign.vds.crawler.support.stubSnapshot
import org.a1tsign.vds.crawler.write.ApiCrawlWriter
import org.a1tsign.vds.crawler.write.CatalogApiClient
import org.a1tsign.vds.crawler.write.CatalogTariff
import org.a1tsign.vds.crawler.write.CrawlWriter
import org.a1tsign.vds.crawler.write.DryRunWriter
import org.a1tsign.vds.crawler.write.Reconciler
import org.a1tsign.vds.support.AbstractDbTest
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Run the opt-in reference adapters through the real fixture server, API and database. Verify repeat runs, isolation and dry-run behavior. */
class CrawlerEndToEndTest : AbstractDbTest() {


    @Test
    fun `the reference adapter fills the catalog once and then changes nothing on a second run`() = withCrawler { ctx ->
        val first = ctx.run()

        assertEquals(expectedSummary(ctx, first, firstRun()), normalise(first))
        val providerId = assertNotNull(ctx.client.findProviderBySlug("example-static")).id
        val detail = ctx.client.getProvider(providerId)
        assertEquals("Example Static Hosting", detail.provider.name)
        assertEquals("https://example-static.test/", detail.provider.websiteUrl)
        assertEquals("ACTIVE", detail.provider.status)
        assertNull(detail.provider.description, "the page carries no description, so none is claimed")

        val legal = assertNotNull(detail.legalInfo, "the snapshot declares legal info, so the catalog has it")
        assertEquals("CRAWLER", legal.dataSource)
        assertEquals("LOW", legal.confidence)
        assertEquals("Example Static Hosting GmbH", legal.legalEntityName)
        assertEquals("DE", legal.registrationCountry)
        assertEquals("HRB 123456 B", legal.registrationNumber)
        assertEquals("Beispielstrasse 1, 10115 Berlin", legal.legalAddress)
        assertEquals(ctx.pageUrl, legal.sourceUrl)

        assertEquals(listOf("BANK_WIRE", "VISA_MASTERCARD"), detail.paymentMethods.map { it.method }.sorted())

        assertEquals(
            listOf("DE/Falkenstein/FSN1/Saxony", "FI/Helsinki/HEL1/Uusimaa"),
            detail.locations.map { "${it.countryCode}/${it.city}/${it.datacenterName}/${it.region}" }.sorted(),
        )

        val tariffs = ctx.tariffsByName()
        assertEquals(listOf("Static CX11", "Static CX21", "Static CX51"), tariffs.keys.sorted())
        val cx51 = tariffs.getValue("Static CX51")
        assertEquals("esh-cx51", cx51.externalId)
        assertEquals(8, cx51.vcpuCount)
        assertEquals(32768, cx51.ramMb)
        assertEquals(360, cx51.diskGb)
        assertEquals("SSD", cx51.diskType)
        assertEquals(2, cx51.ipv4Count)
        assertEquals("KVM", cx51.virtualization)
        assertTrue(cx51.isActive && cx51.inStock)

        val locationIds = detail.locations.map { it.id }.sorted()
        tariffs.values.forEach { tariff ->
            val tariffDetail = ctx.probe.get("/api/v1/tariffs/${tariff.id}", ctx.adminToken).jsonObject
            assertEquals(locationIds, tariffDetail.getValue("locations").jsonArray.map { it.jsonObject.id() }.sorted())
            assertEquals(
                listOf("ANNUAL", "MONTHLY"),
                tariffDetail.getValue("currentPrices").jsonArray.map { it.jsonObject.string("billingPeriod") }.sorted(),
            )
        }
        assertEquals(
            mapOf(
                "Static CX11" to listOf("ANNUAL:48.7000" to "CRAWLER", "MONTHLY:4.5100" to "CRAWLER"),
                "Static CX21" to listOf("ANNUAL:96.9800" to "CRAWLER", "MONTHLY:8.9800" to "CRAWLER"),
                "Static CX51" to listOf("ANNUAL:1299.0000" to "CRAWLER", "MONTHLY:119.0000" to "CRAWLER"),
            ),
            ctx.historyByTariffName(tariffs),
            "6 price rows, every one of them recorded as CRAWLER",
        )

        ctx.harness.clearRecordedCalls()
        clock.advance(Duration.ofHours(1))
        val second = ctx.run()

        assertEquals(expectedSummary(ctx, second, secondRun()), normalise(second))
        assertEquals(0, ctx.harness.countCalls("POST", "/api/v1/providers"), "the provider was created once")
        assertEquals(0, ctx.harness.countCalls("POST", "/api/v1/providers/$providerId/locations"))
        assertEquals(0, ctx.harness.countCalls("POST", "/api/v1/providers/$providerId/tariffs"))
        assertEquals(
            emptyList(),
            ctx.harness.calls().filter { it.method == "POST" && it.path != "/api/v1/auth/login" },
            "nothing at all was created on the second run",
        )
        // Compare all mutations, including repeated link PUTs and idempotent price PUTs, to catch extra writes.
        val expectedNonGetCalls = tariffs.values.flatMap { tariff ->
            listOf("PUT /api/v1/tariffs/${tariff.id}/locations") + List(2) { "PUT /api/v1/tariffs/${tariff.id}/price" }
        }
        assertEquals(
            expectedNonGetCalls.sorted(),
            ctx.harness.calls().filterNot { it.method == "GET" }.map { "${it.method} ${it.path}" }.sorted(),
            "the full non-GET call set of the second run",
        )
        assertEquals(6, ctx.harness.calls().count { it.method == "PUT" && it.path.endsWith("/price") })
        // pricesUnchanged = 6 in the summary above is exactly "every PUT .../price answered 200",
        // since ApiCrawlWriter reports `appended` only for a 201; the history proves the rows.
        assertEquals(tariffs.keys, ctx.tariffsByName().keys, "no tariff was added, renamed or removed")
        assertEquals(
            mapOf(
                "Static CX11" to listOf("ANNUAL:48.7000" to "CRAWLER", "MONTHLY:4.5100" to "CRAWLER"),
                "Static CX21" to listOf("ANNUAL:96.9800" to "CRAWLER", "MONTHLY:8.9800" to "CRAWLER"),
                "Static CX51" to listOf("ANNUAL:1299.0000" to "CRAWLER", "MONTHLY:119.0000" to "CRAWLER"),
            ),
            ctx.historyByTariffName(tariffs),
            "the price history did not grow by a single row",
        )
        tariffs.values.forEach { tariff ->
            assertEquals(
                listOf("ANNUAL", "MONTHLY"),
                ctx.openHistoryRows(tariff.id).map { it.string("billingPeriod") }.sorted(),
                "exactly one open row per tariff and billing period",
            )
        }
    }


    @Test
    fun `the deliberately failing reference adapter fails alone while its neighbours reach the catalog`() {
        val extra = StubProviderAdapter("example-static-2", "example-static-2") { stubSnapshot("example-static-2", clock.instant()) }
        withCrawler(extraEntries = listOf(AdapterEntry(extra, includedByDefault = false))) { ctx ->
            val summary = ctx.run(ExampleStaticAdapter.ID, ExampleFailingAdapter.ID, "example-static-2")

            assertEquals(RunStatus.COMPLETED, summary.status)
            assertEquals(
                listOf(
                    "example-static" to AdapterRunStatus.SUCCESS,
                    "example-failing" to AdapterRunStatus.FAILED,
                    "example-static-2" to AdapterRunStatus.SUCCESS,
                ),
                summary.adapters.map { it.adapterId to it.status },
            )
            val failed = summary.adapters[1]
            assertEquals(FailureReason.ADAPTER_ERROR, failed.failureReason)
            assertTrue(failed.message!!.contains(ExampleFailingAdapter.FAILURE_MESSAGE), failed.message)
            assertNotNull(ctx.client.findProviderBySlug("example-static"))
            assertNotNull(ctx.client.findProviderBySlug("example-static-2"))
            assertNull(ctx.client.findProviderBySlug("example-failing"), "a failed adapter writes nothing")
        }
    }


    @Test
    fun `a dry run writes nothing and predicts exactly what the real run then does`() = withCrawler { ctx ->
        val planned = ctx.run(dryRun = true)

        assertTrue(planned.dryRun)
        assertNull(ctx.client.findProviderBySlug("example-static"), "a dry run touches the catalog not at all")
        assertEquals(
            emptyList(),
            ctx.harness.calls().filter { it.method != "GET" && it.path != "/api/v1/auth/login" },
            "the only calls a dry run makes are the reads it plans from",
        )
        assertEquals(
            listOf(
                "CREATE_PROVIDER", "PUT_LEGAL_INFO", "PUT_PAYMENT_METHODS", "CREATE_LOCATION", "CREATE_LOCATION",
                "CREATE_TARIFF", "CREATE_TARIFF", "CREATE_TARIFF",
                "REPLACE_TARIFF_LOCATIONS", "REPLACE_TARIFF_LOCATIONS", "REPLACE_TARIFF_LOCATIONS",
                "PUT_PRICE", "PUT_PRICE", "PUT_PRICE", "PUT_PRICE", "PUT_PRICE", "PUT_PRICE",
            ),
            planned.plannedOperations.map { it.substringBefore(' ') },
        )

        val real = ctx.run()

        assertEquals(firstRun(), normalise(real).adapters.single().copy(durationMs = 0))
        assertEquals(
            normalise(planned).adapters.single().copy(durationMs = 0),
            normalise(real).adapters.single().copy(durationMs = 0),
            "the preview's counters are the real run's counters",
        )
        assertNotNull(ctx.client.findProviderBySlug("example-static"))
    }


    @Test
    fun `a disallowing robots txt makes the run SKIPPED and leaves the catalog empty`() =
        withCrawler(robots = "User-agent: *\nDisallow: /\n") { ctx ->
            val summary = ctx.run()

            val result = summary.adapters.single()
            assertEquals(AdapterRunStatus.SKIPPED, result.status)
            assertEquals(FailureReason.FETCH, result.failureReason)
            assertEquals(RunStatus.COMPLETED, summary.status, "a site saying no is an answer, not a malfunction")
            assertNull(ctx.client.findProviderBySlug("example-static"))
            assertEquals(emptyList(), ctx.fixture.requestsTo(ExampleStaticAdapter.PAGE_PATH), "the page was never requested")
        }


    @Test
    fun `a changed page appends exactly one price row and closes the previous one at the same instant`() = withCrawler { ctx ->
        ctx.run()
        clock.advance(Duration.ofHours(1))
        ctx.run()

        val tariffId = ctx.tariffsByName().getValue("Static CX11").id
        clock.advance(Duration.ofHours(1))
        ctx.fixture.html(ExampleStaticAdapter.PAGE_PATH, fixturePage("example-static-price-change.html"))
        val third = ctx.run()

        assertEquals(1, third.adapters.single().pricesAppended)
        assertEquals(5, third.adapters.single().pricesUnchanged)
        assertEquals(AdapterRunStatus.SUCCESS, third.adapters.single().status)

        val monthly = ctx.probe.get("/api/v1/tariffs/$tariffId/price-history?billingPeriod=MONTHLY", ctx.adminToken)
            .jsonArray.map { it.jsonObject }
        assertEquals(2, monthly.size, "exactly two history rows for this tariff and period")
        val current = monthly.single { it.optString("effectiveTo") == null }
        val closed = monthly.single { it.optString("effectiveTo") != null }
        assertEquals("5.4900", current.string("amount"))
        assertEquals("4.5100", closed.string("amount"))
        assertEquals(current.string("effectiveFrom"), closed.string("effectiveTo"), "no gap and no overlap")
        assertEquals(listOf("CRAWLER", "CRAWLER"), monthly.map { it.string("dataSource") })
        assertEquals(
            1,
            ctx.openHistoryRows(tariffId).count { it.string("billingPeriod") == "MONTHLY" },
            "still exactly one current MONTHLY price",
        )
        assertEquals(
            listOf("ANNUAL:96.9800" to "CRAWLER", "MONTHLY:8.9800" to "CRAWLER"),
            ctx.historyByTariffName(ctx.tariffsByName()).getValue("Static CX21"),
        )
    }


    private fun firstRun() = result(
        status = AdapterRunStatus.SUCCESS,
        providersCreated = 1,
        locationsCreated = 2,
        tariffsCreated = 3,
        pricesAppended = 6,
    )

    private fun secondRun() = result(status = AdapterRunStatus.SUCCESS, pricesUnchanged = 6)

    private fun result(
        status: AdapterRunStatus,
        providersCreated: Int = 0,
        locationsCreated: Int = 0,
        tariffsCreated: Int = 0,
        pricesAppended: Int = 0,
        pricesUnchanged: Int = 0,
    ) = AdapterRunResult(
        adapterId = ExampleStaticAdapter.ID,
        providerSlug = ExampleStaticAdapter.PROVIDER_SLUG,
        status = status,
        durationMs = 0,
        pagesFetched = 1,
        providersCreated = providersCreated,
        locationsCreated = locationsCreated,
        tariffsCreated = tariffsCreated,
        pricesAppended = pricesAppended,
        pricesUnchanged = pricesUnchanged,
    )

    private fun expectedSummary(ctx: Ctx, actual: CrawlRunSummary, adapter: AdapterRunResult) = CrawlRunSummary(
        runId = actual.runId,
        startedAt = clock.instant(),
        finishedAt = clock.instant(),
        status = RunStatus.COMPLETED,
        dryRun = false,
        adapters = listOf(adapter),
    ).also { assertTrue(ctx.runner.recentRuns().any { run -> run.runId == actual.runId }) }

    /** Durations come from `System.nanoTime()`, so they are the one field a fixed expectation cannot carry. */
    private fun normalise(summary: CrawlRunSummary) =
        summary.copy(adapters = summary.adapters.map { it.copy(durationMs = 0) })


    private class Ctx(
        val harness: CrawlerAppHarness,
        val fixture: FixtureHttpServer,
        val probe: ApiProbe,
        val client: CatalogApiClient,
        val runner: CrawlerRunner,
        val adminToken: String,
        val pageUrl: String,
    ) {
        suspend fun run(vararg adapterIds: String = arrayOf(ExampleStaticAdapter.ID), dryRun: Boolean = false) =
            runner.runOnce(AdapterSelection(adapterIds.toList()), dryRun = dryRun)

        suspend fun tariffsByName(): Map<String, CatalogTariff> {
            val providerId = assertNotNull(client.findProviderBySlug("example-static")).id
            return client.listAllTariffs(providerId).associateBy { it.name }
        }

        /** `"PERIOD:amount" to dataSource` per history row, newest first, for the test's tariffs only. */
        suspend fun historyByTariffName(tariffs: Map<String, CatalogTariff>): Map<String, List<Pair<String, String>>> =
            tariffs.mapValues { (_, tariff) ->
                probe.get("/api/v1/tariffs/${tariff.id}/price-history", adminToken).jsonArray
                    .map { it.jsonObject }
                    .map { "${it.string("billingPeriod")}:${it.string("amount")}" to it.string("dataSource") }
                    .sortedBy { it.first }
            }

        suspend fun openHistoryRows(tariffId: Long): List<JsonObject> =
            probe.get("/api/v1/tariffs/$tariffId/price-history", adminToken).jsonArray
                .map { it.jsonObject }
                .filter { it.optString("effectiveTo") == null }
    }

    /** Use the real API and fixture server. Create the crawler service account over the admin endpoint. */
    private fun withCrawler(
        robots: String = "User-agent: *\nAllow: /\n",
        extraEntries: List<AdapterEntry> = emptyList(),
        block: suspend (Ctx) -> Unit,
    ) = FixtureHttpServer().use { fixture ->
        fixture.robots(body = robots)
        fixture.html(ExampleStaticAdapter.PAGE_PATH, fixturePage("example-static.html"))
        CrawlerAppHarness(
            clock,
            configuredCrawlerUsername = SERVICE_USERNAME,
            configuredCrawlerPassword = SERVICE_PASSWORD,
        ).use { harness ->
            ApiProbe(harness.baseUrl).use { probe ->
                runBlocking {
                    val adminToken = probe.login(BOOTSTRAP_ADMIN, BOOTSTRAP_PASSWORD)
                    // Create the EDITOR through the same admin API used by operators.
                    probe.createUser(adminToken, SERVICE_USERNAME, SERVICE_PASSWORD, "EDITOR")
                    // Start recording after setup so the mutation assertions cover crawler calls only.
                    harness.clearRecordedCalls()
                    val crawlerConfig = harness.crawlerConfig(username = SERVICE_USERNAME, password = SERVICE_PASSWORD)
                        // Politeness is PageFetcherTest's subject; 2 s per page would only slow this down.
                        .copy(hostDelayMs = 50)
                    CatalogApiClient(config = crawlerConfig, clock = clock).use { client ->
                        crawlerHttpClient(FETCH_LIMITS).use { httpClient ->
                            val fetcher = KtorPageFetcher(crawlerConfig, clock, httpClient, FETCH_LIMITS)
                            val writerFactory: (Boolean) -> CrawlWriter = { dryRun ->
                                if (dryRun) DryRunWriter() else ApiCrawlWriter(client)
                            }
                            // Feed the adapter settings through production environment mapping.
                            val env = mapOf(ExampleStaticAdapter.BASE_URL_VARIABLE to fixture.url(""))
                            val runner = CrawlerRunner(
                                config = crawlerConfig,
                                registry = AdapterRegistry(AdapterRegistry.production().entries + extraEntries),
                                fetcher = fetcher,
                                validator = SnapshotValidator(clock),
                                reconciler = Reconciler(client),
                                clock = clock,
                                writerFactory = writerFactory,
                                adapterConfig = { adapterId -> crawlerAdapterConfig(adapterId, env) },
                                pagesFetched = { fetcher.pagesFetched },
                            )
                            block(
                                Ctx(
                                    harness = harness,
                                    fixture = fixture,
                                    probe = probe,
                                    client = client,
                                    runner = runner,
                                    adminToken = adminToken,
                                    pageUrl = fixture.url(ExampleStaticAdapter.PAGE_PATH),
                                ),
                            )
                        }
                    }
                }
            }
        }
    }

    private class ApiProbe(private val baseUrl: String) : AutoCloseable {

        private val client = HttpClient(CIO)

        suspend fun login(username: String, password: String): String {
            val response = client.post("$baseUrl/api/v1/auth/login") {
                contentType(ContentType.Application.Json)
                setBody("""{"username":"$username","password":"$password"}""")
            }
            assertEquals(200, response.status.value, response.bodyAsText())
            return parse(response.bodyAsText()).jsonObject.string("token")
        }

        suspend fun createUser(adminToken: String, username: String, password: String, role: String) {
            val response = client.post("$baseUrl/api/v1/admin/users") {
                header(HttpHeaders.Authorization, "Bearer $adminToken")
                contentType(ContentType.Application.Json)
                setBody("""{"username":"$username","password":"$password","roles":["$role"]}""")
            }
            assertEquals(201, response.status.value, response.bodyAsText())
        }

        suspend fun get(path: String, token: String): JsonElement {
            val response = client.get("$baseUrl$path") { header(HttpHeaders.Authorization, "Bearer $token") }
            assertEquals(200, response.status.value, "GET $path: ${response.bodyAsText()}")
            return parse(response.bodyAsText())
        }

        override fun close() = client.close()
    }

    private companion object {
        const val BOOTSTRAP_ADMIN = "bootstrap-admin"
        const val BOOTSTRAP_PASSWORD = "bootstrap-password-1"
        const val SERVICE_USERNAME = "crawler-e2e"
        const val SERVICE_PASSWORD = "crawler-e2e-password-1"

        /** Loopback: shorter timeouts, production retry budget. */
        val FETCH_LIMITS = FetchLimits(connectTimeoutMs = 2_000, requestTimeoutMs = 5_000, socketTimeoutMs = 5_000)

        val JSON = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

        fun parse(body: String): JsonElement = JSON.parseToJsonElement(body)

        fun fixturePage(name: String): String =
            CrawlerEndToEndTest::class.java.getResource("/crawler/$name")!!.readText()

        fun JsonObject.string(field: String): String = getValue(field).jsonPrimitive.content

        fun JsonObject.optString(field: String): String? = this[field]?.jsonPrimitive?.content

        fun JsonObject.id(): Long = getValue("id").jsonPrimitive.long
    }
}
