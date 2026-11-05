package org.a1tsign.vds.api

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.auth.authenticate
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.a1tsign.vds.api.routes.crawlerRoutes
import org.a1tsign.vds.auth.installBearerJwtAuth
import org.a1tsign.vds.config.ConfigurationException
import org.a1tsign.vds.config.CrawlerConfig
import org.a1tsign.vds.config.HttpConfig
import org.a1tsign.vds.buildComponents
import org.a1tsign.vds.crawler.AdapterEntry
import org.a1tsign.vds.crawler.AdapterRegistry
import org.a1tsign.vds.crawler.AdapterSelection
import org.a1tsign.vds.crawler.CrawlerRunner
import org.a1tsign.vds.crawler.ProviderAdapter
import org.a1tsign.vds.crawler.SnapshotValidator
import org.a1tsign.vds.crawler.support.CrawlerAppHarness
import org.a1tsign.vds.crawler.support.StubPageFetcher
import org.a1tsign.vds.crawler.support.sleepingAdapter
import org.a1tsign.vds.crawler.support.snapshotAdapter
import org.a1tsign.vds.crawler.support.stubSnapshot
import org.a1tsign.vds.crawler.support.stubTariff
import org.a1tsign.vds.crawler.support.testCrawlerConfig
import org.a1tsign.vds.crawler.write.ApiCrawlWriter
import org.a1tsign.vds.crawler.write.CatalogApiClient
import org.a1tsign.vds.crawler.write.CrawlWriter
import org.a1tsign.vds.crawler.write.DryRunWriter
import org.a1tsign.vds.crawler.write.Reconciler
import org.a1tsign.vds.domain.Role
import org.a1tsign.vds.domain.UserStatus
import org.a1tsign.vds.support.AbstractDbTest
import org.a1tsign.vds.support.PostgresSupport
import org.a1tsign.vds.support.UserFixture
import org.a1tsign.vds.support.loginForToken
import org.a1tsign.vds.support.testAppConfig
import org.a1tsign.vds.support.testAppDb
import java.math.BigDecimal
import java.time.Duration
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The four `/api/v1/admin/crawl` endpoints, end to end through the real Ktor routing and real
 * JWT auth -- `AdapterRegistry.production()` ships zero adapters until vds-002.8, so every scenario
 * that needs an adapter builds its own small [AdapterRegistry]/[CrawlerRunner] (same pattern as
 * `CrawlerRunnerTest`) and mounts [crawlerRoutes] directly instead of going through
 * `AppComponents.buildComponents`'s own (necessarily empty) registry.
 */
class CrawlerAdminApiTest : AbstractDbTest() {
    private fun auth(token: String) = HttpHeaders.Authorization to "Bearer $token"

    private fun extractRunId(response: HttpResponse): String = runBlocking {
        Json.parseToJsonElement(response.bodyAsText()).jsonObject.getValue("runId").jsonPrimitive.content
    }

    /** Mints a token for a freshly created user with [role], bypassing HTTP login entirely. */
    private fun CrawlerAppHarness.token(role: Role): String {
        val user = UserFixture(components.database, components.userRepository, components.passwordHasher)
            .create("crawl-${role.name.lowercase()}-${System.nanoTime()}", "irrelevant-password-1", setOf(role), UserStatus.ACTIVE, clock.instant())
        return components.jwtService.issue(user.user.id, user.user.tokenVersion).token
    }

    /** One adapter, a registry/runner/scope around it, and the three admin tokens. */
    private class Harness(
        val registry: AdapterRegistry,
        val runner: CrawlerRunner,
        val scope: CoroutineScope,
        val crawlerConfig: CrawlerConfig,
        val admin: String,
        val viewer: String,
        val editor: String,
        private val catalogClient: CatalogApiClient,
        val adminRunJob: AtomicReference<Job?> = AtomicReference(null),
    ) : AutoCloseable {
        override fun close() {
            scope.cancel()
            catalogClient.close()
        }
    }

