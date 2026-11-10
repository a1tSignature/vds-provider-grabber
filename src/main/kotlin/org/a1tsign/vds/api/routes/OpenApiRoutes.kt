package org.a1tsign.vds.api.routes

import io.ktor.http.ContentType
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import org.a1tsign.vds.auth.requireRole
import org.a1tsign.vds.domain.Role

private val YAML_CONTENT_TYPE: ContentType = ContentType.parse("application/yaml")

private object OpenApiResourceMarker

/**
 * `GET /openapi.yaml`: the static, hand-written contract from `src/main/resources/openapi.yaml`,
 * readable by any authenticated role.
 *
 * Sent with [respondBytes]: `ContentNegotiation` is JSON-only, and `respond(obj)` would pass the
 * YAML to the JSON converter and fail with a `500`.
 */
fun Route.openApiRoutes() {
    get("/openapi.yaml") {
        call.requireRole(Role.VIEWER)
        val classLoader = Thread.currentThread().contextClassLoader
            ?: OpenApiResourceMarker::class.java.classLoader
        val bytes = classLoader.getResourceAsStream("openapi.yaml")?.use { it.readBytes() }
            ?: error("openapi.yaml missing from the classpath")
        call.respondBytes(bytes, YAML_CONTENT_TYPE)
    }
}
