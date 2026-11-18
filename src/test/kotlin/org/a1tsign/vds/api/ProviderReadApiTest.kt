package org.a1tsign.vds.api

import io.ktor.client.request.*
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import kotlinx.serialization.json.*
import org.a1tsign.vds.auth.PasswordHasher
import org.a1tsign.vds.db.UserRepository
import org.a1tsign.vds.domain.Role
import org.a1tsign.vds.support.*
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.test.*

class ProviderReadApiTest : AbstractDbTest() {
    private val root = "/api/v1"
    private fun auth(token: String) = HttpHeaders.Authorization to "Bearer $token"

    @Test fun listFiltersReturnExactIdSets() = kotlinx.coroutines.runBlocking {
        testAppDb(clock) {
            val token = loginForToken("bootstrap-admin", "bootstrap-password-1")
            val fixture = SearchFixture(PostgresSupport.database)

            suspend fun list(vararg params: Pair<String, String>): JsonObject {
                val qs = (params.toList() + ("size" to "50")).joinToString("&") { (k, v) -> "$k=$v" }
                val response = client.get("$root/providers?$qs") { header(auth(token).first, auth(token).second) }
                assertEquals(HttpStatusCode.OK, response.status)
                return Json.parseToJsonElement(response.bodyAsText()).jsonObject
            }
            fun JsonObject.ids(): Set<Long> =
                getValue("content").jsonArray.map { it.jsonObject.getValue("id").jsonPrimitive.long }.toSet()

            assertEquals(setOf(fixture.nimbus.id, fixture.polaris.id), list("locationCountry" to "DE").ids())

            val paypalOnly = list("paymentMethod" to "PAYPAL").ids()
            val btcOnly = list("paymentMethod" to "CRYPTO_BTC").ids()
            val both = list("paymentMethod" to "PAYPAL", "paymentMethod" to "CRYPTO_BTC").ids()
            assertEquals(setOf(fixture.nimbus.id, fixture.zephyr.id), both)
            assertTrue(both.size < paypalOnly.size)
            assertTrue(both.size < btcOnly.size)
            assertTrue(both.all { it in paypalOnly } && both.all { it in btcOnly })

            assertEquals(setOf(fixture.nimbus.id, fixture.polaris.id), list("q" to "QuAsAr").ids())
            assertEquals(setOf(fixture.vertex.id), list("status" to "INACTIVE").ids())
            assertEquals(setOf(fixture.wayfarer.id), list("status" to "UNKNOWN").ids())
            assertEquals(setOf(fixture.nimbus.id, fixture.dormant.id), list("registrationCountry" to "US").ids())
            assertEquals(setOf(fixture.polaris.id), list("registrationCountry" to "DE").ids())
            assertEquals(
                setOf(fixture.nimbus.id, fixture.polaris.id, fixture.vertex.id, fixture.wayfarer.id, fixture.zephyr.id),
                list("hasActiveTariffs" to "true").ids(),
            )
            assertEquals(setOf(fixture.dormant.id), list("hasActiveTariffs" to "false").ids())
            assertEquals(
                setOf(fixture.nimbus.id, fixture.dormant.id),
                list("status" to "ACTIVE", "registrationCountry" to "US").ids(),
            )
        }
    }

    @Test fun listComputesMinMonthlyPriceUsdCorrectly() = kotlinx.coroutines.runBlocking {
        testAppDb(clock) {
            val token = loginForToken("bootstrap-admin", "bootstrap-password-1")
            val fixture = SearchFixture(PostgresSupport.database)
            val response = client.get("$root/providers?size=50") { header(auth(token).first, auth(token).second) }
            val content = Json.parseToJsonElement(response.bodyAsText()).jsonObject.getValue("content").jsonArray
            fun summaryOf(id: Long) = content.first { it.jsonObject.getValue("id").jsonPrimitive.long == id }.jsonObject
            fun minPrice(id: Long): BigDecimal? = summaryOf(id)["minMonthlyPriceUsd"]?.jsonPrimitive?.content?.let(::BigDecimal)
            fun minAnnualPerMonth(id: Long): BigDecimal? =
                summaryOf(id)["minAnnualPriceUsdPerMonth"]?.jsonPrimitive?.content?.let(::BigDecimal)

            assertEquals(BigDecimal("9.0000"), minPrice(fixture.nimbus.id)) // several tariffs; cheapest current MONTHLY wins
            assertNull(minPrice(fixture.polaris.id)) // cheapest is is_active=false, the other in_stock=false -> both excluded
            assertEquals(BigDecimal("18.0000"), minPrice(fixture.vertex.id)) // cheapest has amount_usd=NULL -> ignored
            assertNull(minPrice(fixture.wayfarer.id)) // only ANNUAL prices present
            assertEquals(BigDecimal("10.0000"), minAnnualPerMonth(fixture.wayfarer.id))
            assertEquals(BigDecimal("8.3333"), minAnnualPerMonth(fixture.nimbus.id)) // 100.00 / 12, half-up
            assertNull(minAnnualPerMonth(fixture.vertex.id))
            assertNull(minPrice(fixture.dormant.id)) // no active tariffs at all
            assertEquals(BigDecimal("5.0000"), minPrice(fixture.zephyr.id))

            assertFalse(summaryOf(fixture.wayfarer.id).containsKey("minMonthlyPriceUsd"), "a null minMonthlyPriceUsd must be an absent key")
        }
    }

