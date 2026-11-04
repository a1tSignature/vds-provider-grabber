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
import java.util.concurrent.atomic.AtomicBoolean

/** How many run summaries the in-memory history keeps (spec req. 36). Nothing is persisted. */
const val RUN_HISTORY_SIZE: Int = 20

/**
 * What one run is asked to do: the adapter ids to run (**empty** = the registry's default-selectable
 * entries) and, optionally, a `maxOutOfStockRatio` that overrides the configured one for this run
 * only -- the operator's escape hatch for a genuine mass change (ADR-03).
 */
data class AdapterSelection(
    val adapterIds: List<String> = emptyList(),
    val maxOutOfStockRatio: BigDecimal? = null,
)

/**
 * A run was requested while one was already in progress; task vds-002.7 answers `409` on it.
 * [runId] is the run that holds the slot, or `null` when it finished between the refusal and this
 * message being built.
 */
class CrawlRunInProgressException(val runId: UUID?) :
    Exception("A crawl run (${runId ?: "unknown"}) is already in progress")

/**
 * The orchestrator: one run = the selected adapters, one at a time, each
 * **fetch → validate → reconcile**, each isolated from the others.
 *
 * ### Failure isolation is the point of this class (spec req. 18)
 *
 * Any failure of one adapter -- an exception from its parser, a `RobotsDisallowed`, a validation
 * problem, the mass-change guard, its own time budget -- is caught, recorded in that adapter's
 * [AdapterRunResult], and the run **continues with the next adapter**. There are exactly two
 * exceptions:
 *
 * - [CatalogApiException.Unauthorized] (the client's "second consecutive `401`") stops the run: the
 *   service account is unusable, so every remaining adapter would fail the same way. They are
 *   recorded [AdapterRunStatus.SKIPPED].
 * - [CancellationException] is **re-thrown** after the adapter's result is recorded. Swallowing it
 *   would break both time budgets and the shutdown path, and would leave the run slot held.
 *
 * ### Budgets (spec req. 19)
 *
 * `withTimeout(adapterTimeoutMs)` per adapter -- an expiry is that adapter's
 * [FailureReason.TIMEOUT] and the next adapter still runs -- inside `withTimeoutOrNull(runTimeoutMs)`
 * for the whole run: when the run budget expires the remaining adapters are `SKIPPED` and a summary
 * is still returned ([RunStatus.CANCELLED]), never an exception instead of one.
 *
 * ### One run at a time, process-wide (spec req. 20)
 *
 * An [AtomicBoolean] owned by this instance. [tryReserve] takes the slot **without blocking** and
 * publishes a [RunStatus.RUNNING] summary, so the admin endpoint of task vds-002.7 can answer `202`
 * with a `runId` or `409`, and the scheduler can skip a tick with a WARN. [runOnce] is the
 * reserve-and-run convenience for a direct caller.
 *
 * ### No Ktor server type, by ADR-01
 *
 * Nothing in this file imports `io.ktor.server.*`, `AppComponents` or an `ApplicationCall`: moving
 * the crawler into its own process must cost exactly one new `main()` that calls [runOnce].
 *
 * ### Time
 *
 * `startedAt`/`finishedAt` come from the injected [clock]; every **duration** comes from
 * `System.nanoTime()`, which is monotonic and therefore unaffected by a clock jump -- and
 * `Instant.now()`/`System.currentTimeMillis()` are banned project-wide anyway.
 */
