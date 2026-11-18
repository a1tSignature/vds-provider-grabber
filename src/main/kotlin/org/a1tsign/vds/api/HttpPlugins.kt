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

/** Cap request bodies to keep them within the service's 192 MB heap budget. */
const val MAX_REQUEST_BODY_BYTES: Long = 256L * 1024

internal const val BODY_TOO_LARGE_MESSAGE = "Request body exceeds the $MAX_REQUEST_BODY_BYTES byte limit"

private const val BODY_PUMP_BUFFER_BYTES = 8 * 1024

private fun bodyTooLarge() = PayloadTooLargeException(BODY_TOO_LARGE_MESSAGE)

/**
 * Return 413 without buffering the whole body. Count the decoded stream even when Content-Length fits.
 * CIO honors chunked transfer encoding when both headers are present, but still exposes the declared length.
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

/** Count in the call scope. Ktor's CopyableThrowable preserves PayloadTooLargeException across the channel for 413 handling. */
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

/** Install StatusPages first so it catches errors from subsequent plugins. */
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
        // An empty origin whitelist disables cross-origin requests.
        allowOrigins { origin -> origin in config.corsAllowedOrigins }
        // Ktor's defaults cover only GET/POST/HEAD and simple headers, so the full set is listed.
        allowMethod(HttpMethod.Get)
        allowMethod(HttpMethod.Post)
        allowMethod(HttpMethod.Put)
        allowMethod(HttpMethod.Patch)
        allowMethod(HttpMethod.Delete)
        allowMethod(HttpMethod.Options)
        allowHeader(HttpHeaders.Authorization)
        // Allow Content-Type explicitly so Ktor also permits application/json in cross-origin requests.
        allowHeader(HttpHeaders.ContentType)
    }
}
