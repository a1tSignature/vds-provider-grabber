package org.a1tsign.vds

import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respond
import io.ktor.server.routing.routing
import org.a1tsign.vds.api.routes.opsRoutes
import org.slf4j.event.Level
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean

// Shared between module() (incremented/decremented around every request) and shutdownGracefully()
// (polled during shutdown) — see the shutdown-drain comment below for why a plain atomic counter
// is used instead of relying on EmbeddedServer.stop()'s own grace period.
private val activeRequests = AtomicInteger(0)
private val shuttingDown = AtomicBoolean(false)

fun main() {
    // EmbeddedServer.start() unconditionally registers its OWN internal JVM shutdown hook
    // (io.ktor.server.engine.ShutdownHookKt), enabled by default, which calls server.stop() with
    // the engine's own default grace/timeout the moment it runs — independently of, and racing
    // with, any hook we register ourselves. That is *why* a correctly-parameterised custom hook
    // still didn't drain in-flight requests (see shutdownGracefully's doc comment): Ktor's own
    // hook was tearing the engine down on its own short schedule in parallel. Must be disabled
    // before server.start() so our hook below is the only thing that ever calls server.stop().
    System.setProperty("io.ktor.server.engine.ShutdownHook", "false")

    val port = System.getenv("VDS_HTTP_PORT")?.toInt() ?: 8080
    val server = embeddedServer(CIO, port = port) { module() }
    Runtime.getRuntime().addShutdownHook(Thread { shutdownGracefully(server) })
    server.start(wait = true)
}

fun Application.module() {
    install(CallLogging) {
        level = Level.INFO
    }
    install(ContentNegotiation) {
        json()
    }
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
    routing {
        opsRoutes()
    }
}

private const val SHUTDOWN_DRAIN_CAP_MILLIS = 10_000L
private const val SHUTDOWN_POLL_INTERVAL_MILLIS = 50L

/**
 * Stops accepting new requests, waits up to 10 s for in-flight requests to finish, then exits.
 * Later tasks (connection pool, FX scheduler) hook their own shutdown into this one named
 * function instead of adding more inline shutdown-hook lambdas.
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
 */
private fun shutdownGracefully(server: EmbeddedServer<*, *>) {
    shuttingDown.set(true)
    val deadline = System.currentTimeMillis() + SHUTDOWN_DRAIN_CAP_MILLIS
    while (activeRequests.get() > 0 && System.currentTimeMillis() < deadline) {
        Thread.sleep(SHUTDOWN_POLL_INTERVAL_MILLIS)
    }
    server.stop(gracePeriodMillis = 0, timeoutMillis = 1_000)
}