    private fun buildHarness(
        appHarness: CrawlerAppHarness,
        vararg adapters: ProviderAdapter,
        configuredAdapterIds: List<String> = emptyList(),
        includedByDefault: Boolean = true,
    ): Harness {
        val catalogClient = CatalogApiClient(appHarness.crawlerConfig(), appHarness.clock)
        val registry = AdapterRegistry(adapters.map { AdapterEntry(it, includedByDefault = includedByDefault) })
        val writerFactory: (Boolean) -> CrawlWriter = { dryRun -> if (dryRun) DryRunWriter() else ApiCrawlWriter(catalogClient) }
        val cfg = testCrawlerConfig(adapters = configuredAdapterIds)
        val runner = CrawlerRunner(
            config = cfg,
            registry = registry,
            fetcher = StubPageFetcher(),
            validator = SnapshotValidator(appHarness.clock),
            reconciler = Reconciler(catalogClient),
            clock = appHarness.clock,
            writerFactory = writerFactory,
            adapterTimeoutMs = 20_000,
            runTimeoutMs = 60_000,
        )
        return Harness(
            registry = registry,
            runner = runner,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            crawlerConfig = cfg,
            admin = appHarness.token(Role.ADMIN),
            viewer = appHarness.token(Role.VIEWER),
            editor = appHarness.token(Role.EDITOR),
            catalogClient = catalogClient,
        )
    }

    private fun crawlApp(
        appHarness: CrawlerAppHarness,
        harness: Harness,
        block: suspend ApplicationTestBuilder.() -> Unit,
    ) = testApplication {
        environment { config = MapApplicationConfig() }
        application {
            installApiPlugins(HttpConfig(port = 0, corsAllowedOrigins = emptyList()), appHarness.clock)
            installBearerJwtAuth(appHarness.components.authService)
            routing {
                authenticate("bearer-jwt") {
                    crawlerRoutes(harness.registry, harness.runner, harness.scope, harness.crawlerConfig, harness.adminRunJob)
                }
            }
        }
        block()
    }

    private suspend fun ApplicationTestBuilder.waitForTerminal(token: String, runId: String): JsonObject =
        withTimeout(10_000) {
            while (true) {
                val response = client.get("/api/v1/admin/crawl/runs/$runId") { header(auth(token).first, auth(token).second) }
                val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
                if (body.getValue("status").jsonPrimitive.content != "RUNNING") return@withTimeout body
                delay(20)
            }
            @Suppress("UNREACHABLE_CODE")
            error("unreachable")
        }

    // ---------- 1. disabled by default ----------

    @Test
    fun `disabled by default -- 503 through the envelope, and no crawler component is constructed`() = runBlocking {
        val components = org.a1tsign.vds.buildComponents(testAppConfig(), PostgresSupport.dataSource, clock, PostgresSupport.database)
        assertNull(components.crawlerRegistry)
        assertNull(components.crawlerRunner)
        assertNull(components.crawlerScope)
        assertNull(components.crawlerCatalogClient)
        assertNull(components.crawlerFetchHttpClient)

        testAppDb(clock) {
            val admin = loginForToken("bootstrap-admin", "bootstrap-password-1")
            val run = client.post("/api/v1/admin/crawl/run") {
                header(auth(admin).first, auth(admin).second)
                contentType(ContentType.Application.Json)
                setBody("{}")
            }
            assertEquals(HttpStatusCode.ServiceUnavailable, run.status)

            // Consistent with GET /adapters below: both 503 while disabled, never one 503 and the
            // other an empty 200.
            val runs = client.get("/api/v1/admin/crawl/runs") { header(auth(admin).first, auth(admin).second) }
            assertEquals(HttpStatusCode.ServiceUnavailable, runs.status)

            val adapters = client.get("/api/v1/admin/crawl/adapters") { header(auth(admin).first, auth(admin).second) }
            assertEquals(HttpStatusCode.ServiceUnavailable, adapters.status)

            // No regression: the rest of the (disabled-crawler) app behaves exactly as before.
            assertEquals(HttpStatusCode.OK, client.get("/health").status)
        }
    }

