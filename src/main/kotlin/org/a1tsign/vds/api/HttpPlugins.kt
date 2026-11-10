package org.a1tsign.vds.api

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.hooks.ReceiveRequestBytes
import io.ktor.server.application.install
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.contentLength
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.writeFully
import io.ktor.utils.io.writer
import kotlinx.coroutines.Dispatchers
import org.a1tsign.vds.config.HttpConfig
import org.slf4j.event.Level
import java.time.Clock
import io.ktor.server.plugins.PayloadTooLargeException as KtorPayloadTooLargeException

/** 256 KB. The service targets a 192 MB heap, so request bodies are always capped. */
const val MAX_REQUEST_BODY_BYTES: Long = 256L * 1024

/** The `413` message for both paths of [RequestBodySizeLimit]. */
internal const val BODY_TOO_LARGE_MESSAGE = "Request body exceeds the $MAX_REQUEST_BODY_BYTES byte limit"

private const val BODY_PUMP_BUFFER_BYTES = 8 * 1024

private fun bodyTooLarge() = PayloadTooLargeException(BODY_TOO_LARGE_MESSAGE)

/**
 * Rejects a request body over [MAX_REQUEST_BODY_BYTES] with `413` without holding the whole body in
 * memory. Ktor 3.6.0 has no built-in body limit.
 *
 * - A declared `Content-Length` over the limit is rejected in `onCall`, before any handler reads
 *   the body. This is only a fast path.
 * - Every body is counted as the handler reads it ([countingBodyChannel]) and aborted once the
 *   total passes the limit.
 *
 * Counting also runs when `Content-Length` is within the limit: CIO decodes a request that carries
 * both `Transfer-Encoding: chunked` and `Content-Length` as an unbounded chunked stream, while
 * `contentLength()` still returns the declared value.
 */
val RequestBodySizeLimit = createApplicationPlugin(name = "RequestBodySizeLimit") {
    onCall { call ->
        val declared = call.request.contentLength()
        if (declared != null && declared > MAX_REQUEST_BODY_BYTES) {
            throw bodyTooLarge()
        }
    }
    on(ReceiveRequestBytes) { call, body -> call.countingBodyChannel(body) }
}

/**
 * Copies [source] to the returned channel and fails it once more than [MAX_REQUEST_BODY_BYTES] has
 * been read. Runs in the call's scope, so it cannot outlive the request.
 *
 * Throws Ktor's `PayloadTooLargeException`, not ours: it implements `CopyableThrowable`, so the
 * channel reader receives it unchanged. Any other exception reaches `StatusPages` wrapped in
 * `ClosedByteChannelException` and becomes a `500`.
 */
private fun ApplicationCall.countingBodyChannel(source: ByteReadChannel): ByteReadChannel =
    writer(Dispatchers.Unconfined) {
        var total = 0L
        val buffer = ByteArray(BODY_PUMP_BUFFER_BYTES)
        while (true) {
            val read = source.readAvailable(buffer, 0, buffer.size)
            if (read == -1) {
                break
            }
            total += read
            if (total > MAX_REQUEST_BODY_BYTES) {
                throw KtorPayloadTooLargeException(MAX_REQUEST_BODY_BYTES)
            }
            channel.writeFully(buffer, 0, read)
        }
    }.channel

/**
 * The HTTP contract shared by every endpoint, installed by `Application.module` and the test app.
 *
 * `StatusPages` must come first: as the outermost interceptor it also turns exceptions from the
 * plugins installed after it (body-size limit, `ContentNegotiation`) into [ApiError].
 */
fun Application.installApiPlugins(config: HttpConfig, clock: Clock) {
    install(StatusPages) { installApiErrorHandling(clock) }
    install(CallLogging) {
        level = Level.INFO
        // Method, path and status only: headers (Authorization) and bodies must never be logged.
        format { call ->
            val status = call.response.status()?.value?.toString() ?: "unhandled"
            "${call.request.httpMethod.value} ${call.request.path()} -> $status"
        }
    }
    install(RequestBodySizeLimit)
    install(ContentNegotiation) { json(ApiJson) }
    install(CORS) {
        // Exact-origin whitelist. Empty by default, which allows no cross-origin request at all.
        // Never anyHost(): it would expose the write endpoints to any page on the internet.
        allowOrigins { origin -> origin in config.corsAllowedOrigins }
        // Ktor's defaults cover only GET/POST/HEAD and simple headers, so the full set is listed.
        allowMethod(HttpMethod.Get)
        allowMethod(HttpMethod.Post)
        allowMethod(HttpMethod.Put)
        allowMethod(HttpMethod.Patch)
        allowMethod(HttpMethod.Delete)
        allowMethod(HttpMethod.Options)
        allowHeader(HttpHeaders.Authorization)
        // Also sets allowNonSimpleContentTypes = true. Without this line every cross-origin
        // application/json request, preflight included, gets 403.
        allowHeader(HttpHeaders.ContentType)
    }
}
