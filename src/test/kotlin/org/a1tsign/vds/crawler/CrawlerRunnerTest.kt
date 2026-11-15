package org.a1tsign.vds.crawler

import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.a1tsign.vds.config.ConfigurationException
import org.a1tsign.vds.crawler.model.AdapterRunResult
import org.a1tsign.vds.crawler.model.AdapterRunStatus
import org.a1tsign.vds.crawler.model.CrawlRunSummary
import org.a1tsign.vds.crawler.model.FailureReason
import org.a1tsign.vds.crawler.model.RunStatus
import org.a1tsign.vds.crawler.support.CrawlerAppHarness
import org.a1tsign.vds.crawler.support.RecordingCrawlWriter
import org.a1tsign.vds.crawler.support.StubPageFetcher
import org.a1tsign.vds.crawler.support.invalidSnapshotAdapter
import org.a1tsign.vds.crawler.support.robotsDisallowedAdapter
import org.a1tsign.vds.crawler.support.sleepingAdapter
import org.a1tsign.vds.crawler.support.snapshotAdapter
import org.a1tsign.vds.crawler.support.stubSnapshot
import org.a1tsign.vds.crawler.support.throwingAdapter
import org.a1tsign.vds.crawler.write.ApiCrawlWriter
import org.a1tsign.vds.crawler.write.CatalogApiClient
import org.a1tsign.vds.crawler.write.CatalogClientLimits
import org.a1tsign.vds.crawler.write.CrawlWriter
import org.a1tsign.vds.crawler.write.DryRunWriter
import org.a1tsign.vds.crawler.write.Reconciler
import org.a1tsign.vds.support.AbstractDbTest
import org.a1tsign.vds.support.CatalogFixture
import org.a1tsign.vds.support.PostgresSupport
import org.a1tsign.vds.support.capturingLogs
import org.slf4j.event.Level
import java.math.BigDecimal
import java.time.Duration
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The orchestrator against the **real** application ([CrawlerAppHarness]): failure isolation is a
 * claim about what happens to the *other* adapters' data, so the catalog is real. Every budget is a
 * constructor parameter, so no test waits minutes.
 */
class CrawlerRunnerTest : AbstractDbTest() {

    // ---------- 1. failure isolation, the headline ----------

    @Test
    fun `one broken adapter fails alone while the run completes and its neighbours reach the catalog`() {
        withRunner(
            snapshotAdapter("alpha", stubSnapshot("alpha", clock.instant())),
            throwingAdapter("bravo"),
            snapshotAdapter("charlie", stubSnapshot("charlie", clock.instant())),
        ) { ctx ->
            val summary = ctx.runner.runOnce(AdapterSelection(), dryRun = false)

            assertEquals(RunStatus.COMPLETED, summary.status)
            assertEquals(listOf("alpha", "bravo", "charlie"), summary.adapters.map { it.adapterId })
            assertEquals(
                listOf(AdapterRunStatus.SUCCESS, AdapterRunStatus.FAILED, AdapterRunStatus.SUCCESS),
                summary.adapters.map { it.status },
            )
            assertEquals(FailureReason.ADAPTER_ERROR, summary.adapters[1].failureReason)
            assertNotNull(ctx.client.findProviderBySlug("alpha"))
            assertNull(ctx.client.findProviderBySlug("bravo"), "the broken adapter wrote nothing")
            assertNotNull(ctx.client.findProviderBySlug("charlie"))
        }
    }

    // ---------- 2. the validation gate ----------

    @Test
    fun `an invalid snapshot fails its adapter with zero writes while the next adapter still runs`() {
        withRunner(
            invalidSnapshotAdapter("alpha", clock.instant()),
            snapshotAdapter("bravo", stubSnapshot("bravo", clock.instant())),
            recording = true,
        ) { ctx ->
            val summary = ctx.runner.runOnce(AdapterSelection(), dryRun = false)

            val rejected = summary.adapters.first()
            assertEquals(AdapterRunStatus.FAILED, rejected.status)
            assertEquals(FailureReason.VALIDATION, rejected.failureReason)
            assertTrue(
                rejected.message!!.contains("sourceUrls"),
                "the rendered problem list is the adapter's message: ${rejected.message}",
            )
            assertEquals(AdapterRunStatus.SUCCESS, summary.adapters[1].status)
            assertEquals(
                listOf("CreateProvider", "CreateTariff", "PutPrice", "PutPrice"),
                ctx.recorder!!.kinds(),
                "only the valid snapshot may reach the writer at all",
            )
        }
    }

    // ---------- 3 + 4. budgets ----------

