package org.a1tsign.vds.api

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.a1tsign.vds.buildComponents
import org.a1tsign.vds.crawler.AdapterSelection
import org.a1tsign.vds.discovery.DiscoveryScheduler
import org.a1tsign.vds.discovery.support.DiscoveryAppHarness
import org.a1tsign.vds.discovery.support.TavilyFixtureServer
import org.a1tsign.vds.discovery.support.TavilyReply
import org.a1tsign.vds.discovery.support.tavilyResults
import org.a1tsign.vds.domain.Role
import org.a1tsign.vds.support.AbstractDbTest
import org.a1tsign.vds.support.PostgresSupport
import org.a1tsign.vds.support.capturingLogs
import org.a1tsign.vds.support.loginForToken
import org.a1tsign.vds.support.testAppConfig
import org.a1tsign.vds.support.testAppDb
import org.junit.jupiter.api.io.TempDir
import org.slf4j.event.Level
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The four `/api/v1/admin/discovery` endpoints through the production wiring and real JWT auth. */
class DiscoveryAdminApiTest : AbstractDbTest() {

    private val endpoints = listOf(
        "POST" to "/api/v1/admin/discovery/run",
        "GET" to "/api/v1/admin/discovery/passes",
        "GET" to "/api/v1/admin/discovery/passes/00000000-0000-0000-0000-000000000000",
        "GET" to "/api/v1/admin/discovery/sources",
    )

