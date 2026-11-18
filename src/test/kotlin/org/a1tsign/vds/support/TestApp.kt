package org.a1tsign.vds.support

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.routing.Route
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import org.a1tsign.vds.api.installApiPlugins
import org.a1tsign.vds.config.HttpConfig
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * The instant every [TEST_CLOCK]-driven response carries. Fixed so a test can assert the error
 * envelope's `timestamp` **exactly** -- which is also what pins "`timestamp` comes from the
 * injected [Clock], not `Instant.now()`".
 */
val TEST_NOW: Instant = Instant.parse("2026-10-28T12:00:00Z")

val TEST_CLOCK: Clock = Clock.fixed(TEST_NOW, ZoneOffset.UTC)

/** Install the production API plugins while letting each test supply only the routes it needs. */
fun testApi(
    corsAllowedOrigins: List<String> = emptyList(),
    clock: Clock = TEST_CLOCK,
    routes: Route.() -> Unit,
    block: suspend ApplicationTestBuilder.() -> Unit,
) = testApplication {
    // testApplication's default config loads application.conf/yaml if present and would otherwise
    // auto-install modules; an empty MapApplicationConfig keeps the app to exactly what is
    // installed below.
    environment { config = MapApplicationConfig() }
    application {
        installApiPlugins(HttpConfig(port = 0, corsAllowedOrigins = corsAllowedOrigins), clock)
        routing { routes() }
    }
    block()
}

/**
 * Captures logback events emitted while [block] runs, so a test can assert both halves of the
 * "`500` leaks nothing to the client but the server log has the real exception" requirement, and
 * that `CallLogging` never writes an `Authorization` header or a request body.
 *
 * Attaches to the ROOT logger so it captures whichever logger the code under test actually uses.
 */
fun capturingLogs(block: () -> Unit): LogCapture {
    val root = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger
    val appender = ListAppender<ILoggingEvent>()
    appender.start()
    root.addAppender(appender)
    try {
        block()
    } finally {
        root.detachAppender(appender)
        appender.stop()
    }
    return LogCapture(appender)
}

class LogCapture(private val appender: ListAppender<ILoggingEvent>) {
    fun messages(): List<String> = appender.list.map { it.formattedMessage }

    fun messagesAt(level: Level): List<String> =
        appender.list.filter { it.level.toString() == level.name }.map { it.formattedMessage }

    fun throwablesAt(level: Level): List<String> =
        appender.list
            .filter { it.level.toString() == level.name }
            .mapNotNull { it.throwableProxy }
            .map { "${it.className}: ${it.message}" }
}