class CrawlerRunner(
    private val config: CrawlerConfig,
    private val registry: AdapterRegistry,
    private val fetcher: PageFetcher,
    private val validator: SnapshotValidator,
    private val reconciler: Reconciler,
    private val clock: Clock,
    /**
     * `false` → the writer that really writes, `true` → a [org.a1tsign.vds.crawler.write.DryRunWriter].
     * Called **once per run**, so a dry run's fabricated ids and its 200-line plan are run-scoped.
     */
    private val writerFactory: (dryRun: Boolean) -> CrawlWriter,
    /** The adapter's own `VDS_CRAWLER_ADAPTER_<ID>_*` settings; `crawlerAdapterConfig` in production. */
    private val adapterConfig: (adapterId: String) -> Map<String, String> = { emptyMap() },
    /**
     * The fetcher's cumulative page counter (`KtorPageFetcher::pagesFetched`); the per-adapter
     * `pagesFetched` is the difference across that adapter. Correct because adapters run one at a
     * time -- which is why `maxConcurrentAdapters > 1` is refused below.
     */
    private val pagesFetched: () -> Long = { 0L },
    /** Defaults are the configured production budgets; parameters so a test needs no real minutes. */
    private val adapterTimeoutMs: Long = TimeUnit.MINUTES.toMillis(config.adapterTimeoutMinutes),
    private val runTimeoutMs: Long = TimeUnit.MINUTES.toMillis(config.runTimeoutMinutes),
) {

    init {
        // Running two adapters at once would break both the per-host politeness accounting of
        // `PageFetcher` (shared across adapters) and the per-adapter `pagesFetched` difference, so
        // the value is refused here rather than silently ignored. Raising it is a task of its own.
        if (config.maxConcurrentAdapters != 1) {
            throw ConfigurationException(
                "VDS_CRAWLER_MAX_CONCURRENT_ADAPTERS must be 1 in this version " +
                    "(got ${config.maxConcurrentAdapters}): adapters run strictly one at a time",
            )
        }
    }

    private val running = AtomicBoolean(false)
    private val historyLock = Any()
    private val history = ArrayDeque<CrawlRunSummary>()

    @Volatile
    private var active: Active? = null

    /** `true` while a run holds the slot -- a cheap pre-check; [tryReserve] is the decision. */
    fun isRunning(): Boolean = running.get()

    /**
     * Takes the one run slot without blocking and publishes its [RunStatus.RUNNING] summary.
     * Returns the new `runId`, or `null` when a run is already in progress.
     */
    fun tryReserve(dryRun: Boolean): UUID? {
        if (!running.compareAndSet(false, true)) return null
        val runId = UUID.randomUUID()
        val startedAt = clock.instant()
        active = Active(runId, startedAt, System.nanoTime(), dryRun)
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
            // Including CancellationException (shutdown): the history must not keep a run that is
            // RUNNING forever, and the slot must not stay held.
            publish(summaryOf(run, RunStatus.CANCELLED, run.results.toList(), plannedOperations = emptyList()))
            throw failure
        } finally {
            active = null
            running.set(false)
        }
    }

    /** The last [RUN_HISTORY_SIZE] summaries, newest first, including the one in flight. */
    fun recentRuns(): List<CrawlRunSummary> = synchronized(historyLock) { history.toList() }

    fun run(runId: UUID): CrawlRunSummary? = recentRuns().firstOrNull { it.runId == runId }

    // ---------- the run ----------

    private suspend fun execute(run: Active, selection: AdapterSelection): CrawlRunSummary {
        val adapters = registry.select(selection.adapterIds)
        val ratio = selection.maxOutOfStockRatio ?: config.maxOutOfStockRatio
        val writer = writerFactory(run.dryRun)
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

    /**
     * One adapter, isolated. Everything that can go wrong inside the budget is turned into this
     * adapter's result; only [CancellationException] leaves (after being recorded).
     */
    private suspend fun runAdapter(
        run: Active,
        adapter: ProviderAdapter,
        writer: CrawlWriter,
        ratio: BigDecimal,
    ): AdapterRunResult {
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
            // withTimeoutOrNull, not withTimeout: it returns null for **its own** expiry and
            // re-throws any other cancellation, which is the only sound way to tell "this adapter
            // ran out of time" (the run goes on) from "the run ran out of time or we are shutting
            // down" (record and re-throw). Both are a TimeoutCancellationException, so catching the
            // type cannot discriminate -- and treating the run's expiry as the adapter's would keep
            // the loop going through every remaining adapter inside a cancelled scope.
            withTimeoutOrNull(adapterTimeoutMs) {
                val snapshot = adapter.fetch(CrawlContext(fetcher, clock, run.runId, adapterConfig(adapter.id)))
                val problems = validator.validate(adapter, snapshot)
                if (problems.isNotEmpty()) {
                    // The gate of ADR-03 point 5: a rejected snapshot reaches no writer at all. The
                    // rendered problem list travels in the result's `message`, so the adapter's one
                    // WARN line carries it -- there is deliberately no second log line here.
                    return@withTimeoutOrNull result(
                        AdapterRunStatus.FAILED,
                        FailureReason.VALIDATION,
                        problems.toLogLine(),
                    )
                }
                val outcome = reconciler.reconcile(adapter, snapshot, writer, ratio)
                result(outcome.status, outcome.failureReason, outcome.message, outcome)
            } ?: result(AdapterRunStatus.FAILED, FailureReason.TIMEOUT, "Timed out after ${adapterTimeoutMs}ms")
        } catch (robots: FetchException.RobotsDisallowed) {
            // Not a failure: the site told us not to, which is an answer, not a malfunction.
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
            // A broken adapter: anything from a NullPointerException in a parser to an OOM-adjacent
            // error. Isolated here, never allowed to end the run (spec req. 18). The stack trace is
            // DEBUG and the operator-readable line is the adapter's single WARN below (spec req. 37
            // asks for *one* line per adapter), so the log cannot be flooded by a broken parser.
            logger.debug("crawl run {} adapter {} failed", run.runId, adapter.id, failure)
            result(
                AdapterRunStatus.FAILED,
                FailureReason.ADAPTER_ERROR,
                "${failure.javaClass.simpleName}: ${failure.message}",
            )
        }
    }

    // ---------- history and logging ----------

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

    /**
     * One line per adapter, INFO on success and WARN on `PARTIAL`/`FAILED` (spec req. 37), carrying
     * `runId`, `adapterId`, `providerSlug`, `status`, `durationMs` and the counters -- and nothing
     * else: no token, no password, no page body, no request body.
     */
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

    /** The run in flight: its identity, its monotonic start and the results recorded so far. */
    private class Active(
        val runId: UUID,
        val startedAt: Instant,
        val startedNanos: Long,
        val dryRun: Boolean,
    ) {
        val results = mutableListOf<AdapterRunResult>()

        fun elapsedMs(): Long = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos)
    }

    private companion object {
        val logger = LoggerFactory.getLogger(CrawlerRunner::class.java)
    }
}