    private fun json(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    private suspend fun DiscoveryAppHarness.waitForTerminal(token: String, passId: String): JsonObject = withTimeout(10_000) {
        while (true) {
            val body = json(get("/api/v1/admin/discovery/passes/$passId", token).bodyAsText())
            if (body.getValue("status").jsonPrimitive.content != "RUNNING") return@withTimeout body
            delay(20)
        }
        @Suppress("UNREACHABLE_CODE")
        error("unreachable")
    }

    @Test
    fun `disabled by construction -- every discovery field null, no scheduler, and 503 on every endpoint`() = runBlocking {
        val crawlerOnly = testAppConfig(
            extraEnv = mapOf(
                "VDS_CRAWLER_ENABLED" to "true",
                "VDS_CRAWLER_USERNAME" to "crawler-service",
                "VDS_CRAWLER_PASSWORD" to "crawler-password-1",
            ),
        )
        val components = buildComponents(crawlerOnly, PostgresSupport.dataSource, clock, PostgresSupport.database)
        try {
            assertNotNull(components.crawlerRunner)
            assertNull(components.discoverySearchProvider)
            assertNull(components.discoverySearchHttpClient)
            assertNull(components.discoveryLedger)
            assertNull(components.discoveryRunner)
            assertNull(components.discoveryScope)
            assertNull(DiscoveryScheduler.create(crawlerOnly.discovery, components.discoveryRunner))
            assertTrue(Thread.getAllStackTraces().keys.none { it.name == DiscoveryScheduler.THREAD_NAME })
        } finally {
            components.crawlerScope?.cancel()
            components.crawlerCatalogClient?.close()
            components.crawlerFetchHttpClient?.close()
            components.fxHttpClient?.close()
        }

        testAppDb(clock) {
            val admin = loginForToken("bootstrap-admin", "bootstrap-password-1")
            for ((method, path) in endpoints) {
                val response = if (method == "POST") {
                    client.post(path) {
                        header(HttpHeaders.Authorization, "Bearer $admin")
                        contentType(ContentType.Application.Json)
                        setBody("{}")
                    }
                } else {
                    client.get(path) { header(HttpHeaders.Authorization, "Bearer $admin") }
                }
                assertEquals(HttpStatusCode.ServiceUnavailable, response.status, "$method $path")
                assertEquals(503, json(response.bodyAsText()).getValue("status").jsonPrimitive.int)
            }
        }
    }

    @Test
    fun `discovery enabled without the crawler is announced and not constructed`(@TempDir dir: Path) {
        val config = testAppConfig(
            extraEnv = mapOf(
                "VDS_DISCOVERY_ENABLED" to "true",
                "VDS_DISCOVERY_SEARCH_API_KEY" to "tvly-key",
                "VDS_DISCOVERY_STATE_FILE" to dir.resolve("state.json").toString(),
            ),
        )
        lateinit var components: org.a1tsign.vds.AppComponents
        val logs = capturingLogs { components = buildComponents(config, PostgresSupport.dataSource, clock, PostgresSupport.database) }
        components.fxHttpClient?.close()
        assertNull(components.discoveryRunner)
        assertNull(components.discoverySearchHttpClient)
        assertTrue(logs.messagesAt(Level.WARN).any { it.contains("VDS_CRAWLER_ENABLED is false") }, logs.messages().toString())
    }

    @Test
    fun `trigger answers 202 with a passId, history and lookup work, unknown or malformed ids are 404, other roles are refused`(
        @TempDir dir: Path,
    ) = runBlocking {
        TavilyFixtureServer().use { tavily ->
            tavily.onSearch(TavilyReply(body = tavilyResults(Triple("https://fresh.example/", "Fresh VPS", "vps"))))
            DiscoveryAppHarness(clock, tavily.url(), dir.resolve("state.json")).use { app ->
                val admin = app.token(Role.ADMIN)

                val accepted = app.post("/api/v1/admin/discovery/run", admin, """{"maxQueries": 1}""")
                assertEquals(HttpStatusCode.Accepted, accepted.status)
                val passId = json(accepted.bodyAsText()).getValue("passId").jsonPrimitive.content
                val summary = app.waitForTerminal(admin, passId)
                assertEquals("COMPLETED", summary.getValue("status").jsonPrimitive.content, summary.toString())
                assertEquals(1, summary.getValue("candidatesCreated").jsonPrimitive.int)

                val history = Json.parseToJsonElement(app.get("/api/v1/admin/discovery/passes", admin).bodyAsText()).jsonArray
                assertEquals(listOf(passId), history.map { it.jsonObject.getValue("passId").jsonPrimitive.content })
                assertEquals(HttpStatusCode.NotFound, app.get("/api/v1/admin/discovery/passes/${java.util.UUID.randomUUID()}", admin).status)
                assertEquals(HttpStatusCode.NotFound, app.get("/api/v1/admin/discovery/passes/not-a-uuid", admin).status)
                assertEquals(
                    HttpStatusCode.BadRequest,
                    app.post("/api/v1/admin/discovery/run", admin, """{"maxQueries": 0}""").status,
                )

                for (role in listOf(Role.VIEWER, Role.EDITOR)) {
                    val token = app.token(role)
                    for ((method, path) in endpoints) {
                        val status = if (method == "POST") app.post(path, token).status else app.get(path, token).status
                        assertEquals(HttpStatusCode.Forbidden, status, "$role $method $path")
                    }
                }
                assertTrue(app.http.get("${app.baseUrl}/api/v1/admin/discovery/passes").status == HttpStatusCode.Unauthorized)
            }
        }
    }

    @Test
    fun `a dry run searches but writes nothing and reports the candidates it would have created`(@TempDir dir: Path) = runBlocking {
        TavilyFixtureServer().use { tavily ->
            tavily.onSearch(
                TavilyReply(
                    body = tavilyResults(
                        Triple("https://plan-a.example/", "Plan A VPS", "vps"),
                        Triple("https://plan-b.example/", "Plan B VPS", "vps"),
                    ),
                ),
            )
            DiscoveryAppHarness(clock, tavily.url(), dir.resolve("state.json")).use { app ->
                val admin = app.token(Role.ADMIN)
                val passId = json(app.post("/api/v1/admin/discovery/run", admin, """{"dryRun": true}""").bodyAsText())
                    .getValue("passId").jsonPrimitive.content
                val summary = app.waitForTerminal(admin, passId)

                assertTrue(summary.getValue("dryRun").jsonPrimitive.boolean)
                assertEquals(0, summary.getValue("candidatesCreated").jsonPrimitive.int)
                assertEquals(1, summary.getValue("creditsSpent").jsonPrimitive.int, "a dry run still spends credits")
                val planned = summary.getValue("plannedCandidates").jsonArray.map { it.jsonObject.getValue("slug").jsonPrimitive.content }
                assertEquals(listOf("plan-a-example", "plan-b-example"), planned)
                val candidates = json(
                    app.get("/api/v1/providers?systemStatus=CANDIDATE", app.token(Role.VIEWER)).bodyAsText(),
                ).getValue("content").jsonArray
                assertTrue(candidates.isEmpty(), "nothing was written: $candidates")
            }
        }
    }

    @Test
    fun `a pass and a crawl run refuse each other with 409 in both directions`(@TempDir dir: Path): Unit = runBlocking {
        TavilyFixtureServer().use { tavily ->
            // The pass sits in its search long enough to hold the slot.
            tavily.onSearch(TavilyReply(body = tavilyResults(), delayMs = 1_500))
            DiscoveryAppHarness(clock, tavily.url(), dir.resolve("state.json")).use { app ->
                val admin = app.token(Role.ADMIN)

                val crawlRunner = assertNotNull(app.components.crawlerRunner)
                val crawlId = assertNotNull(crawlRunner.tryReserve(dryRun = true))
                assertEquals(HttpStatusCode.Conflict, app.post("/api/v1/admin/discovery/run", admin).status)
                crawlRunner.runReserved(crawlId, AdapterSelection(adapterIds = emptyList()))

                val accepted = app.post("/api/v1/admin/discovery/run", admin)
                assertEquals(HttpStatusCode.Accepted, accepted.status)
                assertEquals(HttpStatusCode.Conflict, app.post("/api/v1/admin/crawl/run", admin).status)
                assertEquals(HttpStatusCode.Conflict, app.post("/api/v1/admin/discovery/run", admin).status)
                app.waitForTerminal(admin, json(accepted.bodyAsText()).getValue("passId").jsonPrimitive.content)
            }
        }
    }

    @Test
    fun `sources reports the loaded source and the file that failed to load`(@TempDir dir: Path) = runBlocking {
        val sources = dir.resolve("sources").toFile().apply { mkdirs() }.toPath()
        sources.resolve("acme.json").writeText(
            """
            {"schemaVersion": 1, "id": "acme", "providerSlug": "acme-example", "baseUrl": "https://acme.example",
             "pagePath": "/vps", "enabled": true,
             "provider": {"name": {"selector": "h1.name", "text": true}, "websiteUrl": {"selector": "a.site", "attr": "href"}}}
            """.trimIndent(),
        )
        sources.resolve("broken.json").writeText("""{"schemaVersion": 1, "id": "broken"}""")
        TavilyFixtureServer().use { tavily ->
            DiscoveryAppHarness(
                clock, tavily.url(), dir.resolve("state.json"), mapOf("VDS_DISCOVERY_SOURCES_DIR" to sources.toString()),
            ).use { app ->
                val response = app.get("/api/v1/admin/discovery/sources", app.token(Role.ADMIN))
                assertEquals(HttpStatusCode.OK, response.status)
                val body = json(response.bodyAsText())
                val loaded = body.getValue("loaded").jsonArray.single().jsonObject
                assertEquals("acme", loaded.getValue("id").jsonPrimitive.content)
                assertEquals("acme-example", loaded.getValue("providerSlug").jsonPrimitive.content)
                assertTrue(loaded.getValue("enabled").jsonPrimitive.boolean)
                assertEquals("acme.json", loaded.getValue("file").jsonPrimitive.content)
                assertTrue(loaded.containsKey("loadedAt"))
                val failed = body.getValue("failed").jsonArray.single().jsonObject
                assertEquals("broken.json", failed.getValue("file").jsonPrimitive.content)
                assertTrue(failed.getValue("errors").jsonArray.isNotEmpty())
            }
        }
    }
}
