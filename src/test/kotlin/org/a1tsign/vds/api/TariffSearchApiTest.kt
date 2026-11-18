package org.a1tsign.vds.api

import io.ktor.client.request.*
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.a1tsign.vds.auth.PasswordHasher
import org.a1tsign.vds.db.UserRepository
import org.a1tsign.vds.domain.*
import org.a1tsign.vds.support.*
import org.junit.jupiter.api.Test
import java.time.Duration
import kotlin.test.*

/** Scope filter and sort cases to the fixture provider so additional seed rows cannot change their expectations. */
class TariffSearchApiTest : AbstractDbTest() {
    private val root = "/api/v1"

    private suspend fun io.ktor.server.testing.ApplicationTestBuilder.search(
        token: String,
        vararg params: Pair<String, String>,
    ): JsonObject {
        val qs = (params.toList() + ("size" to "50")).joinToString("&") { (k, v) -> "$k=$v" }
        val response = client.get("$root/tariffs?$qs") { bearerAuth(token) }
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        return Json.parseToJsonElement(response.bodyAsText()).jsonObject
    }

    private fun JsonObject.items(): List<JsonObject> = getValue("content").jsonArray.map { it.jsonObject }
    private fun JsonObject.names(): Set<String> = items().map { it.tariffName() }.toSet()
    private fun JsonObject.namesOrdered(): List<String> = items().map { it.tariffName() }
    private fun JsonObject.tariffName(): String = getValue("tariff").jsonObject.getValue("name").jsonPrimitive.content
    private fun JsonObject.itemNamed(name: String): JsonObject = items().first { it.tariffName() == name }

    @Test fun searchFiltersReturnExactIdSetsAndCombineCorrectly() = runBlocking {
        testAppDb(clock) {
            val token = loginForToken("bootstrap-admin", "bootstrap-password-1")
            val fixture = SearchFixture(PostgresSupport.database)
            val zephyrId = fixture.zephyr.id.toString()
            val allZeta = setOf(
                "Zephyr Prime", "Zeta Alpha", "Zeta Beta", "Zeta Gamma",
                "Zeta Delta", "Zeta Epsilon", "Zeta History", "Zeta Empty",
            )

            assertEquals(allZeta, search(token, "providerId" to zephyrId).names())

            assertEquals(allZeta, search(token, "providerId" to zephyrId, "paymentMethod" to "CRYPTO_ETH").names())
            assertEquals(emptySet(), search(token, "providerId" to zephyrId, "paymentMethod" to "SBP").names())

            // Delta deliberately has two Japanese location links to catch duplicate result rows.
            assertEquals(setOf("Zeta Alpha", "Zeta Beta"), search(token, "providerId" to zephyrId, "country" to "BR").names())
            assertEquals(
                setOf("Zeta Gamma", "Zeta Delta", "Zeta Epsilon"),
                search(token, "providerId" to zephyrId, "country" to "JP").names(),
            )

            assertEquals(setOf("Zeta Alpha", "Zeta Empty"), search(token, "providerId" to zephyrId, "diskType" to "HDD").names())
            assertEquals(
                setOf("Zephyr Prime", "Zeta Beta", "Zeta Gamma"),
                search(token, "providerId" to zephyrId, "diskType" to "SSD").names(),
            )
            assertEquals(setOf("Zeta Delta", "Zeta History"), search(token, "providerId" to zephyrId, "diskType" to "NVME").names())
            assertEquals(setOf("Zeta Epsilon"), search(token, "providerId" to zephyrId, "diskType" to "UNKNOWN").names())

            val atOrAboveTwo = setOf("Zephyr Prime", "Zeta Beta", "Zeta Gamma", "Zeta Delta")
            assertEquals(atOrAboveTwo, search(token, "providerId" to zephyrId, "minVcpu" to "2").names())
            assertEquals(atOrAboveTwo, search(token, "providerId" to zephyrId, "minRamMb" to "2048").names())
            assertEquals(
                setOf("Zeta Beta", "Zeta Gamma", "Zeta Delta"),
                search(token, "providerId" to zephyrId, "minDiskGb" to "100").names(),
            )

            assertEquals(allZeta - "Zeta Beta", search(token, "providerId" to zephyrId, "unmeteredTraffic" to "true").names())
            assertEquals(setOf("Zeta Beta"), search(token, "providerId" to zephyrId, "unmeteredTraffic" to "false").names())

            assertEquals(
                setOf("Zeta Delta"),
                search(token, "providerId" to zephyrId, "country" to "JP", "minVcpu" to "5").names(),
            )
        }
    }

