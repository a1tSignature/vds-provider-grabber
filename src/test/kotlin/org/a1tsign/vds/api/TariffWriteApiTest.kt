package org.a1tsign.vds.api

import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import kotlinx.serialization.json.*
import org.a1tsign.vds.auth.PasswordHasher
import org.a1tsign.vds.db.TariffRepository
import org.a1tsign.vds.domain.*
import org.a1tsign.vds.support.*
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.test.*

class TariffWriteApiTest : AbstractDbTest() {
    private val root = "/api/v1"
    private fun auth(token: String) = HttpHeaders.Authorization to "Bearer $token"

    private fun body(
        name: String = "Basic",
        externalId: String? = null,
        vcpuCount: Int = 2,
        ramMb: Int = 2048,
        diskGb: Int = 40,
        diskType: String = "SSD",
        virtualization: String = "KVM",
        trafficLimitGb: Int? = null,
        ipv6Supported: Boolean? = null,
        isActive: Boolean = true,
        inStock: Boolean = true,
        url: String? = "https://example.com/plan",
        ipv4Count: Int = 1,
        cpuModel: String? = null,
        bandwidthMbps: Int? = null,
    ): String = buildJsonObject {
        put("name", name)
        if (externalId != null) put("externalId", externalId)
        put("vcpuCount", vcpuCount)
        put("ramMb", ramMb)
        put("diskGb", diskGb)
        put("diskType", diskType)
        put("virtualization", virtualization)
        if (trafficLimitGb != null) put("trafficLimitGb", trafficLimitGb)
        if (ipv6Supported != null) put("ipv6Supported", ipv6Supported)
        put("isActive", isActive)
        put("inStock", inStock)
        if (url != null) put("url", url)
        put("ipv4Count", ipv4Count)
        if (cpuModel != null) put("cpuModel", cpuModel)
        if (bandwidthMbps != null) put("bandwidthMbps", bandwidthMbps)
    }.toString()

