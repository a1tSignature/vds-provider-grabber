package org.a1tsign.vds.api

import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import kotlinx.serialization.json.*
import org.a1tsign.vds.auth.PasswordHasher
import org.a1tsign.vds.db.LocationRepository
import org.a1tsign.vds.db.TariffLocationTable
import org.a1tsign.vds.db.ProviderRepository
import org.a1tsign.vds.domain.Role
import org.a1tsign.vds.support.*
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Test
import kotlin.test.*

class LocationWriteApiTest : AbstractDbTest() {
    private val root = "/api/v1"
    private fun auth(token: String) = HttpHeaders.Authorization to "Bearer $token"

    @Test fun locationWritesValidateUniquenessCoordinatesDeleteAndRoles() = kotlinx.coroutines.runBlocking {
        testAppDb(clock) {
            val token = loginForToken("bootstrap-admin", "bootstrap-password-1")
            val fixture = CatalogFixture(PostgresSupport.database)
            val one = fixture.createProvider("loc-one", clock.instant())
            val two = fixture.createProvider("loc-two", clock.instant())
            suspend fun post(provider: Long, body: String, bearer: String? = token) = client.post("$root/providers/$provider/locations") {
                if (bearer != null) header(auth(bearer).first, auth(bearer).second)
                contentType(ContentType.Application.Json); setBody(body)
            }
            fun body(country: String = "DE", city: String? = "Frankfurt", dc: String? = "Equinix FR5", coords: String = "") =
                """{"countryCode":"$country"${city?.let { ",\"city\":\"$it\""} ?: ""}${dc?.let { ",\"datacenterName\":\"$it\""} ?: ""}$coords}"""
            val created = post(one.id, body(coords = ",\"latitude\":\"50.110900\",\"longitude\":\"8.682100\""))
            assertEquals(HttpStatusCode.Created, created.status)
            val createdJson = Json.parseToJsonElement(created.bodyAsText()).jsonObject
            val id = createdJson.getValue("id").jsonPrimitive.long
            assertEquals("50.110900", createdJson.getValue("latitude").jsonPrimitive.content)
            assertEquals("8.682100", createdJson.getValue("longitude").jsonPrimitive.content)
            assertEquals("$root/locations/$id", created.headers[HttpHeaders.Location])
            val stored = transaction(PostgresSupport.database) { LocationRepository().find(id)!! }
            assertEquals("DE", stored.countryCode); assertEquals("Frankfurt", stored.city)
            assertEquals("50.110900", stored.latitude.toString()); assertEquals("8.682100", stored.longitude.toString())

            assertEquals(HttpStatusCode.NotFound, post(999999, body()).status)
            assertEquals(HttpStatusCode.Conflict, post(one.id, body()).status)
            assertEquals(HttpStatusCode.Conflict, post(one.id, body(city = "frankfurt", dc = "equinix fr5")).status)
            val otherProvider = post(two.id, body())
            assertEquals(HttpStatusCode.Created, otherProvider.status)
            val otherProviderLocationId = Json.parseToJsonElement(otherProvider.bodyAsText()).jsonObject.getValue("id").jsonPrimitive.long
            val nulls = post(one.id, body(city = null, dc = null)); assertEquals(HttpStatusCode.Created, nulls.status)
            assertEquals(HttpStatusCode.Conflict, post(one.id, body(city = null, dc = null)).status)
            for (invalid in listOf(
                body("de"),
                body(coords = ",\"latitude\":\"10\""),
                body(coords = ",\"latitude\":\"91\",\"longitude\":\"1\""),
                body(coords = ",\"latitude\":\"1\",\"longitude\":\"-180.000001\""),
                body(coords = ",\"latitude\":\"1.1234567\",\"longitude\":\"2\""),
                body(coords = ",\"latitude\":1,\"longitude\":\"2\""),
            )) assertEquals(HttpStatusCode.BadRequest, post(one.id, invalid).status)

            val collision = post(one.id, body(city = "Helsinki", dc = "DC1"))
            val collisionId = Json.parseToJsonElement(collision.bodyAsText()).jsonObject.getValue("id").jsonPrimitive.long
            val putPath = "$root/locations/$id"
            assertEquals(HttpStatusCode.NotFound, client.put("$root/locations/999999") { header(auth(token).first, auth(token).second); contentType(ContentType.Application.Json); setBody(body()) }.status)
            assertEquals(HttpStatusCode.Conflict, client.put(putPath) { header(auth(token).first, auth(token).second); contentType(ContentType.Application.Json); setBody(body(city = "helsinki", dc = "dc1")) }.status)
            clock.advance(java.time.Duration.ofSeconds(2))
            val updated = client.put(putPath) { header(auth(token).first, auth(token).second); contentType(ContentType.Application.Json); setBody(body(city = "Frankfurt", dc = "Equinix FR5").replace("\"countryCode\":\"DE\"", "\"countryCode\":\"DE\",\"region\":\"Hesse\"")) }
            assertEquals(HttpStatusCode.OK, updated.status)
            val updatedRow = transaction(PostgresSupport.database) { LocationRepository().find(id)!! }
            assertEquals("Hesse", updatedRow.region)
            assertEquals(clock.instant(), updatedRow.updatedAt)

            val tariffId = PostgresSupport.dataSource.connection.use { c -> c.prepareStatement("INSERT INTO tariff(provider_id,name,vcpu_count,ram_mb,disk_gb,disk_type,virtualization) VALUES (?, 'Test', 1, 1024, 20, 'SSD', 'KVM') RETURNING id").use { ps -> ps.setLong(1, one.id); ps.executeQuery().use { rs -> rs.next(); rs.getLong(1) } } }
            transaction(PostgresSupport.database) { TariffLocationTable.insert { it[TariffLocationTable.tariffId] = tariffId; it[TariffLocationTable.locationId] = id; it[TariffLocationTable.providerId] = one.id } }
            assertEquals(HttpStatusCode.NoContent, client.delete(putPath) { header(auth(token).first, auth(token).second) }.status)
            assertNull(transaction(PostgresSupport.database) { LocationRepository().find(id) })
            assertNotNull(transaction(PostgresSupport.database) { LocationRepository().find(collisionId) })
            assertNotNull(transaction(PostgresSupport.database) { LocationRepository().find(otherProviderLocationId) })
            PostgresSupport.dataSource.connection.use { c -> c.prepareStatement("SELECT count(*) FROM tariff_location WHERE tariff_id = ? AND location_id = ?").use { ps -> ps.setLong(1, tariffId); ps.setLong(2, id); ps.executeQuery().use { rs -> rs.next(); assertEquals(0, rs.getInt(1)) } } }

            val viewer = loginAs(UserFixture(PostgresSupport.database, org.a1tsign.vds.db.UserRepository(), PasswordHasher()), "loc-viewer", "long-password-viewer", setOf(Role.VIEWER), clock.instant())
            val calls: List<suspend (String?) -> io.ktor.client.statement.HttpResponse> = listOf(
                { t -> post(two.id, body(city = "Auth", dc = "Create"), t) },
                { t -> client.put("$root/locations/$collisionId") { if (t != null) header(auth(t).first, auth(t).second); contentType(ContentType.Application.Json); setBody(body()) } },
                { t -> client.delete("$root/locations/$collisionId") { if (t != null) header(auth(t).first, auth(t).second) } },
            )
            calls.forEach { call -> assertEquals(HttpStatusCode.Unauthorized, call(null).status); assertEquals(HttpStatusCode.Forbidden, call(viewer).status) }
        }
    }
}