    @Test
    fun `an adapter over its own budget is FAILED with TIMEOUT and the next adapter still runs`() {
        withRunner(
            sleepingAdapter("alpha", Duration.ofSeconds(30), stubSnapshot("alpha", clock.instant())),
            snapshotAdapter("bravo", stubSnapshot("bravo", clock.instant())),
            adapterTimeoutOverridesMs = mapOf("alpha" to 100L),
        ) { ctx ->
            val summary = ctx.runner.runOnce(AdapterSelection(), dryRun = false)

            assertEquals(RunStatus.COMPLETED, summary.status)
            assertEquals(AdapterRunStatus.FAILED, summary.adapters.first().status)
            assertEquals(FailureReason.TIMEOUT, summary.adapters.first().failureReason)
            assertEquals(AdapterRunStatus.SUCCESS, summary.adapters[1].status)
            assertNotNull(ctx.client.findProviderBySlug("bravo"))
        }
    }

    @Test
    fun `a run over its own budget returns a summary whose unprocessed adapters are SKIPPED`() {
        withRunner(
            sleepingAdapter("alpha", Duration.ofSeconds(30), stubSnapshot("alpha", clock.instant())),
            snapshotAdapter("bravo", stubSnapshot("bravo", clock.instant())),
            runTimeoutMs = 150,
        ) { ctx ->
            val summary = ctx.runner.runOnce(AdapterSelection(), dryRun = false)

            assertEquals(RunStatus.CANCELLED, summary.status, "a run cut short by its budget is CANCELLED")
            assertNotNull(summary.finishedAt, "a summary is produced, never an exception instead of one")
            assertEquals(
                listOf(AdapterRunStatus.SKIPPED, AdapterRunStatus.SKIPPED),
                summary.adapters.map { it.status },
            )
            assertNull(ctx.client.findProviderBySlug("bravo"), "the skipped adapter never ran")
        }
    }

    // ---------- 5. one run at a time ----------

    @Test
    fun `a second run is refused without blocking while the first one stays RUNNING and unaffected`() {
        withRunner(
            sleepingAdapter("alpha", Duration.ofMillis(300), stubSnapshot("alpha", clock.instant())),
        ) { ctx ->
            coroutineScope {
                val first = async { ctx.runner.runOnce(AdapterSelection(), dryRun = false) }
                while (!ctx.runner.isRunning()) delay(5)

                val refused = assertFailsWith<CrawlRunInProgressException> {
                    ctx.runner.runOnce(AdapterSelection(), dryRun = false)
                }
                val inFlight = ctx.runner.recentRuns().single()
                assertEquals(RunStatus.RUNNING, inFlight.status, "the run in flight is visible in the history")
                assertEquals(inFlight.runId, refused.runId)

                val summary = first.await()
                assertEquals(RunStatus.COMPLETED, summary.status)
                assertEquals(AdapterRunStatus.SUCCESS, summary.adapters.single().status)
                assertEquals(1, ctx.runner.recentRuns().size, "the refused run left no trace")
            }
        }
    }

    // ---------- 6. robots.txt ----------

    @Test
    fun `an adapter robots txt disallows is SKIPPED rather than FAILED and the run continues`() {
        withRunner(
            robotsDisallowedAdapter("alpha"),
            snapshotAdapter("bravo", stubSnapshot("bravo", clock.instant())),
        ) { ctx ->
            val summary = ctx.runner.runOnce(AdapterSelection(), dryRun = false)

            assertEquals(RunStatus.COMPLETED, summary.status)
            assertEquals(AdapterRunStatus.SKIPPED, summary.adapters.first().status)
            assertEquals(FailureReason.FETCH, summary.adapters.first().failureReason)
            assertEquals(AdapterRunStatus.SUCCESS, summary.adapters[1].status)
        }
    }

    // ---------- 7. a whole-run auth failure ----------

    @Test
    fun `a rejected service account stops the run and skips the remaining adapters`() {
        withRunner(
            snapshotAdapter("alpha", stubSnapshot("alpha", clock.instant())),
            snapshotAdapter("bravo", stubSnapshot("bravo", clock.instant())),
        ) { ctx ->
            // The real server refuses every catalog call, including the one made with a freshly
            // issued token -- which is what the client turns into its non-retryable Unauthorized.
            ctx.harness.rejectCatalogCallsAsUnauthorized = true

            val summary = ctx.runner.runOnce(AdapterSelection(), dryRun = false)

            assertEquals(AdapterRunStatus.FAILED, summary.adapters.first().status)
            assertEquals(FailureReason.AUTH, summary.adapters.first().failureReason)
            val skipped = summary.adapters[1]
            assertEquals(AdapterRunStatus.SKIPPED, skipped.status)
            assertEquals(FailureReason.AUTH, skipped.failureReason)
            assertTrue(skipped.message!!.contains("rejected"), "the summary says why: ${skipped.message}")
        }
    }

    // ---------- 8. the run-history ring buffer ----------

