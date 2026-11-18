package org.a1tsign.vds.crawler

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import org.a1tsign.vds.config.ConfigurationException
import org.a1tsign.vds.config.CrawlerConfig
import org.a1tsign.vds.crawler.fetch.FetchException
import org.a1tsign.vds.crawler.fetch.PageFetcher
import org.a1tsign.vds.crawler.model.AdapterRunResult
import org.a1tsign.vds.crawler.model.AdapterRunStatus
import org.a1tsign.vds.crawler.model.CrawlRunSummary
import org.a1tsign.vds.crawler.model.FailureReason
import org.a1tsign.vds.crawler.model.RunStatus
import org.a1tsign.vds.crawler.write.CatalogApiException
import org.a1tsign.vds.crawler.write.CrawlWriter
import org.a1tsign.vds.crawler.write.ReconcileOutcome
import org.a1tsign.vds.crawler.write.Reconciler
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Run summaries are kept in memory only. */
const val RUN_HISTORY_SIZE: Int = 20

/** Use registered defaults for an empty adapter list. The optional stock-change ratio applies only to this run. */
data class AdapterSelection(
    val adapterIds: List<String> = emptyList(),
    val maxOutOfStockRatio: BigDecimal? = null,
)

/** [runId] can be null if the holder finished before the exception was built. */
class CrawlRunInProgressException(val runId: UUID?) :
    Exception("A crawl run (${runId ?: "unknown"}) is already in progress")

/**
 * Fetch, validate and reconcile adapters serially. Record failures and continue unless the catalog rejects the service account or the run is cancelled.
 * Adapter timeouts are local. A run timeout skips pending adapters and returns a CANCELLED summary. Durations use monotonic time.
 */