    @Test fun tariffWritesValidateUniquenessDeleteCascadeAndRoles() = kotlinx.coroutines.runBlocking {
        testAppDb(clock) {
            val token = loginForToken("bootstrap-admin", "bootstrap-password-1")
            val fixture = CatalogFixture(PostgresSupport.database)
            val one = fixture.createProvider("tariff-one", clock.instant())
            val two = fixture.createProvider("tariff-two", clock.instant())
            suspend fun post(providerId: Long, b: String, bearer: String? = token) =
                client.post("$root/providers/$providerId/tariffs") {
                    if (bearer != null) header(auth(bearer).first, auth(bearer).second)
                    contentType(ContentType.Application.Json); setBody(b)
                }

            val created = post(
                one.id,
                body(name = "Basic", inStock = false, isActive = true, cpuModel = "Epyc 7502", bandwidthMbps = 1000),
            )
            assertEquals(HttpStatusCode.Created, created.status)
            val createdJson = Json.parseToJsonElement(created.bodyAsText()).jsonObject
            val id = createdJson.getValue("id").jsonPrimitive.long
            assertEquals("$root/tariffs/$id", created.headers[HttpHeaders.Location])
            assertFalse(createdJson.containsKey("ipv6Supported"), "null ipv6Supported must be omitted on the wire")
            assertFalse(createdJson.containsKey("trafficLimitGb"), "null trafficLimitGb must be omitted on the wire")
            assertEquals(false, createdJson.getValue("inStock").jsonPrimitive.boolean)
            assertEquals(true, createdJson.getValue("isActive").jsonPrimitive.boolean)
            val stored = transaction(PostgresSupport.database) { TariffRepository().find(id)!! }
            assertEquals(
                listOf(one.id, "Basic", 2.toShort(), 2048, 40, DiskType.SSD, Virtualization.KVM, 1.toShort(), null, null, null, false, true, "https://example.com/plan", "Epyc 7502", 1000),
                listOf(stored.providerId, stored.name, stored.vcpuCount, stored.ramMb, stored.diskGb, stored.diskType, stored.virtualization, stored.ipv4Count, stored.trafficLimitGb, stored.ipv6Supported, stored.externalId, stored.inStock, stored.isActive, stored.url, stored.cpuModel, stored.bandwidthMbps),
            )

            assertEquals(HttpStatusCode.Conflict, post(one.id, body(name = "basic")).status)
            assertEquals(HttpStatusCode.Created, post(two.id, body(name = "Basic")).status)

            assertEquals(HttpStatusCode.Created, post(one.id, body(name = "Null Ext A")).status)
            assertEquals(HttpStatusCode.Created, post(one.id, body(name = "Null Ext B")).status)
            assertEquals(HttpStatusCode.Created, post(one.id, body(name = "Ext Plan", externalId = "ext-1")).status)
            assertEquals(HttpStatusCode.Conflict, post(one.id, body(name = "Ext Plan 2", externalId = "ext-1")).status)

            assertEquals(HttpStatusCode.NotFound, post(999999, body(name = "No Provider")).status)
            for ((field, invalid) in listOf(
                "vcpuCount" to body(vcpuCount = 0),
                "ramMb" to body(ramMb = -1),
                "diskType" to body(diskType = "FLOPPY"),
                "virtualization" to body(virtualization = "BOCHS"),
                "url" to body(url = "/relative"),
                "vcpuCount" to body(vcpuCount = Short.MAX_VALUE.toInt() + 1),
                "cpuModel" to body(cpuModel = "x".repeat(129)),
            )) {
                val response = post(one.id, invalid)
                assertEquals(HttpStatusCode.BadRequest, response.status, field)
                val fields = Json.parseToJsonElement(response.bodyAsText()).jsonObject.getValue("details").jsonArray.map { it.jsonObject.getValue("field").jsonPrimitive.content }.toSet()
                assertEquals(setOf(field), fields)
            }
            val multiProblem = post(one.id, body(vcpuCount = 0, ramMb = -1, diskType = "NOPE"))
            assertEquals(HttpStatusCode.BadRequest, multiProblem.status)
            val multiFields = Json.parseToJsonElement(multiProblem.bodyAsText()).jsonObject.getValue("details").jsonArray.map { it.jsonObject.getValue("field").jsonPrimitive.content }.toSet()
            assertEquals(setOf("vcpuCount", "ramMb", "diskType"), multiFields)

            val putPath = "$root/tariffs/$id"
            assertEquals(HttpStatusCode.NotFound, client.put("$root/tariffs/999999") { header(auth(token).first, auth(token).second); contentType(ContentType.Application.Json); setBody(body()) }.status)
            clock.advance(java.time.Duration.ofSeconds(2))
            val updated = client.put(putPath) { header(auth(token).first, auth(token).second); contentType(ContentType.Application.Json); setBody(body(name = "Basic", ramMb = 4096, trafficLimitGb = 1000)) }
            assertEquals(HttpStatusCode.OK, updated.status)
            val afterUpdate = transaction(PostgresSupport.database) { TariffRepository().find(id)!! }
            assertEquals(4096, afterUpdate.ramMb); assertEquals(1000, afterUpdate.trafficLimitGb); assertEquals(clock.instant(), afterUpdate.updatedAt)

            assertEquals(
                HttpStatusCode.Conflict,
                client.put(putPath) { header(auth(token).first, auth(token).second); contentType(ContentType.Application.Json); setBody(body(name = "Ext Plan")) }.status,
            )
            assertEquals(
                HttpStatusCode.Conflict,
                client.put(putPath) { header(auth(token).first, auth(token).second); contentType(ContentType.Application.Json); setBody(body(name = "Basic", externalId = "ext-1")) }.status,
            )

            val location = fixture.createLocation(one.id, "DE", at = clock.instant())
            fixture.linkTariffLocation(id, location.id, one.id)
            fixture.createTariffPrice(id, BigDecimal("5"), "USD", BillingPeriod.MONTHLY, clock.instant())
            fixture.createTariffPrice(id, BigDecimal("6"), "USD", BillingPeriod.ANNUAL, clock.instant())
            val survivor = fixture.createTariff(one.id, "Survivor", clock.instant())
            assertEquals(HttpStatusCode.NoContent, client.delete(putPath) { header(auth(token).first, auth(token).second) }.status)
            assertNull(transaction(PostgresSupport.database) { TariffRepository().find(id) })
            assertNotNull(transaction(PostgresSupport.database) { TariffRepository().find(survivor.id) })
            PostgresSupport.dataSource.connection.use { c ->
                c.prepareStatement("SELECT count(*) FROM tariff_location WHERE tariff_id = ?").use { ps -> ps.setLong(1, id); ps.executeQuery().use { rs -> rs.next(); assertEquals(0, rs.getInt(1)) } }
                c.prepareStatement("SELECT count(*) FROM tariff_price WHERE tariff_id = ?").use { ps -> ps.setLong(1, id); ps.executeQuery().use { rs -> rs.next(); assertEquals(0, rs.getInt(1)) } }
            }

            val viewer = loginAs(UserFixture(PostgresSupport.database, org.a1tsign.vds.db.UserRepository(), PasswordHasher()), "tariff-viewer", "long-password-viewer", setOf(Role.VIEWER), clock.instant())
            val calls: List<suspend (String?) -> io.ktor.client.statement.HttpResponse> = listOf(
                { t -> post(two.id, body(name = "Auth Create"), t) },
                { t -> client.put("$root/tariffs/${survivor.id}") { if (t != null) header(auth(t).first, auth(t).second); contentType(ContentType.Application.Json); setBody(body()) } },
                { t -> client.delete("$root/tariffs/${survivor.id}") { if (t != null) header(auth(t).first, auth(t).second) } },
                { t -> client.put("$root/tariffs/${survivor.id}/locations") { if (t != null) header(auth(t).first, auth(t).second); contentType(ContentType.Application.Json); setBody("[]") } },
            )
            calls.forEach { call -> assertEquals(HttpStatusCode.Unauthorized, call(null).status); assertEquals(HttpStatusCode.Forbidden, call(viewer).status) }
        }
    }

