package org.a1tsign.vds.fx

import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.http.HttpStatusCode
import io.ktor.http.HttpHeaders
import io.ktor.http.URLBuilder
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.a1tsign.vds.auth.PasswordHasher
import org.a1tsign.vds.db.CurrencyRateRepository
import org.a1tsign.vds.db.CurrencyRateTable
import org.a1tsign.vds.domain.Role
import org.a1tsign.vds.fx.FetchedRates
import org.a1tsign.vds.config.Secret
import org.a1tsign.vds.support.AbstractDbTest
import org.a1tsign.vds.support.PostgresSupport
import org.a1tsign.vds.support.StubCurrencyRateProvider
import org.a1tsign.vds.support.TEST_NOW
import org.a1tsign.vds.support.capturingLogs
import org.a1tsign.vds.support.UserFixture
import org.a1tsign.vds.support.loginForToken
import org.a1tsign.vds.support.testAppConfig
import org.a1tsign.vds.support.testAppDb
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.time.Instant
import java.util.concurrent.TimeUnit
import org.slf4j.event.Level
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class FxTest : AbstractDbTest() {
    private val repository = CurrencyRateRepository()
    private val sourceTime = Instant.parse("2026-10-28T12:00:00Z")

    private fun fetched(rates: Map<String, String>, at: Instant = sourceTime) = FetchedRates(
        "stub", at, rates.mapValues { BigDecimal(it.value) },
    )

    private fun refreshService(provider: StubCurrencyRateProvider): Pair<FxRefreshService, CurrencyConverter> {
        val converter = CurrencyConverter(repository)
        return FxRefreshService(provider, PostgresSupport.database, repository, converter, clock) to converter
    }

    @Test
    fun inverseRateAndConversionUseExactDecimals() = runBlocking {
        val provider = StubCurrencyRateProvider(Result.success(fetched(mapOf("EUR" to "0.5"))))
        val (service, converter) = refreshService(provider)
        assertEquals(1, service.refresh())
        assertEquals("2.000000000000", transaction(PostgresSupport.database) { repository.findLatest("EUR")!!.rateToUsd.toPlainString() })
        assertEquals("20.0000", transaction(PostgresSupport.database) { converter.convertToUsd(BigDecimal("10"), "EUR", sourceTime).amountUsd!!.toPlainString() })
    }

    @Test
    fun inversionUsesTwentySignificantDigitsThenTwelvePlaces() = runBlocking {
        val provider = StubCurrencyRateProvider(Result.success(fetched(mapOf("EUR" to "0.92"))))
        val (service) = refreshService(provider)
        service.refresh()
        val expected = BigDecimal.ONE.divide(BigDecimal("0.92"), MathContext(20)).setScale(12, RoundingMode.HALF_UP)
        assertEquals(expected.toPlainString(), transaction(PostgresSupport.database) { repository.findLatest("EUR")!!.rateToUsd.toPlainString() })
    }

    @Test
    fun duplicateSourcePeriodDoesNotInsertAndNewPeriodClearsConverterCache() = runBlocking {
        val provider = StubCurrencyRateProvider(Result.success(fetched(mapOf("EUR" to "0.5"))))
        val (service, converter) = refreshService(provider)
        assertEquals(1, service.refresh())
        assertEquals("2.000000000000", transaction(PostgresSupport.database) { converter.convertToUsd(BigDecimal.TEN, "EUR", sourceTime).usdRate!!.toPlainString() })
        assertEquals(0, service.refresh())
        provider.next = Result.success(fetched(mapOf("EUR" to "0.25"), sourceTime.plusSeconds(3600)))
        assertEquals(1, service.refresh())
        assertEquals("4.000000000000", transaction(PostgresSupport.database) { converter.convertToUsd(BigDecimal.TEN, "EUR", sourceTime).usdRate!!.toPlainString() })
    }

    @Test
    fun failedRefreshLeavesExistingRatesReadableAndReturnsFailure() {
        val provider = StubCurrencyRateProvider(Result.success(fetched(mapOf("EUR" to "0.5"))))
        val (service, converter) = refreshService(provider)
        val logs = capturingLogs {
            runBlocking {
                service.refresh()
                provider.next = Result.failure(IllegalStateException("upstream result was not success"))
                assertNull(service.refresh())
            }
        }
        assertEquals(1, logs.messagesAt(Level.WARN).count { it.contains("FX rate refresh failed") })
        assertEquals("2.000000000000", transaction(PostgresSupport.database) { converter.convertToUsd(BigDecimal.ONE, "EUR", sourceTime).usdRate!!.toPlainString() })
    }

    @Test
    fun parserSkipsInvalidRatesAndNeverStoresUsd() {
        val config = testAppConfig()
        val provider = ErApiCurrencyRateProvider(config)
        try {
            val parsed = provider.parseResponse(
                """{"result":"success","time_last_update_unix":1793188800,"rates":{"EUR":0.5,"USD":1,"ZERO":0,"NEG":-1,"BAD":"nope","MISSING":null}}""",
            )
            assertEquals(setOf("EUR"), parsed.rates.keys)
            assertEquals("0.5", parsed.rates.getValue("EUR").toPlainString())
        } finally {
            provider.client.close()
        }
    }

    @Test
    fun configuredApiKeyNeverEntersUrlAndUsesBearerOnlyForKeyedHost() {
        val base = testAppConfig()
        val key = "secret-fx-key"
        val cases = listOf(
            base.fx.apiUrl to false,
            "https://v6.exchangerate-api.com/v6/latest/USD" to true,
        )
        cases.forEach { (apiUrl, bearerExpected) ->
            val config = base.copy(fx = base.fx.copy(apiUrl = apiUrl, apiKey = Secret(key)))
            val provider = ErApiCurrencyRateProvider(config)
            try {
                val request = HttpRequestBuilder().apply {
                    url.host = URLBuilder(apiUrl).host
                    provider.configureRequest(this)
                }
                assertEquals(false, request.url.buildString().contains(key))
                assertEquals(
                    if (bearerExpected) "Bearer $key" else null,
                    request.headers[HttpHeaders.Authorization],
                )
            } finally {
                provider.client.close()
            }
        }
    }

    @Test
    fun malformedUpstreamCurrencyCodeIsSkippedWithoutFailingTheWholeSnapshot() {
        val provider = StubCurrencyRateProvider(Result.success(fetched(mapOf("EUR" to "0.5", "12X" to "0.3"))))
        val (service, _) = refreshService(provider)
        val logs = capturingLogs { assertEquals(1, runBlocking { service.refresh() }) }
        assertEquals(1, logs.messagesAt(Level.WARN).count { it.contains("malformed FX currency code") })
        assertNotNull(transaction(PostgresSupport.database) { repository.findLatest("EUR") })
        assertNull(transaction(PostgresSupport.database) { repository.findLatest("12X") })
    }

    @Test
    fun schedulerRunsFirstRefreshWithinSecondsNotHours() {
        val provider = StubCurrencyRateProvider(Result.success(fetched(mapOf("EUR" to "0.5"))))
        val (service, _) = refreshService(provider)
        val scheduler = FxRefreshScheduler(service, refreshHours = 1)
        try {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            var stored = transaction(PostgresSupport.database) { repository.findLatest("EUR") }
            while (stored == null && System.nanoTime() < deadline) {
                Thread.sleep(50)
                stored = transaction(PostgresSupport.database) { repository.findLatest("EUR") }
            }
            assertNotNull(stored, "the scheduler's first refresh should run within a few seconds, not after a 100-hour delay")
        } finally {
            scheduler.close()
        }
    }

    @Test
    fun usdConversionNeedsNoStoredRateAndUnknownCurrencyReturnsNulls() {
        val converter = CurrencyConverter(repository)
        assertEquals(0L, transaction(PostgresSupport.database) { CurrencyRateTable.selectAll().count() })
        val usd = converter.convertToUsd(BigDecimal("1.23456"), "USD", sourceTime)
        assertEquals("1.2346", usd.amountUsd!!.toPlainString())
        assertEquals(BigDecimal.ONE, usd.usdRate)
        assertEquals(sourceTime, usd.usdRateAt)
        assertEquals(ConversionResult(null, null, null), transaction(PostgresSupport.database) { converter.convertToUsd(BigDecimal.ONE, "ZZZ", sourceTime) })
    }

    @Test
    fun latestRateSortsBySourceTimestampThenFetchTimestamp() = transaction(PostgresSupport.database) {
        repository.insertRates(mapOf("EUR" to BigDecimal("1.1")), "s", sourceTime, sourceTime)
        repository.insertRates(mapOf("EUR" to BigDecimal("1.2")), "s", sourceTime.plusSeconds(1), sourceTime)
        repository.insertRates(mapOf("EUR" to BigDecimal("1.3")), "t", sourceTime.plusSeconds(1), sourceTime.plusSeconds(1))
        assertEquals("1.300000000000", repository.findLatest("EUR")!!.rateToUsd.toPlainString())
    }

    @Test
    fun editorCanRefreshViewerCannotAndFailuresUseStandard502Envelope() = runBlocking {
        val provider = StubCurrencyRateProvider(Result.success(fetched(mapOf("EUR" to "0.5"))))
        testAppDb(clock, currencyRateProvider = provider) {
            val editorFixture = UserFixture(PostgresSupport.database, org.a1tsign.vds.db.UserRepository(), PasswordHasher())
            val editorToken = loginAsFixture(editorFixture, "fx-editor", Role.EDITOR)
            val viewerToken = loginAsFixture(editorFixture, "fx-viewer", Role.VIEWER)
            val success = client.post("/api/v1/admin/fx/refresh") { bearerAuth(editorToken) }
            assertEquals(HttpStatusCode.OK, success.status)
            assertEquals(1, Json.parseToJsonElement(success.bodyAsText()).jsonObject.getValue("inserted").jsonPrimitive.content.toInt())
            assertEquals(HttpStatusCode.Forbidden, client.post("/api/v1/admin/fx/refresh") { bearerAuth(viewerToken) }.status)
            assertEquals(HttpStatusCode.Unauthorized, client.post("/api/v1/admin/fx/refresh").status)
            provider.next = Result.failure(IllegalStateException("offline"))
            val failure = client.post("/api/v1/admin/fx/refresh") { bearerAuth(editorToken) }
            assertEquals(HttpStatusCode.BadGateway, failure.status)
            val body = Json.parseToJsonElement(failure.bodyAsText()).jsonObject
            assertEquals(setOf("timestamp", "status", "error", "message", "path"), body.keys)
            assertEquals("502", body.getValue("status").jsonPrimitive.content)
        }
    }

    @Test
    fun moreThanDoublePrecisionSurvivesParsingAndDatabaseStorage() = runBlocking {
        val raw = "0.123456789012345678901234567890123456789"
        val provider = ErApiCurrencyRateProvider(testAppConfig())
        val rates = try {
            provider.parseResponse("""{"result":"success","time_last_update_unix":1793188800,"rates":{"EUR":$raw}}""").rates
        } finally { provider.client.close() }
        assertEquals(raw, rates.getValue("EUR").toPlainString())
        val expected = BigDecimal.ONE.divide(BigDecimal(raw), MathContext(20)).setScale(12, RoundingMode.HALF_UP)
        val providerStub = StubCurrencyRateProvider(Result.success(FetchedRates("precision", sourceTime, rates)))
        val (service) = refreshService(providerStub)
        runBlocking { service.refresh() }
        transaction(PostgresSupport.database) {
            assertEquals(expected.toPlainString(), repository.findLatest("EUR")!!.rateToUsd.toPlainString())
        }
    }

    private suspend fun io.ktor.server.testing.ApplicationTestBuilder.loginAsFixture(
        fixture: UserFixture,
        username: String,
        role: Role,
    ): String {
        fixture.create(username, "password-123456", setOf(role), at = TEST_NOW)
        return loginForToken(username, "password-123456")
    }
}
