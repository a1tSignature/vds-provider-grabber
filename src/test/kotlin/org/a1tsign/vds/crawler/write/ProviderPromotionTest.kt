package org.a1tsign.vds.crawler.write

import kotlinx.coroutines.runBlocking
import org.a1tsign.vds.crawler.model.AdapterRunStatus
import org.a1tsign.vds.crawler.model.FailureReason
import org.a1tsign.vds.crawler.model.ProviderSnapshot
import org.a1tsign.vds.crawler.support.CrawlerAppHarness
import org.a1tsign.vds.crawler.support.FIXTURE_PROVIDER_SLUG
import org.a1tsign.vds.crawler.support.fixtureAdapter
import org.a1tsign.vds.crawler.support.validSnapshot
import org.a1tsign.vds.crawler.support.validTariff
import org.a1tsign.vds.crawler.support.withoutLocationLinks
import org.a1tsign.vds.db.ProviderListFilter
import org.a1tsign.vds.db.ProviderRepository
import org.a1tsign.vds.db.TariffPriceTable
import org.a1tsign.vds.db.toTariffPrice
import org.a1tsign.vds.domain.ProviderStatus
import org.a1tsign.vds.domain.SystemStatus
import org.a1tsign.vds.support.AbstractDbTest
import org.a1tsign.vds.support.PostgresSupport
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Runs against the real application ([CrawlerAppHarness]); "no promotion happened" is proved by the
 * server's call log.
 *
 * The first test also covers the `409` trap: the listing hides `CANDIDATE` rows, so a
 * `findProviderBySlug` that did not ask for them would `POST`, hit `uq_provider_slug`, re-read, miss
 * again and fail with `FailureReason.WRITE` on every run.
 */
class ProviderPromotionTest : AbstractDbTest() {

    @Test
    fun `a candidate the run wrote to is promoted exactly once, and a run that writes nothing promotes nothing`() {
        withCatalog { ctx ->
            val providerId = seedProvider(SystemStatus.CANDIDATE)

            val first = ctx.reconcile(snapshot())

            assertEquals(AdapterRunStatus.SUCCESS, first.status, first.message)
            assertFalse(ctx.harness.calls().any { it.method == "POST" && it.path == "/api/v1/providers" }, "the candidate row was found, not re-created")
            assertEquals(1, first.locationsCreated)
            assertEquals(2, first.tariffsCreated)
            assertEquals(listOf(providerId), providerIds(SystemStatus.entries), "exactly one provider row exists")
            assertEquals(SystemStatus.CONFIGURED, systemStatusOf(providerId))
            assertEquals(1, ctx.systemStatusCalls(providerId), "exactly one promotion call")
            assertTrue(providerId in providerIds(listOf(SystemStatus.CONFIGURED)), "promoted rows are catalog content")

            // An already-CONFIGURED row gets no call at all, however much the run writes.
            clock.advance(Duration.ofHours(1))
            ctx.harness.clearRecordedCalls()
            ctx.reconcile(snapshot(amount = BigDecimal("11.1100")))
            assertEquals(0, ctx.systemStatusCalls(providerId), "an already-CONFIGURED row is never written to")

            // The same snapshot again submits four unchanged prices (answered 200, only
            // lastConfirmedAt advances) and no mutation, so nothing is promoted.
            setSystemStatus(providerId, SystemStatus.CANDIDATE)
            val priceRowsBefore = priceRowCount()
            clock.advance(Duration.ofHours(1))
            ctx.harness.clearRecordedCalls()

            val third = ctx.reconcile(snapshot(amount = BigDecimal("11.1100")))

            assertEquals(
                ReconcileOutcome(status = AdapterRunStatus.SUCCESS, pricesUnchanged = 4),
                third,
                "an unchanged snapshot is not a write",
            )
            assertEquals(0, ctx.systemStatusCalls(providerId))
            assertEquals(SystemStatus.CANDIDATE, systemStatusOf(providerId), "nothing was written, so nothing is promoted")
            assertEquals(priceRowsBefore, priceRowCount(), "a confirmation appends no price row")
            assertEquals(
                List(4) { clock.instant() },
                currentPriceConfirmations(),
                "the unchanged prices were confirmed by this run, which still is not a write",
            )
        }
    }

    @Test
    fun `a rejected provider is written to but never promoted and stays out of the listing`() {
        withCatalog { ctx ->
            val providerId = seedProvider(SystemStatus.REJECTED)

            val outcome = ctx.reconcile(snapshot())

            assertEquals(AdapterRunStatus.SUCCESS, outcome.status, outcome.message)
            assertEquals(2, outcome.tariffsCreated, "a rejected row is still a catalog row the crawler may update")
            assertEquals(SystemStatus.REJECTED, systemStatusOf(providerId), "only a human may leave REJECTED")
            assertEquals(0, ctx.systemStatusCalls(providerId))
            assertFalse(providerId in providerIds(listOf(SystemStatus.CONFIGURED)))
        }
    }

