package org.a1tsign.vds.crawler.write

import kotlinx.coroutines.runBlocking
import org.a1tsign.vds.crawler.model.AdapterRunStatus
import org.a1tsign.vds.crawler.model.FailureReason
import org.a1tsign.vds.crawler.model.LocationKey
import org.a1tsign.vds.crawler.model.LocationSnapshot
import org.a1tsign.vds.crawler.model.PriceSnapshot
import org.a1tsign.vds.crawler.model.ProviderSnapshot
import org.a1tsign.vds.crawler.model.TariffSnapshot
import org.a1tsign.vds.crawler.support.CrawlerAppHarness
import org.a1tsign.vds.crawler.support.FIXTURE_PROVIDER_SLUG
import org.a1tsign.vds.crawler.support.RecordingCrawlWriter
import org.a1tsign.vds.crawler.support.fixtureAdapter
import org.a1tsign.vds.crawler.support.validSnapshot
import org.a1tsign.vds.crawler.support.validTariff
import org.a1tsign.vds.db.PriceRepository
import org.a1tsign.vds.db.ProviderListFilter
import org.a1tsign.vds.db.ProviderRepository
import org.a1tsign.vds.db.TariffRepository
import org.a1tsign.vds.domain.BillingPeriod
import org.a1tsign.vds.domain.Confidence
import org.a1tsign.vds.domain.LegalDataSource
import org.a1tsign.vds.domain.PaymentMethod
import org.a1tsign.vds.domain.PriceDataSource
import org.a1tsign.vds.support.AbstractDbTest
import org.a1tsign.vds.support.CatalogFixture
import org.a1tsign.vds.support.PostgresSupport
import org.a1tsign.vds.support.capturingLogs
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.slf4j.event.Level
import java.math.BigDecimal
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The reconciler against the **real** vds-001 application on a real port ([CrawlerAppHarness]) and
 * the real PostgreSQL: the data-safety rules of ADR-03 are about what the catalog looks like
 * afterwards, which a mocked write boundary cannot show.
 *
 * Where a rule is "nothing was written", the proof is either the **server's** own call log
 * (`harness.countCalls`) or a [RecordingCrawlWriter] -- never the absence of a log line. Every
 * count is scoped to the rows the test created.
 */
class ReconcilerTest : AbstractDbTest() {

    // ---------- 1. cold create ----------

    @Test
    fun `a cold create writes every observed section with CRAWLER provenance and counts it`() {
        withCatalog { ctx ->
            val outcome = ctx.reconcile(coldSnapshot())

            assertEquals(AdapterRunStatus.SUCCESS, outcome.status)
            assertEquals(1, outcome.providersCreated)
            assertEquals(2, outcome.locationsCreated)
            assertEquals(3, outcome.tariffsCreated)
            assertEquals(6, outcome.pricesAppended, "three tariffs with a MONTHLY and an ANNUAL price")
            assertEquals(0, outcome.pricesUnchanged)
            assertEquals(0, outcome.tariffsUpdated)
            assertEquals(0, outcome.tariffsMarkedOutOfStock)
            assertEquals(emptyList(), outcome.plannedOperations, "a real run plans nothing, it writes")

            val detail = ctx.client.getProvider(ctx.providerId())
            assertEquals("Acme Hosting", detail.provider.name)
            assertEquals("Acme Hosting GmbH", detail.legalInfo?.legalEntityName)
            assertEquals(LegalDataSource.CRAWLER.name, detail.legalInfo?.dataSource)
            assertEquals(Confidence.MEDIUM.name, detail.legalInfo?.confidence)
            assertEquals("https://acme.example/imprint", detail.legalInfo?.sourceUrl)
            assertEquals(
                listOf("CRYPTO_BTC", "VISA_MASTERCARD"),
                detail.paymentMethods.map { it.method }.sorted(),
            )
            assertEquals(listOf("Amsterdam", "Frankfurt"), detail.locations.mapNotNull { it.city }.sorted())
            assertEquals(listOf("VPS 1", "VPS 2", "VPS 3"), detail.tariffs.map { it.tariff.name }.sorted())

            val amsterdam = detail.locations.single { it.city == "Amsterdam" }
            detail.tariffs.forEach { withPrices ->
                assertEquals(
                    setOf(amsterdam.id),
                    transaction(PostgresSupport.database) { TariffRepository().locationIds(withPrices.tariff.id) },
                    "every tariff of the snapshot links to exactly the location it named",
                )
                val rows = priceHistory(withPrices.tariff.id)
                assertEquals(listOf(BillingPeriod.MONTHLY, BillingPeriod.ANNUAL), rows.map { it.billingPeriod }.sorted())
                assertTrue(rows.all { it.dataSource == PriceDataSource.CRAWLER }, "provenance of every price row")
                assertTrue(rows.all { it.effectiveTo == null }, "each period has exactly one open row")
            }
            val monthly = priceHistory(detail.tariffs.first().tariff.id).single { it.billingPeriod == BillingPeriod.MONTHLY }
            assertEquals("9.9900", monthly.amount.toPlainString(), "NUMERIC(12,4), exactly what the snapshot carried")
        }
    }