    @Test
    fun `the history keeps the last twenty runs newest first and forgets the evicted ones`() {
        withRunner { ctx ->
            val ids = (1..25).map { ctx.runner.runOnce(AdapterSelection(), dryRun = false).runId }

            assertEquals(RUN_HISTORY_SIZE, ctx.runner.recentRuns().size)
            assertEquals(ids.takeLast(RUN_HISTORY_SIZE).reversed(), ctx.runner.recentRuns().map { it.runId })
            assertNull(ctx.runner.run(ids.first()), "an evicted run is simply gone -- nothing is persisted")
            assertEquals(ids.last(), ctx.runner.run(ids.last())?.runId)
            assertNull(ctx.runner.run(UUID.randomUUID()))
        }
    }

    // ---------- 9. counters, timestamps and durations ----------

    @Test
    fun `a successful run reports the reconciler's counters, the fetcher's pages and both clock instants`() {
        withRunner(snapshotAdapter("alpha", stubSnapshot("alpha", clock.instant()))) { ctx ->
            val summary = ctx.runner.runOnce(AdapterSelection(), dryRun = false)

            val result = summary.adapters.single()
            assertTrue(result.durationMs > 0, "durations come from System.nanoTime(), not the frozen clock")
            assertEquals(
                AdapterRunResult(
                    adapterId = "alpha",
                    providerSlug = "alpha",
                    status = AdapterRunStatus.SUCCESS,
                    durationMs = 0,
                    pagesFetched = 1,
                    providersCreated = 1,
                    tariffsCreated = 1,
                    pricesAppended = 2,
                ),
                result.copy(durationMs = 0),
            )
            assertEquals(
                CrawlRunSummary(
                    runId = summary.runId,
                    startedAt = clock.instant(),
                    finishedAt = clock.instant(),
                    status = RunStatus.COMPLETED,
                    dryRun = false,
                ),
                summary.copy(adapters = emptyList()),
            )
        }
    }

    @Test
    fun `a dry run reports the same counters and the plan, and writes nothing`() {
        withRunner(snapshotAdapter("alpha", stubSnapshot("alpha", clock.instant()))) { ctx ->
            val summary = ctx.runner.runOnce(AdapterSelection(), dryRun = true)

            assertTrue(summary.dryRun)
            assertEquals(AdapterRunStatus.SUCCESS, summary.adapters.single().status)
            assertEquals(1, summary.adapters.single().tariffsCreated)
            assertEquals(
                listOf("CREATE_PROVIDER", "CREATE_TARIFF", "PUT_PRICE", "PUT_PRICE"),
                summary.plannedOperations.map { it.substringBefore(' ') },
            )
            assertNull(ctx.client.findProviderBySlug("alpha"))
        }
    }

    @Test
    fun `the per-run maxOutOfStockRatio override reaches the reconciler`() {
        val fixture = CatalogFixture(PostgresSupport.database)
        withRunner(snapshotAdapter("alpha", stubSnapshot("alpha", clock.instant(), tariffs = emptyList()))) { ctx ->
            val provider = fixture.createProvider("alpha", clock.instant())
            (1..4).forEach { fixture.createTariff(provider.id, "VPS $it", clock.instant()) }

            val guarded = ctx.runner.runOnce(AdapterSelection(), dryRun = false)
            val overridden = ctx.runner.runOnce(AdapterSelection(maxOutOfStockRatio = BigDecimal("1.0")), dryRun = false)

            assertEquals(FailureReason.MASS_CHANGE_GUARD, guarded.adapters.single().failureReason)
            assertEquals(AdapterRunStatus.SUCCESS, overridden.adapters.single().status)
            assertEquals(4, overridden.adapters.single().tariffsMarkedOutOfStock)
        }
    }

    // ---------- 10. logging ----------

    @Test
    fun `one INFO opens and one closes the run, a failed adapter logs exactly one WARN, and no secret leaks`() {
        var runId: UUID? = null
        val logs = capturingLogs {
            withRunner(
                snapshotAdapter("alpha", stubSnapshot("alpha", clock.instant())),
                throwingAdapter("bravo"),
            ) { ctx ->
                runId = ctx.runner.runOnce(AdapterSelection(), dryRun = false).runId
            }
        }

        val infos = logs.messagesAt(Level.INFO)
        assertEquals(1, infos.count { it.contains("crawl run $runId starting:") })
        assertEquals(1, infos.count { it.contains("crawl run $runId finished:") })
        assertEquals(1, infos.count { it.contains("adapter alpha") }, "INFO for the successful adapter")
        val warnings = logs.messagesAt(Level.WARN).filter { it.contains("adapter bravo") }
        assertEquals(1, warnings.size, "exactly one WARN per failed adapter: $warnings")
        assertTrue(warnings.single().contains("$runId"), warnings.single())
        assertTrue(warnings.single().contains("provider bravo"), warnings.single())
        assertTrue(warnings.single().contains("status=FAILED"), warnings.single())
        assertTrue(warnings.single().contains("durationMs="), warnings.single())

        val captured = logs.messages().joinToString("\n")
        assertTrue(captured.isNotEmpty(), "nothing was captured, so the assertion would be vacuous")
        assertTrue(!captured.contains("crawler-password-1"), "the service-account password leaked")
        assertTrue(!captured.contains("Bearer"), "an Authorization value leaked")
        assertTrue(!captured.contains("eyJ"), "a JWT leaked")
    }