    @Test
    fun `a dry run records the promotion and performs none`() {
        withCatalog { ctx ->
            val providerId = seedProvider(SystemStatus.CANDIDATE)
            val writer = DryRunWriter()

            val outcome = ctx.reconcile(snapshot(), writer = writer)

            assertEquals(AdapterRunStatus.SUCCESS, outcome.status, outcome.message)
            assertTrue(
                outcome.plannedOperations.any { it.startsWith("PROMOTE_PROVIDER provider=$FIXTURE_PROVIDER_SLUG") },
                "the plan must tell the operator the promotion would happen: ${outcome.plannedOperations}",
            )
            assertEquals(0, ctx.systemStatusCalls(providerId), "a dry run promotes nothing")
            assertEquals(SystemStatus.CANDIDATE, systemStatusOf(providerId))
        }
    }

    private class Ctx(val harness: CrawlerAppHarness, val client: CatalogApiClient, private val reconciler: Reconciler) {
        private val apiWriter = ApiCrawlWriter(client)

        suspend fun reconcile(snapshot: ProviderSnapshot, writer: CrawlWriter = apiWriter): ReconcileOutcome =
            reconciler.reconcile(fixtureAdapter(snapshot.provider.slug), snapshot, writer, BigDecimal("0.5"))

        fun systemStatusCalls(providerId: Long): Int =
            harness.countCalls("PUT", "/api/v1/providers/$providerId/system-status")
    }

    private fun withCatalog(block: suspend (Ctx) -> Unit) = CrawlerAppHarness(clock).use { harness ->
        CatalogApiClient(
            config = harness.crawlerConfig(),
            clock = clock,
            limits = CatalogClientLimits(connectTimeoutMs = 1_000, requestTimeoutMs = 5_000, socketTimeoutMs = 5_000),
            backoffJitter = { 0L },
            sleep = { },
        ).use { client ->
            runBlocking { block(Ctx(harness, client, Reconciler(client))) }
        }
    }

    /** A discovered candidate as the discovery leg will create it: `UNKNOWN` + the given system status. */
    private fun seedProvider(systemStatus: SystemStatus): Long = transaction(PostgresSupport.database) {
        ProviderRepository().insert(
            slug = FIXTURE_PROVIDER_SLUG,
            name = FIXTURE_PROVIDER_SLUG,
            websiteUrl = "https://$FIXTURE_PROVIDER_SLUG.example",
            description = null,
            status = ProviderStatus.UNKNOWN,
            now = clock.instant(),
            systemStatus = systemStatus,
        ).id
    }

    private fun setSystemStatus(providerId: Long, systemStatus: SystemStatus) = transaction(PostgresSupport.database) {
        ProviderRepository().updateSystemStatus(providerId, systemStatus, clock.instant())
    }

    private fun systemStatusOf(providerId: Long): SystemStatus = transaction(PostgresSupport.database) {
        ProviderRepository().find(providerId)!!.systemStatus
    }

    private fun priceRowCount(): Long = transaction(PostgresSupport.database) { TariffPriceTable.selectAll().count() }

    private fun currentPriceConfirmations(): List<Instant> = transaction(PostgresSupport.database) {
        TariffPriceTable.selectAll().where { TariffPriceTable.effectiveTo.isNull() }.map { it.toTariffPrice().lastConfirmedAt }
    }

    private fun providerIds(statuses: List<SystemStatus>): List<Long> = transaction(PostgresSupport.database) {
        ProviderRepository().filteredIds(ProviderListFilter(systemStatuses = statuses)).sorted()
    }

    private companion object {
        /**
         * No location links: the link set is otherwise replaced unconditionally, so a second pass
         * could never write nothing.
         */
        fun snapshot(amount: BigDecimal = BigDecimal("9.9900")): ProviderSnapshot = validSnapshot()
            .copy(
                tariffs = listOf(
                    validTariff("VPS 1", "vps-1", amount).copy(prices = usd(amount)),
                    validTariff("VPS 2", "vps-2", amount).copy(prices = usd(amount)),
                ),
                locations = validSnapshot().locations!!.take(1),
            )
            .withoutLocationLinks()

        /** USD, so `NO_FX_RATE` (no rate is ingested here) cannot mask a counter assertion. */
        private fun usd(amount: BigDecimal) = validTariff("x", amount = amount).prices.map { it.copy(currency = "USD") }
    }
}