    // ---------- 2. idempotent re-run ----------

    @Test
    fun `the identical snapshot a second time creates nothing and appends no price row`() {
        withCatalog { ctx ->
            ctx.reconcile(coldSnapshot())
            val providerId = ctx.providerId()
            val tariffIds = ctx.client.listAllTariffs(providerId).map { it.id }
            val before = tariffIds.associateWith { priceHistory(it).size }
            // The price endpoint rejects an effectiveFrom that is not after the current row's, and
            // the test clock does not move by itself.
            clock.advance(Duration.ofHours(1))
            ctx.harness.clearRecordedCalls()

            val second = ctx.reconcile(coldSnapshot())

            assertEquals(AdapterRunStatus.SUCCESS, second.status)
            assertEquals(0, ctx.harness.countCalls("POST", "/api/v1/providers"))
            assertEquals(0, ctx.harness.countCalls("POST", "/api/v1/providers/$providerId/locations"))
            assertEquals(0, ctx.harness.countCalls("POST", "/api/v1/providers/$providerId/tariffs"))
            assertEquals(0, ctx.harness.countCalls("PUT", "/api/v1/providers/$providerId"))
            assertEquals(0, ctx.harness.countCalls("PUT", "/api/v1/providers/$providerId/legal-info"))
            assertEquals(0, ctx.harness.countCalls("PUT", "/api/v1/providers/$providerId/payment-methods"))
            assertEquals(
                ReconcileOutcome(status = AdapterRunStatus.SUCCESS, pricesUnchanged = 6),
                second,
                "the second run writes nothing but the six idempotent price submissions",
            )
            assertEquals(before, tariffIds.associateWith { priceHistory(it).size }, "no history row was appended")
            tariffIds.forEach { id ->
                assertEquals(
                    2,
                    priceHistory(id).count { it.effectiveTo == null },
                    "exactly one open row per billing period",
                )
            }
        }
    }

    // ---------- 3. a changed price ----------

    @Test
    fun `a changed price appends one row, closes the previous one at the new effectiveFrom and keeps CRAWLER`() {
        withCatalog { ctx ->
            val first = singleTariffSnapshot(BigDecimal("10.0000"))
            ctx.reconcile(first)
            val tariffId = ctx.client.listAllTariffs(ctx.providerId()).single().id
            clock.advance(Duration.ofHours(1))

            val second = ctx.reconcile(singleTariffSnapshot(BigDecimal("12.0000")))

            assertEquals(1, second.pricesAppended)
            assertEquals(0, second.pricesUnchanged)
            val rows = priceHistory(tariffId).sortedBy { it.effectiveFrom }
            assertEquals(2, rows.size)
            val closed = rows.first()
            val current = rows.last()
            assertEquals("10.0000", closed.amount.toPlainString())
            assertEquals("12.0000", current.amount.toPlainString())
            assertEquals(current.effectiveFrom, closed.effectiveTo, "the closed row ends where the new one starts")
            assertNull(current.effectiveTo)
            assertEquals(listOf(PriceDataSource.CRAWLER, PriceDataSource.CRAWLER), rows.map { it.dataSource })
        }
    }

    // ---------- 4 + 5. matching ----------