    @Test fun listSortingByMinMonthlyPriceUsdAndValidation() = kotlinx.coroutines.runBlocking {
        testAppDb(clock) {
            val token = loginForToken("bootstrap-admin", "bootstrap-password-1")
            val fixture = SearchFixture(PostgresSupport.database)

            suspend fun idsSorted(sort: String): List<Long> {
                val response = client.get("$root/providers?sort=$sort&size=50") { header(auth(token).first, auth(token).second) }
                assertEquals(HttpStatusCode.OK, response.status)
                return Json.parseToJsonElement(response.bodyAsText()).jsonObject.getValue("content").jsonArray
                    .map { it.jsonObject.getValue("id").jsonPrimitive.long }
            }

            val nullPriced = setOf(fixture.polaris.id, fixture.dormant.id)
            val asc = idsSorted("minMonthlyPriceUsd")
            val desc = idsSorted("minMonthlyPriceUsd,desc")
            assertEquals(nullPriced, asc.takeLast(2).toSet(), "nulls must sort last ascending")
            assertEquals(nullPriced, desc.takeLast(2).toSet(), "nulls must sort last descending too")

            // Sort by the lower monthly equivalent, including annual-only providers.
            val expectedAscOrder = listOf(fixture.zephyr.id, fixture.nimbus.id, fixture.wayfarer.id, fixture.vertex.id)
            assertEquals(expectedAscOrder, asc.take(4))
            assertEquals(expectedAscOrder.reversed(), desc.take(4))

            assertEquals(
                HttpStatusCode.BadRequest,
                client.get("$root/providers?sort=unknown") { header(auth(token).first, auth(token).second) }.status,
            )
            assertEquals(
                HttpStatusCode.BadRequest,
                client.get("$root/providers?size=101") { header(auth(token).first, auth(token).second) }.status,
            )
            assertEquals(
                HttpStatusCode.OK,
                client.get("$root/providers?sort=id") { header(auth(token).first, auth(token).second) }.status,
            )
        }
    }

    @Test fun providerDetailReturnsFullPayload() = kotlinx.coroutines.runBlocking {
        testAppDb(clock) {
            val token = loginForToken("bootstrap-admin", "bootstrap-password-1")
            val fixture = SearchFixture(PostgresSupport.database)

            val response = client.get("$root/providers/${fixture.nimbus.id}") { header(auth(token).first, auth(token).second) }
            assertEquals(HttpStatusCode.OK, response.status)
            val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject

            assertEquals("nimbus-cloud", json.getValue("provider").jsonObject.getValue("slug").jsonPrimitive.content)
            assertEquals("US", json.getValue("legalInfo").jsonObject.getValue("registrationCountry").jsonPrimitive.content)
            assertEquals(
                setOf("PAYPAL", "CRYPTO_BTC", "BANK_WIRE"),
                json.getValue("paymentMethods").jsonArray.map { it.jsonObject.getValue("method").jsonPrimitive.content }.toSet(),
            )
            assertEquals(
                setOf("DE", "US"),
                json.getValue("locations").jsonArray.map { it.jsonObject.getValue("countryCode").jsonPrimitive.content }.toSet(),
            )

            val tariffs = json.getValue("tariffs").jsonArray
            assertEquals(3, tariffs.size, "all three nimbus tariffs are active")
            fun tariffNamed(name: String) = tariffs.first { it.jsonObject.getValue("tariff").jsonObject.getValue("name").jsonPrimitive.content == name }.jsonObject

            val proPrices = tariffNamed("Nimbus Pro").getValue("prices").jsonArray
            assertEquals(1, proPrices.size, "only the current MONTHLY row, the closed one is excluded")
            assertEquals("9.0000", proPrices[0].jsonObject.getValue("amountUsd").jsonPrimitive.content)

            val archivePrices = tariffNamed("Nimbus Archive").getValue("prices").jsonArray
            assertEquals("ANNUAL", archivePrices[0].jsonObject.getValue("billingPeriod").jsonPrimitive.content)

            assertEquals(
                HttpStatusCode.NotFound,
                client.get("$root/providers/999999") { header(auth(token).first, auth(token).second) }.status,
            )
        }
    }

