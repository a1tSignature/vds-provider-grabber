package org.a1tsign.vds.api

import io.ktor.client.request.*
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.a1tsign.vds.auth.PasswordHasher
import org.a1tsign.vds.db.CurrencyRateRepository
import org.a1tsign.vds.db.PriceRepository
import org.a1tsign.vds.db.UserRepository
import org.a1tsign.vds.domain.BillingPeriod
import org.a1tsign.vds.domain.PriceDataSource
import org.a1tsign.vds.domain.Role
import org.a1tsign.vds.fx.CurrencyConverter
import org.a1tsign.vds.fx.FetchedRates
import org.a1tsign.vds.fx.FxRefreshService
import org.a1tsign.vds.support.AbstractDbTest
import org.a1tsign.vds.support.CatalogFixture
import org.a1tsign.vds.support.PostgresSupport
import org.a1tsign.vds.support.StubCurrencyRateProvider
import org.a1tsign.vds.support.UserFixture
import org.a1tsign.vds.support.capturingLogs
import org.a1tsign.vds.support.loginAs
import org.a1tsign.vds.support.loginForToken
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.slf4j.event.Level
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeFormatter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PriceWriteApiTest : AbstractDbTest() {
    private val root = "/api/v1"

    private fun isoInstant(instant: Instant): String = DateTimeFormatter.ISO_INSTANT.format(instant)

    private fun priceBody(
        billingPeriod: BillingPeriod = BillingPeriod.MONTHLY,
        amount: String = "10",
        currency: String = "USD",
        effectiveFrom: Instant? = null,
        dataSource: String? = null,
    ): String = kotlinx.serialization.json.buildJsonObject {
        put("billingPeriod", billingPeriod.name)
        put("amount", amount)
        put("currency", currency)
        if (effectiveFrom != null) put("effectiveFrom", isoInstant(effectiveFrom))
        if (dataSource != null) put("dataSource", dataSource)
    }.toString()

    private fun closeBody(billingPeriod: BillingPeriod = BillingPeriod.MONTHLY, effectiveTo: Instant? = null): String =
        kotlinx.serialization.json.buildJsonObject {
            put("billingPeriod", billingPeriod.name)
            if (effectiveTo != null) put("effectiveTo", isoInstant(effectiveTo))
        }.toString()

    private suspend fun io.ktor.server.testing.ApplicationTestBuilder.putPrice(tariffId: Long, token: String, body: String): HttpResponse =
        client.put("$root/tariffs/$tariffId/price") { bearerAuth(token); contentType(ContentType.Application.Json); setBody(body) }

    private suspend fun io.ktor.server.testing.ApplicationTestBuilder.closePrice(tariffId: Long, token: String, body: String): HttpResponse =
        client.post("$root/tariffs/$tariffId/price/close") { bearerAuth(token); contentType(ContentType.Application.Json); setBody(body) }

    private suspend fun io.ktor.server.testing.ApplicationTestBuilder.correctPrice(tariffId: Long, priceId: Long, token: String): HttpResponse =
        client.delete("$root/tariffs/$tariffId/prices/$priceId") { bearerAuth(token) }

    private fun json(response: HttpResponse): JsonObject = runBlocking { Json.parseToJsonElement(response.bodyAsText()).jsonObject }

    private fun currentRowCount(tariffId: Long, billingPeriod: BillingPeriod): Long = transaction(PostgresSupport.database) {
        org.a1tsign.vds.db.TariffPriceTable.selectAll()
            .where {
                (org.a1tsign.vds.db.TariffPriceTable.tariffId eq tariffId) and
                    (org.a1tsign.vds.db.TariffPriceTable.billingPeriod eq billingPeriod.name) and
                    org.a1tsign.vds.db.TariffPriceTable.effectiveTo.isNull()
            }.count()
    }

    private fun rowCount(tariffId: Long, billingPeriod: BillingPeriod): Long = transaction(PostgresSupport.database) {
        org.a1tsign.vds.db.TariffPriceTable.selectAll()
            .where {
                (org.a1tsign.vds.db.TariffPriceTable.tariffId eq tariffId) and
                    (org.a1tsign.vds.db.TariffPriceTable.billingPeriod eq billingPeriod.name)
            }.count()
    }

    @Test
    fun idempotentResubmissionBackdatingAndFutureSkew() = runBlocking {
        org.a1tsign.vds.support.testAppDb(clock) {
            val token = loginForToken("bootstrap-admin", "bootstrap-password-1")
            val fixture = CatalogFixture(PostgresSupport.database)
            val provider = fixture.createProvider("price-one", clock.instant())
            val tariff = fixture.createTariff(provider.id, "Basic", clock.instant())

            val first = putPrice(tariff.id, token, priceBody(amount = "10", currency = "USD"))
            assertEquals(HttpStatusCode.Created, first.status)
            val firstId = json(first).getValue("id").jsonPrimitive.long
            clock.advance(Duration.ofSeconds(5))
            val secondEffectiveFrom = clock.instant()
            val second = putPrice(tariff.id, token, priceBody(amount = "12", currency = "USD"))
            assertEquals(HttpStatusCode.Created, second.status)
            val secondId = json(second).getValue("id").jsonPrimitive.long
            assertEquals(2L, rowCount(tariff.id, BillingPeriod.MONTHLY))
            assertEquals(1L, currentRowCount(tariff.id, BillingPeriod.MONTHLY))
            val repo = PriceRepository()
            val firstRow = transaction(PostgresSupport.database) { repo.findById(firstId)!! }
            val secondRow = transaction(PostgresSupport.database) { repo.findById(secondId)!! }
            assertEquals(secondEffectiveFrom, firstRow.effectiveTo)
            assertEquals(secondEffectiveFrom, secondRow.effectiveFrom)
            assertNull(secondRow.effectiveTo)

            clock.advance(Duration.ofSeconds(5))
            val resubmit = putPrice(tariff.id, token, priceBody(amount = "12.0000", currency = "USD"))
            assertEquals(HttpStatusCode.OK, resubmit.status)
            assertEquals(secondId, json(resubmit).getValue("id").jsonPrimitive.long)
            assertEquals(2L, rowCount(tariff.id, BillingPeriod.MONTHLY))

            clock.advance(Duration.ofSeconds(5))
            val currencyChange = putPrice(tariff.id, token, priceBody(amount = "12", currency = "EUR"))
            assertEquals(HttpStatusCode.Created, currencyChange.status)
            assertEquals(3L, rowCount(tariff.id, BillingPeriod.MONTHLY))
            val thirdId = json(currencyChange).getValue("id").jsonPrimitive.long

            val thirdRow = transaction(PostgresSupport.database) { repo.findById(thirdId)!! }
            val equalAttempt = putPrice(tariff.id, token, priceBody(amount = "99", currency = "EUR", effectiveFrom = thirdRow.effectiveFrom))
            assertEquals(HttpStatusCode.Conflict, equalAttempt.status)
            val earlierAttempt = putPrice(tariff.id, token, priceBody(amount = "99", currency = "EUR", effectiveFrom = thirdRow.effectiveFrom.minusSeconds(1)))
            assertEquals(HttpStatusCode.Conflict, earlierAttempt.status)

            val now = clock.instant()
            val tooFarFuture = putPrice(tariff.id, token, priceBody(billingPeriod = BillingPeriod.ANNUAL, amount = "5", currency = "USD", effectiveFrom = now.plusSeconds(61)))
            assertEquals(HttpStatusCode.BadRequest, tooFarFuture.status)
            assertEquals(setOf("effectiveFrom"), json(tooFarFuture).getValue("details").jsonArray.map { it.jsonObject.getValue("field").jsonPrimitive.content }.toSet())

            val withinSkew = putPrice(tariff.id, token, priceBody(billingPeriod = BillingPeriod.ANNUAL, amount = "5", currency = "USD", effectiveFrom = now.plusSeconds(59)))
            assertEquals(HttpStatusCode.Created, withinSkew.status)
            val storedSkewed = transaction(PostgresSupport.database) {
                repo.findById(json(withinSkew).getValue("id").jsonPrimitive.long)!!
            }
            assertEquals(now.plusSeconds(59), storedSkewed.effectiveFrom)

            assertEquals(PriceDataSource.MANUAL, storedSkewed.dataSource)
            clock.advance(Duration.ofSeconds(5))
            val crawlerWrite = putPrice(tariff.id, token, priceBody(billingPeriod = BillingPeriod.QUARTERLY, amount = "7", currency = "USD", dataSource = "CRAWLER"))
            assertEquals(HttpStatusCode.Created, crawlerWrite.status)
            val crawlerRow = transaction(PostgresSupport.database) { repo.findById(json(crawlerWrite).getValue("id").jsonPrimitive.long)!! }
            assertEquals(PriceDataSource.CRAWLER, crawlerRow.dataSource)
            val badDataSource = putPrice(tariff.id, token, priceBody(billingPeriod = BillingPeriod.BIENNIAL, amount = "7", currency = "USD", dataSource = "NOPE"))
            assertEquals(HttpStatusCode.BadRequest, badDataSource.status)
            assertEquals(setOf("dataSource"), json(badDataSource).getValue("details").jsonArray.map { it.jsonObject.getValue("field").jsonPrimitive.content }.toSet())

            assertEquals(HttpStatusCode.NotFound, putPrice(999999, token, priceBody()).status)

            val viewerToken = loginAs(UserFixture(PostgresSupport.database, UserRepository(), PasswordHasher()), "price-viewer", "long-password-viewer", setOf(Role.VIEWER), clock.instant())
            assertEquals(HttpStatusCode.Forbidden, putPrice(tariff.id, viewerToken, priceBody()).status)
            assertEquals(
                HttpStatusCode.Unauthorized,
                client.put("$root/tariffs/${tariff.id}/price") { contentType(ContentType.Application.Json); setBody(priceBody()) }.status,
            )
        }
    }

    @Test
    fun fxFreezeNoRateWarningAndUsdShortcut() = runBlocking {
        org.a1tsign.vds.support.testAppDb(clock) {
            val token = loginForToken("bootstrap-admin", "bootstrap-password-1")
            val fixture = CatalogFixture(PostgresSupport.database)
            val provider = fixture.createProvider("price-fx", clock.instant())
            val tariff = fixture.createTariff(provider.id, "Basic", clock.instant())
            val rateRepository = CurrencyRateRepository()
            val converter = CurrencyConverter(rateRepository)
            val stub = StubCurrencyRateProvider(Result.success(FetchedRates("stub", clock.instant(), mapOf("EUR" to BigDecimal("0.5")))))
            val refreshService = FxRefreshService(stub, PostgresSupport.database, rateRepository, converter, clock)
            assertEquals(1, refreshService.refresh())

            val written = putPrice(tariff.id, token, priceBody(amount = "10", currency = "EUR"))
            assertEquals(HttpStatusCode.Created, written.status)
            val priceId = json(written).getValue("id").jsonPrimitive.long
            val repo = PriceRepository()
            val beforeRefresh = transaction(PostgresSupport.database) { repo.findById(priceId)!! }
            assertEquals(BigDecimal("20.0000"), beforeRefresh.amountUsd)
            assertEquals(BigDecimal("2.000000000000"), beforeRefresh.usdRate)

            clock.advance(Duration.ofSeconds(10))
            stub.next = Result.success(FetchedRates("stub", clock.instant(), mapOf("EUR" to BigDecimal("0.25"))))
            assertEquals(1, refreshService.refresh())
            val afterRefresh = transaction(PostgresSupport.database) { repo.findById(priceId)!! }
            assertEquals(beforeRefresh.amountUsd, afterRefresh.amountUsd)
            assertEquals(beforeRefresh.usdRate, afterRefresh.usdRate)
            assertEquals(beforeRefresh.usdRateAt, afterRefresh.usdRateAt)

            val noRate = putPrice(tariff.id, token, priceBody(billingPeriod = BillingPeriod.ANNUAL, amount = "5", currency = "GBP"))
            assertTrue(noRate.status.value in 200..299)
            assertEquals(listOf("NO_FX_RATE"), json(noRate).getValue("warnings").jsonArray.map { it.jsonPrimitive.content })
            val noRateRow = transaction(PostgresSupport.database) { repo.findById(json(noRate).getValue("id").jsonPrimitive.long)!! }
            assertNull(noRateRow.amountUsd); assertNull(noRateRow.usdRate); assertNull(noRateRow.usdRateAt)

            assertTrue(json(written).containsKey("warnings"))
            assertEquals(emptyList(), json(written).getValue("warnings").jsonArray.map { it.jsonPrimitive.content })

            val usdWrite = putPrice(tariff.id, token, priceBody(billingPeriod = BillingPeriod.SEMIANNUAL, amount = "8", currency = "USD"))
            assertEquals(HttpStatusCode.Created, usdWrite.status)
            val usdRow = transaction(PostgresSupport.database) { repo.findById(json(usdWrite).getValue("id").jsonPrimitive.long)!! }
            assertEquals(0, BigDecimal.ONE.compareTo(usdRow.usdRate))
            assertEquals(usdRow.effectiveFrom, usdRow.usdRateAt)
            assertEquals(BigDecimal("8.0000"), usdRow.amountUsd)
            assertEquals(
                0L,
                transaction(PostgresSupport.database) {
                    org.a1tsign.vds.db.CurrencyRateTable.selectAll()
                        .where { org.a1tsign.vds.db.CurrencyRateTable.currency eq "USD" }
                        .count()
                },
            )
        }
    }

    @Test
    fun closeSemantics() = runBlocking {
        org.a1tsign.vds.support.testAppDb(clock) {
            val token = loginForToken("bootstrap-admin", "bootstrap-password-1")
            val fixture = CatalogFixture(PostgresSupport.database)
            val provider = fixture.createProvider("price-close", clock.instant())
            val tariff = fixture.createTariff(provider.id, "Basic", clock.instant())
            val write = putPrice(tariff.id, token, priceBody())
            assertEquals(HttpStatusCode.Created, write.status)
            val rowEffectiveFrom = transaction(PostgresSupport.database) {
                PriceRepository().findById(json(write).getValue("id").jsonPrimitive.long)!!.effectiveFrom
            }

            assertEquals(HttpStatusCode.Conflict, closePrice(tariff.id, token, closeBody(effectiveTo = rowEffectiveFrom)).status)
            assertEquals(HttpStatusCode.Conflict, closePrice(tariff.id, token, closeBody(effectiveTo = rowEffectiveFrom.minusSeconds(1))).status)

            val now = clock.instant()
            assertEquals(HttpStatusCode.BadRequest, closePrice(tariff.id, token, closeBody(effectiveTo = now.plusSeconds(61))).status)

            clock.advance(Duration.ofSeconds(10))
            val closed = closePrice(tariff.id, token, closeBody())
            assertEquals(HttpStatusCode.OK, closed.status)
            assertEquals(0L, currentRowCount(tariff.id, BillingPeriod.MONTHLY))

            assertEquals(HttpStatusCode.NotFound, closePrice(tariff.id, token, closeBody()).status)

            assertEquals(HttpStatusCode.NotFound, closePrice(999999, token, closeBody()).status)
        }
    }

    @Test
    fun adminCorrectionSemanticsAndRoles() = runBlocking {
        org.a1tsign.vds.support.testAppDb(clock) {
            val token = loginForToken("bootstrap-admin", "bootstrap-password-1")
            val editorToken = loginAs(UserFixture(PostgresSupport.database, UserRepository(), PasswordHasher()), "price-editor", "long-password-editor", setOf(Role.EDITOR), clock.instant())
            val fixture = CatalogFixture(PostgresSupport.database)
            val one = fixture.createProvider("price-admin-one", clock.instant())
            val two = fixture.createProvider("price-admin-two", clock.instant())
            val tariff = fixture.createTariff(one.id, "Basic", clock.instant())
            val otherTariff = fixture.createTariff(two.id, "Other", clock.instant())
            val repo = PriceRepository()

            val aResponse = putPrice(tariff.id, token, priceBody(amount = "10"))
            val aId = json(aResponse).getValue("id").jsonPrimitive.long
            clock.advance(Duration.ofSeconds(5))
            val bResponse = putPrice(tariff.id, token, priceBody(amount = "12"))
            val bId = json(bResponse).getValue("id").jsonPrimitive.long

            assertEquals(HttpStatusCode.Forbidden, correctPrice(tariff.id, bId, editorToken).status)

            assertEquals(HttpStatusCode.Conflict, correctPrice(tariff.id, aId, token).status)

            assertEquals(HttpStatusCode.NotFound, correctPrice(otherTariff.id, bId, token).status)

            assertEquals(HttpStatusCode.NotFound, correctPrice(tariff.id, 999999, token).status)

            val logs = capturingLogs {
                val deletion = runBlocking { correctPrice(tariff.id, bId, token) }
                assertEquals(HttpStatusCode.NoContent, deletion.status)
            }
            assertNull(transaction(PostgresSupport.database) { repo.findById(bId) })
            val reopenedA = transaction(PostgresSupport.database) { repo.findById(aId)!! }
            assertNull(reopenedA.effectiveTo)
            val warnLines = logs.messagesAt(Level.WARN)
            assertTrue(warnLines.any { it.contains(tariff.id.toString()) && it.contains("MONTHLY") && it.contains("12.0000") && it.contains("USD") })

            val cResponse = putPrice(tariff.id, token, priceBody(billingPeriod = BillingPeriod.ANNUAL, amount = "100"))
            val cId = json(cResponse).getValue("id").jsonPrimitive.long
            clock.advance(Duration.ofSeconds(5))
            val closeResponse = closePrice(tariff.id, token, closeBody(billingPeriod = BillingPeriod.ANNUAL))
            assertEquals(HttpStatusCode.OK, closeResponse.status)
            val cClosedAt = transaction(PostgresSupport.database) { repo.findById(cId)!!.effectiveTo!! }
            clock.advance(Duration.ofSeconds(5))
            val dResponse = putPrice(tariff.id, token, priceBody(billingPeriod = BillingPeriod.ANNUAL, amount = "110"))
            val dId = json(dResponse).getValue("id").jsonPrimitive.long
            assertEquals(HttpStatusCode.NoContent, correctPrice(tariff.id, dId, token).status)
            val cAfterDeletion = transaction(PostgresSupport.database) { repo.findById(cId)!! }
            assertEquals(cClosedAt, cAfterDeletion.effectiveTo)
        }
    }

    @Test
    fun lastConfirmedAtAdvancesOnlyOnUnchangedResubmission() = runBlocking {
        org.a1tsign.vds.support.testAppDb(clock) {
            val token = loginForToken("bootstrap-admin", "bootstrap-password-1")
            val fixture = CatalogFixture(PostgresSupport.database)
            val provider = fixture.createProvider("price-confirm", clock.instant())
            val tariff = fixture.createTariff(provider.id, "Basic", clock.instant())
            val repo = PriceRepository()
            fun row(id: Long) = transaction(PostgresSupport.database) { repo.findById(id)!! }

            val writtenAt = clock.instant()
            val created = putPrice(tariff.id, token, priceBody(effectiveFrom = writtenAt.minus(Duration.ofDays(1))))
            assertEquals(HttpStatusCode.Created, created.status)
            val id = json(created).getValue("id").jsonPrimitive.long
            val original = row(id)
            assertEquals(writtenAt, original.lastConfirmedAt, "a back-dated effectiveFrom is not the confirmation time")

            val confirmedAt = clock.advance(Duration.ofHours(2))
            val confirmed = putPrice(
                tariff.id, token,
                priceBody(amount = "10.0000", effectiveFrom = confirmedAt, dataSource = PriceDataSource.CRAWLER.name),
            )
            assertEquals(HttpStatusCode.OK, confirmed.status)
            assertEquals(id, json(confirmed).getValue("id").jsonPrimitive.long)
            assertEquals(confirmedAt, Instant.parse(json(confirmed).getValue("lastConfirmedAt").jsonPrimitive.content))
            assertEquals(1L, rowCount(tariff.id, BillingPeriod.MONTHLY))
            assertEquals(original.copy(lastConfirmedAt = confirmedAt), row(id), "only the stamp moved")

            suspend fun get(path: String) = client.get("$root$path") { bearerAuth(token) }.bodyAsText()
            val stamp = "\"lastConfirmedAt\":\"${isoInstant(confirmedAt)}\""
            listOf(
                "/providers/${provider.id}",
                "/tariffs/${tariff.id}",
                "/tariffs?providerId=${provider.id}",
                "/tariffs/${tariff.id}/price-history",
            ).forEach { path -> assertTrue(stamp in get(path), "$path must carry $stamp") }

            // Still after the login, so the token stays valid, but before the stored stamp.
            clock.set(confirmedAt.minus(Duration.ofHours(1)))
            assertEquals(HttpStatusCode.OK, putPrice(tariff.id, token, priceBody()).status)
            assertEquals(confirmedAt, row(id).lastConfirmedAt, "a clock that went backwards must not lower the stamp")

            clock.set(confirmedAt.plus(Duration.ofHours(1)))
            assertEquals(HttpStatusCode.OK, closePrice(tariff.id, token, closeBody()).status)
            assertEquals(confirmedAt, row(id).lastConfirmedAt, "closing is not a confirmation")

            val previous = putPrice(tariff.id, token, priceBody(billingPeriod = BillingPeriod.ANNUAL, amount = "100"))
            val previousId = json(previous).getValue("id").jsonPrimitive.long
            val previousStamp = row(previousId).lastConfirmedAt
            clock.advance(Duration.ofHours(1))
            val replacement = putPrice(tariff.id, token, priceBody(billingPeriod = BillingPeriod.ANNUAL, amount = "110"))
            clock.advance(Duration.ofHours(1))
            assertEquals(HttpStatusCode.NoContent, correctPrice(tariff.id, json(replacement).getValue("id").jsonPrimitive.long, token).status)
            assertNull(row(previousId).effectiveTo)
            assertEquals(previousStamp, row(previousId).lastConfirmedAt, "a reopened row keeps its own stamp")
        }
    }
}