    @Test
    fun `a tariff matched by externalId is renamed and one matched by name has its externalId backfilled`() {
        val fixture = CatalogFixture(PostgresSupport.database)
        withCatalog { ctx ->
            val provider = fixture.createProvider(FIXTURE_PROVIDER_SLUG, clock.instant())
            val renamed = fixture.createTariff(provider.id, "Old", clock.instant(), externalId = "p-1")
            val backfilled = fixture.createTariff(provider.id, "VPS 1", clock.instant(), externalId = null)

            val outcome = ctx.reconcile(
                tariffsSnapshot(
                    usd(validTariff(name = "New", externalId = "p-1")),
                    usd(validTariff(name = "vps 1", externalId = "p-9")),
                ),
            )

            assertEquals(AdapterRunStatus.SUCCESS, outcome.status)
            assertEquals(0, outcome.tariffsCreated, "both tariffs matched an existing row")
            assertEquals(2, outcome.tariffsUpdated)
            val stored = ctx.client.listAllTariffs(provider.id).associateBy { it.id }
            assertEquals(2, stored.size, "no third row was created")
            assertEquals("New", stored.getValue(renamed.id).name)
            assertEquals("p-1", stored.getValue(renamed.id).externalId)
            assertEquals("p-9", stored.getValue(backfilled.id).externalId)
            assertEquals("vps 1", stored.getValue(backfilled.id).name)
        }
    }

    // ---------- 6 + 7. partial vs observed-empty sections ----------

    @Test
    fun `a null payment-method and location section is never sent and leaves both sets untouched`() {
        val fixture = CatalogFixture(PostgresSupport.database)
        withCatalog { ctx ->
            val provider = fixture.createProvider(FIXTURE_PROVIDER_SLUG, clock.instant())
            fixture.createPaymentMethods(provider.id, listOf(PaymentMethod.PAYPAL to "only PayPal"))
            val location = fixture.createLocation(provider.id, "DE", "Berlin", "DC1", clock.instant())
            val tariff = fixture.createTariff(provider.id, "VPS 1", clock.instant())
            fixture.linkTariffLocation(tariff.id, location.id, provider.id)
            val blind = validSnapshot().copy(
                paymentMethods = null,
                locations = null,
                tariffs = listOf(usd(validTariff("VPS 1").copy(locationKeys = null))),
                tariffListComplete = false,
            )

            val recorder = RecordingCrawlWriter()
            ctx.reconcile(blind, writer = recorder)
            ctx.harness.clearRecordedCalls()
            val outcome = ctx.reconcile(blind)

            assertEquals(AdapterRunStatus.SUCCESS, outcome.status)
            assertTrue(
                recorder.kinds().none { it == "PutPaymentMethods" || it == "ReplaceTariffLocations" },
                "a not-observed section must not even be attempted, but was: ${recorder.kinds()}",
            )
            assertEquals(0, ctx.harness.countCalls("PUT", "/api/v1/providers/${provider.id}/payment-methods"))
            assertEquals(0, ctx.harness.countCalls("PUT", "/api/v1/tariffs/${tariff.id}/locations"))
            assertEquals(
                listOf(PaymentMethod.PAYPAL to "only PayPal"),
                transaction(PostgresSupport.database) {
                    ProviderRepository().paymentMethods(provider.id).map { it.method to it.note }
                },
                "the human-entered payment method and its note survived",
            )
            assertEquals(
                setOf(location.id),
                transaction(PostgresSupport.database) { TariffRepository().locationIds(tariff.id) },
            )
        }
    }

    @Test
    fun `an observed empty payment-method set clears the catalog's set`() {
        val fixture = CatalogFixture(PostgresSupport.database)
        withCatalog { ctx ->
            val provider = fixture.createProvider(FIXTURE_PROVIDER_SLUG, clock.instant())
            fixture.createPaymentMethods(provider.id, listOf(PaymentMethod.PAYPAL to null))

            ctx.reconcile(validSnapshot().copy(paymentMethods = emptySet(), tariffs = null, tariffListComplete = false))

            assertEquals(
                emptyList(),
                transaction(PostgresSupport.database) { ProviderRepository().paymentMethods(provider.id) },
                "`observed and empty` is not the same as `not observed`",
            )
        }
    }

