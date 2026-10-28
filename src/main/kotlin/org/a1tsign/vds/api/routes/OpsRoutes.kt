package org.a1tsign.vds.api.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.Serializable
import java.util.Properties

@Serializable
private data class HealthResponse(val status: String)

@Serializable
private data class VersionResponse(val version: String, val buildTimestamp: String)

/**
 * Operational endpoints that do not depend on the database: liveness and build metadata.
 * `GET /health/ready` (needs the database) is added by a later task, not here.
 */
fun Route.opsRoutes() {
    get("/health") {
        call.respond(HttpStatusCode.OK, HealthResponse(status = "UP"))
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
