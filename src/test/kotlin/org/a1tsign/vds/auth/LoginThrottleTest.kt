package org.a1tsign.vds.auth

import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.Clock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import io.ktor.http.HttpStatusCode
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import org.a1tsign.vds.domain.Role
import org.a1tsign.vds.support.AbstractDbTest
import org.a1tsign.vds.support.PostgresSupport
import org.a1tsign.vds.support.UserFixture
import org.a1tsign.vds.support.loginResponse
import org.a1tsign.vds.support.testAppConfig
import org.a1tsign.vds.support.testAppDb
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.a1tsign.vds.db.UserRepository
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.a1tsign.vds.api.TooManyRequestsException
import org.a1tsign.vds.api.ServiceUnavailableException

class LoginThrottleTest : AbstractDbTest() {
    private class TestClock(var now: Instant = Instant.parse("2024-01-01T00:00:00Z")) : Clock() {
        override fun getZone() = ZoneOffset.UTC
        override fun withZone(zone: java.time.ZoneId): Clock = this
        override fun instant() = now
    }

    @Test
    fun `pair limit expires after sliding window and successful login clears pair`() {
        val clock = TestClock()
        val throttle = LoginThrottle(clock)
        repeat(4) { fail(throttle, "ip", "user") }
        throttle.recordSuccess(throttle.reserve("ip", "user"))
        repeat(4) { fail(throttle, "ip", "user") }
        throttle.release(throttle.reserve("ip", "user"))
        fail(throttle, "ip", "user")
        assertFailsWith<TooManyRequestsException> { throttle.reserve("ip", "user") }
        clock.now = clock.now.plus(Duration.ofMinutes(5)).plusSeconds(1)
        throttle.release(throttle.reserve("ip", "user"))
    }

    @Test
    fun `counter map stays bounded`() {
        val throttle = LoginThrottle(Clock.fixed(Instant.EPOCH, ZoneOffset.UTC))
        repeat(5_100) { fail(throttle, "ip-$it", "user") }
        assertEquals(10_000, throttle.size)
    }

