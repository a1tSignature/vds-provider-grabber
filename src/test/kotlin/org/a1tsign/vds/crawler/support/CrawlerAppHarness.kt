package org.a1tsign.vds.crawler.support

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.response.respond
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.a1tsign.vds.AppComponents
import org.a1tsign.vds.auth.BootstrapAdmin
import org.a1tsign.vds.auth.PasswordHasher
import org.a1tsign.vds.buildComponents
import org.a1tsign.vds.config.CrawlerConfig
import org.a1tsign.vds.config.loadConfig
import org.a1tsign.vds.module
import org.a1tsign.vds.db.UserRepository
import org.a1tsign.vds.domain.Role
import org.a1tsign.vds.domain.UserStatus
import org.a1tsign.vds.support.MutableTestClock
import org.a1tsign.vds.support.PostgresSupport
import org.a1tsign.vds.support.StubCurrencyRateProvider
import org.a1tsign.vds.support.UserFixture
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** One request as the **server** saw it; the only sound way to count logins. */
data class RecordedApiCall(val method: String, val path: String)

/**
 * Boots the **real** vds-001 application on a **real** ephemeral port
 * (`embeddedServer(CIO, port = 0)` + the production `buildComponents`/`module`) against the shared
 * Testcontainers PostgreSQL, and creates the `EDITOR` service account the crawler logs in as.
 *
 * `testApplication`'s in-process client is deliberately **not** used here: the crawler↔API HTTP
 * boundary is exactly what task vds-002.4 exists to get right (token caching, the `401` re-login,
 * `200`-vs-`201`, the `409`s, a closed port), and this project has already shipped a green suite
 * whose tests mocked the boundary the bug lived on (see `07-knowledge/rework-log`). `testApplication`
 * stays fine for everything that is not this boundary.
 *
 * Owned by the test: `CrawlerAppHarness(clock).use { … }`, inside a class extending `AbstractDbTest`
 * so the schema is reset per test.
 */