    // ---------- 2. trigger ----------

    @Test
    fun `trigger starts a run visible immediately, which reaches a terminal state with counters`() = runBlocking {
        CrawlerAppHarness(clock).use { appHarness ->
            val slug = "trigger-prov"
            buildHarness(appHarness, snapshotAdapter(slug, stubSnapshot(slug, clock.instant()))).use { harness ->
                crawlApp(appHarness, harness) {
                    val response = client.post("/api/v1/admin/crawl/run") {
                        header(auth(harness.admin).first, auth(harness.admin).second)
                        contentType(ContentType.Application.Json)
                        setBody("{}")
                    }
                    assertEquals(HttpStatusCode.Accepted, response.status)
                    val runId = extractRunId(response)
                    UUID.fromString(runId)

                    val summary = waitForTerminal(harness.admin, runId)
                    assertEquals("COMPLETED", summary.getValue("status").jsonPrimitive.content)
                    val adapterResults = summary.getValue("adapters").jsonArray
                    assertEquals(1, adapterResults.size)
                    assertEquals("SUCCESS", adapterResults[0].jsonObject.getValue("status").jsonPrimitive.content)
                    assertEquals(1, adapterResults[0].jsonObject.getValue("tariffsCreated").jsonPrimitive.content.toInt())
                }
            }
        }
    }

    @Test
    fun `trigger with an absent adapterIds runs the configured VDS_CRAWLER_ADAPTERS selection, not the registry's own includedByDefault entries`() = runBlocking {
        CrawlerAppHarness(clock).use { appHarness ->
            val alpha = snapshotAdapter("alpha", stubSnapshot("alpha", clock.instant()))
            val bravo = snapshotAdapter("bravo", stubSnapshot("bravo", clock.instant()))
            // alpha is the registry's own includedByDefault entry; the operator's configured
            // selection is bravo only -- an absent `adapterIds` in the request body must follow the
            // configured selection, not the registry's hardcoded default.
            CatalogApiClient(appHarness.crawlerConfig(), appHarness.clock).use { catalogClient ->
                val registry = AdapterRegistry(
                    listOf(AdapterEntry(alpha, includedByDefault = true), AdapterEntry(bravo, includedByDefault = false)),
                )
                val cfg = testCrawlerConfig(adapters = listOf("bravo"))
                val runner = CrawlerRunner(
                    config = cfg,
                    registry = registry,
                    fetcher = StubPageFetcher(),
                    validator = SnapshotValidator(appHarness.clock),
                    reconciler = Reconciler(catalogClient),
                    clock = appHarness.clock,
                    writerFactory = { dryRun -> if (dryRun) DryRunWriter() else ApiCrawlWriter(catalogClient) },
                    adapterTimeoutMs = 20_000,
                    runTimeoutMs = 60_000,
                )
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                val admin = appHarness.token(Role.ADMIN)
                try {
                    crawlApp(appHarness, Harness(registry, runner, scope, cfg, admin, admin, admin, catalogClient)) {
                        val response = client.post("/api/v1/admin/crawl/run") {
                            header(auth(admin).first, auth(admin).second)
                            contentType(ContentType.Application.Json)
                            setBody("{}")
                        }
                        assertEquals(HttpStatusCode.Accepted, response.status)
                        val runId = extractRunId(response)

                        val summary = waitForTerminal(admin, runId)
                        val ranAdapterIds = summary.getValue("adapters").jsonArray.map { it.jsonObject.getValue("adapterId").jsonPrimitive.content }
                        assertEquals(listOf("bravo"), ranAdapterIds)
                    }
                } finally {
                    scope.cancel()
                }
            }
        }
    }

    // ---------- 3. concurrency ----------

