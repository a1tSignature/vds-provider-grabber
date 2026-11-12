package org.a1tsign.vds.api

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.ContentTransformationException
import io.ktor.server.plugins.statuspages.StatusPagesConfig
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.response.header
import io.ktor.server.response.respond
import kotlinx.serialization.Serializable
import org.a1tsign.vds.config.nowUtc
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Instant
import java.util.UUID
import io.ktor.server.plugins.PayloadTooLargeException as KtorPayloadTooLargeException

/** One field-level failure of a request body or query string. */
@Serializable
data class ApiErrorDetail(val field: String, val message: String)

/**
 * The body of every non-2xx response, so a client needs one parser.
 *
 * [details] is present (possibly empty) only for a validation failure. Otherwise the key is omitted,
 * not sent as `null`, because [ApiJson] has `explicitNulls = false`.
 */
@Serializable
data class ApiError(
    @Serializable(with = InstantSerializer::class) val timestamp: Instant,
    val status: Int,
    val error: String,
    val message: String,
    val path: String,
    val details: List<ApiErrorDetail>? = null,
)

/**
 * An expected failure raised by a handler. The status travels with the exception, so
 * [installApiErrorHandling] maps the whole hierarchy in one branch and new endpoints need no
 * registration.
 *
 * Sealed: subtypes must be declared in package `org.a1tsign.vds.api`, not in `api.routes`.
 */
sealed class ApiException(
    val status: HttpStatusCode,
    override val message: String,
    val details: List<ApiErrorDetail>? = null,
) : RuntimeException(message)

/** `400`. The only exception that puts `details` on the wire -- build it via [Validation]. */
class ValidationException(details: List<ApiErrorDetail>) :
    ApiException(HttpStatusCode.BadRequest, "Request validation failed", details)

/** `401`: missing or invalid credentials. The message does not say which. */
class UnauthorizedException : ApiException(HttpStatusCode.Unauthorized, "Authentication required")

/** `403` -- authenticated but not permitted. */
class ForbiddenException : ApiException(HttpStatusCode.Forbidden, "Access denied")

class NotFoundException(message: String) : ApiException(HttpStatusCode.NotFound, message)

class ConflictException(message: String) : ApiException(HttpStatusCode.Conflict, message)

/** `502` -- an upstream dependency answered, but unusably. */
class BadGatewayException(message: String) : ApiException(HttpStatusCode.BadGateway, message)

/**
 * `413`. Ours, raised by the body-size limit plugin; Ktor's own
 * `io.ktor.server.plugins.PayloadTooLargeException` is mapped to the same envelope.
 */
class PayloadTooLargeException(message: String) : ApiException(HttpStatusCode.PayloadTooLarge, message)

/** `429`. [retryAfterSeconds] is sent as the mandatory `Retry-After` header. */
class TooManyRequestsException(val retryAfterSeconds: Long) :
    ApiException(HttpStatusCode.TooManyRequests, "Too many requests")

/** `503` -- a dependency is down. */
class ServiceUnavailableException : ApiException(HttpStatusCode.ServiceUnavailable, "Service temporarily unavailable")

private val logger = LoggerFactory.getLogger("org.a1tsign.vds.api.ApiError")

private const val INTERNAL_ERROR_MESSAGE = "Internal error"

/** Fixed, because Ktor's own deserialization message names the internal DTO class. */
private const val MALFORMED_BODY_MESSAGE = "Malformed request body"

private const val UNSUPPORTED_MEDIA_TYPE_MESSAGE = "Content-Type must be application/json"

/** `404` for a request no route matched at all -- see [installApiErrorHandling]'s `unhandled` registration. */
private const val NOT_FOUND_MESSAGE = "Not found"

/**
 * Maps every failure to [ApiError]. Install as `install(StatusPages) { installApiErrorHandling(clock) }`.
 *
 * `StatusPages` picks the most specific registered supertype. Ktor's
 * `ContentTransformationException` extends `IOException`, not `BadRequestException`, so it needs its
 * own `415` branch; its payload-too-large subtype gets a separate `413` branch.
 *
 * A `500` body must never carry a stack trace, class name, SQL or JDBC URL. It gets a fixed message
 * and a short ref, which is logged at ERROR next to the real exception.
 */
fun StatusPagesConfig.installApiErrorHandling(clock: Clock) {
    // `unhandled` fires only when nothing responded at all (`call.isHandled` is false) -- unlike
    // `status(HttpStatusCode.NotFound) { ... }`, which would also re-wrap a 404 a handler already
    // produced with its own message (e.g. "Tariff not found"). This is what gives an unmatched
    // `/api/**` path, and `GET /` with no SPA bundle on the classpath, the standard JSON envelope
    // instead of Ktor's bare empty 404.
    unhandled { call ->
        call.respondApiError(clock, HttpStatusCode.NotFound, NOT_FOUND_MESSAGE)
    }
    exception<ApiException> { call, cause ->
        if (cause is TooManyRequestsException) {
            call.response.header(HttpHeaders.RetryAfter, cause.retryAfterSeconds.toString())
        }
        call.respondApiError(clock, cause.status, cause.message, cause.details)
    }
    exception<KtorPayloadTooLargeException> { call, _ ->
        // Same message as the Content-Length path, so both limit paths look identical to a client.
        call.respondApiError(clock, HttpStatusCode.PayloadTooLarge, BODY_TOO_LARGE_MESSAGE)
    }
    exception<ContentTransformationException> { call, cause ->
        // A missing or non-JSON Content-Type is a client mistake: 415 and WARN, not 500 and ERROR.
        // Ktor would answer 415 by itself, but the Throwable branch below intercepts first.
        logger.warn("Rejected an unsupported Content-Type on ${call.request.path()}: ${cause.message}")
        call.respondApiError(clock, HttpStatusCode.UnsupportedMediaType, UNSUPPORTED_MEDIA_TYPE_MESSAGE)
    }
    exception<BadRequestException> { call, cause ->
        // The real reason (unknown key, a JSON number where a money string is required, a
        // timestamp without an offset, malformed JSON) is useful to us, not to the client.
        logger.warn("Rejected a malformed request body on ${call.request.path()}: ${cause.message}")
        call.respondApiError(clock, HttpStatusCode.BadRequest, MALFORMED_BODY_MESSAGE)
    }
    exception<Throwable> { call, cause ->
        // 8 hex digits: easy to read out, unlikely to collide within the log retention window.
        val ref = UUID.randomUUID().toString().take(8)
        logger.error(
            "Unhandled exception (ref: $ref) on ${call.request.httpMethod.value} ${call.request.path()}",
            cause,
        )
        call.respondApiError(
            clock,
            HttpStatusCode.InternalServerError,
            "$INTERNAL_ERROR_MESSAGE (ref: $ref)",
        )
    }
}

private suspend fun ApplicationCall.respondApiError(
    clock: Clock,
    status: HttpStatusCode,
    message: String,
    details: List<ApiErrorDetail>? = null,
) = respond(
    status,
    ApiError(
        // The injected Clock, not Instant.now(), so tests can assert the exact envelope.
        timestamp = clock.nowUtc(),
        status = status.value,
        error = status.description,
        message = message,
        path = request.path(),
        details = details,
    ),
)
