package org.a1tsign.vds.auth

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.a1tsign.vds.api.ApiJson
import org.a1tsign.vds.api.dto.LoginRequest
import org.a1tsign.vds.db.UserRepository
import org.a1tsign.vds.domain.Role
import org.a1tsign.vds.domain.UserStatus
import org.a1tsign.vds.support.AbstractDbTest
import org.a1tsign.vds.support.PostgresSupport
import org.a1tsign.vds.support.UserFixture
import org.a1tsign.vds.support.capturingLogs
import org.a1tsign.vds.support.loginForToken
import org.a1tsign.vds.support.loginAs
import org.a1tsign.vds.support.loginResponse
import org.a1tsign.vds.support.testAppConfig
import org.a1tsign.vds.support.testAppDb
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.Date
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AuthTest : AbstractDbTest() {
    @Test
    fun fullApplicationHarnessUsesAllowlistAndInjectedClock() = runBlocking {
        val capture = capturingLogs {
            runBlocking {
                testAppDb(clock, testAppConfig(withBootstrapAdmin = false)) {
                    assertEquals(HttpStatusCode.OK, client.get("/health").status)
                    val error = loginResponse("missing", "not-the-password")
                    assertEquals(HttpStatusCode.Unauthorized, error.status)
                    val body = Json.parseToJsonElement(error.bodyAsText()).jsonObject
                    assertEquals(clock.instant().toString(), body.getValue("timestamp").jsonPrimitive.content)
                }
            }
        }
        assertTrue(capture.messages().any { it.contains("No users exist") })
    }

    @Test
    fun bootstrapCreatesAnAdminOnlyForAnEmptyUserTableAndIsIdempotent() = runBlocking {
        val config = testAppConfig()
        testAppDb(clock, config) {
            val token = loginForToken("bootstrap-admin", "bootstrap-password-1")
            assertTrue(token.isNotBlank())
            val before = bootstrapRow("bootstrap-admin")
            assertNotNull(before)
            assertEquals("ACTIVE", before.status)
            assertEquals(1L, before.count)
            val hashBefore = before.passwordHash

            BootstrapAdmin(PostgresSupport.database, UserRepository(), PasswordHasher())
                .ensure(config.bootstrapAdmin, clock.instant())
            val after = bootstrapRow("bootstrap-admin")
            assertNotNull(after)
            assertEquals(1L, after.count)
            assertEquals(hashBefore, after.passwordHash)
            assertEquals(setOf("ADMIN"), userRoles("bootstrap-admin"))
        }
    }

    @Test
    fun concurrentApplicationStartsCreateOnlyOneBootstrapAdmin() {
        val firstConfig = testAppConfig(bootstrapUsername = "bootstrap-one")
        val secondConfig = testAppConfig(bootstrapUsername = "bootstrap-two")
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val starts = listOf(firstConfig, secondConfig).map { config ->
                pool.submit {
                    start.await()
                    val users = UserRepository()
                    val hasher = PasswordHasher()
                    BootstrapAdmin(PostgresSupport.database, users, hasher)
                        .ensure(config.bootstrapAdmin, clock.instant())
                }
            }
            start.countDown()
            starts.forEach { it.get(30, TimeUnit.SECONDS) }

            val count = PostgresSupport.dataSource.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery("SELECT count(*) FROM app_user").use { rows ->
                        rows.next()
                        rows.getLong(1)
                    }
                }
            }
            assertEquals(1L, count)
            val createdAdminNames = listOf("bootstrap-one", "bootstrap-two").filter { userRoles(it) == setOf("ADMIN") }
            assertEquals(1, createdAdminNames.size)
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun loginReturnsExactRolesExpiryAndDoesNotLeakCredentialsToLogs() = runBlocking {
        val passwordHasher = PasswordHasher()
        val fixture = fixture(passwordHasher)
        val user = fixture.create("editor-user", TEST_PASSWORD, setOf(Role.EDITOR, Role.VIEWER), at = clock.instant())
        val secret = testAppConfig().jwt.secret.reveal()
        var responseBody = ""
        var token = ""
        val capture = capturingLogs {
            runBlocking {
                testAppDb(clock, testAppConfig(withBootstrapAdmin = false), passwordHasher) {
                    val response = loginResponse("editor-user", TEST_PASSWORD)
                    assertEquals(HttpStatusCode.OK, response.status)
                    responseBody = response.bodyAsText()
                    val body = Json.parseToJsonElement(responseBody).jsonObject
                    token = body.getValue("token").jsonPrimitive.content
                    assertEquals("editor-user", body.getValue("username").jsonPrimitive.content)
                    assertEquals(listOf("EDITOR", "VIEWER"), body.getValue("roles").jsonArray.map { it.jsonPrimitive.content })
                    assertEquals("2024-01-02T00:00:00Z", body.getValue("expiresAt").jsonPrimitive.content)
                }
            }
        }
        assertTrue(token.isNotBlank())
        assertFalse(responseBody.contains(TEST_PASSWORD))
        assertFalse(responseBody.contains(user.user.passwordHash))
        assertFalse(responseBody.contains(secret))
        assertTrue(capture.messages().none { line ->
            line.contains(TEST_PASSWORD) || line.contains(user.user.passwordHash) || line.contains(secret) || line.contains(token)
        })
    }

    @Test
    fun failedLoginsHaveIdenticalBodiesAndUnknownUsernameStillRunsOneBcryptVerification() {
        val passwords = CountingPasswordHasher()
        val fixture = fixture(passwords)
        val knownUser = fixture.create("known", TEST_PASSWORD, setOf(Role.VIEWER), at = clock.instant())
        val disabledUser = fixture.create("disabled", TEST_PASSWORD, setOf(Role.VIEWER), UserStatus.DISABLED, clock.instant())
        val secret = testAppConfig(withBootstrapAdmin = false).jwt.secret.reveal()
        var bodies = emptyList<String>()

        val capture = capturingLogs {
            runBlocking {
                testAppDb(clock, testAppConfig(withBootstrapAdmin = false), passwords) {
                    val wrong = loginResponse("known", "wrong-password")
                    val unknown = loginResponse("unknown", "wrong-password")
                    val disabled = loginResponse("disabled", TEST_PASSWORD)
                    assertEquals(HttpStatusCode.Unauthorized, wrong.status)
                    bodies = listOf(wrong.bodyAsText(), unknown.bodyAsText(), disabled.bodyAsText())
                    assertEquals(bodies[0], bodies[1])
                    assertEquals(bodies[0], bodies[2])
                }
            }
        }
        assertEquals(3, passwords.verifications.get())
        assertEquals(1, passwords.unknownUserVerifications.get())
        assertTrue(bodies.none { it.contains(TEST_PASSWORD) || it.contains(knownUser.user.passwordHash) || it.contains(secret) })
        assertTrue(capture.messages().none { line ->
            line.contains(TEST_PASSWORD) || line.contains(knownUser.user.passwordHash) ||
                line.contains(disabledUser.user.passwordHash) || line.contains(secret)
        })
    }

    @Test
    fun bearerProviderRejectsMalformedWrongSignatureAndNoneAlgorithmAndEnforcesRoles() = runBlocking {
        val appConfig = testAppConfig(withBootstrapAdmin = false)

        testAppDb(clock, appConfig, authenticatedRoutes = {
            get("/test/read") { call.requireRole(Role.VIEWER); call.respondText("read") }
            get("/test/write") { call.requireRole(Role.EDITOR); call.respondText("write") }
        }) {
            val users = fixture()
            val viewerToken = loginAs(users, "viewer", TEST_PASSWORD, setOf(Role.VIEWER), clock.instant())
            val adminToken = loginAs(users, "admin", TEST_PASSWORD, setOf(Role.ADMIN), clock.instant())
            assertEquals(HttpStatusCode.Unauthorized, client.get("/test/read").status)
            assertEquals(HttpStatusCode.Unauthorized, getWithBearer("/test/read", "malformed-token").status)
            val wrongSignature = makeToken(88, appConfig.jwt.secret.reveal() + "different")
            assertEquals(HttpStatusCode.Unauthorized, getWithBearer("/test/read", wrongSignature).status)
            val noneToken = makeToken(88, null)
            assertEquals(HttpStatusCode.Unauthorized, getWithBearer("/test/read", noneToken).status)

            assertEquals(HttpStatusCode.OK, getWithBearer("/test/read", viewerToken).status)
            assertEquals(HttpStatusCode.Forbidden, getWithBearer("/test/write", viewerToken).status)
            assertEquals(HttpStatusCode.OK, getWithBearer("/test/write", adminToken).status)
        }
    }

    @Test
    fun jwtExpiryUsesInjectedClockAndHonorsThirtySecondLeeway() = runBlocking {
        fixture().create("clocked", TEST_PASSWORD, setOf(Role.VIEWER), at = clock.instant())
        testAppDb(clock, testAppConfig(withBootstrapAdmin = false, jwtTtlHours = 1), authenticatedRoutes = {
            get("/test/read") { call.requireRole(Role.VIEWER); call.respondText("read") }
        }) {
            val token = loginForToken("clocked", TEST_PASSWORD)
            clock.advance(Duration.ofHours(1).plusSeconds(20))
            assertEquals(HttpStatusCode.OK, getWithBearer("/test/read", token).status)
            clock.advance(Duration.ofSeconds(11))
            assertEquals(HttpStatusCode.Unauthorized, getWithBearer("/test/read", token).status)
        }
    }

    @Test
    fun databaseStatusRoleAndTokenVersionChangesApplyOnNextRequest() = runBlocking {
        fixture().create("disable-me", TEST_PASSWORD, setOf(Role.VIEWER), at = clock.instant())
        fixture().create("role-change", TEST_PASSWORD, setOf(Role.EDITOR, Role.VIEWER), at = clock.instant())
        fixture().create("version-change", TEST_PASSWORD, setOf(Role.VIEWER), at = clock.instant())

        testAppDb(clock, testAppConfig(withBootstrapAdmin = false), authenticatedRoutes = {
            get("/test/read") { call.requireRole(Role.VIEWER); call.respondText("read") }
            get("/test/write") { call.requireRole(Role.EDITOR); call.respondText("write") }
        }) {
            val disableToken = loginForToken("disable-me", TEST_PASSWORD)
            updateUser("disable-me", "UPDATE app_user SET status = 'DISABLED' WHERE username = ?")
            assertEquals(HttpStatusCode.Unauthorized, getWithBearer("/test/read", disableToken).status)

            val roleToken = loginForToken("role-change", TEST_PASSWORD)
            PostgresSupport.dataSource.connection.use { connection ->
                connection.prepareStatement(
                    "DELETE FROM user_role WHERE user_id = (SELECT id FROM app_user WHERE username = ?) " +
                        "AND role_id = (SELECT id FROM role WHERE code = 'EDITOR')",
                ).use { it.setString(1, "role-change"); it.executeUpdate() }
            }
            assertEquals(HttpStatusCode.OK, getWithBearer("/test/read", roleToken).status)
            assertEquals(HttpStatusCode.Forbidden, getWithBearer("/test/write", roleToken).status)

            val versionToken = loginForToken("version-change", TEST_PASSWORD)
            updateUser("version-change", "UPDATE app_user SET token_version = token_version + 1 WHERE username = ?")
            assertEquals(HttpStatusCode.Unauthorized, getWithBearer("/test/read", versionToken).status)
        }
    }

    @Test
    fun absentBootstrapConfigAllowsStartupAndWarns() = runBlocking {
        val capture = capturingLogs {
            runBlocking {
                testAppDb(clock, testAppConfig(withBootstrapAdmin = false)) {
                    assertEquals(HttpStatusCode.OK, client.get("/health").status)
                }
            }
        }
        assertTrue(capture.messagesAt(org.slf4j.event.Level.WARN).any { it.contains("No users exist") })
    }

    @Test
    fun passwordPolicyUsesCharacterAndUtf8ByteLimits() {
        val passwords = PasswordHasher()
        assertNotNull(passwords.passwordPolicyError("12345678901"))
        assertNotNull(passwords.passwordPolicyError("é".repeat(37)))
        assertEquals(null, passwords.passwordPolicyError("123456789012"))
        assertEquals(null, passwords.passwordPolicyError("é".repeat(36)))
    }

    private fun fixture(passwords: PasswordHasher = PasswordHasher()) =
        UserFixture(PostgresSupport.database, UserRepository(), passwords)

    private suspend fun io.ktor.server.testing.ApplicationTestBuilder.getWithBearer(path: String, token: String) =
        client.get(path) { header(HttpHeaders.Authorization, "Bearer $token") }

    private fun updateUser(username: String, sql: String) {
        PostgresSupport.dataSource.connection.use { connection ->
            connection.prepareStatement(sql).use { it.setString(1, username); it.executeUpdate() }
        }
    }

    private fun bootstrapRow(username: String): BootstrapRow? = PostgresSupport.dataSource.connection.use { connection ->
        connection.prepareStatement(
            "SELECT u.status, u.password_hash, count(*) OVER () FROM app_user u WHERE u.username = ?",
        ).use { statement ->
            statement.setString(1, username)
            statement.executeQuery().use { rows ->
                if (!rows.next()) null else BootstrapRow(rows.getString(1), rows.getString(2), rows.getLong(3))
            }
        }
    }

    private fun userRoles(username: String): Set<String> = PostgresSupport.dataSource.connection.use { connection ->
        connection.prepareStatement(
            "SELECT r.code FROM role r JOIN user_role ur ON ur.role_id = r.id " +
                "JOIN app_user u ON u.id = ur.user_id WHERE u.username = ?",
        ).use { statement ->
            statement.setString(1, username)
            statement.executeQuery().use { rows -> buildSet { while (rows.next()) add(rows.getString(1)) } }
        }
    }

    private fun makeToken(userId: Long, secret: String?): String {
        val algorithm = secret?.let(Algorithm::HMAC256) ?: Algorithm.none()
        val now = clock.instant()
        return JWT.create().withIssuer(JwtService.ISSUER).withSubject(userId.toString()).withClaim("ver", 0)
            .withIssuedAt(Date.from(now)).withExpiresAt(Date.from(now.plusSeconds(3600))).sign(algorithm)
    }

    private data class BootstrapRow(val status: String, val passwordHash: String, val count: Long)

    private class CountingPasswordHasher : PasswordHasher() {
        val verifications = AtomicInteger()
        val unknownUserVerifications = AtomicInteger()

        override fun verify(password: String, encoded: String?): Boolean {
            verifications.incrementAndGet()
            if (encoded == null) unknownUserVerifications.incrementAndGet()
            return super.verify(password, encoded)
        }
    }

    companion object {
        const val TEST_PASSWORD = "correct-password"
    }
}
