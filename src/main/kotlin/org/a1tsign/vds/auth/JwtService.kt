package org.a1tsign.vds.auth

import com.auth0.jwt.JWT
import com.auth0.jwt.JWTVerifier
import com.auth0.jwt.algorithms.Algorithm
import org.a1tsign.vds.config.JwtConfig
import org.a1tsign.vds.api.UnauthorizedException
import java.time.Clock
import java.time.Instant
import java.util.Date

data class IssuedToken(val token: String, val expiresAt: Instant)
data class TokenClaims(val userId: Long, val tokenVersion: Int)

class JwtService(config: JwtConfig, private val clock: Clock) {
    private val algorithm = Algorithm.HMAC256(config.secret.reveal())
    private val verifier = (JWT.require(algorithm).withIssuer(ISSUER)
        .withClaimPresence("exp").acceptLeeway(CLOCK_LEEWAY_SECONDS) as JWTVerifier.BaseVerification).build(clock)
    private val ttlSeconds = Math.multiplyExact(config.ttlHours, 3600)

    fun issue(userId: Long, tokenVersion: Int): IssuedToken {
        val issuedAt = clock.instant()
        val expiresAt = issuedAt.plusSeconds(ttlSeconds)
        val token = JWT.create().withIssuer(ISSUER).withSubject(userId.toString())
            .withClaim("ver", tokenVersion).withIssuedAt(Date.from(issuedAt)).withExpiresAt(Date.from(expiresAt))
            .sign(algorithm)
        return IssuedToken(token, expiresAt)
    }

    fun verify(token: String): TokenClaims {
        return try {
            val decoded = verifier.verify(token)
            val id = decoded.subject?.toLongOrNull() ?: throw UnauthorizedException()
            val version = decoded.getClaim("ver").asInt() ?: throw UnauthorizedException()
            TokenClaims(id, version)
        } catch (e: UnauthorizedException) {
            throw e
        } catch (_: RuntimeException) {
            throw UnauthorizedException()
        }
    }

    companion object {
        const val ISSUER = "vds-catalog"
        private const val CLOCK_LEEWAY_SECONDS = 30L
    }
}