    @Test fun providerTariffsSubResource() = kotlinx.coroutines.runBlocking {
        testAppDb(clock) {
            val token = loginForToken("bootstrap-admin", "bootstrap-password-1")
            val fixture = SearchFixture(PostgresSupport.database)

            suspend fun tariffsPage(id: Long, query: String = ""): JsonObject {
                val response = client.get("$root/providers/$id/tariffs$query") { header(auth(token).first, auth(token).second) }
                assertEquals(HttpStatusCode.OK, response.status)
                return Json.parseToJsonElement(response.bodyAsText()).jsonObject
            }

            val polarisDefault = tariffsPage(fixture.polaris.id)
            assertEquals(2, polarisDefault.getValue("totalElements").jsonPrimitive.long)
            val polarisActiveOnly = tariffsPage(fixture.polaris.id, "?activeOnly=true")
            assertEquals(1, polarisActiveOnly.getValue("totalElements").jsonPrimitive.long)
            assertEquals(
                listOf("Polaris Standard"),
                polarisActiveOnly.getValue("content").jsonArray.map { it.jsonObject.getValue("name").jsonPrimitive.content },
            )

            assertEquals(
                HttpStatusCode.NotFound,
                client.get("$root/providers/999999/tariffs") { header(auth(token).first, auth(token).second) }.status,
            )

            val page0 = tariffsPage(fixture.nimbus.id, "?size=2&page=0")
            assertEquals(3, page0.getValue("totalElements").jsonPrimitive.long)
            assertEquals(2, page0.getValue("totalPages").jsonPrimitive.int)
            assertEquals(2, page0.getValue("content").jsonArray.size)
            val page1 = tariffsPage(fixture.nimbus.id, "?size=2&page=1")
            assertEquals(1, page1.getValue("content").jsonArray.size)
        }
    }

    @Test fun readEndpointsAllowAnonymousAndAuthenticatedCallers() = kotlinx.coroutines.runBlocking {
        testAppDb(clock) {
            val fixture = SearchFixture(PostgresSupport.database)
            val viewerToken = loginAs(
                UserFixture(PostgresSupport.database, UserRepository(), PasswordHasher()),
                "read-viewer", "long-password-viewer", setOf(Role.VIEWER), clock.instant(),
            )
            val calls: List<suspend (String?) -> HttpResponse> = listOf(
                { t -> client.get("$root/providers") { if (t != null) header(auth(t).first, auth(t).second) } },
                { t -> client.get("$root/providers/${fixture.nimbus.id}") { if (t != null) header(auth(t).first, auth(t).second) } },
                { t -> client.get("$root/providers/${fixture.nimbus.id}/tariffs") { if (t != null) header(auth(t).first, auth(t).second) } },
            )
            calls.forEach { call ->
                assertEquals(HttpStatusCode.OK, call(null).status)
                assertEquals(HttpStatusCode.OK, call(viewerToken).status)
            }
        }
    }

    @Test fun wireFormatHasNoMoneyNumbersAndZTimestamps() = kotlinx.coroutines.runBlocking {
        testAppDb(clock) {
            val token = loginForToken("bootstrap-admin", "bootstrap-password-1")
            val fixture = SearchFixture(PostgresSupport.database)

            val listResponse = client.get("$root/providers?size=50") { header(auth(token).first, auth(token).second) }
            val content = Json.parseToJsonElement(listResponse.bodyAsText()).jsonObject.getValue("content").jsonArray
            content.forEach { item ->
                item.jsonObject["minMonthlyPriceUsd"]?.let { value ->
                    assertTrue(value is JsonPrimitive && value.isString, "minMonthlyPriceUsd must be a JSON string, not a number")
                }
            }
            val wayfarerItem = content.first { it.jsonObject.getValue("slug").jsonPrimitive.content == "search-wayfarer" }.jsonObject
            assertFalse(wayfarerItem.containsKey("minMonthlyPriceUsd"))

            val detailResponse = client.get("$root/providers/${fixture.nimbus.id}") { header(auth(token).first, auth(token).second) }
            val detail = Json.parseToJsonElement(detailResponse.bodyAsText()).jsonObject
            val createdAt = detail.getValue("provider").jsonObject.getValue("createdAt").jsonPrimitive.content
            assertTrue(createdAt.endsWith("Z"), "timestamps must be UTC ISO-8601 with a trailing Z")
            val priceAmount = detail.getValue("tariffs").jsonArray
                .first { it.jsonObject.getValue("tariff").jsonObject.getValue("name").jsonPrimitive.content == "Nimbus Basic" }
                .jsonObject.getValue("prices").jsonArray[0].jsonObject.getValue("amount")
            assertTrue(priceAmount is JsonPrimitive && priceAmount.isString, "amount must be a JSON string, not a number")
        }
    }
}
