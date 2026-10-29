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

/** 256 KB. The heap this runs on is 192 MB, so a request body is never allowed to be "whatever". */
const val MAX_REQUEST_BODY_BYTES: Long = 256L * 1024

/** The one `413` message, shared by both body-limit paths -- see [RequestBodySizeLimit]. */
internal const val BODY_TOO_LARGE_MESSAGE = "Request body exceeds the $MAX_REQUEST_BODY_BYTES byte limit"

private const val BODY_PUMP_BUFFER_BYTES = 8 * 1024

private fun bodyTooLarge() = PayloadTooLargeException(BODY_TOO_LARGE_MESSAGE)

/**
 * Rejects a request body larger than [MAX_REQUEST_BODY_BYTES] as a `413`, **without ever holding
 * the whole body in memory** -- the bug the limit exists to prevent would be reintroduced by
 * reading the body first and measuring it afterwards.
 *
 * Ktor 3.6.0 ships no body-limit plugin of its own: `ktor-server-core-jvm-3.6.0.jar` contains the
 * exception type `io.ktor.server.plugins.PayloadTooLargeException` and nothing that throws it for
 * an oversized request (verified by listing the jar). Hence this plugin, with two paths:
 *
 * - **A declared `Content-Length` over the limit:** rejected in `onCall`, which runs before any
 *   route handler and therefore before anything reads the body (pinned by a test asserting the
 *   handler never runs). Nothing is consumed at all. This is only a cheap fast path, never the
 *   enforcement.
 * - **Every request, whatever its framing:** the raw body channel is wrapped via the
 *   `ReceiveRequestBytes` hook and bytes are counted as the request handler reads them, aborting
 *   the moment the running total passes the limit. At most [BODY_PUMP_BUFFER_BYTES] is buffered
 *   here on top of whatever the reader asked for.
 *
 * The counting wrapper deliberately does **not** skip requests that declare a `Content-Length`,
 * because `Content-Length` is not a trustworthy statement about how many body bytes the engine
 * will deliver. `io.ktor.http.cio.parseHttpBody` tests `isTransferEncodingChunked` *before* it
 * looks at `contentLength`, and the CIO server pipeline rejects only a *duplicate* `Content-Length`
 * header, never a `Transfer-Encoding` + `Content-Length` conflict. A request carrying both is
 * therefore decoded as an unbounded chunked stream while `request.contentLength()` returns the
 * declared lie -- measured over a raw socket against a real `embeddedServer(CIO)`: with the
 * wrapper skipped, 270 336 bytes reached a handler behind a 256 KB cap. Pinned by
 * `a body declaring both Content-Length and chunked framing cannot bypass the limit`, which also
 * covers an understated `Content-Length`. The cost of always counting is one pass through an
 * 8 KB buffer per request.
 *
 * Both paths answer with the standard `413` envelope, but they cannot throw the *same* exception,
 * and that is not a style choice -- see [countingBodyChannel].
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
 * Pumps [source] through a counting loop, failing the downstream channel once more than
 * [MAX_REQUEST_BODY_BYTES] has been read. Scoped to the call (`ApplicationCall : CoroutineScope`),
 * so the pump cannot outlive the request it belongs to.
 *
 * It throws **Ktor's** `PayloadTooLargeException`, not ours, because the exception has to survive
 * a channel boundary. `io.ktor.utils.io.CloseToken.wrapCause` decides what the *reader* of a
 * failed channel sees (verified against the 3.6.0 bytecode):
 *
 * ```
 * origin is CopyableThrowable<*>   -> origin.createCopy()          // arrives as itself
 * origin is CancellationException  -> CancellationException(...)
 * else                             -> ClosedByteChannelException(origin)
 * ```
 *
 * Ktor's `PayloadTooLargeException` implements `kotlinx.coroutines.CopyableThrowable` and so takes
 * the first branch. Our [PayloadTooLargeException] is a plain `RuntimeException`: thrown here it
 * reached `StatusPages` wrapped in `ClosedByteChannelException`, matched the catch-all and became
 * a `500` -- measured, not assumed. Ours is still what the `Content-Length` path and any handler
 * throws, since that never crosses a channel.
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
 * The HTTP contract shared by every endpoint. Install once, in `Application.module` and in tests
 * (`support/TestApp.kt`), so a test exercises the real wire format rather than a look-alike.
 *
 * Order is deliberate: `StatusPages` first, so it is the outermost interceptor and turns an
 * exception raised by any plugin installed after it -- the body-size limit, `ContentNegotiation`'s
 * deserialization -- into the standard envelope.
 */
fun Application.installApiPlugins(config: HttpConfig, clock: Clock) {
    install(StatusPages) { installApiErrorHandling(clock) }
    install(CallLogging) {
        level = Level.INFO
        // Spelled out rather than left to the default format, to make it explicit and testable
        // that one access-log line is method + path + status and nothing else: no header (an
        // `Authorization: Bearer ...` above all) and no byte of the request body is ever logged.
        format { call ->
            val status = call.response.status()?.value?.toString() ?: "unhandled"
            "${call.request.httpMethod.value} ${call.request.path()} -> $status"
        }
    }
    install(RequestBodySizeLimit)
    install(ContentNegotiation) { json(ApiJson) }
    install(CORS) {
        // An exact-origin whitelist. With VDS_CORS_ALLOWED_ORIGINS unset (the default) the list is
        // empty, the predicate matches nothing, and no cross-origin request is allowed at all --
        // never a fallback to anyHost(), which would make the write endpoints reachable from any
        // page on the internet. Same-origin requests (no Origin header) are unaffected.
        allowOrigins { origin -> origin in config.corsAllowedOrigins }
        // The full method/header set is listed rather than leaning on Ktor's defaults (which cover
        // only GET/POST/HEAD and the simple request headers): the write methods and Authorization
        // are *not* default, and the next 40 endpoints should read the allowed set here, not in the
        // library.
        allowMethod(HttpMethod.Get)
        allowMethod(HttpMethod.Post)
        allowMethod(HttpMethod.Put)
        allowMethod(HttpMethod.Patch)
        allowMethod(HttpMethod.Delete)
        allowMethod(HttpMethod.Options)
        allowHeader(HttpHeaders.Authorization)
        // Not an allowlist entry like the one above: `CORSConfig.allowHeader` special-cases this
        // one name and sets `allowNonSimpleContentTypes = true` (3.6.0 bytecode), which is what
        // permits a non-simple Content-Type such as `application/json` cross-origin. Delete it and
        // *every* cross-origin JSON request answers 403 -- the preflight and the real POST alike,
        // i.e. the whole browser-facing API. Pinned by
        // `an allowed origin may send a cross-origin JSON request`.
        allowHeader(HttpHeaders.ContentType)
    }
}
