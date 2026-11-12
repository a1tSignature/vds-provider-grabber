package org.a1tsign.vds.api.routes

import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import io.ktor.server.http.content.staticResources
import io.ktor.server.request.path
import io.ktor.server.response.header
import io.ktor.server.routing.Route

/** First path segment of a route group that is never part of the SPA, so it must never fall back to `index.html`. */
private val RESERVED_FIRST_SEGMENTS = setOf("api", "health", "version")

private fun isReservedPath(path: String): Boolean {
    if (path == "/openapi.yaml") return true
    val firstSegment = path.removePrefix("/").substringBefore('/')
    return firstSegment in RESERVED_FIRST_SEGMENTS
}

private const val IMMUTABLE_CACHE_CONTROL = "public, max-age=31536000, immutable"
private const val NO_CACHE_CACHE_CONTROL = "no-cache"

private fun ApplicationCall.setStaticCacheControl() {
    val value = if (request.path().startsWith("/assets/")) IMMUTABLE_CACHE_CONTROL else NO_CACHE_CACHE_CONTROL
    response.header(HttpHeaders.CacheControl, value)
}

/**
 * Serves the SPA bundle from [basePackage] (production: `"static"`) under `/`, with `index.html`
 * as both the directory index and the deep-link fallback (so `/providers/42` resolves client-side).
 *
 * `filter` -- not `fallback` -- is what keeps the API safe: it skips this route entirely for a
 * reserved first segment, so Ktor falls through to a genuine route miss instead of this route's own
 * `default("index.html")`, which would otherwise happily turn `/api/v1/typo` into a `200 text/html`
 * (`default` resolves unconditionally once the exact resource lookup fails, regardless of path).
 * `StatusPagesConfig.unhandled` (`api/ApiError.kt`) then turns that genuine miss into the standard
 * JSON envelope, for every path including one with no bundle on the classpath at all.
 */
fun Route.staticRoutes(basePackage: String) {
    staticResources("/", basePackage) {
        default("index.html")
        filter { call -> isReservedPath(call.request.path()) }
        enableAutoHeadResponse()
        modify { _, call -> call.setStaticCacheControl() }
    }
}
