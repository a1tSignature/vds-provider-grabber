package org.a1tsign.vds.api.routes

import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import org.a1tsign.vds.api.Validation
import org.a1tsign.vds.api.dto.LoginRequest
import org.a1tsign.vds.api.dto.LoginResponse
import org.a1tsign.vds.auth.AuthService

fun Route.authRoutes(auth: AuthService) {
    post("/api/v1/auth/login") {
        val request = call.receive<LoginRequest>()
        val validation = Validation()
        validation.requireNonBlank("username", request.username)
        if (request.password.isEmpty()) validation.reject("password", "must not be blank")
        validation.throwIfInvalid()
        val result = auth.loginWithBcryptPermit(call.request.local.remoteHost, request.username, request.password)
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
