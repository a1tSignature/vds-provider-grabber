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

const val CRAWL_SHUTDOWN_GRACE_MS: Long = 10_000

/** Add jitter so scheduler ticks do not line up. */
private const val JITTER_SHARE = 10

/**
 * First tick is delayed to protect provider sites during restart loops. Later ticks add jitter.
 * Busy ticks are skipped. [close] cancels active work and waits for a bounded cleanup.
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

    private fun arm(delayMs: Long) {
        if (!accepting) return
        try {
            executor.schedule(
                {
                    try {
                        tick()
                    } catch (failure: Throwable) {
                        // Catch tick failures so the next tick still gets armed.
                        logger.warn("crawl scheduler tick failed; the scheduler will still re-arm", failure)
                    }
                    arm(intervalMs + jitterMs(intervalMs / JITTER_SHARE))
                },
                delayMs,
                TimeUnit.MILLISECONDS,
            )
        } catch (rejected: RejectedExecutionException) {
            logger.debug("crawl scheduler is shutting down, no further tick is scheduled")
        }
    }

    /**
     * The slot is reserved **inside** the launched coroutine, not before launching it: if `close()`
     * cancels [scope] in the window between creating the coroutine and it actually starting, the
     * coroutine never runs its body and therefore never calls [CrawlerRunner.tryReserve] -- so the
     * slot is never taken and cannot be left permanently reserved with no job left to join. Reserving
     * first and launching second (the previous shape) had exactly that window.
     */
    private fun tick() {
        if (!accepting) return
        val job = scope.launch {
            val runId = runner.tryReserve(dryRun)
            if (runId == null) {
                logger.warn("crawl scheduler tick skipped: a crawl run is already in progress")
                return@launch
            }
            try {
                runner.runReserved(runId, selection)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Throwable) {
                // Catch unexpected run failures so they do not kill the timer.
                logger.warn("crawl run {} ended with an unhandled failure", runId, failure)
            }
        }
        inFlight.set(job)
    }

    override fun close() {
        accepting = false
        executor.shutdownNow()
        val job = inFlight.getAndSet(null)
        if (job != null && job.isActive) {
            job.cancel()
            // Block the shutdown thread until the run unwinds or the grace period expires.
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
        /** Returns null while crawling is disabled, without allocating a scheduler thread. */
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
            if (!config.schedulerEnabled) return null
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
