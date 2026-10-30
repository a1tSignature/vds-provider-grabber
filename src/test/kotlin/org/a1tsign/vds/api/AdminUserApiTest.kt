package org.a1tsign.vds.api

import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.a1tsign.vds.auth.PasswordHasher
import org.a1tsign.vds.db.UserRepository
import org.a1tsign.vds.domain.Role
import org.a1tsign.vds.domain.UserStatus
import org.a1tsign.vds.support.*
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.test.*

class AdminUserApiTest : AbstractDbTest() {
    private val path = "/api/v1/admin/users"
    private fun auth(token: String) = HttpHeaders.Authorization to "Bearer $token"

    @Test fun createValidationAuthorizationAndSafePagedList() = runBlocking {
        testAppDb(clock) {
            val admin = loginForToken("bootstrap-admin", "bootstrap-password-1")
            val created = client.post(path) { header(auth(admin).first, auth(admin).second); contentType(ContentType.Application.Json); setBody("""{"username":"created-user","password":"long-password-1","email":"a@example.test","roles":["EDITOR"]}""") }
            assertEquals(HttpStatusCode.Created, created.status)
            val id = Json.parseToJsonElement(created.bodyAsText()).jsonObject.getValue("id").jsonPrimitive.long
            val twelve = client.post(path) { header(auth(admin).first, auth(admin).second); contentType(ContentType.Application.Json); setBody("""{"username":"twelve-char","password":"123456789012","roles":["VIEWER"]}""") }
            assertEquals(HttpStatusCode.Created, twelve.status)
            val twelveId = Json.parseToJsonElement(twelve.bodyAsText()).jsonObject.getValue("id").jsonPrimitive.long
            assertEquals("$path/$id", created.headers[HttpHeaders.Location])
            assertEquals(HttpStatusCode.Conflict, client.post(path) { header(auth(admin).first, auth(admin).second); contentType(ContentType.Application.Json); setBody("""{"username":"created-user","password":"long-password-1","roles":["EDITOR"]}""") }.status)
            assertEquals(HttpStatusCode.BadRequest, client.post(path) { header(auth(admin).first, auth(admin).second); contentType(ContentType.Application.Json); setBody("""{"username":"bad-role","password":"long-password-1","roles":["WHAT"]}""") }.status)
            for (password in listOf("12345678901", "é".repeat(36) + "x")) {
                assertEquals(HttpStatusCode.BadRequest, client.post(path) { header(auth(admin).first, auth(admin).second); contentType(ContentType.Application.Json); setBody(ApiJson.encodeToString(org.a1tsign.vds.api.dto.CreateUserRequest("bad-${password.length}", password, null, listOf("EDITOR")))) }.status)
            }
            val knownHash = transaction(PostgresSupport.database) { UserRepository().findById(id)!!.user.passwordHash }
            val list = client.get(path) { header(auth(admin).first, auth(admin).second); parameter("size", "1") }
            val raw = list.bodyAsText()
            assertEquals(HttpStatusCode.OK, list.status)
            assertFalse(raw.contains(knownHash) || raw.contains("passwordHash") || raw.contains("password_hash"))
            assertEquals(HttpStatusCode.BadRequest, client.get(path) { header(auth(admin).first, auth(admin).second); parameter("size", "101") }.status)
            assertEquals(HttpStatusCode.BadRequest, client.get(path) { header(auth(admin).first, auth(admin).second); parameter("sort", "unknown") }.status)
            val bootstrapId = transaction(PostgresSupport.database) { UserRepository().findByUsername("bootstrap-admin")!!.user.id }
            val page0 = Json.parseToJsonElement(client.get(path) { header(auth(admin).first, auth(admin).second); parameter("size", "1"); parameter("page", "0") }.bodyAsText()).jsonObject
            val page1Response = client.get(path) { header(auth(admin).first, auth(admin).second); parameter("size", "1"); parameter("page", "1") }
            val page1 = Json.parseToJsonElement(page1Response.bodyAsText()).jsonObject
            assertEquals(page0.getValue("totalElements").jsonPrimitive.long, page1.getValue("totalElements").jsonPrimitive.long)
            assertEquals(page0.getValue("totalPages").jsonPrimitive.int, page1.getValue("totalPages").jsonPrimitive.int)
            assertEquals(bootstrapId, page0.getValue("content").jsonArray.single().jsonObject.getValue("id").jsonPrimitive.long)
            assertEquals(id, page1.getValue("content").jsonArray.single().jsonObject.getValue("id").jsonPrimitive.long)
            assertEquals(3L, page1.getValue("totalElements").jsonPrimitive.long)
            assertEquals(3, page1.getValue("totalPages").jsonPrimitive.int)
            val page2 = Json.parseToJsonElement(client.get(path) { header(auth(admin).first, auth(admin).second); parameter("size", "1"); parameter("page", "2") }.bodyAsText()).jsonObject
            assertEquals(twelveId, page2.getValue("content").jsonArray.single().jsonObject.getValue("id").jsonPrimitive.long)
            assertEquals(HttpStatusCode.BadRequest, client.patch("$path/$id") { header(auth(admin).first, auth(admin).second); contentType(ContentType.Application.Json); setBody("{}") }.status)
            assertEquals(HttpStatusCode.NotFound, client.patch("$path/999999") { header(auth(admin).first, auth(admin).second); contentType(ContentType.Application.Json); setBody("""{"status":"DISABLED"}""") }.status)
            assertEquals(HttpStatusCode.NotFound, client.put("$path/999999/password") { header(auth(admin).first, auth(admin).second); contentType(ContentType.Application.Json); setBody("""{"password":"long-password-1"}""") }.status)
            val editor = loginForToken("created-user", "long-password-1")
            val viewer = loginForToken("twelve-char", "123456789012")
            val noAuthChecks: List<suspend (String?) -> io.ktor.client.statement.HttpResponse> = listOf(
                { token: String? -> client.get(path) { if (token != null) header(auth(token).first, auth(token).second) } },
                { token: String? -> client.post(path) { if (token != null) header(auth(token).first, auth(token).second); contentType(ContentType.Application.Json); setBody("""{"username":"denied-${token?.take(5)}","password":"long-password-1","roles":["EDITOR"]}""") } },
                { token: String? -> client.patch("$path/$id") { if (token != null) header(auth(token).first, auth(token).second); contentType(ContentType.Application.Json); setBody("""{"status":"ACTIVE"}""") } },
                { token: String? -> client.put("$path/$id/password") { if (token != null) header(auth(token).first, auth(token).second); contentType(ContentType.Application.Json); setBody("""{"password":"long-password-2"}""") } },
            )
            for (route in noAuthChecks) {
                assertEquals(HttpStatusCode.Unauthorized, route(null).status)
                assertEquals(HttpStatusCode.Forbidden, route(editor).status)
                assertEquals(HttpStatusCode.Forbidden, route(viewer).status)
            }
        }
    }