    @Test fun maxPriceUsdExcludesMissingOrNullFxCurrentPrices() = runBlocking {
        testAppDb(clock) {
            val token = loginForToken("bootstrap-admin", "bootstrap-password-1")
            val fixture = SearchFixture(PostgresSupport.database)

            // Nimbus Archive has no current MONTHLY price at all (ANNUAL only).
            assertEquals(
                setOf("Nimbus Basic", "Nimbus Pro"),
                search(token, "providerId" to fixture.nimbus.id.toString(), "maxPriceUsd" to "15").names(),
            )
            val nimbusUncapped = search(token, "providerId" to fixture.nimbus.id.toString())
            assertEquals(setOf("Nimbus Basic", "Nimbus Pro", "Nimbus Archive"), nimbusUncapped.names())
            assertFalse(nimbusUncapped.itemNamed("Nimbus Archive").containsKey("currentPrice"))

            // Vertex Edge's current MONTHLY price has amount_usd = NULL (no FX rate).
            assertEquals(
                setOf("Vertex Core"),
                search(token, "providerId" to fixture.vertex.id.toString(), "maxPriceUsd" to "100").names(),
            )
            val vertexUncapped = search(token, "providerId" to fixture.vertex.id.toString())
            assertEquals(setOf("Vertex Edge", "Vertex Core"), vertexUncapped.names())
            val edgeItem = vertexUncapped.itemNamed("Vertex Edge")
            assertTrue(edgeItem.containsKey("currentPrice"), "Edge has a current price row, just no FX rate")
            val edgePrice = edgeItem.getValue("currentPrice").jsonObject
            assertFalse(edgePrice.containsKey("amountUsd"), "a null amountUsd must be an absent key, not JSON null")
            assertTrue(edgePrice.getValue("amount").let { it is JsonPrimitive && it.isString }, "amount must never be a JSON number")
        }
    }

    @Test fun billingPeriodSelectsItsOwnCurrentPriceNeverMixingPeriods() = runBlocking {
        testAppDb(clock) {
            val token = loginForToken("bootstrap-admin", "bootstrap-password-1")
            val fixture = SearchFixture(PostgresSupport.database)

            val monthly = search(token, "providerId" to fixture.nimbus.id.toString())
            assertFalse(monthly.itemNamed("Nimbus Archive").containsKey("currentPrice"), "Archive is ANNUAL-only")

            val annual = search(token, "providerId" to fixture.nimbus.id.toString(), "billingPeriod" to "ANNUAL")
            assertEquals(setOf("Nimbus Basic", "Nimbus Pro", "Nimbus Archive"), annual.names())
            val archive = annual.itemNamed("Nimbus Archive").getValue("currentPrice").jsonObject
            assertEquals("ANNUAL", archive.getValue("billingPeriod").jsonPrimitive.content)
            assertEquals("100.0000", archive.getValue("amountUsd").jsonPrimitive.content)
            assertFalse(annual.itemNamed("Nimbus Basic").containsKey("currentPrice"), "a MONTHLY-only tariff has no ANNUAL current price")
        }
    }

