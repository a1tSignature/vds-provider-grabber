package org.a1tsign.vds.api.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import java.sql.SQLException
import java.util.Properties
import javax.sql.DataSource
import kotlin.time.Duration.Companion.seconds

@Serializable
private data class HealthResponse(val status: String)

@Serializable
private data class ReadinessFailureResponse(val status: String, val reason: String)

@Serializable
private data class VersionResponse(val version: String, val buildTimestamp: String)

private val READY_TIMEOUT = 2.seconds

/**
 * Operational endpoints. `GET /health` never touches the database (process liveness only);
 * `GET /health/ready` does, and must never hang: acquiring a pool connection plus running the
 * query is bounded by [READY_TIMEOUT], independent of Hikari's own `connectionTimeout` setting
 * (that bounds only the "acquire a connection" half, not the query, and defaults to 5 s here —
 * see `AppConfig` — longer than the 2 s this endpoint must answer within, so it cannot be relied
 * on alone).
 *
 * The bound is `withTimeoutOrNull(READY_TIMEOUT)` wrapped around `runInterruptible(Dispatchers.IO)`
 * rather than a plain `withContext(Dispatchers.IO) { ... }`. That distinction matters: a plain
 * `withContext` around a *blocking* JDBC call has no suspension point inside it, so when the
 * timeout fires, `withTimeoutOrNull` cancels the coroutine but there is nothing to interrupt —
 * the worker thread keeps blocking inside Hikari's own connection-acquisition wait until *that*
 * internally resolves (measured empirically: with PostgreSQL stopped, a plain `withContext`
 * version answered in ~5 s, i.e. Hikari's `connectionTimeoutMs` default, not 2 s).
 * `runInterruptible` additionally calls `Thread.interrupt()` on the worker thread the moment the
 * coroutine is cancelled; HikariCP's connection-acquisition wait (`ConcurrentBag.borrow`, a
 * `SynchronousQueue.poll(timeout, unit)` under the hood) responds to that interrupt immediately,
 * so the whole check is actually bounded by [READY_TIMEOUT] end to end (verified empirically:
 * same scenario answers in ~2 s with this version).
 */
fun Route.opsRoutes(dataSource: DataSource) {
    get("/health") {
        call.respond(HttpStatusCode.OK, HealthResponse(status = "UP"))
    }

    get("/health/ready") {
        val isUp = withTimeoutOrNull(READY_TIMEOUT) {
            runInterruptible(Dispatchers.IO) {
                try {
                    dataSource.connection.use { connection ->
                        connection.createStatement().use { statement ->
                            statement.executeQuery("SELECT 1")
                        }
                    }
                    true
                } catch (e: SQLException) {
                    false
                }
            }
        } ?: false

        if (isUp) {
            call.respond(HttpStatusCode.OK, HealthResponse(status = "UP"))
        } else {
            call.respond(
                HttpStatusCode.ServiceUnavailable,
                ReadinessFailureResponse(status = "DOWN", reason = "database unavailable"),
            )
        }
    }

    get("/version") {
        val properties = Properties()
        val classLoader = Thread.currentThread().contextClassLoader
            ?: VersionResponse::class.java.classLoader
        classLoader.getResourceAsStream("version.properties")?.use {
            properties.load(it)
        }
        call.respond(
            HttpStatusCode.OK,
            VersionResponse(
                version = properties.getProperty("version", "unknown"),
                buildTimestamp = properties.getProperty("buildTimestamp", "unknown"),
            ),
        )
    }
}
