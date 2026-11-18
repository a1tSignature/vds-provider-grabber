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

@Serializable
data class ApiErrorDetail(val field: String, val message: String)

/** Shared non-2xx envelope. [details] is present only for validation failures and can be empty. */
@Serializable
data class ApiError(
    @Serializable(with = InstantSerializer::class) val timestamp: Instant,
    val status: Int,
    val error: String,
    val message: String,
    val path: String,
    val details: List<ApiErrorDetail>? = null,
)

/** StatusPages maps the hierarchy using the status carried by each expected failure. */
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

class ForbiddenException : ApiException(HttpStatusCode.Forbidden, "Access denied")

class NotFoundException(message: String) : ApiException(HttpStatusCode.NotFound, message)

class ConflictException(message: String) : ApiException(HttpStatusCode.Conflict, message)

/** `502` -- an upstream dependency answered, but unusably. */
class BadGatewayException(message: String) : ApiException(HttpStatusCode.BadGateway, message)

/** Maps to the same 413 envelope as Ktor's streaming body-limit exception. */
class PayloadTooLargeException(message: String) : ApiException(HttpStatusCode.PayloadTooLarge, message)

/** `429`. [retryAfterSeconds] is sent as the mandatory `Retry-After` header. */
class TooManyRequestsException(val retryAfterSeconds: Long) :
    ApiException(HttpStatusCode.TooManyRequests, "Too many requests")

class ServiceUnavailableException : ApiException(HttpStatusCode.ServiceUnavailable, "Service temporarily unavailable")

private val logger = LoggerFactory.getLogger("org.a1tsign.vds.api.ApiError")

private const val INTERNAL_ERROR_MESSAGE = "Internal error"

/** Fixed, because Ktor's own deserialization message names the internal DTO class. */
private const val MALFORMED_BODY_MESSAGE = "Malformed request body"

private const val UNSUPPORTED_MEDIA_TYPE_MESSAGE = "Content-Type must be application/json"

private const val NOT_FOUND_MESSAGE = "Not found"

/**
 * Ktor's ContentTransformationException is an IOException and needs its own 415 mapping. Its payload-limit subtype maps to 413.
 * Unexpected failures expose only a fixed message and a log reference.
 */
fun StatusPagesConfig.installApiErrorHandling(clock: Clock) {
    // Handle unmatched calls here so a 404 already written by a handler keeps its message.
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
        call.respondApiError(clock, HttpStatusCode.PayloadTooLarge, BODY_TOO_LARGE_MESSAGE)
    }
    exception<ContentTransformationException> { call, cause ->
        // Map unsupported Content-Type to 415 before the catch-all handler can turn it into 500.
        logger.warn("Rejected an unsupported Content-Type on ${call.request.path()}: ${cause.message}")
        call.respondApiError(clock, HttpStatusCode.UnsupportedMediaType, UNSUPPORTED_MEDIA_TYPE_MESSAGE)
    }
    exception<BadRequestException> { call, cause ->
        // Log the deserialization reason while returning a fixed client-safe message.
        logger.warn("Rejected a malformed request body on ${call.request.path()}: ${cause.message}")
        call.respondApiError(clock, HttpStatusCode.BadRequest, MALFORMED_BODY_MESSAGE)
    }
    exception<Throwable> { call, cause ->
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
        timestamp = clock.nowUtc(),
        status = status.value,
        error = status.description,
        message = message,
        path = request.path(),
        details = details,
    ),
)