    @Test
    fun `a run already in progress answers 409, then a third trigger succeeds once it finishes`() = runBlocking {
        CrawlerAppHarness(clock).use { appHarness ->
            val slug = "slow-prov"
            buildHarness(appHarness, sleepingAdapter(slug, Duration.ofMillis(600), stubSnapshot(slug, clock.instant()))).use { harness ->
                crawlApp(appHarness, harness) {
                    val first = client.post("/api/v1/admin/crawl/run") {
                        header(auth(harness.admin).first, auth(harness.admin).second)
                        contentType(ContentType.Application.Json)
                        setBody("{}")
                    }
                    assertEquals(HttpStatusCode.Accepted, first.status)

                    val second = client.post("/api/v1/admin/crawl/run") {
                        header(auth(harness.admin).first, auth(harness.admin).second)
                        contentType(ContentType.Application.Json)
                        setBody("{}")
                    }
                    assertEquals(HttpStatusCode.Conflict, second.status)

                    waitForTerminal(harness.admin, extractRunId(first))

                    val third = client.post("/api/v1/admin/crawl/run") {
                        header(auth(harness.admin).first, auth(harness.admin).second)
                        contentType(ContentType.Application.Json)
                        setBody("{}")
                    }
                    assertEquals(HttpStatusCode.Accepted, third.status)
                    assertNotEquals(extractRunId(first), extractRunId(third))
                    waitForTerminal(harness.admin, extractRunId(third))
                }
            }
        }
    }

    // ---------- 4. dry run ----------

    @Test
    fun `dry run performs no writes and reports dryRun=true with planned operations`() = runBlocking {
        CrawlerAppHarness(clock).use { appHarness ->
            val slug = "dry-run-prov"
            buildHarness(appHarness, snapshotAdapter(slug, stubSnapshot(slug, clock.instant()))).use { harness ->
                crawlApp(appHarness, harness) {
                    val response = client.post("/api/v1/admin/crawl/run") {
                        header(auth(harness.admin).first, auth(harness.admin).second)
                        contentType(ContentType.Application.Json)
                        setBody("""{"dryRun":true}""")
                    }
                    assertEquals(HttpStatusCode.Accepted, response.status)
                    val summary = waitForTerminal(harness.admin, extractRunId(response))
                    assertTrue(summary.getValue("dryRun").jsonPrimitive.boolean)
                    assertTrue(summary.getValue("plannedOperations").jsonArray.isNotEmpty())
                }

                // The catalog was never touched: the provider this dry run "created" does not exist.
                CatalogApiClient(appHarness.crawlerConfig(), appHarness.clock).use { client ->
                    assertNull(client.findProviderBySlug(slug))
                }
            }
        }
    }

    // ---------- 5. maxOutOfStockRatio override ----------

