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

// Shared between module() (incremented/decremented around every request) and shutdownGracefully()
// (polled during shutdown) — see the shutdown-drain comment below for why a plain atomic counter
// is used instead of relying on EmbeddedServer.stop()'s own grace period.
private val activeRequests = AtomicInteger(0)
private val shuttingDown = AtomicBoolean(false)

private val logger = LoggerFactory.getLogger("org.a1tsign.vds.Application")

fun main() {
    // EmbeddedServer.start() unconditionally registers its OWN internal JVM shutdown hook
    // (io.ktor.server.engine.ShutdownHookKt), enabled by default, which calls server.stop() with
    // the engine's own default grace/timeout the moment it runs — independently of, and racing
    // with, any hook we register ourselves. That is *why* a correctly-parameterised custom hook
    // still didn't drain in-flight requests (see shutdownGracefully's doc comment): Ktor's own
    // hook was tearing the engine down on its own short schedule in parallel. Must be disabled
    // before server.start() so our hook below is the only thing that ever calls server.stop().
    System.setProperty("io.ktor.server.engine.ShutdownHook", "false")

    // Config is loaded, and validated, before anything else — including before the HTTP server
    // binds a port. A misconfiguration must fail fast with one clear log line, not start
    // half-broken (nobody is watching this box). No stack trace: the message already names every
    // offending variable, and ConfigurationException.message is logged as-is.
    val config = try {
        loadConfig()
    } catch (e: ConfigurationException) {
        logger.error(e.message)
        exitProcess(1)
    }

    // Database plumbing runs before the HTTP server binds: a pool that cannot migrate must never
    // serve traffic. buildDataSource/runMigrations read every tunable from config.db — nothing
    // here is hardcoded. A migration failure gets the same fail-fast treatment as a config
    // error: one clear ERROR line (never the exception's stack trace into a running server) and
    // a non-zero exit, not a half-migrated process left serving requests.
    //
    // HikariDataSource(HikariConfig) eagerly initializes the pool and throws
    // PoolInitializationException (a RuntimeException) if the database is unreachable at boot —
    // caught here with the same fail-fast pattern as ConfigurationException/FlywayException below,
    // instead of letting it propagate as an uncaught stack-trace dump.
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
    // buildComponents can also throw ConfigurationException now: an unknown id in
    // VDS_CRAWLER_ADAPTERS is caught by AdapterRegistry.select() while wiring the crawler (spec
    // req. 39) and must fail startup exactly like a malformed VDS_* variable does above.
    val components = try {
        buildComponents(config, dataSource, clock, database)
    } catch (e: ConfigurationException) {
        logger.error(e.message)
        exitProcess(1)
    }
    BootstrapAdmin(database, components.userRepository, components.passwordHasher).ensure(config.bootstrapAdmin, clock.nowUtc())
    val server = embeddedServer(CIO, port = config.http.port) { module(components) }
    val fxScheduler = FxRefreshScheduler(components.fxRefreshService, config.fx.refreshHours)
    // null exactly when the crawler is disabled, following components.crawlerRunner (ADR-01: no
    // thread is started while VDS_CRAWLER_ENABLED is false).
    val crawlerScheduler = components.crawlerRunner?.let { runner -> CrawlScheduler.create(config.crawler, runner) }
    // null exactly when discovery was not constructed (VDS_DISCOVERY_ENABLED=false): no thread then.
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

fun Application.module(components: AppComponents) {
    // The whole HTTP contract -- error envelope, shared Json, body-size limit, CORS, CallLogging --
    // lives in api/HttpPlugins.kt so tests install exactly what production does.
    installApiPlugins(components.config.http, components.clock)
    // Tracks in-flight requests with a plain counter instead of trusting the CIO engine's own
    // stop(gracePeriodMillis, ...) to drain them (it does not — see shutdownGracefully). Phase
    // chosen is Monitoring, the same phase CallLogging installs into, so every request that gets
    // this far is counted exactly once; the try/finally guarantees the decrement runs even if a
    // handler throws.
    intercept(ApplicationCallPipeline.Monitoring) {
        if (shuttingDown.get()) {
            // New work is rejected immediately once shutdown starts: see the trade-off comment
            // in shutdownGracefully — this is the "stop accepting new requests" half of the
            // contract, enforced at the pipeline level since CIOApplicationEngine exposes no way
            // to close the listening socket without also tearing down in-flight connections.
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
    registerRoutes(components)
}

private const val SHUTDOWN_DRAIN_CAP_MILLIS = 10_000L
private const val SHUTDOWN_POLL_INTERVAL_MILLIS = 50L

/**
 * Stops accepting new requests, waits up to 10 s for in-flight requests to finish, then exits.
 * Later tasks (connection pool, FX scheduler, vds-002's crawler) hook their own shutdown into this
 * one named function instead of adding more inline shutdown-hook lambdas.
 *
 * `EmbeddedServer.stop(gracePeriodMillis, timeoutMillis)` alone does **not** achieve this on Ktor
 * 3.6.0's CIO engine, for two compounding reasons found empirically (reproduced independently
 * three times each, see the task's Developer Report addenda and the spec review):
 * 1. `EmbeddedServer.start()` registers its own internal shutdown hook by default, which calls
 *    `stop()` with the engine's own short default grace/timeout the moment SIGTERM arrives —
 *    see the comment in `main()` for why that is disabled.
 * 2. Even with that hook disabled and the right values passed explicitly,
 *    `CIOApplicationEngine.stopSuspend`'s `serverJob.join()` resolves in ~1 s regardless of the
 *    configured grace period, cancelling in-flight request coroutines early — the grace-period
 *    parameter is not a reliable drain mechanism on this engine at all.
 * So this function drains in-flight requests itself, via [activeRequests]/[shuttingDown], and
 * only calls `server.stop()` afterwards to actually tear the engine down (nothing should still
 * be running by then, or the 10 s budget has already been spent).
 *
 * Known trade-off: while this function is polling, the TCP listener is still open (there is no
 * API on `CIOApplicationEngine` to close it without also severing in-flight connections, which is
 * exactly the behaviour this function exists to avoid) — new connections can still arrive during
 * the drain window. They are rejected with `503` at the very first pipeline phase, before running
 * any route handler, instead of being served or counted, so they do not extend the drain.
 *
 * [crawlerScheduler] (null when the crawler is disabled) is closed first, the same way
 * [fxScheduler] is: it stops its own periodic ticks and gives an in-flight run up to its own grace
 * period to unwind (`CrawlScheduler.close()`). An in-flight admin-triggered run
 * ([crawlerAdminRunJob], set by `CrawlerRoutes.kt` right after `launch`) is given the same bounded
 * grace period next, mirroring `CrawlScheduler.close()`'s own cancel-then-join -- before this, only
 * the scheduler's own ticks were ever awaited on shutdown. [crawlerScope] is then cancelled -- that
 * is what makes an admin-triggered run (launched on this scope, not `GlobalScope`) cancellable by
 * this shutdown path even if the grace period above was exceeded. The crawler's two HTTP clients
 * ([crawlerCatalogClient], [crawlerFetchHttpClient]) are closed last, once nothing can start a new
 * call on them, mirroring [fxHttpClient] below.
 *
 * [discovery] (vds-004) is stopped **first**, before the request drain: once [shuttingDown] is set
 * the pass's own loopback catalog calls are answered `503`, so a pass left running through the
 * drain would keep paying one search credit per query for candidates it can no longer write. Its
 * scheduler is closed and an in-flight admin-triggered pass gets a bounded grace period; each pass
 * publishes its summary and flushes its credits while it unwinds (neither needs HTTP). Its scope
 * and search client are closed later, before the crawler's catalog client.
 *
 * The Hikari pool is closed last of all, after `server.stop()`, once nothing can issue a new query.
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
    // Before the drain, not after it: from here on the pass's own loopback catalog calls get a 503,
    // so every further query would spend a credit on results that can no longer be written.
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

/** What `shutdownGracefully` closes for vds-004 discovery; every field `null` while discovery is disabled. */
private class DiscoveryShutdown(
    val scheduler: DiscoveryScheduler?,
    val scope: CoroutineScope?,
    val adminPassJob: AtomicReference<Job?>,
    val searchHttpClient: io.ktor.client.HttpClient?,
)

/** Cancels [job] and waits up to [graceMs] for it to unwind -- the admin-triggered crawl run's shape. */
private fun joinBounded(job: Job?, graceMs: Long, what: String) {
    if (job == null || !job.isActive) return
    job.cancel()
    val finished = runBlocking { withTimeoutOrNull(graceMs) { job.join() } }
    if (finished == null) {
        logger.warn("{} did not finish within {} ms of being cancelled", what, graceMs)
    }
}