    @Test fun tariffLocationReplacementIsAtomicAndCrossProviderSafe() = kotlinx.coroutines.runBlocking {
        testAppDb(clock) {
            val token = loginForToken("bootstrap-admin", "bootstrap-password-1")
            val fixture = CatalogFixture(PostgresSupport.database)
            val one = fixture.createProvider("link-one", clock.instant())
            val two = fixture.createProvider("link-two", clock.instant())
            val tariff = fixture.createTariff(one.id, "Linked Plan", clock.instant())
            val l1 = fixture.createLocation(one.id, "DE", at = clock.instant())
            val l2 = fixture.createLocation(one.id, "US", at = clock.instant())
            val l3 = fixture.createLocation(one.id, "FR", at = clock.instant())
            val otherProviderLocation = fixture.createLocation(two.id, "NL", at = clock.instant())
            val path = "$root/tariffs/${tariff.id}/locations"
            suspend fun put(ids: List<Long>) = client.put(path) {
                header(auth(token).first, auth(token).second); contentType(ContentType.Application.Json)
                setBody(ids.toString())
            }

            assertEquals(HttpStatusCode.OK, put(listOf(l1.id, l2.id)).status)
            assertEquals(HttpStatusCode.OK, put(listOf(l2.id, l3.id)).status)
            val afterReplace = transaction(PostgresSupport.database) { TariffRepository().locationIds(tariff.id) }
            assertEquals(setOf(l2.id, l3.id), afterReplace)

            assertEquals(HttpStatusCode.Conflict, put(listOf(l2.id, otherProviderLocation.id)).status)
            assertEquals(setOf(l2.id, l3.id), transaction(PostgresSupport.database) { TariffRepository().locationIds(tariff.id) })

            assertEquals(HttpStatusCode.NotFound, put(listOf(999999)).status)
            assertEquals(setOf(l2.id, l3.id), transaction(PostgresSupport.database) { TariffRepository().locationIds(tariff.id) })

            assertEquals(HttpStatusCode.BadRequest, put(listOf(l1.id, l1.id)).status)

            assertEquals(HttpStatusCode.NotFound, client.put("$root/tariffs/999999/locations") { header(auth(token).first, auth(token).second); contentType(ContentType.Application.Json); setBody("[]") }.status)

            assertEquals(HttpStatusCode.OK, put(emptyList()).status)
            assertEquals(emptySet(), transaction(PostgresSupport.database) { TariffRepository().locationIds(tariff.id) })
        }
    }
}
