package org.a1tsign.vds.api

import io.ktor.client.request.bearerAuth
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.a1tsign.vds.db.toTariffPrice
import org.a1tsign.vds.domain.BillingPeriod
import org.a1tsign.vds.support.AbstractDbTest
import org.a1tsign.vds.support.CatalogFixture
import org.a1tsign.vds.support.PostgresSupport
import org.a1tsign.vds.support.loginForToken
import org.a1tsign.vds.support.testAppDb
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.math.BigDecimal
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Mandatory concurrency case (task's "How to verify" item 11): 10 threads, released together by a
 * `CountDownLatch`, write different amounts for the same `(tariff, MONTHLY)`. Every response must
 * be `201` or `409` -- never `500` -- and the resulting history must be a gap-free, overlap-free
 * chain with exactly one open row. Run once with no prior price, once with an existing current row.
 */
class PriceConcurrencyTest : AbstractDbTest() {
    private val root = "/api/v1"
    private val threadCount = 10

    private fun priceBody(amount: String, billingPeriod: BillingPeriod = BillingPeriod.MONTHLY): String =
        buildJsonObject {
            put("billingPeriod", billingPeriod.name)
            put("amount", amount)
            put("currency", "USD")
        }.toString()

    @Test
    fun concurrentWritesWithNoPriorPriceFormAGapFreeChain() = runBlocking {
        testAppDb(clock) {
            val token = loginForToken("bootstrap-admin", "bootstrap-password-1")
            val fixture = CatalogFixture(PostgresSupport.database)
            val provider = fixture.createProvider("price-race-fresh", clock.instant())
            val tariff = fixture.createTariff(provider.id, "Basic", clock.instant())
            runConcurrentWrites(tariff.id, token)
            assertChainIsGapFreeAndSingleCurrent(tariff.id)
        }
    }

    @Test
    fun concurrentWritesAgainstAnExistingCurrentPriceFormAGapFreeChain() = runBlocking {
        testAppDb(clock) {
            val token = loginForToken("bootstrap-admin", "bootstrap-password-1")
            val fixture = CatalogFixture(PostgresSupport.database)
            val provider = fixture.createProvider("price-race-existing", clock.instant())
            val tariff = fixture.createTariff(provider.id, "Basic", clock.instant())
            fixture.createTariffPrice(tariff.id, BigDecimal("1"), "USD", BillingPeriod.MONTHLY, clock.instant())
            runConcurrentWrites(tariff.id, token)
            assertChainIsGapFreeAndSingleCurrent(tariff.id)
        }
    }

    private suspend fun io.ktor.server.testing.ApplicationTestBuilder.runConcurrentWrites(tariffId: Long, token: String) {
        val ready = CountDownLatch(threadCount)
        val start = CountDownLatch(1)
        val statuses = java.util.concurrent.ConcurrentLinkedQueue<Int>()
        val serverErrors = AtomicInteger(0)
        val threads = (1..threadCount).map { i ->
            Thread {
                ready.countDown()
                start.await()
                try {
                    val response = runBlocking {
                        client.put("$root/tariffs/$tariffId/price") {
                            bearerAuth(token)
                            contentType(ContentType.Application.Json)
                            setBody(priceBody(amount = (100 + i).toString()))
                        }
                    }
                    statuses.add(response.status.value)
                } catch (e: Exception) {
                    serverErrors.incrementAndGet()
                }
            }.also { it.start() }
        }
        ready.await()
        start.countDown()
        threads.forEach { it.join() }

        assertEquals(0, serverErrors.get(), "no request should throw/except on the client side")
        assertEquals(threadCount, statuses.size)
        assertTrue(statuses.all { it == 201 || it == 409 }, "every response must be 201 or 409, never 500: $statuses")
    }

    private fun assertChainIsGapFreeAndSingleCurrent(tariffId: Long) {
        val rows = transaction(PostgresSupport.database) {
            org.a1tsign.vds.db.TariffPriceTable.selectAll()
                .where {
                    (org.a1tsign.vds.db.TariffPriceTable.tariffId eq tariffId) and
                        (org.a1tsign.vds.db.TariffPriceTable.billingPeriod eq BillingPeriod.MONTHLY.name)
                }
                .map { it.toTariffPrice() }
                .sortedBy { it.effectiveFrom }
        }
        assertEquals(1, rows.count { it.effectiveTo == null }, "exactly one current row")
        rows.dropLast(1).forEachIndexed { index, row ->
            assertEquals(rows[index + 1].effectiveFrom, row.effectiveTo, "row $index must close exactly where row ${index + 1} starts")
        }
        assertEquals(null, rows.last().effectiveTo, "only the last row may be open")
    }
}
