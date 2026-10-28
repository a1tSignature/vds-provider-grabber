package org.a1tsign.vds

import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import org.a1tsign.vds.api.routes.opsRoutes
import org.slf4j.event.Level

fun main() {
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
    routing {
        opsRoutes()
    }
}

/**
 * Stops accepting new requests, waits up to 10 s for in-flight requests to finish, then exits.
 * Later tasks (connection pool, FX scheduler) hook their own shutdown into this one named
 * function instead of adding more inline shutdown-hook lambdas.
 */
private fun shutdownGracefully(server: EmbeddedServer<*, *>) {
    server.stop(gracePeriodMillis = 1_000, timeoutMillis = 10_000)
}
