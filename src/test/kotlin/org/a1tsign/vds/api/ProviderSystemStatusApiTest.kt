package org.a1tsign.vds.api

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.a1tsign.vds.auth.PasswordHasher
import org.a1tsign.vds.db.UserRepository
import org.a1tsign.vds.domain.BillingPeriod
import org.a1tsign.vds.domain.Role
import org.a1tsign.vds.domain.SystemStatus
import org.a1tsign.vds.support.AbstractDbTest
import org.a1tsign.vds.support.CatalogFixture
import org.a1tsign.vds.support.PostgresSupport
import org.a1tsign.vds.support.UserFixture
import org.a1tsign.vds.support.loginAs
import org.a1tsign.vds.support.loginForToken
import org.a1tsign.vds.support.testAppDb
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Provider visibility and the write contract of `provider.systemStatus`. */
class ProviderSystemStatusApiTest : AbstractDbTest() {

    @Test
    fun `the public listing includes CONFIGURED and CANDIDATE but excludes REJECTED`() = runBlocking {
        testAppDb(clock) {
            val token = adminToken()
            val configured = createProvider(token, "conf-a", systemStatus = null)
            val candidate = createProvider(token, "cand-b", systemStatus = "CANDIDATE")
            val rejected = createProvider(token, "rej-c", systemStatus = "REJECTED")

            val default = list(token)
            assertEquals(listOf(configured, candidate), default.ids())
            assertEquals(2, default.total())
            assertEquals(setOf("CANDIDATE", "CONFIGURED"), default.systemStatuses().toSet(), "the field is on the wire")
            assertEquals(HttpStatusCode.NotFound, rawGet(token, "/api/v1/providers/$rejected").status)

            assertEquals(listOf(candidate), list(token, "systemStatus" to "CANDIDATE").ids())
            assertEquals(
                listOf(configured, candidate),
                list(token, "systemStatus" to "CANDIDATE", "systemStatus" to "CONFIGURED").ids(),
            )
            assertEquals(HttpStatusCode.BadRequest, rawList(token, "systemStatus" to "REJECTED").status)

            // The id-resolving sort path (ProviderRepository.filteredIds) carries the predicate too.
            assertEquals(listOf(candidate), list(token, "systemStatus" to "CANDIDATE", "sort" to "minMonthlyPriceUsd").ids())

            assertEquals(HttpStatusCode.BadRequest, rawList(token, "systemStatus" to "BOGUS").status)
        }
    }

    @Test
    fun `a candidate is visible in detail and cross-provider searches`() = runBlocking {
        testAppDb(clock) {
            val token = adminToken()
            val fixture = CatalogFixture(PostgresSupport.database)
            val candidate = createProvider(token, "cand-tariffs", systemStatus = "CANDIDATE")
            val visible = createProvider(token, "conf-tariffs", systemStatus = null)
            val candidateTariff = fixture.createTariff(candidate, "Candidate Plan", clock.instant()).id
            val visibleTariff = fixture.createTariff(visible, "Visible Plan", clock.instant()).id
            listOf(candidateTariff, visibleTariff).forEach {
                fixture.createTariffPrice(it, BigDecimal("5.0000"), "USD", BillingPeriod.MONTHLY, clock.instant())
            }

            val detail = get(token, "/api/v1/providers/$candidate").jsonObject
            assertEquals("CANDIDATE", detail.getValue("provider").jsonObject.string("systemStatus"))

            val ownTariffs = get(token, "/api/v1/providers/$candidate/tariffs").jsonObject
            assertEquals(
                listOf(candidateTariff),
                ownTariffs.getValue("content").jsonArray.map { it.jsonObject.id() },
                "a candidate's own tariffs are never filtered -- the crawler's first run depends on it",
            )

            val search = get(token, "/api/v1/tariffs?size=50").jsonObject
            assertEquals(
                setOf(visibleTariff, candidateTariff),
                search.getValue("content").jsonArray.map { it.jsonObject.getValue("tariff").jsonObject.id() }.toSet(),
            )
            assertEquals(2, search.getValue("totalElements").jsonPrimitive.long)

            // By id, a tariff follows the provider-detail rule rather than the listing rule.
            assertEquals(HttpStatusCode.OK, rawGet(token, "/api/v1/tariffs/$candidateTariff").status)

            // Promotion does not change catalog visibility.
            setSystemStatus(token, candidate, "CONFIGURED")
            val afterPromotion = get(token, "/api/v1/tariffs?size=50").jsonObject
            assertEquals(
                setOf(visibleTariff, candidateTariff),
                afterPromotion.getValue("content").jsonArray.map { it.jsonObject.getValue("tariff").jsonObject.id() }.toSet(),
            )
            assertEquals(2, afterPromotion.getValue("totalElements").jsonPrimitive.long)
        }
    }