    @Test fun activeOnlyDefaultsTrueAndInStockFiltersIndependently() = runBlocking {
        testAppDb(clock) {
            val token = loginForToken("bootstrap-admin", "bootstrap-password-1")
            val fixture = SearchFixture(PostgresSupport.database)
            val polarisId = fixture.polaris.id.toString()

            assertEquals(setOf("Polaris Standard"), search(token, "providerId" to polarisId).names())
            assertEquals(
                setOf("Polaris Mini", "Polaris Standard"),
                search(token, "providerId" to polarisId, "activeOnly" to "false").names(),
            )
            assertEquals(
                setOf("Polaris Standard"),
                search(token, "providerId" to polarisId, "activeOnly" to "false", "inStock" to "false").names(),
                "inStock=false must return exactly the out-of-stock ones",
            )
        }
    }

    @Test fun sortByPriceUsdNullsLastAndTieBreaksByIdAscBothDirectionsPlusValidation() = runBlocking {
        testAppDb(clock) {
            val token = loginForToken("bootstrap-admin", "bootstrap-password-1")
            val fixture = SearchFixture(PostgresSupport.database)
            val zephyrId = fixture.zephyr.id.toString()

            // Four distinct non-null values (5, 10, 30, 40) plus three null ones -- proves both
            // the asc/desc reversal and "nulls last in both directions".
            val unmeteredAsc = search(token, "providerId" to zephyrId, "unmeteredTraffic" to "true", "sort" to "priceUsd").namesOrdered()
            assertEquals(
                listOf("Zephyr Prime", "Zeta Alpha", "Zeta Gamma", "Zeta History", "Zeta Delta", "Zeta Epsilon", "Zeta Empty"),
                unmeteredAsc,
            )
            val unmeteredDesc = search(token, "providerId" to zephyrId, "unmeteredTraffic" to "true", "sort" to "priceUsd,desc").namesOrdered()
            assertEquals(
                listOf("Zeta History", "Zeta Gamma", "Zeta Alpha", "Zephyr Prime", "Zeta Delta", "Zeta Epsilon", "Zeta Empty"),
                unmeteredDesc,
            )

            // Alpha and Beta are tied at 10.00 -- id ASC tie-break, same order both directions.
            val tieAsc = search(token, "providerId" to zephyrId, "country" to "BR", "sort" to "priceUsd").namesOrdered()
            val tieDesc = search(token, "providerId" to zephyrId, "country" to "BR", "sort" to "priceUsd,desc").namesOrdered()
            assertEquals(listOf("Zeta Alpha", "Zeta Beta"), tieAsc)
            assertEquals(listOf("Zeta Alpha", "Zeta Beta"), tieDesc)

            assertEquals(HttpStatusCode.BadRequest, client.get("$root/tariffs?sort=unknown") { bearerAuth(token) }.status)
            assertEquals(HttpStatusCode.BadRequest, client.get("$root/tariffs?size=101") { bearerAuth(token) }.status)
            assertEquals(HttpStatusCode.OK, client.get("$root/tariffs?sort=id") { bearerAuth(token) }.status)
        }
    }

    @Test fun tariffDetailReturnsFullPayloadAndClosedPeriodDisappears() = runBlocking {
        testAppDb(clock) {
            val token = loginForToken("bootstrap-admin", "bootstrap-password-1")
            val fixture = SearchFixture(PostgresSupport.database)

            val listed = search(token, "providerId" to fixture.zephyr.id.toString(), "country" to "BR")
            val alphaId = listed.itemNamed("Zeta Alpha").getValue("tariff").jsonObject.getValue("id").jsonPrimitive.long

            val detailResponse = client.get("$root/tariffs/$alphaId") { bearerAuth(token) }
            assertEquals(HttpStatusCode.OK, detailResponse.status)
            val detail = Json.parseToJsonElement(detailResponse.bodyAsText()).jsonObject
            assertEquals("Zeta Alpha", detail.getValue("tariff").jsonObject.getValue("name").jsonPrimitive.content)
            assertEquals("search-zephyr", detail.getValue("provider").jsonObject.getValue("slug").jsonPrimitive.content)
            assertEquals(
                setOf("BR"),
                detail.getValue("locations").jsonArray.map { it.jsonObject.getValue("countryCode").jsonPrimitive.content }.toSet(),
            )
            val pricesBeforeClose = detail.getValue("currentPrices").jsonArray
            assertEquals(1, pricesBeforeClose.size)
            assertEquals("MONTHLY", pricesBeforeClose[0].jsonObject.getValue("billingPeriod").jsonPrimitive.content)

            // Close the current MONTHLY price (the clock starts at the same instant the fixture's
            // prices were inserted at, so it must advance first or `effectiveTo` would not be after
            // the current row's `effectiveFrom`).
            clock.advance(Duration.ofDays(1))
            val close = client.post("$root/tariffs/$alphaId/price/close") {
                bearerAuth(token)
                contentType(ContentType.Application.Json)
                setBody(buildJsonObject { put("billingPeriod", "MONTHLY") }.toString())
            }
            assertEquals(HttpStatusCode.OK, close.status, close.bodyAsText())

            val afterClose = Json.parseToJsonElement(
                client.get("$root/tariffs/$alphaId") { bearerAuth(token) }.bodyAsText(),
            ).jsonObject
            assertEquals(0, afterClose.getValue("currentPrices").jsonArray.size, "the closed period must disappear from the detail")

            assertEquals(HttpStatusCode.NotFound, client.get("$root/tariffs/999999") { bearerAuth(token) }.status)
        }
    }

