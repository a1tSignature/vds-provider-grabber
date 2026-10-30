package org.a1tsign.vds.api

import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import kotlinx.serialization.json.*
import org.a1tsign.vds.auth.PasswordHasher
import org.a1tsign.vds.db.ProviderRepository
import org.a1tsign.vds.domain.*
import org.a1tsign.vds.support.*
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Test
import org.a1tsign.vds.service.ProviderService
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*

class ProviderWriteApiTest : AbstractDbTest() {
    private val path = "/api/v1/providers"
    private fun bearer(token: String) = HttpHeaders.Authorization to "Bearer $token"

    @Test fun simultaneousFirstLegalInfoWritesAreSerialized() {
        val fixture = CatalogFixture(PostgresSupport.database)
        val provider = fixture.createProvider("concurrent-legal", clock.instant())
        val service = ProviderService(PostgresSupport.database, ProviderRepository(), clock)
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            PostgresSupport.dataSource.connection.use { lockConnection ->
                lockConnection.autoCommit = false
                lockConnection.prepareStatement("SELECT id FROM provider WHERE id = ? FOR UPDATE").use { statement ->
                    statement.setLong(1, provider.id)
                    statement.executeQuery().use { assertTrue(it.next()) }
                }
                val writes = listOf("First Entity", "Second Entity").map { entity ->
                    pool.submit<org.a1tsign.vds.domain.LegalInfo> {
                        ready.countDown()
                        check(start.await(10, TimeUnit.SECONDS))
                        kotlinx.coroutines.runBlocking {
                            service.putLegalInfo(provider.id, entity, "US", null, null, LegalDataSource.MANUAL, Confidence.HIGH, null, null)
                        }
                    }
                }
                assertTrue(ready.await(10, TimeUnit.SECONDS), "both writes should be ready before release")
                start.countDown()
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                var waitingWriters = 0
                while (waitingWriters < 2 && System.nanoTime() < deadline) {
                    PostgresSupport.dataSource.connection.use { observer ->
                        observer.prepareStatement("SELECT count(*) FROM pg_stat_activity WHERE datname = current_database() AND wait_event_type = 'Lock' AND query ILIKE '%FOR UPDATE%' AND pid <> pg_backend_pid()").use { statement ->
                            statement.executeQuery().use { result -> result.next(); waitingWriters = result.getInt(1) }
                        }
                    }
                    if (waitingWriters < 2) Thread.sleep(10)
                }
                lockConnection.commit()
                assertTrue(waitingWriters >= 2, "both writes should wait for the provider row lock")
                assertEquals(2, writes.map { it.get(10, TimeUnit.SECONDS) }.size)
            }
        } finally {
            pool.shutdownNow()
        }
        val stored = transaction(PostgresSupport.database) { ProviderRepository().legalInfo(provider.id) }
        assertNotNull(stored)
        assertTrue(stored.legalEntityName in setOf("First Entity", "Second Entity"))
        PostgresSupport.dataSource.connection.use { connection ->
            connection.prepareStatement("SELECT count(*) FROM provider_legal_info WHERE provider_id = ?").use { statement ->
                statement.setLong(1, provider.id)
                statement.executeQuery().use { result -> result.next(); assertEquals(1, result.getInt(1)) }
            }
        }
    }

    @Test fun providerWritesValidationAndRoleBoundaries() = kotlinx.coroutines.runBlocking {
        testAppDb(clock) {
            val admin = loginForToken("bootstrap-admin", "bootstrap-password-1")
            val created = client.post(path) { header(bearer(admin).first, bearer(admin).second); contentType(ContentType.Application.Json); setBody("""{"slug":"alpha-host","name":"Alpha Host","websiteUrl":"https://alpha.example","description":"VDS","status":"ACTIVE"}""") }
            assertEquals(HttpStatusCode.Created, created.status)
            val id = Json.parseToJsonElement(created.bodyAsText()).jsonObject.getValue("id").jsonPrimitive.long
            assertEquals("$path/$id", created.headers[HttpHeaders.Location])
            val stored = transaction(PostgresSupport.database) { ProviderRepository().find(id)!! }
            assertEquals(listOf("alpha-host", "Alpha Host", "https://alpha.example", "VDS", ProviderStatus.ACTIVE), listOf(stored.slug, stored.name, stored.websiteUrl, stored.description, stored.status))
            val updated = client.put("$path/$id") { header(bearer(admin).first, bearer(admin).second); contentType(ContentType.Application.Json); setBody("""{"slug":"alpha-host","name":"Alpha Hosting","websiteUrl":"https://alpha.example/new","description":null,"status":"INACTIVE"}""") }
            assertEquals(HttpStatusCode.OK, updated.status)
            val afterUpdate = transaction(PostgresSupport.database) { ProviderRepository().find(id)!! }
            assertEquals(listOf("Alpha Hosting", "https://alpha.example/new", null, ProviderStatus.INACTIVE), listOf(afterUpdate.name, afterUpdate.websiteUrl, afterUpdate.description, afterUpdate.status))
            assertEquals(HttpStatusCode.Conflict, client.post(path) { header(bearer(admin).first, bearer(admin).second); contentType(ContentType.Application.Json); setBody("""{"slug":"alpha-host","name":"Other","websiteUrl":"https://other.example","status":"ACTIVE"}""") }.status)
            for (url in listOf("ftp://alpha.example", "/relative", "http://", "https://a.example/" + "x".repeat(2031))) {
                val response = client.post(path) { header(bearer(admin).first, bearer(admin).second); contentType(ContentType.Application.Json); setBody(ApiJson.encodeToString(org.a1tsign.vds.api.dto.ProviderWriteRequest("bad-url", "Bad", url, null, "ACTIVE"))) }
                assertEquals(HttpStatusCode.BadRequest, response.status)
            }
            val allProblems = client.post(path) { header(bearer(admin).first, bearer(admin).second); contentType(ContentType.Application.Json); setBody("""{"slug":"Foo_Bar","name":" ","websiteUrl":"ftp://bad","status":"NOPE"}""") }
            val details = Json.parseToJsonElement(allProblems.bodyAsText()).jsonObject.getValue("details").jsonArray.map { it.jsonObject.getValue("field").jsonPrimitive.content }.toSet()
            assertEquals(setOf("slug", "name", "websiteUrl", "status"), details)
            val collision = transaction(PostgresSupport.database) { ProviderRepository().insert("beta-host", "Beta", "https://beta.example", null, ProviderStatus.ACTIVE, clock.instant()) }
            transaction(PostgresSupport.database) { ProviderRepository().replacePaymentMethods(collision.id, listOf(PaymentMethod.OTHER to "keep")) }
            assertEquals(HttpStatusCode.Conflict, client.put("$path/$id") { header(bearer(admin).first, bearer(admin).second); contentType(ContentType.Application.Json); setBody("""{"slug":"beta-host","name":"Alpha","websiteUrl":"https://alpha.example","status":"ACTIVE"}""") }.status)
            assertEquals(HttpStatusCode.NotFound, client.put("$path/999999") { header(bearer(admin).first, bearer(admin).second); contentType(ContentType.Application.Json); setBody("""{"slug":"absent","name":"Missing","websiteUrl":"https://missing.example","status":"ACTIVE"}""") }.status)
            val legal = """{"legalEntityName":"Alpha LLC","registrationCountry":"US","registrationNumber":"R1","dataSource":"MANUAL","confidence":"HIGH"}"""
            val first = client.put("$path/$id/legal-info") { header(bearer(admin).first, bearer(admin).second); contentType(ContentType.Application.Json); setBody(legal) }
            clock.advance(java.time.Duration.ofSeconds(2))
            val second = client.put("$path/$id/legal-info") { header(bearer(admin).first, bearer(admin).second); contentType(ContentType.Application.Json); setBody(legal) }
            assertEquals(HttpStatusCode.OK, first.status); assertEquals(HttpStatusCode.OK, second.status)
            val repeatedLegal = transaction(PostgresSupport.database) { ProviderRepository().legalInfo(id)!! }
            assertEquals(id, repeatedLegal.providerId)
            assertEquals(clock.instant(), repeatedLegal.updatedAt)
            val changedLegal = client.put("$path/$id/legal-info") { header(bearer(admin).first, bearer(admin).second); contentType(ContentType.Application.Json); setBody("""{"legalEntityName":"Alpha Holdings","registrationCountry":"GB","dataSource":"PROVIDER_SITE","confidence":"MEDIUM"}""") }
            assertEquals(HttpStatusCode.OK, changedLegal.status)
            assertEquals("Alpha Holdings", transaction(PostgresSupport.database) { ProviderRepository().legalInfo(id)!!.legalEntityName })
            PostgresSupport.dataSource.connection.use { connection -> connection.prepareStatement("SELECT count(*) FROM provider_legal_info WHERE provider_id = ?").use { ps -> ps.setLong(1, id); ps.executeQuery().use { rs -> rs.next(); assertEquals(1, rs.getInt(1)) } } }
            val allMethods = PaymentMethod.entries
            val allBody = allMethods.joinToString(prefix = "[", postfix = "]") { "{\"method\":\"${it.name}\",\"note\":\"${it.name}\"}" }
            val all = client.put("$path/$id/payment-methods") { header(bearer(admin).first, bearer(admin).second); contentType(ContentType.Application.Json); setBody(allBody) }
            assertEquals(HttpStatusCode.OK, all.status)
            assertEquals(allMethods.toSet(), Json.parseToJsonElement(all.bodyAsText()).jsonArray.map { PaymentMethod.valueOf(it.jsonObject.getValue("method").jsonPrimitive.content) }.toSet())
            val replacement = client.put("$path/$id/payment-methods") { header(bearer(admin).first, bearer(admin).second); contentType(ContentType.Application.Json); setBody("""[{"method":"MIR","note":"ruble"},{"method":"CASH","note":"in person"}]""") }
            assertEquals(setOf(PaymentMethod.MIR, PaymentMethod.CASH), Json.parseToJsonElement(replacement.bodyAsText()).jsonArray.map { PaymentMethod.valueOf(it.jsonObject.getValue("method").jsonPrimitive.content) }.toSet())
            assertEquals("in person", transaction(PostgresSupport.database) { ProviderRepository().paymentMethods(id).single { it.method == PaymentMethod.CASH }.note })
            assertEquals(HttpStatusCode.BadRequest, client.put("$path/$id/payment-methods") { header(bearer(admin).first, bearer(admin).second); contentType(ContentType.Application.Json); setBody("""[{"method":"BITCOIN"}]""") }.status)
            assertEquals(HttpStatusCode.BadRequest, client.put("$path/$id/payment-methods") { header(bearer(admin).first, bearer(admin).second); contentType(ContentType.Application.Json); setBody("""[{"method":"CASH"},{"method":"CASH"}]""") }.status)
            assertEquals(HttpStatusCode.NotFound, client.put("$path/999999/legal-info") { header(bearer(admin).first, bearer(admin).second); contentType(ContentType.Application.Json); setBody(legal) }.status)
            assertEquals(HttpStatusCode.NotFound, client.delete("$path/999999") { header(bearer(admin).first, bearer(admin).second) }.status)

            val viewerFixture = UserFixture(PostgresSupport.database, org.a1tsign.vds.db.UserRepository(), PasswordHasher())
            viewerFixture.create("catalog-viewer", "long-password-v", setOf(Role.VIEWER), at = clock.instant())
            val viewer = loginForToken("catalog-viewer", "long-password-v")
            val protectedCalls: List<suspend (String?) -> io.ktor.client.statement.HttpResponse> = listOf(
                { token -> client.post(path) { if (token != null) header(bearer(token).first, bearer(token).second); contentType(ContentType.Application.Json); setBody("""{"slug":"auth-create","name":"A","websiteUrl":"https://a.test","status":"ACTIVE"}""") } },
                { token -> client.put("$path/$id") { if (token != null) header(bearer(token).first, bearer(token).second); contentType(ContentType.Application.Json); setBody("""{"slug":"alpha-host","name":"Alpha","websiteUrl":"https://alpha.example","status":"ACTIVE"}""") } },
                { token -> client.delete("$path/$id") { if (token != null) header(bearer(token).first, bearer(token).second) } },
                { token -> client.put("$path/$id/legal-info") { if (token != null) header(bearer(token).first, bearer(token).second); contentType(ContentType.Application.Json); setBody(legal) } },
                { token -> client.put("$path/$id/payment-methods") { if (token != null) header(bearer(token).first, bearer(token).second); contentType(ContentType.Application.Json); setBody("[]") } },
            )
            protectedCalls.forEach { call -> assertEquals(HttpStatusCode.Unauthorized, call(null).status); assertEquals(HttpStatusCode.Forbidden, call(viewer).status) }
            var locationId = 0L
            var tariffId = 0L
            PostgresSupport.dataSource.connection.use { connection ->
                connection.prepareStatement("INSERT INTO location(provider_id,country_code) VALUES (?, 'US') RETURNING id").use { ps -> ps.setLong(1, id); ps.executeQuery().use { rs -> rs.next(); locationId = rs.getLong(1) } }
                connection.prepareStatement("INSERT INTO tariff(provider_id,name,vcpu_count,ram_mb,disk_gb,disk_type,virtualization) VALUES (?, 'Basic', 1, 1024, 20, 'SSD', 'KVM') RETURNING id").use { ps -> ps.setLong(1, id); ps.executeQuery().use { rs -> rs.next(); tariffId = rs.getLong(1) } }
                connection.prepareStatement("INSERT INTO tariff_price(tariff_id,billing_period,amount,currency,effective_from,effective_to,data_source) VALUES (?, 'MONTHLY', 5, 'USD', '2025-01-01T00:00:00Z', '2025-02-01T00:00:00Z', 'MANUAL'), (?, 'MONTHLY', 6, 'USD', '2025-02-01T00:00:00Z', NULL, 'MANUAL')").use { ps -> ps.setLong(1, tariffId); ps.setLong(2, tariffId); ps.executeUpdate() }
            }
            assertEquals(HttpStatusCode.NoContent, client.delete("$path/$id") { header(bearer(admin).first, bearer(admin).second) }.status)
            assertNull(transaction(PostgresSupport.database) { ProviderRepository().find(id) })
            assertNotNull(transaction(PostgresSupport.database) { ProviderRepository().find(collision.id) })
            PostgresSupport.dataSource.connection.use { connection ->
                for ((table, column, value) in listOf(Triple("location", "id", locationId), Triple("tariff", "id", tariffId))) connection.prepareStatement("SELECT count(*) FROM $table WHERE $column = ?").use { ps -> ps.setLong(1, value); ps.executeQuery().use { rs -> rs.next(); assertEquals(0, rs.getInt(1)) } }
                connection.prepareStatement("SELECT count(*) FROM tariff_price WHERE tariff_id = ?").use { ps -> ps.setLong(1, tariffId); ps.executeQuery().use { rs -> rs.next(); assertEquals(0, rs.getInt(1)) } }
                connection.prepareStatement("SELECT (SELECT count(*) FROM provider_legal_info WHERE provider_id = ?)+(SELECT count(*) FROM provider_payment_method WHERE provider_id = ?)+(SELECT count(*) FROM location WHERE provider_id = ?)+(SELECT count(*) FROM tariff WHERE provider_id = ?)").use { ps -> repeat(4) { ps.setLong(it + 1, id) }; ps.executeQuery().use { rs -> rs.next(); assertEquals(0, rs.getInt(1)) } }
                connection.prepareStatement("SELECT count(*) FROM provider_payment_method WHERE provider_id = ? AND method = 'OTHER'").use { ps -> ps.setLong(1, collision.id); ps.executeQuery().use { rs -> rs.next(); assertEquals(1, rs.getInt(1)) } }
            }
        }
    }
}
