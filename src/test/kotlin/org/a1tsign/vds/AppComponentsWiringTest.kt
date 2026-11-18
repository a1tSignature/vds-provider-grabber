package org.a1tsign.vds

import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.a1tsign.vds.auth.PasswordHasher
import org.a1tsign.vds.config.crawlerAdapterConfig
import org.a1tsign.vds.config.loadConfig
import org.a1tsign.vds.crawler.AdapterSelection
import org.a1tsign.vds.crawler.adapters.ExampleStaticAdapter
import org.a1tsign.vds.crawler.model.AdapterRunStatus
import org.a1tsign.vds.crawler.model.RunStatus
import org.a1tsign.vds.crawler.support.FixtureHttpServer
import org.a1tsign.vds.crawler.support.closedLoopbackPort
import org.a1tsign.vds.domain.Role
import org.a1tsign.vds.domain.UserStatus
import org.a1tsign.vds.support.AbstractDbTest
import org.a1tsign.vds.support.PostgresSupport
import org.a1tsign.vds.support.StubCurrencyRateProvider
import org.a1tsign.vds.support.UserFixture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Exercise production composition so per-adapter environment settings reach the reference adapter through real wiring. */
class AppComponentsWiringTest : AbstractDbTest() {

    @Test
    fun `the components built from the environment hand the adapter its own configuration`() {
        FixtureHttpServer().use { fixture ->
            fixture.robots(body = "User-agent: *\nAllow: /\n")
            fixture.html(ExampleStaticAdapter.PAGE_PATH, fixturePage())
            // The API port has to be known before buildComponents (the crawler's catalog client is
            // constructed from the configured base URL), so it is reserved and immediately released.
            val apiPort = closedLoopbackPort()
            val config = loadConfig(
                mapOf(
                    "VDS_DB_URL" to PostgresSupport.jdbcUrl,
                    "VDS_DB_USER" to PostgresSupport.username,
                    "VDS_DB_PASSWORD" to PostgresSupport.password,
                    "VDS_JWT_SECRET" to "test-jwt-secret-012345678901234567890123456789",
                    "VDS_HTTP_PORT" to apiPort.toString(),
                    "VDS_CRAWLER_ENABLED" to "true",
                    "VDS_CRAWLER_API_BASE_URL" to "http://127.0.0.1:$apiPort",
                    "VDS_CRAWLER_USERNAME" to SERVICE_USERNAME,
                    "VDS_CRAWLER_PASSWORD" to SERVICE_PASSWORD,
                    "VDS_CRAWLER_ADAPTERS" to ExampleStaticAdapter.ID,
                    ExampleStaticAdapter.BASE_URL_VARIABLE to fixture.url(""),
                    // Use a short delay because this fixture exercises wiring.
                    "VDS_CRAWLER_HOST_DELAY_MS" to "50",
                ),
            )

            assertEquals(
                mapOf(ExampleStaticAdapter.BASE_URL_CONFIG_KEY to fixture.url("")),
                crawlerAdapterConfig(ExampleStaticAdapter.ID, config.crawler.adapterEnv.reveal()),
            )
            assertEquals("***(1 VDS_CRAWLER_ADAPTER_* variables)", config.crawler.adapterEnv.toString())

            val components = buildComponents(
                config,
                PostgresSupport.dataSource,
                clock,
                PostgresSupport.database,
                PasswordHasher(),
                StubCurrencyRateProvider(Result.failure(IllegalStateException("FX is not exercised here"))),
            )
            UserFixture(components.database, components.userRepository, components.passwordHasher)
                .create(SERVICE_USERNAME, SERVICE_PASSWORD, setOf(Role.EDITOR), UserStatus.ACTIVE, clock.instant())
            val server = embeddedServer(CIO, port = apiPort, host = "127.0.0.1") { module(components) }
            try {
                server.start(wait = false)
                val runner = assertNotNull(components.crawlerRunner, "the crawler is enabled, so it exists")

                val summary = runBlocking {
                    runner.runOnce(AdapterSelection(listOf(ExampleStaticAdapter.ID)), dryRun = false)
                }

                val result = summary.adapters.single()
                assertEquals(RunStatus.COMPLETED, summary.status)
                assertEquals(AdapterRunStatus.SUCCESS, result.status, result.message)
                assertNull(result.failureReason)
                assertEquals(1, result.providersCreated)
                assertEquals(3, result.tariffsCreated)
                assertNotNull(
                    runBlocking { components.crawlerCatalogClient!!.findProviderBySlug(ExampleStaticAdapter.PROVIDER_SLUG) },
                    "the run really wrote the reference provider through the admin API",
                )
            } finally {
                server.stop(gracePeriodMillis = 0, timeoutMillis = 1_000)
                components.crawlerScope?.cancel()
                components.crawlerCatalogClient?.close()
                components.crawlerFetchHttpClient?.close()
                components.fxHttpClient?.close()
            }
        }
    }

    private companion object {
        const val SERVICE_USERNAME = "crawler-wiring"
        const val SERVICE_PASSWORD = "crawler-wiring-password-1"

        fun fixturePage(): String =
            AppComponentsWiringTest::class.java.getResource("/crawler/example-static.html")!!.readText()
    }
}
