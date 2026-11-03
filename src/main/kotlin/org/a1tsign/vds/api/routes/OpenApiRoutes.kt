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
 * `GET /openapi.yaml` -- serves the hand-written OpenAPI 3 contract
 * (`src/main/resources/openapi.yaml`) verbatim from the classpath. The document is static and
 * never generated at runtime (see
 * `06-decisions/vds-001-adr-07-openapi-handwritten-vs-ktor-generated`), so there is nothing
 * role-specific to filter -- any authenticated role (VIEWER included) may read it.
 *
 * `ContentNegotiation` is configured JSON-only (see `api/HttpPlugins.kt`), so the body is written
 * with [respondBytes] and an explicit [ContentType] rather than `respond(obj)` -- the latter would
 * hand the YAML text to the JSON converter and fail with a 500, not serve the document.
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