    @Test fun dictionariesMatchEnumEntriesAndKeepTheTwoDataSourcesSeparate() = runBlocking {
        testAppDb(clock) {
            val token = loginForToken("bootstrap-admin", "bootstrap-password-1")
            val response = client.get("$root/dictionaries") { bearerAuth(token) }
            assertEquals(HttpStatusCode.OK, response.status)
            val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            fun set(key: String): Set<String> = json.getValue(key).jsonArray.map { it.jsonPrimitive.content }.toSet()

            assertEquals(PaymentMethod.entries.map { it.name }.toSet(), set("paymentMethods"))
            assertEquals(BillingPeriod.entries.map { it.name }.toSet(), set("billingPeriods"))
            assertEquals(DiskType.entries.map { it.name }.toSet(), set("diskTypes"))
            assertEquals(Virtualization.entries.map { it.name }.toSet(), set("virtualizationTypes"))
            assertEquals(ProviderStatus.entries.map { it.name }.toSet(), set("providerStatuses"))
            assertEquals(Confidence.entries.map { it.name }.toSet(), set("confidenceLevels"))
            assertEquals(LegalDataSource.entries.map { it.name }.toSet(), set("legalDataSources"))
            assertEquals(PriceDataSource.entries.map { it.name }.toSet(), set("priceDataSources"))
            assertNotEquals(set("legalDataSources"), set("priceDataSources"), "the two data-source enums must not be conflated")
        }
    }

    @Test fun readEndpointsAllowAnonymousAndAuthenticatedCallers() = runBlocking {
        testAppDb(clock) {
            val fixture = SearchFixture(PostgresSupport.database)
            val adminToken = loginForToken("bootstrap-admin", "bootstrap-password-1")
            val viewerToken = loginAs(
                UserFixture(PostgresSupport.database, UserRepository(), PasswordHasher()),
                "tariff-viewer", "long-password-viewer", setOf(Role.VIEWER), clock.instant(),
            )
            val anyId = search(adminToken, "providerId" to fixture.zephyr.id.toString())
                .items().first().getValue("tariff").jsonObject.getValue("id").jsonPrimitive.long

            val calls: List<suspend (String?) -> HttpResponse> = listOf(
                { t -> client.get("$root/tariffs") { if (t != null) bearerAuth(t) } },
                { t -> client.get("$root/tariffs/$anyId") { if (t != null) bearerAuth(t) } },
                { t -> client.get("$root/tariffs/$anyId/price-history") { if (t != null) bearerAuth(t) } },
                { t -> client.get("$root/dictionaries") { if (t != null) bearerAuth(t) } },
            )
            calls.forEach { call ->
                assertEquals(HttpStatusCode.OK, call(null).status)
                assertEquals(HttpStatusCode.OK, call(viewerToken).status)
            }
        }
    }
}
