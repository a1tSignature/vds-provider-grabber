package org.a1tsign.vds.discovery.support

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO as ClientCIO
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import kotlinx.coroutines.cancel
import org.a1tsign.vds.AppComponents
import org.a1tsign.vds.auth.BootstrapAdmin
import org.a1tsign.vds.auth.PasswordHasher
import org.a1tsign.vds.buildComponents
import org.a1tsign.vds.config.loadConfig
import org.a1tsign.vds.crawler.support.closedLoopbackPort
import org.a1tsign.vds.db.ProviderRepository
import org.a1tsign.vds.domain.Provider
import org.a1tsign.vds.domain.ProviderStatus
import org.a1tsign.vds.domain.Role
import org.a1tsign.vds.domain.SystemStatus
import org.a1tsign.vds.domain.UserStatus
import org.a1tsign.vds.module
import org.a1tsign.vds.support.MutableTestClock
import org.a1tsign.vds.support.PostgresSupport
import org.a1tsign.vds.support.StubCurrencyRateProvider
import org.a1tsign.vds.support.UserFixture
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.nio.file.Path

/** Serve the production crawler and discovery wiring on loopback against the shared test database. */
class DiscoveryAppHarness(
    val clock: MutableTestClock,
    searchEndpoint: String,
    val stateFile: Path,
    extraEnv: Map<String, String> = emptyMap(),
) : AutoCloseable {

    private val port = closedLoopbackPort()

    val baseUrl = "http://127.0.0.1:$port"

    val components: AppComponents = buildComponents(
        loadConfig(
            buildMap {
                put("VDS_DB_URL", PostgresSupport.jdbcUrl)
                put("VDS_DB_USER", PostgresSupport.username)
                put("VDS_DB_PASSWORD", PostgresSupport.password)
                put("VDS_JWT_SECRET", "test-jwt-secret-012345678901234567890123456789")
                put("VDS_HTTP_PORT", port.toString())
                put("VDS_CRAWLER_ENABLED", "true")
                put("VDS_CRAWLER_API_BASE_URL", baseUrl)
                put("VDS_CRAWLER_USERNAME", SERVICE_USERNAME)
                put("VDS_CRAWLER_PASSWORD", SERVICE_PASSWORD)
                put("VDS_DISCOVERY_ENABLED", "true")
                put("VDS_DISCOVERY_SEARCH_ENDPOINT", searchEndpoint)
                put("VDS_DISCOVERY_SEARCH_API_KEY", API_KEY)
                put("VDS_DISCOVERY_STATE_FILE", stateFile.toString())
                put("VDS_DISCOVERY_QUERIES", "vps hosting")
                put("VDS_DISCOVERY_QUERY_DELAY_MS", "1")
                putAll(extraEnv)
            },
        ),
        PostgresSupport.dataSource,
        clock,
        PostgresSupport.database,
        PasswordHasher(),
        StubCurrencyRateProvider(Result.failure(IllegalStateException("FX is not exercised here"))),
    )

    private val server: EmbeddedServer<*, *> = embeddedServer(CIO, port = port, host = "127.0.0.1") { module(components) }

    val http = HttpClient(ClientCIO) { expectSuccess = false }

    init {
        BootstrapAdmin(components.database, components.userRepository, components.passwordHasher)
            .ensure(components.config.bootstrapAdmin, clock.instant())
        UserFixture(components.database, components.userRepository, components.passwordHasher)
            .create(SERVICE_USERNAME, SERVICE_PASSWORD, setOf(Role.EDITOR), UserStatus.ACTIVE, clock.instant())
        server.start(wait = false)
    }

    fun token(role: Role): String {
        val user = UserFixture(components.database, components.userRepository, components.passwordHasher)
            .create("disc-${role.name.lowercase()}-${System.nanoTime()}", "irrelevant-password-1", setOf(role), UserStatus.ACTIVE, clock.instant())
        return components.jwtService.issue(user.user.id, user.user.tokenVersion).token
    }

    fun seedProvider(slug: String, websiteUrl: String, systemStatus: SystemStatus): Provider = transaction(components.database) {
        ProviderRepository().insert(slug, "Seeded $slug", websiteUrl, null, ProviderStatus.ACTIVE, clock.instant(), systemStatus)
    }

    suspend fun get(path: String, token: String): HttpResponse =
        http.get("$baseUrl$path") { header(HttpHeaders.Authorization, "Bearer $token") }

    suspend fun post(path: String, token: String, body: String = "{}"): HttpResponse =
        http.post("$baseUrl$path") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    override fun close() {
        http.close()
        server.stop(gracePeriodMillis = 0, timeoutMillis = 1_000)
        components.discoveryScope?.cancel()
        components.discoverySearchHttpClient?.close()
        components.crawlerScope?.cancel()
        components.crawlerCatalogClient?.close()
        components.crawlerFetchHttpClient?.close()
        components.fxHttpClient?.close()
    }

    companion object {
        const val SERVICE_USERNAME = "discovery-service"
        const val SERVICE_PASSWORD = "discovery-service-password-1"

        /** Distinctive, so a log/response scan for it is meaningful. */
        const val API_KEY = "tvly-SECRET-KEY-must-never-appear-0123456789"
    }
}

/** A Tavily `200` body with exactly these results (`url`, `title`, `content`, plus a `score`). */
fun tavilyResults(vararg results: Triple<String, String?, String?>, score: Double = 0.5): String =
    results.joinToString(prefix = """{"results":[""", postfix = "]}") { (url, title, content) ->
        val t = title?.let { "\"$it\"" } ?: "null"
        val c = content?.let { "\"$it\"" } ?: "null"
        """{"url":"$url","title":$t,"content":$c,"score":$score}"""
    }