    @Test
    fun `maxOutOfStockRatio is validated and an override changes the mass-change guard's outcome`() = runBlocking {
        CrawlerAppHarness(clock).use { appHarness ->
            val slug = "ratio-prov"
            val seedSnapshot = stubSnapshot(slug, clock.instant(), tariffs = listOf(stubTariff("A"), stubTariff("B"), stubTariff("C")))
            val flipSnapshot = stubSnapshot(slug, clock.instant(), tariffs = emptyList())
            val callCount = java.util.concurrent.atomic.AtomicInteger(0)
            val adapter = object : ProviderAdapter {
                override val id = "ratio-adapter"
                override val providerSlug = slug
                override val sourceKind = org.a1tsign.vds.crawler.SourceKind.HTML
                override suspend fun fetch(ctx: org.a1tsign.vds.crawler.CrawlContext) =
                    if (callCount.getAndIncrement() == 0) seedSnapshot else flipSnapshot
            }
            buildHarness(appHarness, adapter).use { harness ->
                crawlApp(appHarness, harness) {
                    // Invalid before anything is reserved: a run already in progress or not plays no part.
                    val tooHigh = client.post("/api/v1/admin/crawl/run") {
                        header(auth(harness.admin).first, auth(harness.admin).second)
                        contentType(ContentType.Application.Json)
                        setBody("""{"maxOutOfStockRatio":"1.5"}""")
                    }
                    assertEquals(HttpStatusCode.BadRequest, tooHigh.status)

                    val jsonNumber = client.post("/api/v1/admin/crawl/run") {
                        header(auth(harness.admin).first, auth(harness.admin).second)
                        contentType(ContentType.Application.Json)
                        setBody("""{"maxOutOfStockRatio":0.9}""")
                    }
                    assertEquals(HttpStatusCode.BadRequest, jsonNumber.status)

                    // Run 1: seeds the provider with 3 in-stock tariffs.
                    val seed = client.post("/api/v1/admin/crawl/run") {
                        header(auth(harness.admin).first, auth(harness.admin).second)
                        contentType(ContentType.Application.Json)
                        setBody("{}")
                    }
                    assertEquals(HttpStatusCode.Accepted, seed.status)
                    waitForTerminal(harness.admin, extractRunId(seed))

                    // Run 2: all 3 disappear, above the default 0.5 ratio -> the guard trips, zero writes.
                    val guarded = client.post("/api/v1/admin/crawl/run") {
                        header(auth(harness.admin).first, auth(harness.admin).second)
                        contentType(ContentType.Application.Json)
                        setBody("""{"maxOutOfStockRatio":"0.9"}""")
                    }
                    assertEquals(HttpStatusCode.Accepted, guarded.status)
                    val guardedSummary = waitForTerminal(harness.admin, extractRunId(guarded))
                    val guardedAdapter = guardedSummary.getValue("adapters").jsonArray[0].jsonObject
                    // 3 of 3 flipping exceeds even the 0.9 override (3 <= 0.9*3=2.7 is false): still guarded.
                    assertEquals("FAILED", guardedAdapter.getValue("status").jsonPrimitive.content)
                    assertEquals("MASS_CHANGE_GUARD", guardedAdapter.getValue("failureReason").jsonPrimitive.content)

                    // Run 3: the same disappearance, with ratio=1 -- "exceeds" is strict, so 1.0 always passes.
                    val allowed = client.post("/api/v1/admin/crawl/run") {
                        header(auth(harness.admin).first, auth(harness.admin).second)
                        contentType(ContentType.Application.Json)
                        setBody("""{"maxOutOfStockRatio":"1"}""")
                    }
                    assertEquals(HttpStatusCode.Accepted, allowed.status)
                    val allowedSummary = waitForTerminal(harness.admin, extractRunId(allowed))
                    val allowedAdapter = allowedSummary.getValue("adapters").jsonArray[0].jsonObject
                    assertEquals("SUCCESS", allowedAdapter.getValue("status").jsonPrimitive.content)
                    assertEquals(3, allowedAdapter.getValue("tariffsMarkedOutOfStock").jsonPrimitive.content.toInt())
                }
            }
        }
    }

    // ---------- 6. unknown adapter id ----------

    @Test
    fun `an unknown adapter id in the body is a 400 listing the known ids`() = runBlocking {
        CrawlerAppHarness(clock).use { appHarness ->
            buildHarness(appHarness, snapshotAdapter("known-adapter", stubSnapshot("known-adapter", clock.instant()))).use { harness ->
                crawlApp(appHarness, harness) {
                    val response = client.post("/api/v1/admin/crawl/run") {
                        header(auth(harness.admin).first, auth(harness.admin).second)
                        contentType(ContentType.Application.Json)
                        setBody("""{"adapterIds":["bogus-id"]}""")
                    }
                    assertEquals(HttpStatusCode.BadRequest, response.status)
                    val body = response.bodyAsText()
                    assertTrue(body.contains("bogus-id"))
                    assertTrue(body.contains("known-adapter"))
                }
            }
        }
    }

    @Test
    fun `an unknown adapter id in VDS_CRAWLER_ADAPTERS fails buildComponents at startup`() {
        val cfg = testAppConfig(
            extraEnv = mapOf(
                "VDS_CRAWLER_ENABLED" to "true",
                "VDS_CRAWLER_USERNAME" to "crawler-service",
                "VDS_CRAWLER_PASSWORD" to "crawler-password-1",
                "VDS_CRAWLER_ADAPTERS" to "does-not-exist",
            ),
        )
        // AdapterRegistry.production() ships zero adapters in this increment, so any non-empty
        // selection is unknown by definition -- which is exactly the fail-fast path under test.
        assertFailsWith<ConfigurationException> {
            buildComponents(cfg, PostgresSupport.dataSource, clock, PostgresSupport.database)
        }
    }

