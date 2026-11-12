package org.a1tsign.vds.api.routes

import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import org.a1tsign.vds.api.Validation
import org.a1tsign.vds.api.dto.LoginRequest
import org.a1tsign.vds.api.dto.LoginResponse
import org.a1tsign.vds.auth.AuthService

fun Route.authRoutes(auth: AuthService, trustForwardedFor: Boolean = false) {
    post("/api/v1/auth/login") {
        val request = call.receive<LoginRequest>()
        val validation = Validation()
        validation.requireNonBlank("username", request.username)
        if (request.password.isEmpty()) validation.reject("password", "must not be blank")
        validation.throwIfInvalid()
        val result = auth.loginWithBcryptPermit(
            clientAddress(call.request.local.remoteHost, call.request.headers.getAll("X-Forwarded-For"), trustForwardedFor),
            request.username, request.password)
        call.respond(
            LoginResponse(
                result.issued.token,
                result.issued.expiresAt,
                result.username,
                result.roles.sorted(),
            ),
        )
    }
}

/** The rightmost `X-Forwarded-For` entry is the one the nearest proxy appended; earlier ones are client-supplied. */
internal fun clientAddress(peer: String, forwardedFor: List<String>?, trustForwardedFor: Boolean): String {
    if (!trustForwardedFor) return peer
    return forwardedFor.orEmpty()
        .flatMap { it.split(",") }
        .map { it.trim() }
        .lastOrNull { it.isNotEmpty() }
        ?: peer
}
