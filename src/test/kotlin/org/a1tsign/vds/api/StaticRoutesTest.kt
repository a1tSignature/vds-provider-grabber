package org.a1tsign.vds.api

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.a1tsign.vds.auth.PasswordHasher
import org.a1tsign.vds.db.UserRepository
import org.a1tsign.vds.domain.Role
import org.a1tsign.vds.support.AbstractDbTest
import org.a1tsign.vds.support.PostgresSupport
import org.a1tsign.vds.support.UserFixture
import org.a1tsign.vds.support.loginAs
import org.a1tsign.vds.support.testAppDb
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `api/routes/StaticRoutes.kt` against the fixture bundle under
 * `src/test/resources/static-fixture/` (not a Node build -- see the task's "how to verify").
 */
class StaticRoutesTest : AbstractDbTest() {
    private fun auth(token: String) = HttpHeaders.Authorization to "Bearer $token"

    @Test
    fun `root and a deep link both serve the fixture index with no-cache`() = kotlinx.coroutines.runBlocking {
        testAppDb(clock, staticBasePackage = "static-fixture") {
            for (path in listOf("/", "/providers/42")) {
                val response = client.get(path)
                assertEquals(HttpStatusCode.OK, response.status, "GET $path")
                assertTrue(response.contentType()?.match(ContentType.Text.Html) == true, "GET $path content type")
                assertTrue(response.bodyAsText().contains("static-fixture-index"), "GET $path body")
                assertEquals("no-cache", response.headers[HttpHeaders.CacheControl], "GET $path cache-control")
            }
        }
    }

    @Test
    fun `an asset carries the immutable cache header`() = kotlinx.coroutines.runBlocking {
        testAppDb(clock, staticBasePackage = "static-fixture") {
            val response = client.get("/assets/app.js")

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(
                "public, max-age=31536000, immutable",
                response.headers[HttpHeaders.CacheControl],
            )
        }
    }

    @Test
    fun `an unmatched path under a reserved prefix gets the JSON 404 envelope, not HTML`() = kotlinx.coroutines.runBlocking {
        testAppDb(clock, staticBasePackage = "static-fixture") {
            for (path in listOf("/api/v1/definitely-missing", "/health/nope", "/version/x", "/api")) {
                val response = client.get(path)
                assertEquals(HttpStatusCode.NotFound, response.status, "GET $path")
                assertTrue(
                    response.contentType()?.match(ContentType.Application.Json) == true,
                    "GET $path must answer JSON, not HTML, was ${response.contentType()}",
                )
                val envelope = Json.parseToJsonElement(response.bodyAsText()).jsonObject
                assertEquals(404, envelope.getValue("status").jsonPrimitive.int)
                assertTrue(envelope.containsKey("error"))
                assertTrue(envelope.containsKey("message"))
                assertEquals(path, envelope.getValue("path").jsonPrimitive.content)
            }
        }
    }

    @Test
    fun `openapi yaml without a token still answers 401, not HTML`() = kotlinx.coroutines.runBlocking {
        testAppDb(clock, staticBasePackage = "static-fixture") {
            val response = client.get("/openapi.yaml")

            assertEquals(HttpStatusCode.Unauthorized, response.status)
            assertTrue(response.contentType()?.match(ContentType.Application.Json) == true)
        }
    }

    @Test
    fun `an existing handler's 404 keeps its own message`() = kotlinx.coroutines.runBlocking {
        testAppDb(clock, staticBasePackage = "static-fixture") {
            val viewer = UserFixture(PostgresSupport.database, UserRepository(), PasswordHasher())
            val token = loginAs(viewer, "static-routes-viewer", "long-password-viewer", setOf(Role.VIEWER), clock.instant())

            val response = client.get("/api/v1/tariffs/999999") { header(auth(token).first, auth(token).second) }

            assertEquals(HttpStatusCode.NotFound, response.status)
            val envelope = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals("Tariff not found", envelope.getValue("message").jsonPrimitive.content)
        }
    }

    @Test
    fun `a base package with no resources answers 404 for the root but leaves the API untouched`() = kotlinx.coroutines.runBlocking {
        testAppDb(clock, staticBasePackage = "static-fixture-missing-package") {
            val viewer = UserFixture(PostgresSupport.database, UserRepository(), PasswordHasher())
            val token = loginAs(viewer, "static-routes-empty-viewer", "long-password-viewer", setOf(Role.VIEWER), clock.instant())

            val root = client.get("/")
            assertEquals(HttpStatusCode.NotFound, root.status)
            assertTrue(root.contentType()?.match(ContentType.Application.Json) == true)

            val dictionaries = client.get("/api/v1/dictionaries") { header(auth(token).first, auth(token).second) }
            assertEquals(HttpStatusCode.OK, dictionaries.status)
        }
    }
}
