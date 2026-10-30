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
 * The single error body of every non-2xx response: `400, 401, 403, 404, 409, 413, 415, 429, 500,
 * 503` all look like this, so a client needs one parser.
 *
 * [details] is present (possibly empty) only for a validation failure and omitted otherwise --
 * `explicitNulls = false` on [ApiJson] is what drops the key rather than sending `"details":null`.
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
 * Every failure a handler raises deliberately. Carrying the status on the exception is what lets
 * [installApiErrorHandling] map the whole hierarchy with one `exception<ApiException>` branch, so
 * a later task adding an endpoint cannot forget to register its status.
 *
 * Being `sealed`, it can only be extended from inside `org.a1tsign.vds.api` -- route files live in
 * `org.a1tsign.vds.api.routes`, so a later task that needs a new failure type adds it to **this
 * file** rather than next to its endpoint.
 */
sealed class ApiException(
    val status: HttpStatusCode,
    override val message: String,
    val details: List<ApiErrorDetail>? = null,
) : RuntimeException(message)

/** `400`. The only exception that puts `details` on the wire -- build it via [Validation]. */
class ValidationException(details: List<ApiErrorDetail>) :
    ApiException(HttpStatusCode.BadRequest, "Request validation failed", details)

/** `401` -- no or invalid credentials. The message is deliberately uninformative. */
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

/**
 * What the client is told when a request body could not be read. Deliberately fixed: Ktor's own
 * message for a deserialization failure names the DTO class
 * ("Failed to convert request body to class org.a1tsign.vds.api.XxxRequest"), which is internal
 * structure the client has no business seeing.
 */
private const val MALFORMED_BODY_MESSAGE = "Malformed request body"

/** `415`. What a client that forgot `-H 'Content-Type: application/json'` is told. */
private const val UNSUPPORTED_MEDIA_TYPE_MESSAGE = "Content-Type must be application/json"

/**
 * Maps every failure onto [ApiError]. Install as `install(StatusPages) { installApiErrorHandling(clock) }`.
 *
 * `StatusPages` dispatches to the most specific registered supertype, so the five branches below
 * partition every throwable: our own hierarchy; Ktor's `ContentTransformationException` family,
 * which extends `java.io.IOException` and **not** `BadRequestException` (verified with `javap` on
 * `ktor-server-core-jvm-3.6.0`) -- its body-size member registered separately as a `413`, the rest
 * as a `415`; Ktor's `BadRequestException` (what `ContentNegotiation` wraps *all* deserialization
 * failures in); and everything else.
 *
 * The catch-all is the security-relevant one: a `500` body must never carry a stack trace, an
 * exception class name, a SQL fragment or a JDBC URL. It gets a fixed generic message plus a short
 * correlation ref that is also logged at ERROR next to the real exception, so a user-reported
 * `Internal error (ref: 7f3a2c91)` can be grepped straight to the log line.
 */
fun StatusPagesConfig.installApiErrorHandling(clock: Clock) {
    exception<ApiException> { call, cause ->
        if (cause is TooManyRequestsException) {
            call.response.header(HttpHeaders.RetryAfter, cause.retryAfterSeconds.toString())
        }
        call.respondApiError(clock, cause.status, cause.message, cause.details)
    }
    exception<KtorPayloadTooLargeException> { call, _ ->
        // Same message as our own PayloadTooLargeException carries on the Content-Length path, so
        // a client cannot tell which of the two body-limit paths rejected it.
        call.respondApiError(clock, HttpStatusCode.PayloadTooLarge, BODY_TOO_LARGE_MESSAGE)
    }
    exception<ContentTransformationException> { call, cause ->
        // A missing or non-JSON Content-Type on a receive<Dto>() endpoint: a client mistake, so a
        // 4xx and a WARN -- never the catch-all's 500 + ERROR, which would mislead the client and
        // inflate the server-error rate the later dashboards are built on.
        //
        // 415 is Ktor's own verdict on this family, not a guess: DefaultEnginePipeline answers
        // UnsupportedMediaType for both UnsupportedMediaTypeException and
        // CannotTransformContentToTypeException (3.6.0 bytecode). This branch exists because
        // StatusPages' catch-all intercepts first and shadowed that default into a 500.
        // Measured, both for a request with no Content-Type header at all and for text/plain and
        // application/xml: all three raise CannotTransformContentToTypeException (no converter
        // claims them), never UnsupportedMediaTypeException -- which 3.6.0 only ever throws on the
        // multipart paths -- so one branch on the family covers them all.
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
        // The first group of a UUID is 8 hex digits -- short enough to read over the phone, wide
        // enough not to collide within a log retention window.
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
        // The injected Clock, never Instant.now(): a test can then assert the envelope exactly.
        timestamp = clock.nowUtc(),
        status = status.value,
        error = status.description,
        message = message,
        path = request.path(),
        details = details,
    ),
)