    // ---------- 12. cancellation hygiene ----------

    @Test
    fun `cancelling the run releases the slot, records CANCELLED and lets the next run start`() {
        withRunner(
            sleepingAdapter("alpha", Duration.ofMillis(300), stubSnapshot("alpha", clock.instant())),
        ) { ctx ->
            coroutineScope {
                val job = launch { ctx.runner.runOnce(AdapterSelection(), dryRun = false) }
                while (!ctx.runner.isRunning()) delay(5)
                job.cancelAndJoin()

                assertFalse(ctx.runner.isRunning(), "a swallowed CancellationException would hold the slot")
                assertEquals(RunStatus.CANCELLED, ctx.runner.recentRuns().single().status)

                val next = ctx.runner.runOnce(AdapterSelection(), dryRun = false)
                assertEquals(RunStatus.COMPLETED, next.status)
                assertEquals(2, ctx.runner.recentRuns().size)
            }
        }
    }

    // ---------- M9. the maxConcurrentAdapters guard ----------

    @Test
    fun `a configured concurrency above 1 is refused at construction, not silently ignored`() {
        // M9 of 05-reviews/vds-002.5-6-combined-quality.md: the one-line comparison standing
        // between a config of 2 and the broken PageFetcher politeness / pagesFetched accounting
        // the report names had no test -- every other test constructs with the default of 1.
        CrawlerAppHarness(clock).use { harness ->
            CatalogApiClient(config = harness.crawlerConfig(), clock = clock).use { client ->
                assertFailsWith<ConfigurationException> {
                    CrawlerRunner(
                        config = harness.crawlerConfig().copy(maxConcurrentAdapters = 2),
                        registry = AdapterRegistry(emptyList()),
                        fetcher = StubPageFetcher(),
                        validator = SnapshotValidator(clock),
                        reconciler = Reconciler(client),
                        clock = clock,
                        writerFactory = { RecordingCrawlWriter() },
                    )
                }
            }
        }
    }

    // ---------- helpers ----------

    private class Ctx(
        val runner: CrawlerRunner,
        val harness: CrawlerAppHarness,
        val client: CatalogApiClient,
        val fetcher: StubPageFetcher,
        val recorder: RecordingCrawlWriter?,
    )

    private fun withRunner(
        vararg adapters: ProviderAdapter,
        adapterTimeoutMs: Long = 20_000,
        adapterTimeoutOverridesMs: Map<String, Long> = emptyMap(),
        runTimeoutMs: Long = 60_000,
        recording: Boolean = false,
        block: suspend (Ctx) -> Unit,
    ) = CrawlerAppHarness(clock).use { harness ->
        CatalogApiClient(
            config = harness.crawlerConfig(),
            clock = clock,
            limits = FAST_LIMITS,
            backoffJitter = { 0L },
            sleep = { },
        ).use { client ->
            val fetcher = StubPageFetcher()
            val recorder = if (recording) RecordingCrawlWriter() else null
            val writerFactory: (Boolean) -> CrawlWriter = { dryRun ->
                recorder ?: if (dryRun) DryRunWriter() else ApiCrawlWriter(client)
            }
            val runner = CrawlerRunner(
                config = harness.crawlerConfig(),
                registry = AdapterRegistry(adapters.map { AdapterEntry(it, includedByDefault = true) }),
                fetcher = fetcher,
                validator = SnapshotValidator(clock),
                reconciler = Reconciler(client),
                clock = clock,
                writerFactory = writerFactory,
                pagesFetched = { fetcher.pagesFetched },
                adapterTimeoutMs = adapterTimeoutMs,
                adapterTimeoutOverridesMs = adapterTimeoutOverridesMs,
                runTimeoutMs = runTimeoutMs,
            )
            runBlocking { block(Ctx(runner, harness, client, fetcher, recorder)) }
        }
    }

    private companion object {
        /** Production retry budget, shorter timeouts -- a loopback call that fails, fails fast. */
        val FAST_LIMITS = CatalogClientLimits(
            connectTimeoutMs = 1_000,
            requestTimeoutMs = 5_000,
            socketTimeoutMs = 5_000,
        )
    }
}