    @Test fun createRejectsOverLongUsernameAndEmailWithBadRequestNotInternalError() = runBlocking {
        testAppDb(clock) {
            val admin = loginForToken("bootstrap-admin", "bootstrap-password-1")
            val overLongUsername = client.post(path) {
                header(auth(admin).first, auth(admin).second)
                contentType(ContentType.Application.Json)
                setBody("""{"username":"${"a".repeat(65)}","password":"long-password-1","roles":["VIEWER"]}""")
            }
            assertEquals(HttpStatusCode.BadRequest, overLongUsername.status)
            val overLongEmail = client.post(path) {
                header(auth(admin).first, auth(admin).second)
                contentType(ContentType.Application.Json)
                setBody("""{"username":"email-len-user","password":"long-password-1","email":"${"a".repeat(250)}@example.com","roles":["VIEWER"]}""")
            }
            assertEquals(HttpStatusCode.BadRequest, overLongEmail.status)
        }
    }

    @Test fun lastAdminAndTokenVersionRules() = runBlocking {
        testAppDb(clock) {
            val repo = UserRepository(); val fixture = UserFixture(PostgresSupport.database, repo, PasswordHasher())
            val admin = loginForToken("bootstrap-admin", "bootstrap-password-1")
            val bootstrapId = transaction(PostgresSupport.database) { repo.findByUsername("bootstrap-admin")!!.user.id }
            assertEquals(HttpStatusCode.Conflict, client.patch("$path/$bootstrapId") { header(auth(admin).first, auth(admin).second); contentType(ContentType.Application.Json); setBody("""{"status":"DISABLED"}""") }.status)
            assertEquals(HttpStatusCode.Conflict, client.patch("$path/$bootstrapId") { header(auth(admin).first, auth(admin).second); contentType(ContentType.Application.Json); setBody("""{"roles":["EDITOR"]}""") }.status)
            val demotedUser = fixture.create("disabled-editor", "long-password-4", setOf(Role.EDITOR), at = clock.instant())
            val disabledToken = loginForToken("disabled-editor", "long-password-4")
            assertEquals(HttpStatusCode.OK, client.patch("$path/${demotedUser.user.id}") { header(auth(admin).first, auth(admin).second); contentType(ContentType.Application.Json); setBody("""{"status":"DISABLED"}""") }.status)
            assertEquals(HttpStatusCode.Unauthorized, client.get(path) { header(auth(disabledToken).first, auth(disabledToken).second) }.status)
            val second = fixture.create("second-admin", "long-password-2", setOf(Role.ADMIN), at = clock.instant())
            val token2 = loginForToken("second-admin", "long-password-2")
            assertEquals(HttpStatusCode.OK, client.patch("$path/$bootstrapId") { header(auth(token2).first, auth(token2).second); contentType(ContentType.Application.Json); setBody("""{"roles":["EDITOR"]}""") }.status)
            assertEquals(setOf(Role.EDITOR), transaction(PostgresSupport.database) { repo.findById(bootstrapId)!!.roles })
            assertEquals(HttpStatusCode.Forbidden, client.patch("$path/${second.user.id}") { header(auth(admin).first, auth(admin).second); contentType(ContentType.Application.Json); setBody("""{"status":"ACTIVE"}""") }.status)
            assertEquals(HttpStatusCode.OK, client.patch("$path/$bootstrapId") { header(auth(token2).first, auth(token2).second); contentType(ContentType.Application.Json); setBody("""{"roles":["ADMIN"]}""") }.status)
            assertEquals(HttpStatusCode.OK, client.patch("$path/$bootstrapId") { header(auth(token2).first, auth(token2).second); contentType(ContentType.Application.Json); setBody("""{"status":"DISABLED"}""") }.status)
            assertEquals(HttpStatusCode.Unauthorized, client.get(path) { header(auth(admin).first, auth(admin).second) }.status)
            assertEquals(HttpStatusCode.OK, client.patch("$path/$bootstrapId") { header(auth(token2).first, auth(token2).second); contentType(ContentType.Application.Json); setBody("""{"status":"ACTIVE"}""") }.status)
            assertEquals(HttpStatusCode.NoContent, client.put("$path/${second.user.id}/password") { header(auth(token2).first, auth(token2).second); contentType(ContentType.Application.Json); setBody("""{"password":"changed-password-3"}""") }.status)
            assertEquals(HttpStatusCode.Unauthorized, client.get(path) { header(auth(token2).first, auth(token2).second) }.status)
            val renewed = loginForToken("second-admin", "changed-password-3")
            assertEquals(HttpStatusCode.OK, client.patch("$path/${second.user.id}") { header(auth(renewed).first, auth(renewed).second); contentType(ContentType.Application.Json); setBody("""{"status":"DISABLED"}""") }.status)
            assertEquals(HttpStatusCode.Unauthorized, client.get(path) { header(auth(renewed).first, auth(renewed).second) }.status)
            val bootstrapAgain = loginForToken("bootstrap-admin", "bootstrap-password-1")
            assertEquals(HttpStatusCode.Conflict, client.patch("$path/$bootstrapId") { header(auth(bootstrapAgain).first, auth(bootstrapAgain).second); contentType(ContentType.Application.Json); setBody("""{"status":"DISABLED"}""") }.status)
        }
    }