    @Test
    fun `a location the API rejects leaves the tariff that links only to it with its existing link set untouched`() {
        // M2 of 05-reviews/vds-002.5-6-combined-quality.md: the branch ADR-03 exists for -- a
        // refused location's link set is skipped WHOLE, never truncated -- had no test.
        val fixture = CatalogFixture(PostgresSupport.database)
        withCatalog { ctx ->
            val provider = fixture.createProvider(FIXTURE_PROVIDER_SLUG, clock.instant())
            val existing = fixture.createLocation(provider.id, "DE", "Berlin", "DC1", clock.instant())
            val tariff = fixture.createTariff(provider.id, "VPS 1", clock.instant())
            fixture.linkTariffLocation(tariff.id, existing.id, provider.id)
            val badKey = LocationKey(countryCode = "ZZZ", city = "Nowhere")

            val outcome = ctx.reconcile(
                validSnapshot().copy(
                    legalInfo = null,
                    paymentMethods = null,
                    locations = listOf(LocationSnapshot(key = badKey)),
                    tariffs = listOf(usd(validTariff("VPS 1").copy(locationKeys = listOf(badKey)))),
                    tariffListComplete = true,
                ),
            )

            assertEquals(AdapterRunStatus.PARTIAL, outcome.status)
            assertTrue(outcome.message!!.contains("400"), "outcome message: ${outcome.message}")
            assertEquals(0, ctx.harness.countCalls("PUT", "/api/v1/tariffs/${tariff.id}/locations"))
            assertEquals(
                setOf(existing.id),
                transaction(PostgresSupport.database) { TariffRepository().locationIds(tariff.id) },
                "a refused location must not truncate the tariff's existing link set",
            )
        }
    }

    // ---------- 8 + 9. disappearance ----------

    @Test
    fun `a complete tariff list flips exactly the missing tariffs out of stock and resurrects nothing`() {
        val fixture = CatalogFixture(PostgresSupport.database)
        withCatalog { ctx ->
            val provider = fixture.createProvider(FIXTURE_PROVIDER_SLUG, clock.instant())
            val kept = (1..3).map { fixture.createTariff(provider.id, "VPS $it", clock.instant()) }
            val gone = fixture.createTariff(provider.id, "VPS 4", clock.instant())
            val deactivated = fixture.createTariff(
                provider.id,
                "VPS 5",
                clock.instant(),
                isActive = false,
                inStock = true,
            )

            val outcome = ctx.reconcile(
                tariffsSnapshot(*kept.map { usd(validTariff(it.name)) }.toTypedArray()),
            )

            assertEquals(AdapterRunStatus.SUCCESS, outcome.status)
            assertEquals(2, outcome.tariffsMarkedOutOfStock, "the absent one and the deactivated absent one")
            val stored = ctx.client.listAllTariffs(provider.id).associateBy { it.id }
            assertEquals(5, stored.size, "nothing is ever deleted")
            kept.forEach { row ->
                assertTrue(stored.getValue(row.id).inStock, "${row.name} must stay in stock")
                assertTrue(stored.getValue(row.id).isActive, "${row.name} must stay active")
            }
            assertEquals(false, stored.getValue(gone.id).inStock)
            assertEquals(true, stored.getValue(gone.id).isActive, "out of stock is not deactivation")
            assertEquals(false, stored.getValue(deactivated.id).inStock)
            assertEquals(
                false,
                stored.getValue(deactivated.id).isActive,
                "the catalog's isActive must be echoed verbatim, or a deactivated tariff is resurrected",
            )
        }
    }

    @Test
    fun `an incomplete tariff list never flips a tariff out of stock`() {
        val fixture = CatalogFixture(PostgresSupport.database)
        withCatalog { ctx ->
            val provider = fixture.createProvider(FIXTURE_PROVIDER_SLUG, clock.instant())
            val all = (1..4).map { fixture.createTariff(provider.id, "VPS $it", clock.instant()) }
            val recorder = RecordingCrawlWriter()

            val outcome = ctx.reconcile(
                tariffsSnapshot(usd(validTariff("VPS 1"))).copy(tariffListComplete = false),
                writer = recorder,
            )

            assertEquals(0, outcome.tariffsMarkedOutOfStock)
            assertTrue(
                recorder.mutations().none {
                    it is RecordingCrawlWriter.Mutation.UpdateTariff && it.tariffId != all.first().id
                },
                "only the observed tariff may be written at all: ${recorder.mutations()}",
            )
            assertTrue(
                ctx.client.listAllTariffs(provider.id).all { it.inStock },
                "an adapter that cannot claim completeness must not flip anything",
            )
        }
    }

    // ---------- 10. the mass-change guard ----------

