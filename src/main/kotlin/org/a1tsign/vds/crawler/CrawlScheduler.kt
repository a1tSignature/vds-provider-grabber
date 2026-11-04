package org.a1tsign.vds.crawler

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.a1tsign.vds.config.CrawlerConfig
import org.slf4j.LoggerFactory
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** How long [CrawlScheduler.close] lets a cancelled run unwind before giving up on it. */
const val CRAWL_SHUTDOWN_GRACE_MS: Long = 10_000

/** Share of the interval that may be added as random jitter, so ticks do not line up (spec req. 21). */
private const val JITTER_SHARE = 10

/**
 * The periodic trigger, shaped after the project's own precedent (`fx/FxRefreshScheduler`): one
 * **daemon** single-threaded `ScheduledExecutorService` and nothing else -- no third-party scheduling
 * library and no OS-level timer entry (spec req. 21; the grep guard over this package pins it).
 *
 * - **Readiness is never delayed**: nothing runs on the startup path. The first tick is
 *   `VDS_CRAWLER_INITIAL_DELAY_MINUTES` away (default 10), so a crash-restart loop cannot hammer
 *   provider sites, and every following tick adds up to 10 % random jitter.
 * - **A tick that finds a run in progress is skipped with one WARN**: it never queues a second run.
 *   The decision is `CrawlerRunner.tryReserve`, the same non-blocking slot the admin endpoint uses,
 *   so "at most one run at a time" has exactly one implementation.
 * - **The tick does not block the timer thread**: the run is launched in this scheduler's own
 *   [CoroutineScope], which is what makes [close] able to cancel it.
 * - **[close] is shutdown**: it stops accepting ticks, cancels an in-flight run and gives it
 *   [CRAWL_SHUTDOWN_GRACE_MS] to unwind. Task vds-002.7 calls it from `Application.kt`'s
 *   `shutdownGracefully`, before `server.stop()`.
 *
 * It cannot be started by accident: the only constructor is private and [create] returns `null`
 * while `VDS_CRAWLER_ENABLED` is `false` -- so a disabled crawler has no thread and no scope.
 */
class CrawlScheduler private constructor(
    private val runner: CrawlerRunner,
    private val selection: AdapterSelection,
    private val dryRun: Boolean,
    private val intervalMs: Long,
    private val initialDelayMs: Long,
    private val jitterMs: (Long) -> Long,
) : AutoCloseable {

    private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "crawl-scheduler").apply { isDaemon = true }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val inFlight = AtomicReference<Job?>()

    @Volatile
    private var accepting = true

    init {
        arm(initialDelayMs)
    }

    /** Ticks are re-armed one at a time, because the jitter differs per tick. */
    private fun arm(delayMs: Long) {
        if (!accepting) return
        try {
            executor.schedule(
                {
                    tick()
                    arm(intervalMs + jitterMs(intervalMs / JITTER_SHARE))
                },
                delayMs,
                TimeUnit.MILLISECONDS,
            )
        } catch (rejected: RejectedExecutionException) {
            // close() won the race with this re-arm; nothing to do.
            logger.debug("crawl scheduler is shutting down, no further tick is scheduled")
        }
    }

    private fun tick() {
        if (!accepting) return
        val runId = runner.tryReserve(dryRun)
        if (runId == null) {
            logger.warn("crawl scheduler tick skipped: a crawl run is already in progress")
            return
        }
        inFlight.set(
            scope.launch {
                try {
                    runner.runReserved(runId, selection)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (failure: Throwable) {
                    // The runner already logged the run's own outcome; this is the last line of
                    // defence, exactly like the FX scheduler's -- a failed tick never kills the timer.
                    logger.warn("crawl run {} ended with an unhandled failure", runId, failure)
                }
            },
        )
    }

    override fun close() {
        accepting = false
        executor.shutdownNow()
        val job = inFlight.getAndSet(null)
        if (job != null && job.isActive) {
            job.cancel()
            // runBlocking on the shutdown thread on purpose: the caller (Application.kt's
            // shutdownGracefully) is plain blocking code and must not return before the run let go.
            val finished = runBlocking { withTimeoutOrNull(CRAWL_SHUTDOWN_GRACE_MS) { job.join() } }
            if (finished == null) {
                logger.warn("crawl run did not finish within {} ms of being cancelled", CRAWL_SHUTDOWN_GRACE_MS)
            }
        }
        scope.cancel()
        try {
            executor.awaitTermination(1, TimeUnit.SECONDS)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    companion object {
        /**
         * The scheduler for [config], or **`null` when the crawler is disabled** -- the "nothing is
         * constructed, no thread is started" guarantee of ADR-01 is therefore not something a caller
         * can forget.
         *
         * [intervalMs]/[initialDelayMs]/[jitterMs] default to the configured production values and
         * are parameters only so a test does not have to wait minutes.
         */
        fun create(
            config: CrawlerConfig,
            runner: CrawlerRunner,
            selection: AdapterSelection = AdapterSelection(config.adapters),
            dryRun: Boolean = config.dryRunDefault,
            intervalMs: Long = TimeUnit.HOURS.toMillis(config.intervalHours),
            initialDelayMs: Long = TimeUnit.MINUTES.toMillis(config.initialDelayMinutes),
            jitterMs: (Long) -> Long = { bound -> ThreadLocalRandom.current().nextLong(bound + 1) },
        ): CrawlScheduler? {
            if (!config.enabled) {
                logger.info("crawl scheduler not started: VDS_CRAWLER_ENABLED is false")
                return null
            }
            logger.info(
                "crawl scheduler armed: first tick in {} ms, then every {} ms + up to {}% jitter, dryRun={}",
                initialDelayMs,
                intervalMs,
                JITTER_SHARE,
                dryRun,
            )
            return CrawlScheduler(runner, selection, dryRun, intervalMs, initialDelayMs, jitterMs)
        }

        private val logger = LoggerFactory.getLogger(CrawlScheduler::class.java)
    }
}
