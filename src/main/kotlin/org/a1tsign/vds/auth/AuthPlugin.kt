package org.a1tsign.vds.auth

import io.ktor.server.auth.Authentication
import io.ktor.server.auth.AuthenticationFailedCause
import io.ktor.server.auth.AuthenticationContext
import io.ktor.server.auth.AuthenticationProvider
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.util.AttributeKey
import org.a1tsign.vds.api.UnauthorizedException

data class VdsPrincipal(val userId: Long, val username: String, val roles: Set<org.a1tsign.vds.domain.Role>)

private class BearerJwtProvider(configuration: Config) : AuthenticationProvider(configuration) {
    private val auth: AuthService = configuration.auth
    private val optional: Boolean = configuration.optional
    override suspend fun onAuthenticate(context: AuthenticationContext) {
        val header = context.call.request.headers["Authorization"]
        val token = header?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }
            ?.substringAfter(' ', "")?.takeIf { it.isNotBlank() }
        if (token == null) {
            if (optional) return
            context.challenge()
            return
        }

        val user = auth.authenticate(token)
        if (user != null) context.principal(VdsPrincipal(user.id, user.username, user.roles))
        else context.challenge()
    }

    private fun AuthenticationContext.challenge() {
        challenge(ChallengeKey, AuthenticationFailedCause.InvalidCredentials) { _, _ ->
            throw UnauthorizedException()
        }
    }

    class Config(name: String?) : AuthenticationProvider.Config(name) {
        lateinit var auth: AuthService
        var optional: Boolean = false
        fun auth(service: AuthService) { auth = service }
    }

    companion object { private val ChallengeKey = AttributeKey<AuthenticationProvider>("BearerJwtChallenge") }
}

fun Application.installBearerJwtAuth(auth: AuthService) {
    install(Authentication) {
        register(BearerJwtProvider(BearerJwtProvider.Config("bearer-jwt").apply { auth(auth) }))
        register(BearerJwtProvider(BearerJwtProvider.Config("bearer-jwt-optional").apply {
            auth(auth)
            optional = true
        }))
    }
}