    @Test
    fun `the mass-change guard refuses a wipe with zero writes, and yields below the threshold or at ratio one`() {
        val fixture = CatalogFixture(PostgresSupport.database)
        val logs = capturingLogs {
            withCatalog { ctx ->
                val wiped = fixture.createProvider("acme", clock.instant())
                (1..4).forEach { fixture.createTariff(wiped.id, "VPS $it", clock.instant()) }
                val small = fixture.createProvider("beta", clock.instant())
                (1..2).forEach { fixture.createTariff(small.id, "VPS $it", clock.instant()) }
                val overridden = fixture.createProvider("gamma", clock.instant())
                (1..4).forEach { fixture.createTariff(overridden.id, "VPS $it", clock.instant()) }

                val recorder = RecordingCrawlWriter()
                val guarded = ctx.reconcile(emptyCompleteSnapshot("acme"), writer = recorder)

                assertEquals(AdapterRunStatus.FAILED, guarded.status)
                assertEquals(FailureReason.MASS_CHANGE_GUARD, guarded.failureReason)
                assertEquals(emptyList(), recorder.mutations(), "the guard must perform zero writes")
                assertTrue(
                    ctx.client.listAllTariffs(wiped.id).all { it.inStock && it.isActive },
                    "the catalog must be untouched",
                )

                val belowThreshold = ctx.reconcile(emptyCompleteSnapshot("beta"))
                assertEquals(AdapterRunStatus.SUCCESS, belowThreshold.status)
                assertEquals(2, belowThreshold.tariffsMarkedOutOfStock, "under three in-stock tariffs the guard is off")
                assertTrue(ctx.client.listAllTariffs(small.id).none { it.inStock })

                val raisedRatio = ctx.reconcile(emptyCompleteSnapshot("gamma"), ratio = BigDecimal("1.0"))
                assertEquals(AdapterRunStatus.SUCCESS, raisedRatio.status)
                assertEquals(4, raisedRatio.tariffsMarkedOutOfStock, "ratio 1.0 is the operator's override")
                assertTrue(ctx.client.listAllTariffs(overridden.id).none { it.inStock })
            }
        }

        val warning = logs.messagesAt(Level.WARN).single { it.contains("Mass-change guard") }
        assertTrue(warning.contains(" 4 of 4 "), "both counts must be named: $warning")
        assertTrue(warning.contains("0.5"), "the ratio in force must be named: $warning")
    }

    @Test
    fun `the mass-change guard also counts tariffs the snapshot reports present but out of stock`() {
        // I1 of 05-reviews/vds-002.5-6-combined-quality.md: a complete snapshot that *lists* every
        // tariff with inStock = false is the same disappearance as one that omits them, but used to
        // bypass the guard entirely because checkMassChange only counted TariffPlan.missing.
        val fixture = CatalogFixture(PostgresSupport.database)
        withCatalog { ctx ->
            val provider = fixture.createProvider(FIXTURE_PROVIDER_SLUG, clock.instant())
            val tariffs = (1..4).map { fixture.createTariff(provider.id, "VPS $it", clock.instant()) }
            val wipeAttempt = tariffsSnapshot(*tariffs.map { usd(validTariff(it.name).copy(inStock = false)) }.toTypedArray())
            val recorder = RecordingCrawlWriter()

            val guarded = ctx.reconcile(wipeAttempt, writer = recorder)

            assertEquals(AdapterRunStatus.FAILED, guarded.status)
            assertEquals(FailureReason.MASS_CHANGE_GUARD, guarded.failureReason)
            assertEquals(emptyList(), recorder.mutations(), "the guard must perform zero writes")
            assertTrue(
                ctx.client.listAllTariffs(provider.id).all { it.inStock },
                "the catalog must be untouched",
            )

            val overridden = ctx.reconcile(wipeAttempt, ratio = BigDecimal("1.0"))
            assertEquals(AdapterRunStatus.SUCCESS, overridden.status)
            assertEquals(
                4,
                overridden.tariffsMarkedOutOfStock,
                "an observed inStock = false counts the same as absence",
            )
            assertTrue(ctx.client.listAllTariffs(provider.id).none { it.inStock })
        }
    }

    // ---------- 11. the concurrent-create race ----------