class CrawlerAppHarness(
    val clock: MutableTestClock,
    val serviceUsername: String = "crawler-service",
    val servicePassword: String = "crawler-password-1",
    configuredCrawlerUsername: String = serviceUsername,
    configuredCrawlerPassword: String = servicePassword,
) : AutoCloseable {

    private val recorded = CopyOnWriteArrayList<RecordedApiCall>()

    /**
     * Fault injection at the real boundary: while `true`, every request except the login endpoint
     * is answered `401` **before** routing. That is how "a second consecutive 401 is not retried a
     * third time" is provable without mocking the client -- the server really does refuse a freshly
     * issued token.
     */
    @Volatile
    var rejectCatalogCallsAsUnauthorized: Boolean = false

    private val forcedFailuresLeft = AtomicInteger(0)

    @Volatile
    private var forcedFailureStatus: HttpStatusCode = HttpStatusCode.ServiceUnavailable

    /**
     * Makes the server answer the next [times] non-login requests with [status] **before** routing,
     * so the `5xx` retry loop is driven by a real server response rather than by a mocked client.
     * `times = Int.MAX_VALUE` is "every call from now on"; [clearForcedFailures] cancels the rest.
     */
    fun failNextCatalogCalls(times: Int, status: HttpStatusCode = HttpStatusCode.ServiceUnavailable) {
        forcedFailureStatus = status
        forcedFailuresLeft.set(times)
    }

    fun clearForcedFailures() = forcedFailuresLeft.set(0)

    val components: AppComponents = buildComponents(
        testCrawlerAppConfig(crawlerUsername = configuredCrawlerUsername, crawlerPassword = configuredCrawlerPassword),
        PostgresSupport.dataSource,
        clock,
        PostgresSupport.database,
        PasswordHasher(),
        StubCurrencyRateProvider(Result.failure(IllegalStateException("FX is not exercised by crawler tests"))),
    )

    private val server: EmbeddedServer<*, *>

    val port: Int

    val baseUrl: String

    /** The `EDITOR` service account id -- `recordedBy` on every price row the crawler writes. */
    val serviceUserId: Long

    init {
        val config = components.config
        BootstrapAdmin(components.database, components.userRepository, components.passwordHasher)
            .ensure(config.bootstrapAdmin, clock.instant())
        serviceUserId = UserFixture(components.database, components.userRepository, components.passwordHasher)
            .create(serviceUsername, servicePassword, setOf(Role.EDITOR), UserStatus.ACTIVE, clock.instant())
            .user.id
        server = embeddedServer(CIO, port = 0, host = "127.0.0.1") {
            // Setup is the first phase, so a recorded call is counted even when it is rejected
            // below, and the rejection happens before authentication or routing.
            intercept(ApplicationCallPipeline.Setup) {
                val path = call.request.path()
                recorded += RecordedApiCall(call.request.httpMethod.value, path)
                if (path != LOGIN_PATH) {
                    if (rejectCatalogCallsAsUnauthorized) {
                        call.respond(HttpStatusCode.Unauthorized)
                        finish()
                    } else if (forcedFailuresLeft.getAndUpdate { left -> (left - 1).coerceAtLeast(0) } > 0) {
                        call.respond(forcedFailureStatus)
                        finish()
                    }
                }
            }
            module(components)
        }
        server.start(wait = false)
        port = runBlocking { server.engine.resolvedConnectors().first().port }
        baseUrl = "http://127.0.0.1:$port"
    }

    /** The crawler configuration pointing at this server, built by the real `loadConfig`. */
    fun crawlerConfig(
        apiBaseUrl: String = baseUrl,
        username: String = serviceUsername,
        password: String = servicePassword,
    ): CrawlerConfig = testCrawlerAppConfig(apiBaseUrl, username, password).crawler

    fun calls(): List<RecordedApiCall> = recorded.toList()

    fun countCalls(method: String, path: String): Int =
        recorded.count { it.method == method && it.path == path }

    fun loginCount(): Int = countCalls("POST", LOGIN_PATH)

    fun clearRecordedCalls() = recorded.clear()

    /**
     * Bumps the service account's `token_version`, which is what vds-001 does on a role/status
     * change -- every token issued before this call is refused with a `401` from then on.
     */
    fun revokeServiceAccountTokens() = transaction(components.database) {
        UserRepository().updateUser(serviceUserId, UserStatus.ACTIVE, setOf(Role.EDITOR), clock.instant(), revoke = true)
    }

    override fun close() {
        server.stop(gracePeriodMillis = 0, timeoutMillis = 1_000)
        components.fxHttpClient?.close()
        // task vds-002.7's widened buildComponents() means every harness instance with the crawler
        // enabled now also owns a live crawlerScope + two HttpClient(CIO) -- close them here too,
        // the same way Application.kt's shutdownGracefully does for the real process.
        components.crawlerScope?.cancel()
        components.crawlerCatalogClient?.close()
        components.crawlerFetchHttpClient?.close()
    }

    private companion object {
        const val LOGIN_PATH = "/api/v1/auth/login"
    }
}

/** A port nothing listens on: bound to learn a free number, then released. */
fun closedLoopbackPort(): Int = java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))
    .use { it.localPort }

private fun testCrawlerAppConfig(
    apiBaseUrl: String = "http://127.0.0.1:8080",
    crawlerUsername: String = "crawler-service",
    crawlerPassword: String = "crawler-password-1",
) = loadConfig(
    buildMap {
        put("VDS_DB_URL", PostgresSupport.jdbcUrl)
        put("VDS_DB_USER", PostgresSupport.username)
        put("VDS_DB_PASSWORD", PostgresSupport.password)
        put("VDS_JWT_SECRET", "test-jwt-secret-012345678901234567890123456789")
        put("VDS_JWT_TTL_HOURS", "24")
        put("VDS_BOOTSTRAP_ADMIN_USERNAME", "bootstrap-admin")
        put("VDS_BOOTSTRAP_ADMIN_PASSWORD", "bootstrap-password-1")
        put("VDS_CRAWLER_ENABLED", "true")
        put("VDS_CRAWLER_API_BASE_URL", apiBaseUrl)
        put("VDS_CRAWLER_USERNAME", crawlerUsername)
        put("VDS_CRAWLER_PASSWORD", crawlerPassword)
    },
)
