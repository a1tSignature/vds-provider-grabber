package org.a1tsign.vds

import com.zaxxer.hikari.HikariDataSource
import com.zaxxer.hikari.pool.HikariPool.PoolInitializationException
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respond
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.a1tsign.vds.api.installApiPlugins
import org.a1tsign.vds.api.registerRoutes
import org.a1tsign.vds.auth.BootstrapAdmin
import org.a1tsign.vds.config.ConfigurationException
import org.a1tsign.vds.config.loadConfig
import org.a1tsign.vds.config.nowUtc
import org.a1tsign.vds.crawler.CRAWL_SHUTDOWN_GRACE_MS
import org.a1tsign.vds.crawler.CrawlScheduler
import org.a1tsign.vds.crawler.write.CatalogApiClient
import org.a1tsign.vds.db.buildDataSource
import org.a1tsign.vds.discovery.DISCOVERY_SHUTDOWN_GRACE_MS
import org.a1tsign.vds.discovery.DiscoveryScheduler
import org.a1tsign.vds.db.runMigrations
import org.a1tsign.vds.fx.FxRefreshScheduler
import org.flywaydb.core.api.FlywayException
import org.jetbrains.exposed.v1.jdbc.Database
import org.slf4j.LoggerFactory
import java.time.Clock
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.system.exitProcess

private val activeRequests = AtomicInteger(0)
private val shuttingDown = AtomicBoolean(false)

private val logger = LoggerFactory.getLogger("org.a1tsign.vds.Application")

fun main() {
    // Disable Ktor's JVM shutdown hook before start so it cannot stop CIO while the application drains requests.
    System.setProperty("io.ktor.server.engine.ShutdownHook", "false")

    val config = try {
        loadConfig()
    } catch (e: ConfigurationException) {
        logger.error(e.message)
        exitProcess(1)
    }

    // Complete database initialization and migrations before binding HTTP. Hikari opens the pool eagerly and can fail in the constructor.
    val dataSource = try {
        buildDataSource(config.db)
    } catch (e: PoolInitializationException) {
        logger.error("Database pool initialization failed: ${e.cause?.message ?: e.message}")
        exitProcess(1)
    }
    try {
        runMigrations(dataSource)
    } catch (e: FlywayException) {
        logger.error("Database migration failed: ${e.message}")
        exitProcess(1)
    }
    val database = Database.connect(dataSource)
    val clock = Clock.systemUTC()
    val components = try {
        buildComponents(config, dataSource, clock, database)
    } catch (e: ConfigurationException) {
        logger.error(e.message)
        exitProcess(1)
    }
    BootstrapAdmin(database, components.userRepository, components.passwordHasher).ensure(config.bootstrapAdmin, clock.nowUtc())
    val server = embeddedServer(CIO, port = config.http.port) { module(components) }
    val fxScheduler = FxRefreshScheduler(components.fxRefreshService, config.fx.refreshHours)
    val crawlerScheduler = components.crawlerRunner?.let { runner -> CrawlScheduler.create(config.crawler, runner) }
    val discoveryScheduler = DiscoveryScheduler.create(config.discovery, components.discoveryRunner)
    Runtime.getRuntime().addShutdownHook(
        Thread {
            shutdownGracefully(
                server,
                clock,
                dataSource,
                fxScheduler,
                components.fxHttpClient,
                crawlerScheduler,
                components.crawlerScope,
                components.crawlerAdminRunJob,
                components.crawlerCatalogClient,
                components.crawlerFetchHttpClient,
                DiscoveryShutdown(
                    discoveryScheduler,
                    components.discoveryScope,
                    components.discoveryAdminPassJob,
                    components.discoverySearchHttpClient,
                ),
            )
        },
    )
    server.start(wait = true)
}

fun Application.module(components: AppComponents, staticBasePackage: String = "static") {
    installApiPlugins(components.config.http, components.clock)
    // Count requests at Monitoring so shutdown can drain them independently of CIO. The finally block accounts for handlers that throw.
    intercept(ApplicationCallPipeline.Monitoring) {
        if (shuttingDown.get()) {
            // CIO keeps accepting connections during the drain. Reject new calls before handlers run or the active count increases.
            call.respond(HttpStatusCode.ServiceUnavailable)
            return@intercept
        }
        activeRequests.incrementAndGet()
        try {
            proceed()
        } finally {
            activeRequests.decrementAndGet()
        }
    }
    registerRoutes(components, staticBasePackage)
}

private const val SHUTDOWN_DRAIN_CAP_MILLIS = 10_000L
private const val SHUTDOWN_POLL_INTERVAL_MILLIS = 50L

/**
 * Reject new calls while draining active requests for up to 10 seconds. CIO 3.6.0 can end request coroutines before its configured grace period.
 * Stop discovery first so it cannot spend credits while loopback writes return 503.
 * Give scheduled and admin work bounded shutdown waits, then cancel scopes before closing clients. Close the pool after CIO stops.
 */
private fun shutdownGracefully(
    server: EmbeddedServer<*, *>,
    clock: Clock,
    dataSource: HikariDataSource,
    fxScheduler: FxRefreshScheduler,
    fxHttpClient: io.ktor.client.HttpClient?,
    crawlerScheduler: CrawlScheduler?,
    crawlerScope: CoroutineScope?,
    crawlerAdminRunJob: AtomicReference<Job?>,
    crawlerCatalogClient: CatalogApiClient?,
    crawlerFetchHttpClient: io.ktor.client.HttpClient?,
    discovery: DiscoveryShutdown,
) {
    shuttingDown.set(true)
    // Stop discovery before the drain because loopback writes return 503 once shutdown starts.
    discovery.scheduler?.close()
    joinBounded(discovery.adminPassJob.get(), DISCOVERY_SHUTDOWN_GRACE_MS, "admin-triggered discovery pass")
    val deadline = clock.nowUtc().plusMillis(SHUTDOWN_DRAIN_CAP_MILLIS)
    while (activeRequests.get() > 0 && clock.nowUtc().isBefore(deadline)) {
        Thread.sleep(SHUTDOWN_POLL_INTERVAL_MILLIS)
    }
    fxScheduler.close()
    fxHttpClient?.close()
    crawlerScheduler?.close()
    val adminJob = crawlerAdminRunJob.get()
    if (adminJob != null && adminJob.isActive) {
        adminJob.cancel()
        val finished = runBlocking { withTimeoutOrNull(CRAWL_SHUTDOWN_GRACE_MS) { adminJob.join() } }
        if (finished == null) {
            logger.warn("admin-triggered crawl run did not finish within {} ms of being cancelled", CRAWL_SHUTDOWN_GRACE_MS)
        }
    }
    discovery.scope?.cancel()
    discovery.searchHttpClient?.close()
    crawlerScope?.cancel()
    crawlerCatalogClient?.close()
    crawlerFetchHttpClient?.close()
    server.stop(gracePeriodMillis = 0, timeoutMillis = 1_000)
    dataSource.close()
}

private class DiscoveryShutdown(
    val scheduler: DiscoveryScheduler?,
    val scope: CoroutineScope?,
    val adminPassJob: AtomicReference<Job?>,
    val searchHttpClient: io.ktor.client.HttpClient?,
)

private fun joinBounded(job: Job?, graceMs: Long, what: String) {
    if (job == null || !job.isActive) return
    job.cancel()
    val finished = runBlocking { withTimeoutOrNull(graceMs) { job.join() } }
    if (finished == null) {
        logger.warn("{} did not finish within {} ms of being cancelled", what, graceMs)
    }
}