    // ---------- 7. authorization ----------

    @Test
    fun `401 without a token, 403 as VIEWER and as EDITOR, success as ADMIN -- on all four endpoints`() = runBlocking {
        CrawlerAppHarness(clock).use { appHarness ->
            buildHarness(appHarness, snapshotAdapter("auth-prov", stubSnapshot("auth-prov", clock.instant()))).use { harness ->
                crawlApp(appHarness, harness) {
                    suspend fun postRun(token: String?) = client.post("/api/v1/admin/crawl/run") {
                        token?.let { header(auth(it).first, auth(it).second) }
                        contentType(ContentType.Application.Json)
                        setBody("{}")
                    }
                    suspend fun getRuns(token: String?) = client.get("/api/v1/admin/crawl/runs") {
                        token?.let { header(auth(it).first, auth(it).second) }
                    }
                    suspend fun getRun(token: String?) = client.get("/api/v1/admin/crawl/runs/${UUID.randomUUID()}") {
                        token?.let { header(auth(it).first, auth(it).second) }
                    }
                    suspend fun getAdapters(token: String?) = client.get("/api/v1/admin/crawl/adapters") {
                        token?.let { header(auth(it).first, auth(it).second) }
                    }

                    for (call in listOf(::postRun, ::getRuns, ::getRun, ::getAdapters)) {
                        assertEquals(HttpStatusCode.Unauthorized, call(null).status)
                        assertEquals(HttpStatusCode.Forbidden, call(harness.viewer).status)
                        assertEquals(HttpStatusCode.Forbidden, call(harness.editor).status)
                    }

                    assertEquals(HttpStatusCode.Accepted, postRun(harness.admin).status)
                    assertEquals(HttpStatusCode.OK, getRuns(harness.admin).status)
                    assertEquals(HttpStatusCode.NotFound, getRun(harness.admin).status)
                    assertEquals(HttpStatusCode.OK, getAdapters(harness.admin).status)
                }
            }
        }
    }

    // ---------- 8. runs/{id} 404s and the 20-entry ring buffer ----------

    @Test
    fun `a random or malformed runId is 404, and 25 runs keep exactly the last 20, newest first`() = runBlocking {
        CrawlerAppHarness(clock).use { appHarness ->
            buildHarness(appHarness).use { harness -> // zero adapters -- every run completes instantly
                crawlApp(appHarness, harness) {
                    val random = client.get("/api/v1/admin/crawl/runs/${UUID.randomUUID()}") {
                        header(auth(harness.admin).first, auth(harness.admin).second)
                    }
                    assertEquals(HttpStatusCode.NotFound, random.status)

                    val malformed = client.get("/api/v1/admin/crawl/runs/not-a-uuid") {
                        header(auth(harness.admin).first, auth(harness.admin).second)
                    }
                    assertEquals(HttpStatusCode.NotFound, malformed.status)

                    val runIds = (1..25).map {
                        harness.runner.runOnce(AdapterSelection(), dryRun = false).runId
                    }

                    val list = client.get("/api/v1/admin/crawl/runs") {
                        header(auth(harness.admin).first, auth(harness.admin).second)
                    }
                    assertEquals(HttpStatusCode.OK, list.status)
                    val ids = Json.parseToJsonElement(list.bodyAsText()).jsonArray.map { it.jsonObject.getValue("runId").jsonPrimitive.content }
                    assertEquals(20, ids.size)
                    // Newest first: the ring buffer kept the last 20 of the 25, in reverse creation order.
                    assertEquals(runIds.takeLast(20).reversed().map { it.toString() }, ids)
                }
            }
        }
    }

    // ---------- 9. adapters list ----------

