package org.a1tsign.vds.auth

import org.a1tsign.vds.api.UnauthorizedException
import org.a1tsign.vds.config.nowUtc
import org.a1tsign.vds.db.UserRepository
import org.a1tsign.vds.domain.UserStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.seconds
import org.a1tsign.vds.api.ServiceUnavailableException
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.time.Clock

data class LoginResult(val issued: IssuedToken, val username: String, val roles: Set<String>)

class AuthService(
    private val database: Database,
    private val users: UserRepository,
    private val jwt: JwtService,
    private val passwords: PasswordHasher,
    private val clock: Clock,
    val loginThrottle: LoginThrottle,
) {
    private val bcryptSemaphore = Semaphore(2)

    suspend fun loginWithBcryptPermit(ip: String, username: String, password: String): LoginResult {
        val reservation = loginThrottle.reserve(ip, username)
        val acquired = try {
            withTimeoutOrNull(2.seconds) { bcryptSemaphore.acquire() }
        } catch (e: Throwable) {
            loginThrottle.release(reservation)
            throw e
        }
        if (acquired == null) {
            loginThrottle.release(reservation)
            throw ServiceUnavailableException()
        }
        try {
            val result = login(username, password)
            loginThrottle.recordSuccess(reservation)
            return result
        } catch (e: UnauthorizedException) {
            loginThrottle.recordFailure(reservation)
            throw e
        } catch (e: Throwable) {
            loginThrottle.release(reservation)
            throw e
        } finally {
            bcryptSemaphore.release()
        }
    }

    suspend fun login(username: String, password: String): LoginResult {
        val user = withContext(Dispatchers.IO) {
            transaction(database) { users.findByUsername(username) }
        }
        val passwordMatches = withContext(Dispatchers.IO) {
            passwords.verify(password, user?.user?.passwordHash)
        }
        if (user == null || user.user.status != UserStatus.ACTIVE || !passwordMatches) throw UnauthorizedException()
        withContext(Dispatchers.IO) {
            transaction(database) { users.touchLastLogin(user.user.id, clock.nowUtc()) }
        }
        return LoginResult(
            jwt.issue(user.user.id, user.user.tokenVersion), user.user.username, user.roles.map { it.name }.toSet(),
        )
    }

    suspend fun authenticate(token: String): AuthenticatedUser? {
        val claims = try {
            jwt.verify(token)
        } catch (_: UnauthorizedException) {
            return null
        }
        val state = withContext(Dispatchers.IO) {
            transaction(database) { users.loadForAuth(claims.userId) }
        } ?: return null
        if (state.status != UserStatus.ACTIVE || state.tokenVersion != claims.tokenVersion) return null
        return AuthenticatedUser(state.userId, state.username, state.roles)
    }
}

data class AuthenticatedUser(val id: Long, val username: String, val roles: Set<org.a1tsign.vds.domain.Role>)