    @Test
    fun `a provider created by another writer between the read and the create is absorbed`() {
        withCatalog { ctx ->
            // The race, at the real boundary: the create this writer delegates is preceded by the
            // other writer's successful create, so the API really answers 409.
            val racing = object : CrawlWriter by ctx.writer {
                override suspend fun createProvider(request: ProviderWrite): Long {
                    ctx.client.createProvider(request)
                    return ctx.writer.createProvider(request)
                }
            }

            val outcome = ctx.reconcile(coldSnapshot(), writer = racing)

            assertEquals(AdapterRunStatus.SUCCESS, outcome.status, "a lost race is not a failure")
            assertEquals(0, outcome.providersCreated, "the existing row was reused, not created")
            assertEquals(3, outcome.tariffsCreated, "the rest of the snapshot was applied to it")
            assertNotNull(ctx.client.findProviderBySlug(FIXTURE_PROVIDER_SLUG))
            assertEquals(
                1L,
                transaction(PostgresSupport.database) { ProviderRepository().countFiltered(ProviderListFilter()) },
                "exactly one provider row exists",
            )
        }
    }

    @Test
    fun `two snapshot tariffs that collide on a row created concurrently are not both written into it`() {
        // M3 of 05-reviews/vds-002.5-6-combined-quality.md: createTariff's 409 re-read matched
        // against a fresh catalog list without consulting the ids this run already claimed, so a
        // row that did not exist at planning time but collides with two snapshot tariffs could be
        // written twice. Both snapshot tariffs share one externalId a concurrent writer's row also
        // carries, so they are both unmatched at plan time and both 409 against the same row.
        withCatalog { ctx ->
            var primed = false
            val racing = object : CrawlWriter by ctx.writer {
                override suspend fun createTariff(providerId: Long, request: TariffWrite): Long {
                    if (!primed) {
                        primed = true
                        ctx.client.createTariff(
                            providerId,
                            TariffWrite(
                                name = "Concurrent", vcpuCount = 1, ramMb = 512, diskGb = 10,
                                diskType = "SSD", ipv4Count = 1, virtualization = "KVM",
                                isActive = true, inStock = true, externalId = "shared-ext",
                            ),
                        )
                    }
                    return ctx.writer.createTariff(providerId, request)
                }
            }

            val outcome = ctx.reconcile(
                tariffsSnapshot(
                    usd(validTariff(name = "Alpha", externalId = "shared-ext")),
                    usd(validTariff(name = "Beta", externalId = "shared-ext")),
                ),
                writer = racing,
            )

            assertEquals(AdapterRunStatus.PARTIAL, outcome.status, "the collision degrades, it does not fail the adapter")
            val stored = ctx.client.listAllTariffs(ctx.providerId())
            assertEquals(1, stored.size, "the concurrently created row must not receive two different tariffs' data")
            assertEquals("shared-ext", stored.single().externalId)
            assertEquals(
                "Alpha",
                stored.single().name,
                "the first tariff's write must not be silently overwritten by the collision",
            )
        }
    }

    @Test
    fun `a snapshot tariff whose match is already claimed by an earlier tariff of the same snapshot is skipped, not duplicated`() {
        val fixture = CatalogFixture(PostgresSupport.database)
        withCatalog { ctx ->
            val provider = fixture.createProvider(FIXTURE_PROVIDER_SLUG, clock.instant())
            fixture.createTariff(provider.id, "VPS 1", clock.instant(), externalId = "p-1")

            val outcome = ctx.reconcile(
                tariffsSnapshot(
                    usd(validTariff(name = "New Name", externalId = "p-1")),
                    usd(validTariff(name = "vps 1", externalId = null)),
                ),
            )

            assertEquals(AdapterRunStatus.PARTIAL, outcome.status)
            val stored = ctx.client.listAllTariffs(provider.id)
            assertEquals(1, stored.size, "the collision must not create a second row")
            assertEquals("New Name", stored.single().name, "the first tariff's rename is not overwritten by the collision")
        }
    }

    // ---------- 12. a 4xx degrades to PARTIAL ----------