    @Test fun concurrentLastAdminsCannotBothDemote() {
        val users = UserRepository(); val fixture = UserFixture(PostgresSupport.database, users, PasswordHasher())
        val a = fixture.create("concurrent-admin-a", "long-password-a", setOf(Role.ADMIN), at = clock.instant())
        val b = fixture.create("concurrent-admin-b", "long-password-b", setOf(Role.ADMIN), at = clock.instant())
        val start = CountDownLatch(1); val pool = Executors.newFixedThreadPool(2)
        try {
            val futures = listOf(a, b).map { target -> pool.submit<Boolean> { start.await(); try { kotlinx.coroutines.runBlocking { org.a1tsign.vds.service.UserService(PostgresSupport.database, users, PasswordHasher(), clock).patch(target.user.id, UserStatus.DISABLED, null) }; true } catch (_: org.a1tsign.vds.api.ConflictException) { false } } }
            start.countDown(); val results = futures.map { it.get() }
            assertEquals(1, results.count { it })
            assertEquals(1L, transaction(PostgresSupport.database) { users.countActiveAdmins() })
        } finally { pool.shutdownNow() }
    }

    @Test fun partialPatchReadsTargetAfterWaitingForMutationMutex() = runBlocking {
        val users = UserRepository(); val fixture = UserFixture(PostgresSupport.database, users, PasswordHasher())
        val target = fixture.create("stale-patch-target", "long-password-target", setOf(Role.ADMIN), at = clock.instant())
        fixture.create("stale-patch-other-admin", "long-password-other", setOf(Role.ADMIN), at = clock.instant())

        PostgresSupport.dataSource.connection.use { lockConnection ->
            lockConnection.autoCommit = false
            lockConnection.createStatement().use { it.executeQuery("SELECT id FROM role WHERE code = 'ADMIN' FOR UPDATE").use { rs -> assertTrue(rs.next()) } }
            try {
                val pending = async(Dispatchers.IO) {
                    org.a1tsign.vds.service.UserService(PostgresSupport.database, users, PasswordHasher(), clock)
                        .patch(target.user.id, null, setOf(Role.EDITOR))
                }
                val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10)
                var waiting = false
                while (!waiting && System.nanoTime() < deadline) {
                    PostgresSupport.dataSource.connection.use { observer ->
                        observer.prepareStatement(
                            "SELECT count(*) FROM pg_stat_activity WHERE datname = current_database() AND wait_event_type = 'Lock' AND query ILIKE '%FOR UPDATE%' AND pid <> pg_backend_pid()",
                        ).use { statement -> statement.executeQuery().use { rs -> waiting = rs.next() && rs.getInt(1) > 0 } }
                    }
                    if (!waiting) Thread.sleep(10)
                }
                assertTrue(waiting, "PATCH should wait for the shared ADMIN row mutex")
                PostgresSupport.dataSource.connection.use { updater ->
                    updater.prepareStatement("UPDATE app_user SET status = 'DISABLED' WHERE id = ?").use { statement -> statement.setLong(1, target.user.id); assertEquals(1, statement.executeUpdate()) }
                }
                lockConnection.commit()
                pending.await()
            } finally {
                if (!lockConnection.autoCommit) runCatching { lockConnection.rollback() }
            }
        }
        val after = transaction(PostgresSupport.database) { users.findById(target.user.id)!! }
        assertEquals(UserStatus.DISABLED, after.user.status)
        assertEquals(setOf(Role.EDITOR), after.roles)
        assertEquals(1L, transaction(PostgresSupport.database) { users.countActiveAdmins() })
    }
}