    @Test
    fun `adapters lists every registered entry, enabled reflecting the configured selection`() = runBlocking {
        CrawlerAppHarness(clock).use { appHarness ->
            val alpha = snapshotAdapter("alpha", stubSnapshot("alpha", clock.instant()))
            val bravo = snapshotAdapter("bravo", stubSnapshot("bravo", clock.instant()))

            // Empty VDS_CRAWLER_ADAPTERS -- only the includedByDefault entry is enabled.
            CatalogApiClient(appHarness.crawlerConfig(), appHarness.clock).use { catalogClient ->
                val registry = AdapterRegistry(
                    listOf(AdapterEntry(alpha, includedByDefault = true), AdapterEntry(bravo, includedByDefault = false)),
                )
                val runner = CrawlerRunner(
                    config = testCrawlerConfig(),
                    registry = registry,
                    fetcher = StubPageFetcher(),
                    validator = SnapshotValidator(appHarness.clock),
                    reconciler = Reconciler(catalogClient),
                    clock = appHarness.clock,
                    writerFactory = { DryRunWriter() },
                )
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                val admin = appHarness.token(Role.ADMIN)
                try {
                    crawlApp(appHarness, Harness(registry, runner, scope, testCrawlerConfig(), admin, admin, admin, catalogClient)) {
                        val response = client.get("/api/v1/admin/crawl/adapters") { header(auth(admin).first, auth(admin).second) }
                        assertEquals(HttpStatusCode.OK, response.status)
                        val entries = Json.parseToJsonElement(response.bodyAsText()).jsonArray.associateBy { it.jsonObject.getValue("id").jsonPrimitive.content }
                        assertEquals(2, entries.size)
                        assertTrue(entries.getValue("alpha").jsonObject.getValue("enabled").jsonPrimitive.boolean)
                        assertTrue(!entries.getValue("bravo").jsonObject.getValue("enabled").jsonPrimitive.boolean)
                    }

                    // An explicit selection flips which one is enabled.
                    crawlApp(appHarness, Harness(registry, runner, scope, testCrawlerConfig(adapters = listOf("bravo")), admin, admin, admin, catalogClient)) {
                        val response = client.get("/api/v1/admin/crawl/adapters") { header(auth(admin).first, auth(admin).second) }
                        val entries = Json.parseToJsonElement(response.bodyAsText()).jsonArray.associateBy { it.jsonObject.getValue("id").jsonPrimitive.content }
                        assertTrue(!entries.getValue("alpha").jsonObject.getValue("enabled").jsonPrimitive.boolean)
                        assertTrue(entries.getValue("bravo").jsonObject.getValue("enabled").jsonPrimitive.boolean)
                    }
                } finally {
                    scope.cancel()
                }
            }
        }
    }

    // ---------- 10. shutdown ----------

    @Test
    fun `the crawler's scope and HTTP clients are cancellable and closeable, as shutdownGracefully relies on`() = runBlocking {
        // Application.kt's shutdownGracefully itself touches process-wide shared state
        // (activeRequests/shuttingDown) that must not leak into the rest of this JVM's test suite,
        // so this exercises the real objects it closes, in the same order, directly -- see the
        // Developer Report for why the private function itself is not invoked via reflection here.
        CrawlerAppHarness(clock).use { appHarness ->
            val components = appHarness.components
            val scope = requireNotNull(components.crawlerScope)
            val catalogClient = requireNotNull(components.crawlerCatalogClient)
            val fetchClient = requireNotNull(components.crawlerFetchHttpClient)

            val inFlight = scope.launch { delay(60_000) }

            scope.cancel()
            catalogClient.close()
            fetchClient.close()

            // join (not just the synchronous isCancelled flip, which Job documents as flipping
            // immediately on cancel() even if the child never got a chance to react) proves
            // cancellation actually propagated and the coroutine really unwound.
            inFlight.join()
            assertTrue(inFlight.isCancelled)
            assertTrue(inFlight.isCompleted)
        }
    }
}