    @Test
    fun `the write contract is additive and only the dedicated endpoint changes systemStatus`() = runBlocking {
        testAppDb(clock) {
            val token = adminToken()
            val id = createProvider(token, "write-contract", systemStatus = null)
            assertEquals("CONFIGURED", providerDto(token, id).string("systemStatus"), "POST without the field is visible")

            setSystemStatus(token, id, "CANDIDATE")
            // The dangerous case: an ordinary field update must not promote a candidate.
            val updated = updateProvider(token, id, "write-contract", name = "Renamed", systemStatus = null)
            assertEquals(HttpStatusCode.OK, updated.status)
            assertEquals("CANDIDATE", providerDto(token, id).string("systemStatus"), "an absent systemStatus leaves it alone")
            assertEquals("Renamed", providerDto(token, id).string("name"), "the rest of the update did happen")

            // ... but an explicit one on the same endpoint is applied.
            updateProvider(token, id, "write-contract", name = "Renamed", systemStatus = "CONFIGURED")
            assertEquals("CONFIGURED", providerDto(token, id).string("systemStatus"))

            SystemStatus.entries.forEach { value ->
                val response = setSystemStatus(token, id, value.name)
                assertEquals(HttpStatusCode.OK, response.status)
                assertEquals(value.name, body(response).jsonObject.string("systemStatus"), "the updated DTO is returned")
            }

            assertEquals(HttpStatusCode.BadRequest, setSystemStatus(token, id, "BOGUS").status)

            val viewer = loginAs(
                UserFixture(PostgresSupport.database, UserRepository(), PasswordHasher()),
                "system-status-viewer",
                "long-password-viewer",
                setOf(Role.VIEWER),
                clock.instant(),
            )
            assertEquals(HttpStatusCode.Forbidden, setSystemStatus(viewer, id, "REJECTED").status)
        }
    }

    @Test
    fun `the dictionary endpoint lists the new values`() = runBlocking {
        testAppDb(clock) {
            val token = adminToken()
            val dictionaries = get(token, "/api/v1/dictionaries").jsonObject
            assertEquals(
                SystemStatus.entries.map { it.name },
                dictionaries.getValue("systemStatuses").jsonArray.map { it.jsonPrimitive.content },
            )
            assertTrue(dictionaries.containsKey("providerStatuses"), "the pre-existing keys are untouched")
        }
    }

    // ---------- helpers ----------

    private suspend fun ApplicationTestBuilder.adminToken() = loginForToken("bootstrap-admin", "bootstrap-password-1")

    private fun auth(token: String) = HttpHeaders.Authorization to "Bearer $token"

    private suspend fun ApplicationTestBuilder.createProvider(token: String, slug: String, systemStatus: String?): Long {
        val field = systemStatus?.let { ",\"systemStatus\":\"$it\"" } ?: ""
        val response = client.post("/api/v1/providers") {
            header(auth(token).first, auth(token).second)
            contentType(ContentType.Application.Json)
            setBody(
                """{"slug":"$slug","name":"Provider $slug","websiteUrl":"https://$slug.example","status":"UNKNOWN"$field}""",
            )
        }
        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
        return body(response).jsonObject.id()
    }

    private suspend fun ApplicationTestBuilder.updateProvider(
        token: String,
        id: Long,
        slug: String,
        name: String,
        systemStatus: String?,
    ): HttpResponse {
        val field = systemStatus?.let { ",\"systemStatus\":\"$it\"" } ?: ""
        return client.put("/api/v1/providers/$id") {
            header(auth(token).first, auth(token).second)
            contentType(ContentType.Application.Json)
            setBody("""{"slug":"$slug","name":"$name","websiteUrl":"https://$slug.example","status":"UNKNOWN"$field}""")
        }
    }

    private suspend fun ApplicationTestBuilder.setSystemStatus(token: String, id: Long, value: String): HttpResponse =
        client.put("/api/v1/providers/$id/system-status") {
            header(auth(token).first, auth(token).second)
            contentType(ContentType.Application.Json)
            setBody("""{"systemStatus":"$value"}""")
        }

    private suspend fun ApplicationTestBuilder.providerDto(token: String, id: Long): JsonObject =
        get(token, "/api/v1/providers/$id").jsonObject.getValue("provider").jsonObject

    private suspend fun ApplicationTestBuilder.rawGet(token: String, path: String): HttpResponse =
        client.get(path) { header(auth(token).first, auth(token).second) }

    private suspend fun ApplicationTestBuilder.get(token: String, path: String) =
        rawGet(token, path).let {
            assertEquals(HttpStatusCode.OK, it.status, "GET $path: ${it.bodyAsText()}")
            body(it)
        }

    private suspend fun ApplicationTestBuilder.rawList(token: String, vararg params: Pair<String, String>): HttpResponse {
        val query = (params.toList() + ("size" to "50")).joinToString("&") { (key, value) -> "$key=$value" }
        return rawGet(token, "/api/v1/providers?$query")
    }

    private suspend fun ApplicationTestBuilder.list(token: String, vararg params: Pair<String, String>): JsonObject {
        val response = rawList(token, *params)
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        return body(response).jsonObject
    }

    private companion object {
        val JSON = Json { ignoreUnknownKeys = true }

        suspend fun body(response: HttpResponse) = JSON.parseToJsonElement(response.bodyAsText())

        fun JsonObject.ids(): List<Long> = getValue("content").jsonArray.map { it.jsonObject.id() }

        fun JsonObject.systemStatuses(): List<String> =
            getValue("content").jsonArray.map { it.jsonObject.string("systemStatus") }

        fun JsonObject.total(): Long = getValue("totalElements").jsonPrimitive.long

        fun JsonObject.id(): Long = getValue("id").jsonPrimitive.long

        fun JsonObject.string(field: String): String = getValue(field).jsonPrimitive.content
    }
}