    @Test
    fun `concurrent pair admissions reserve the final slot atomically`() {
        val throttle = LoginThrottle(Clock.fixed(Instant.EPOCH, ZoneOffset.UTC))
        repeat(4) { fail(throttle, "ip", "user") }
        val start = CountDownLatch(1)
        val pool = java.util.concurrent.Executors.newFixedThreadPool(2)
        try {
            val attempts = (1..2).map {
                pool.submit<LoginThrottle.Reservation?> {
                    start.await()
                    try {
                        throttle.reserve("ip", "user")
                    } catch (_: TooManyRequestsException) {
                        null
                    }
                }
            }
            start.countDown()
            val admissions = attempts.map { it.get(5, TimeUnit.SECONDS) }
            assertEquals(1, admissions.count { it != null })
            assertEquals(2, throttle.size)
            throttle.release(admissions.single { it != null }!!)
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `active reservations cannot be evicted to free admission capacity`() {
        val throttle = LoginThrottle(Clock.fixed(Instant.EPOCH, ZoneOffset.UTC), maxEntries = 2)
        val active = throttle.reserve("ip-1", "user")
        assertFailsWith<ServiceUnavailableException> { throttle.reserve("ip-2", "user") }
        assertEquals(2, throttle.size)
        throttle.release(active)
    }

    @Test
    fun `http login enforces pair and ip caps before bcrypt and expires counters`() = runBlocking {
        val passwords = CountingHasher()
        UserFixture(PostgresSupport.database, UserRepository(), passwords)
            .create("other", "correct-password", setOf(Role.VIEWER), at = clock.instant())
        UserFixture(PostgresSupport.database, UserRepository(), passwords)
            .create("same", "correct-password", setOf(Role.VIEWER), at = clock.instant())
        testAppDb(clock, testAppConfig(withBootstrapAdmin = false), passwords) {
            repeat(5) { assertEquals(HttpStatusCode.Unauthorized, loginResponse("missing", "wrong-password").status) }
            val beforeThrottle = passwords.verifications.get()
            val limited = loginResponse("missing", "wrong-password")
            assertEquals(HttpStatusCode.TooManyRequests, limited.status)
            assertTrue(limited.headers[io.ktor.http.HttpHeaders.RetryAfter] != null)
            val error = Json.parseToJsonElement(limited.bodyAsText()).jsonObject
            assertEquals(429, error.getValue("status").jsonPrimitive.content.toInt())
            assertEquals("Too Many Requests", error.getValue("error").jsonPrimitive.content)
            assertEquals("/api/v1/auth/login", error.getValue("path").jsonPrimitive.content)
            assertEquals(beforeThrottle, passwords.verifications.get())
            assertEquals(HttpStatusCode.OK, loginResponse("other", "correct-password").status)
            repeat(4) { assertEquals(HttpStatusCode.Unauthorized, loginResponse("same", "bad-password").status) }
            assertEquals(HttpStatusCode.OK, loginResponse("same", "correct-password").status)
            repeat(4) { assertEquals(HttpStatusCode.Unauthorized, loginResponse("same", "bad-password").status) }
            assertEquals(HttpStatusCode.Unauthorized, loginResponse("same", "bad-password").status)
            assertEquals(HttpStatusCode.TooManyRequests, loginResponse("same", "correct-password").status)
            clock.advance(Duration.ofMinutes(5).plusSeconds(1))
            assertEquals(HttpStatusCode.Unauthorized, loginResponse("missing", "wrong-password").status)
        }
    }

    @Test
    fun `http login enforces independent ip cap across usernames`() = runBlocking {
        val passwords = PasswordHasher()
        UserFixture(PostgresSupport.database, UserRepository(), passwords)
            .create("correct-user", "correct-password", setOf(Role.VIEWER), at = clock.instant())
        testAppDb(clock, testAppConfig(withBootstrapAdmin = false), passwords) {
            repeat(20) { index ->
                assertEquals(HttpStatusCode.Unauthorized, loginResponse("name-$index", "wrong-password").status)
            }
            assertEquals(HttpStatusCode.TooManyRequests, loginResponse("correct-user", "correct-password").status)
        }
    }

    @Test
    fun `unknown user bcrypt work holds permits and third request times out`() = runBlocking {
        val passwords = BlockingHasher()
        testAppDb(clock, testAppConfig(withBootstrapAdmin = false), passwords) {
            val first = async { loginResponse("missing-a", "wrong-password") }
            val second = async { loginResponse("missing-b", "wrong-password") }
            assertTrue(passwords.started.await(5, TimeUnit.SECONDS), "two unknown-user verifications should start")
            try {
                val third = loginResponse("missing-c", "wrong-password")
                assertEquals(HttpStatusCode.ServiceUnavailable, third.status)
            } finally {
                passwords.release.countDown()
            }
            assertEquals(HttpStatusCode.Unauthorized, first.await().status)
            assertEquals(HttpStatusCode.Unauthorized, second.await().status)
        }
    }

    private class CountingHasher : PasswordHasher() {
        val verifications = AtomicInteger()
        override fun verify(password: String, encoded: String?): Boolean {
            verifications.incrementAndGet()
            return super.verify(password, encoded)
        }
    }

    private fun fail(throttle: LoginThrottle, ip: String, username: String) {
        throttle.recordFailure(throttle.reserve(ip, username))
    }

    private class BlockingHasher : PasswordHasher() {
        val started = CountDownLatch(2)
        val release = CountDownLatch(1)
        override fun verify(password: String, encoded: String?): Boolean {
            started.countDown()
            check(release.await(10, TimeUnit.SECONDS)) { "test did not release blocked bcrypt" }
            return super.verify(password, encoded)
        }
    }
}
