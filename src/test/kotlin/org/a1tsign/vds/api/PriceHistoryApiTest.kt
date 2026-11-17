package org.a1tsign.vds.api

import io.ktor.client.request.*
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.a1tsign.vds.auth.PasswordHasher
import org.a1tsign.vds.db.UserRepository
import org.a1tsign.vds.domain.Role
import org.a1tsign.vds.support.*
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeFormatter
import kotlin.test.*

/** `GET /api/v1/tariffs/{id}/price-history` -- task vds-001.16. */
class PriceHistoryApiTest : AbstractDbTest() {
    private val root = "/api/v1"

    // Matches SearchFixture's own fixed base instant -- see its "Zeta History" tariff doc comment.
    private val base: Instant = Instant.parse("2024-01-01T00:00:00Z")

    private fun iso(instant: Instant): String = DateTimeFormatter.ISO_INSTANT.format(instant)

    private suspend fun io.ktor.server.testing.ApplicationTestBuilder.tariffIdNamed(
        token: String,
        providerId: Long,
        name: String,
    ): Long {
        val response = client.get("$root/tariffs?providerId=$providerId&size=50") { bearerAuth(token) }
        assertEquals(HttpStatusCode.OK, response.status)
        val content = Json.parseToJsonElement(response.bodyAsText()).jsonObject.getValue("content").jsonArray
        return content.first { it.jsonObject.getValue("tariff").jsonObject.getValue("name").jsonPrimitive.content == name }
            .jsonObject.getValue("tariff").jsonObject.getValue("id").jsonPrimitive.long
    }

    private suspend fun io.ktor.server.testing.ApplicationTestBuilder.history(
        token: String,
        tariffId: Long,
        query: String = "",
    ): HttpResponse = client.get("$root/tariffs/$tariffId/price-history$query") { bearerAuth(token) }

    @Test fun historyIsNewestFirstAndBillingPeriodNarrows() = runBlocking {
        testAppDb(clock) {
            val token = loginForToken("bootstrap-admin", "bootstrap-password-1")
            val fixture = SearchFixture(PostgresSupport.database)
            val historyId = tariffIdNamed(token, fixture.zephyr.id, "Zeta History")

            val all = history(token, historyId)
            assertEquals(HttpStatusCode.OK, all.status)
            val allRows = Json.parseToJsonElement(all.bodyAsText()).jsonArray
            assertEquals(5, allRows.size)
            val amountsDesc = allRows.map { it.jsonObject.getValue("amount").jsonPrimitive.content }
            assertEquals(
                listOf("500.0000", "40.0000", "30.0000", "20.0000", "10.0000"),
                amountsDesc,
                "newest effectiveFrom first",
            )

            val monthlyOnly = history(token, historyId, "?billingPeriod=MONTHLY")
            val monthlyRows = Json.parseToJsonElement(monthlyOnly.bodyAsText()).jsonArray
            assertEquals(4, monthlyRows.size, "the ANNUAL row must be excluded")
            assertTrue(monthlyRows.all { it.jsonObject.getValue("billingPeriod").jsonPrimitive.content == "MONTHLY" })
        }
    }

    @Test fun fromToSelectExactlyTheOverlappingRows() = runBlocking {
        testAppDb(clock) {
            val token = loginForToken("bootstrap-admin", "bootstrap-password-1")
            val fixture = SearchFixture(PostgresSupport.database)
            val historyId = tariffIdNamed(token, fixture.zephyr.id, "Zeta History")

            val from = iso(base.plus(Duration.ofDays(5)))
            val to = iso(base.plus(Duration.ofDays(35)))
            val windowed = history(token, historyId, "?billingPeriod=MONTHLY&from=$from&to=$to")
            assertEquals(HttpStatusCode.OK, windowed.status)
            val amounts = Json.parseToJsonElement(windowed.bodyAsText()).jsonArray
                .map { it.jsonObject.getValue("amount").jsonPrimitive.content }
            // row1 (0-10d: starts before `from`, ends inside), row2 (10-20d: fully inside) and
            // row3 (20-30d: fully inside) overlap [5d, 35d); row4 (40d-open: entirely after `to`)
            // does not.
            assertEquals(setOf("10.0000", "20.0000", "30.0000"), amounts.toSet())
            assertFalse(amounts.contains("40.0000"))

            // An open-ended row overlaps any interval with no upper bound (`to` unset).
            val openEnded = history(token, historyId, "?billingPeriod=MONTHLY&from=${iso(base.plus(Duration.ofDays(35)))}")
            val openAmounts = Json.parseToJsonElement(openEnded.bodyAsText()).jsonArray
                .map { it.jsonObject.getValue("amount").jsonPrimitive.content }
            assertEquals(listOf("40.0000"), openAmounts)
        }
    }

    @Test fun malformedTimestampAndUnknownOrEmptyTariffsAreHandled() = runBlocking {
        testAppDb(clock) {
            val token = loginForToken("bootstrap-admin", "bootstrap-password-1")
            val fixture = SearchFixture(PostgresSupport.database)
            val historyId = tariffIdNamed(token, fixture.zephyr.id, "Zeta History")
            val emptyId = tariffIdNamed(token, fixture.zephyr.id, "Zeta Empty")

            assertEquals(
                HttpStatusCode.BadRequest,
                history(token, historyId, "?from=2024-01-01T00:00:00").status,
                "an offset-less timestamp must be rejected",
            )

            val empty = history(token, emptyId)
            assertEquals(HttpStatusCode.OK, empty.status)
            assertEquals(0, Json.parseToJsonElement(empty.bodyAsText()).jsonArray.size)

            assertEquals(HttpStatusCode.NotFound, history(token, 999999).status)
        }
    }

    @Test fun priceHistoryAllowsAnonymousAndAuthenticatedCallers() = runBlocking {
        testAppDb(clock) {
            val fixture = SearchFixture(PostgresSupport.database)
            val adminToken = loginForToken("bootstrap-admin", "bootstrap-password-1")
            val historyId = tariffIdNamed(adminToken, fixture.zephyr.id, "Zeta History")
            val viewerToken = loginAs(
                UserFixture(PostgresSupport.database, UserRepository(), PasswordHasher()),
                "history-viewer", "long-password-history", setOf(Role.VIEWER), clock.instant(),
            )
            assertEquals(HttpStatusCode.OK, client.get("$root/tariffs/$historyId/price-history").status)
            assertEquals(HttpStatusCode.OK, history(viewerToken, historyId).status)
        }
    }
}