    @Test
    fun `a tariff the API rejects makes the adapter PARTIAL while the other tariffs are written`() {
        val logs = capturingLogs {
            withCatalog { ctx ->
                val rejected = usd(validTariff("VPS 2", "vps-2")).copy(vcpuCount = 0)
                val snapshot = coldSnapshot().copy(
                    tariffs = listOf(usd(validTariff("VPS 1", "vps-1")), rejected, usd(validTariff("VPS 3", "vps-3"))),
                )

                val outcome = ctx.reconcile(snapshot)

                assertEquals(AdapterRunStatus.PARTIAL, outcome.status)
                assertEquals(FailureReason.WRITE, outcome.failureReason)
                assertEquals(2, outcome.tariffsCreated)
                assertEquals(4, outcome.pricesAppended, "the two accepted tariffs keep their prices")
                assertTrue(outcome.message!!.contains("400"), "outcome message: ${outcome.message}")
                assertEquals(
                    listOf("VPS 1", "VPS 3"),
                    ctx.client.listAllTariffs(ctx.providerId()).map { it.name }.sorted(),
                )
            }
        }

        val warnings = logs.messagesAt(Level.WARN)
        val rejections = warnings.filter { it.contains("-> 400") }
        assertTrue(
            rejections.any { it.contains("Request validation failed") && it.contains("vcpuCount") },
            "a WARN must carry the status and the envelope's message/details: $rejections",
        )
        val captured = warnings.joinToString("\n")
        assertTrue(!captured.contains("\"diskType\""), "the request body must not be logged: $captured")
        assertTrue(!captured.contains("ipv6Supported"), "the request body must not be logged: $captured")
    }

    @Test
    fun `a provider the API refuses leaves the adapter PARTIAL with nothing written`() {
        withCatalog { ctx ->
            val refused = coldSnapshot().copy(
                provider = coldSnapshot().provider.copy(slug = "beta", name = "n".repeat(300)),
            )

            val outcome = ctx.reconcile(refused)

            assertEquals(AdapterRunStatus.PARTIAL, outcome.status)
            assertEquals(FailureReason.WRITE, outcome.failureReason)
            assertEquals(0, outcome.providersCreated)
            assertEquals(0, outcome.tariffsCreated, "nothing can follow a provider that does not exist")
            assertNull(ctx.client.findProviderBySlug("beta"))
        }
    }

    @Test
    fun `a 5xx that outlasts the retry budget ends the adapter as FAILED instead of throwing`() {
        withCatalog { ctx ->
            ctx.reconcile(coldSnapshot())
            clock.advance(Duration.ofHours(1))
            ctx.harness.failNextCatalogCalls(times = Int.MAX_VALUE)

            val outcome = ctx.reconcile(coldSnapshot())

            ctx.harness.clearForcedFailures()
            assertEquals(AdapterRunStatus.FAILED, outcome.status, "a dead catalog is this adapter's failure, not an exception")
            assertEquals(FailureReason.WRITE, outcome.failureReason)
            assertTrue(outcome.message!!.contains("503"), "outcome message: ${outcome.message}")
            assertEquals(
                3,
                ctx.client.listAllTariffs(ctx.providerId()).size,
                "the first run's writes are untouched by the second run's failure",
            )
        }
    }

    // ---------- 13. dry run ----------

    @Test
    fun `a dry run touches nothing yet reports exactly the counters the real run then produces`() {
        withCatalog { ctx ->
            val snapshot = coldSnapshot()
            val planner = DryRunWriter()

            val dry = ctx.reconcile(snapshot, writer = planner)

            assertNull(ctx.client.findProviderBySlug(FIXTURE_PROVIDER_SLUG), "a dry run writes nothing at all")
            assertEquals(0, ctx.harness.countCalls("POST", "/api/v1/providers"))
            assertTrue(
                dry.plannedOperations.contains("CREATE_TARIFF provider=acme name=VPS 1"),
                "the plan must name the intended operations: ${dry.plannedOperations}",
            )
            assertEquals(
                listOf(
                    "CREATE_PROVIDER",
                    "PUT_LEGAL_INFO",
                    "PUT_PAYMENT_METHODS",
                    "CREATE_LOCATION",
                    "CREATE_LOCATION",
                    "CREATE_TARIFF",
                    "CREATE_TARIFF",
                    "CREATE_TARIFF",
                    "REPLACE_TARIFF_LOCATIONS",
                    "REPLACE_TARIFF_LOCATIONS",
                    "REPLACE_TARIFF_LOCATIONS",
                ) + List(6) { "PUT_PRICE" },
                dry.plannedOperations.map { it.substringBefore(' ') },
                "the plan follows the write order of spec req. 29",
            )

            val real = ctx.reconcile(snapshot)
            assertEquals(
                real,
                dry.copy(plannedOperations = emptyList()),
                "the preview's counters must be the ones the real run then reports",
            )
        }
    }