class CrawlerRunner(
    private val config: CrawlerConfig,
    private val registry: AdapterRegistry,
    private val fetcher: PageFetcher,
    private val validator: SnapshotValidator,
    private val reconciler: Reconciler,
    private val clock: Clock,
    /** Create the writer once per run so fabricated IDs and the dry-run plan stay run-scoped. */
    private val writerFactory: (dryRun: Boolean) -> CrawlWriter,
    private val adapterConfig: (adapterId: String) -> Map<String, String> = { emptyMap() },
    /** Per-adapter fetch counts are deltas of a shared counter and require serial execution. */
    private val pagesFetched: () -> Long = { 0L },
    /** Defaults are the configured production budgets; parameters so a test needs no real minutes. */
    private val adapterTimeoutMs: Long = TimeUnit.MINUTES.toMillis(config.adapterTimeoutMinutes),
    private val adapterTimeoutOverridesMs: Map<String, Long> = emptyMap(),
    private val runTimeoutMs: Long = TimeUnit.MINUTES.toMillis(config.runTimeoutMinutes),
    private val slot: RunSlot = RunSlot(),
) {

    init {
        // Serial execution keeps the fetched-page counter attributable to each adapter and preserves shared per-host politeness.
        if (config.maxConcurrentAdapters != 1) {
            throw ConfigurationException(
                "VDS_CRAWLER_MAX_CONCURRENT_ADAPTERS must be 1 in this version " +
                    "(got ${config.maxConcurrentAdapters}): adapters run strictly one at a time",
            )
        }
    }

    private val historyLock = Any()
    private val history = ArrayDeque<CrawlRunSummary>()

    @Volatile
    private var active: Active? = null

    fun isRunning(): Boolean = slot.holder()?.kind == RunKind.CRAWL

    /**
     * Takes the one run slot without blocking and publishes its [RunStatus.RUNNING] summary.
     * Returns the new `runId`, or `null` when a run -- or, with the shared slot, a discovery pass --
     * is already in progress.
     */
    fun tryReserve(dryRun: Boolean): UUID? {
        val runId = UUID.randomUUID()
        val holder = slot.tryTake(RunKind.CRAWL, runId) ?: return null
        val startedAt = clock.instant()
        active = Active(runId, startedAt, System.nanoTime(), dryRun, holder)
        publish(CrawlRunSummary(runId = runId, startedAt = startedAt, status = RunStatus.RUNNING, dryRun = dryRun))
        return runId
    }

    /** Reserve and run; throws [CrawlRunInProgressException] instead of waiting. */
    suspend fun runOnce(selection: AdapterSelection, dryRun: Boolean): CrawlRunSummary {
        val runId = tryReserve(dryRun) ?: throw CrawlRunInProgressException(active?.runId)
        return runReserved(runId, selection)
    }

    /** Runs the run [tryReserve] handed out. The slot is released however this returns. */
    suspend fun runReserved(runId: UUID, selection: AdapterSelection): CrawlRunSummary {
        val run = active
        check(run != null && run.runId == runId) { "Run $runId is not the reserved run" }
        try {
            return execute(run, selection)
        } catch (failure: Throwable) {
            // On cancellation, publish a terminal summary and retain the partial dry-run plan before releasing the slot.
            publish(
                summaryOf(run, RunStatus.CANCELLED, run.results.toList(), plannedOperations = run.writer?.plannedOperations() ?: emptyList()),
            )
            throw failure
        } finally {
            active = null
            slot.release(run.holder)
        }
    }

    /** The last [RUN_HISTORY_SIZE] summaries, newest first, including the one in flight. */
    fun recentRuns(): List<CrawlRunSummary> = synchronized(historyLock) { history.toList() }

    fun run(runId: UUID): CrawlRunSummary? = recentRuns().firstOrNull { it.runId == runId }


    private suspend fun execute(run: Active, selection: AdapterSelection): CrawlRunSummary {
        val adapters = registry.select(selection.adapterIds)
        val ratio = selection.maxOutOfStockRatio ?: config.maxOutOfStockRatio
        val writer = writerFactory(run.dryRun)
        run.writer = writer
        logger.info(
            "crawl run {} starting: adapters={} dryRun={} maxOutOfStockRatio={}",
            run.runId,
            adapters.size,
            run.dryRun,
            ratio.toPlainString(),
        )

        var authFailed = false
        val completed = withTimeoutOrNull(runTimeoutMs) {
            for (adapter in adapters) {
                val result = runAdapter(run, adapter, writer, ratio)
                run.results += result
                log(run, result)
                if (result.failureReason == FailureReason.AUTH) {
                    authFailed = true
                    break
                }
            }
            true
        } != null

        val skipped = adapters.drop(run.results.size).map { adapter ->
            AdapterRunResult(
                adapterId = adapter.id,
                providerSlug = adapter.providerSlug,
                status = AdapterRunStatus.SKIPPED,
                durationMs = 0,
                failureReason = if (authFailed) FailureReason.AUTH else FailureReason.TIMEOUT,
                message = if (authFailed) {
                    "Skipped: the crawler service account was rejected, so the run stopped"
                } else {
                    "Skipped: the run's time budget expired"
                },
            )
        }
        skipped.forEach { log(run, it) }
        // A run cut short by its own budget is CANCELLED; one that merely stopped early because the
        // service account is unusable is COMPLETED with the remaining adapters SKIPPED.
        val status = if (completed) RunStatus.COMPLETED else RunStatus.CANCELLED
        val summary = summaryOf(run, status, run.results + skipped, writer.plannedOperations())
        publish(summary)
        logger.info(
            "crawl run {} finished: status={} adapters={} dryRun={} durationMs={}",
            summary.runId,
            summary.status,
            summary.adapters.size,
            summary.dryRun,
            run.elapsedMs(),
        )
        return summary
    }

    private suspend fun runAdapter(
        run: Active,
        adapter: ProviderAdapter,
        writer: CrawlWriter,
        ratio: BigDecimal,
    ): AdapterRunResult {
        val timeoutMs = adapterTimeoutOverridesMs[adapter.id] ?: adapterTimeoutMs
        val startedNanos = System.nanoTime()
        val pagesBefore = pagesFetched()
        fun elapsedMs() = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos)
        fun pages() = (pagesFetched() - pagesBefore).toInt()
        fun result(
            status: AdapterRunStatus,
            reason: FailureReason? = null,
            message: String? = null,
            outcome: ReconcileOutcome? = null,
        ) = AdapterRunResult(
            adapterId = adapter.id,
            providerSlug = adapter.providerSlug,
            status = status,
            durationMs = elapsedMs(),
            pagesFetched = pages(),
            providersCreated = outcome?.providersCreated ?: 0,
            locationsCreated = outcome?.locationsCreated ?: 0,
            tariffsCreated = outcome?.tariffsCreated ?: 0,
            tariffsUpdated = outcome?.tariffsUpdated ?: 0,
            tariffsMarkedOutOfStock = outcome?.tariffsMarkedOutOfStock ?: 0,
            pricesAppended = outcome?.pricesAppended ?: 0,
            pricesUnchanged = outcome?.pricesUnchanged ?: 0,
            noFxRateCount = outcome?.noFxRateCount ?: 0,
            failureReason = reason,
            message = message,
        )

        return try {
            // withTimeoutOrNull handles only this adapter's timeout. Run-budget and shutdown cancellation still propagate.
            withTimeoutOrNull(timeoutMs) {
                val snapshot = adapter.fetch(CrawlContext(fetcher, clock, run.runId, adapterConfig(adapter.id)))
                val problems = validator.validate(adapter, snapshot)
                if (problems.isNotEmpty()) {
                    // Reject invalid snapshots before opening the write path. The result message feeds the adapter's WARN line.
                    return@withTimeoutOrNull result(
                        AdapterRunStatus.FAILED,
                        FailureReason.VALIDATION,
                        problems.toLogLine(),
                    )
                }
                val outcome = reconciler.reconcile(adapter, snapshot, writer, ratio)
                result(outcome.status, outcome.failureReason, outcome.message, outcome)
                // A timed-out adapter can leave committed writes without returning counters. The resulting counts are a lower bound.
            } ?: result(AdapterRunStatus.FAILED, FailureReason.TIMEOUT, "Timed out after ${timeoutMs}ms")
        } catch (robots: FetchException.RobotsDisallowed) {
            // A robots refusal is a skip because the site explicitly declined the fetch.
            result(AdapterRunStatus.SKIPPED, FailureReason.FETCH, robots.message)
        } catch (fetch: FetchException) {
            result(AdapterRunStatus.FAILED, FailureReason.FETCH, fetch.message)
        } catch (unauthorized: CatalogApiException.Unauthorized) {
            result(AdapterRunStatus.FAILED, FailureReason.AUTH, unauthorized.message)
        } catch (cancellation: CancellationException) {
            // The run budget or a shutdown. Record, then re-throw -- never swallow.
            val cut = result(AdapterRunStatus.SKIPPED, FailureReason.TIMEOUT, "Cut short: the run was cancelled")
            run.results += cut
            log(run, cut)
            throw cancellation
        } catch (failure: Throwable) {
            // Keep unexpected parser failures isolated and put stack traces at DEBUG.
            logger.debug("crawl run {} adapter {} failed", run.runId, adapter.id, failure)
            result(
                AdapterRunStatus.FAILED,
                FailureReason.ADAPTER_ERROR,
                "${failure.javaClass.simpleName}: ${failure.message}",
            )
        }
    }


    private fun publish(summary: CrawlRunSummary) = synchronized(historyLock) {
        history.removeIf { it.runId == summary.runId }
        history.addFirst(summary)
        while (history.size > RUN_HISTORY_SIZE) history.removeLast()
    }

    private fun summaryOf(
        run: Active,
        status: RunStatus,
        adapters: List<AdapterRunResult>,
        plannedOperations: List<String>,
    ) = CrawlRunSummary(
        runId = run.runId,
        startedAt = run.startedAt,
        finishedAt = clock.instant(),
        status = status,
        dryRun = run.dryRun,
        adapters = adapters,
        plannedOperations = plannedOperations,
    )

    /** Log status and counts without credentials or raw content. */
    private fun log(run: Active, result: AdapterRunResult) {
        val line = "crawl run {} adapter {} provider {} status={} durationMs={} pages={} " +
            "providersCreated={} locationsCreated={} tariffsCreated={} tariffsUpdated={} " +
            "tariffsMarkedOutOfStock={} pricesAppended={} pricesUnchanged={} noFxRate={} reason={} message={}"
        val arguments = arrayOf<Any?>(
            run.runId,
            result.adapterId,
            result.providerSlug,
            result.status,
            result.durationMs,
            result.pagesFetched,
            result.providersCreated,
            result.locationsCreated,
            result.tariffsCreated,
            result.tariffsUpdated,
            result.tariffsMarkedOutOfStock,
            result.pricesAppended,
            result.pricesUnchanged,
            result.noFxRateCount,
            result.failureReason,
            result.message,
        )
        when (result.status) {
            AdapterRunStatus.SUCCESS -> logger.info(line, *arguments)
            AdapterRunStatus.SKIPPED -> logger.info(line, *arguments)
            AdapterRunStatus.PARTIAL, AdapterRunStatus.FAILED -> logger.warn(line, *arguments)
        }
    }

    private class Active(
        val runId: UUID,
        val startedAt: Instant,
        val startedNanos: Long,
        val dryRun: Boolean,
        val holder: RunHolder,
    ) {
        val results = mutableListOf<AdapterRunResult>()

        /** Set by [execute] before the first adapter runs; read by [runReserved]'s cancellation path. */
        @Volatile
        var writer: CrawlWriter? = null

        fun elapsedMs(): Long = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos)
    }

    private companion object {
        val logger = LoggerFactory.getLogger(CrawlerRunner::class.java)
    }
}
