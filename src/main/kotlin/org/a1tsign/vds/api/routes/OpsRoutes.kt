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

// Bounds the query itself (JDBC queryTimeout, whole seconds), which READY_TIMEOUT cannot reach.
private const val READY_QUERY_TIMEOUT_SECONDS = 1

/**
 * `GET /health` reports process liveness only. `GET /health/ready` checks the database and answers
 * within [READY_TIMEOUT], even though Hikari's `connectionTimeout` is longer.
 *
 * A blocking JDBC call ignores coroutine cancellation, so two bounds are needed:
 * - `runInterruptible` interrupts the worker thread on timeout, which aborts Hikari's wait for a
 *   pool connection. A plain `withContext(Dispatchers.IO)` would wait the full `connectionTimeout`.
 * - pgjdbc's socket read ignores interrupts, so a server that accepts the connection but never
 *   answers is bounded only by [READY_QUERY_TIMEOUT_SECONDS] (`statement.queryTimeout`).
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
                            statement.queryTimeout = READY_QUERY_TIMEOUT_SECONDS
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