    @Test
    fun `a dry run of an oversized snapshot caps its plan at 200 lines`() {
        withCatalog { ctx ->
            val planner = DryRunWriter()
            val many = (1..120).map { usd(validTariff("VPS $it", "vps-$it")) }

            val dry = ctx.reconcile(coldSnapshot().copy(tariffs = many), writer = planner)

            assertEquals(120, dry.tariffsCreated, "the counters are not capped")
            assertEquals(240, dry.pricesAppended)
            assertEquals(PLANNED_OPERATIONS_LIMIT, dry.plannedOperations.size)
            assertNull(ctx.client.findProviderBySlug(FIXTURE_PROVIDER_SLUG))
        }
    }

    // ---------- helpers ----------

    private class Ctx(
        val harness: CrawlerAppHarness,
        val client: CatalogApiClient,
        val reconciler: Reconciler,
        val writer: ApiCrawlWriter,
    ) {
        suspend fun reconcile(
            snapshot: ProviderSnapshot,
            writer: CrawlWriter = this.writer,
            ratio: BigDecimal = DEFAULT_RATIO,
        ): ReconcileOutcome =
            reconciler.reconcile(fixtureAdapter(snapshot.provider.slug), snapshot, writer, ratio)

        suspend fun providerId(): Long = client.findProviderBySlug(FIXTURE_PROVIDER_SLUG)!!.id
    }

    private fun withCatalog(block: suspend (Ctx) -> Unit) = CrawlerAppHarness(clock).use { harness ->
        CatalogApiClient(
            config = harness.crawlerConfig(),
            clock = clock,
            limits = FAST_LIMITS,
            backoffJitter = { 0L },
            sleep = { },
        ).use { client ->
            runBlocking { block(Ctx(harness, client, Reconciler(client), ApiCrawlWriter(client))) }
        }
    }

    private fun priceHistory(tariffId: Long) = transaction(PostgresSupport.database) {
        PriceRepository().history(tariffId, null, null, null)
    }

    private companion object {
        val DEFAULT_RATIO: BigDecimal = BigDecimal("0.5")

        /** Production retry budget, shorter timeouts -- a loopback call that fails, fails fast. */
        val FAST_LIMITS = CatalogClientLimits(
            connectTimeoutMs = 1_000,
            requestTimeoutMs = 5_000,
            socketTimeoutMs = 5_000,
        )

        /**
         * USD prices, so `NO_FX_RATE` (no rate is ever ingested in these tests) does not mask the
         * counter assertions; `noFxRateCount` has its own test in `CatalogApiClientTest`.
         */
        fun usd(tariff: TariffSnapshot): TariffSnapshot =
            tariff.copy(prices = tariff.prices.map { it.copy(currency = "USD") })

        fun coldSnapshot(): ProviderSnapshot = validSnapshot().copy(
            tariffs = listOf(
                usd(validTariff("VPS 1", "vps-1")),
                usd(validTariff("VPS 2", "vps-2")),
                usd(validTariff("VPS 3", "vps-3")),
            ),
        )

        /** Only tariffs are observed: no legal info, no payment methods, no locations, no links. */
        fun tariffsSnapshot(vararg tariffs: TariffSnapshot): ProviderSnapshot = validSnapshot().copy(
            legalInfo = null,
            paymentMethods = null,
            locations = null,
            tariffs = tariffs.map { it.copy(locationKeys = null) },
            tariffListComplete = true,
        )

        fun singleTariffSnapshot(amount: BigDecimal): ProviderSnapshot = tariffsSnapshot(
            validTariff("VPS 1", "vps-1").copy(
                locationKeys = null,
                prices = listOf(PriceSnapshot(BillingPeriod.MONTHLY, amount, "USD")),
            ),
        )

        /** A `tariffListComplete = true` snapshot that observed **no** tariff at all. */
        fun emptyCompleteSnapshot(slug: String): ProviderSnapshot = validSnapshot().copy(
            provider = validSnapshot().provider.copy(slug = slug),
            legalInfo = null,
            paymentMethods = null,
            locations = null,
            tariffs = emptyList(),
            tariffListComplete = true,
        )
    }
}
