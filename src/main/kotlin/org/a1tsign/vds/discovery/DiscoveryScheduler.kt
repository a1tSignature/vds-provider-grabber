package org.a1tsign.vds.discovery

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.a1tsign.vds.config.DiscoveryConfig
import org.slf4j.LoggerFactory
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** How long [DiscoveryScheduler.close] (and shutdown's join of an admin-triggered pass) lets a cancelled pass unwind. */
const val DISCOVERY_SHUTDOWN_GRACE_MS: Long = 10_000

/** Share of the interval that may be added as random jitter, so ticks do not line up. */
private const val JITTER_SHARE = 10

/**
 * The periodic discovery trigger -- `crawler/CrawlScheduler` verbatim: one **daemon**
 * single-threaded `ScheduledExecutorService` named `discovery-scheduler`, no Quartz, no cron
 * library, no system cron entry.
 *
 * **Its own thread, not `CrawlScheduler`'s.** Sharing that executor would mean `CrawlScheduler`
 * accepting an injected executor it no longer owns, and its `close()` (`shutdownNow` +
 * `awaitTermination`) would then have to stop shutting it down while the discovery ticks still
 * need it -- an ownership change in a vds-002 class and an ordering constraint in
 * `shutdownGracefully`, for one idle daemon thread. This is the one thread discovery adds.
 *
 * - **Readiness is never delayed**: the first tick is `VDS_DISCOVERY_INITIAL_DELAY_MINUTES` away
 *   (default 15), then every `VDS_DISCOVERY_INTERVAL_HOURS` (48) plus up to 10 % jitter.
 * - **A tick that finds the shared run slot taken** -- by a crawl run or another pass -- is skipped
 *   with one WARN and waits for the next tick; it never queues.
 * - **The tick does not block the timer thread**: the pass runs in this scheduler's own scope.
 * - **[close] is shutdown**: no further ticks, the in-flight pass is cancelled and given
 *   [DISCOVERY_SHUTDOWN_GRACE_MS] to publish its summary and flush its credits.
 *
 * [create] returns `null` while discovery is disabled, so a disabled discovery has no thread.
 */
class DiscoveryScheduler private constructor(
    private val runner: DiscoveryRunner,
    private val intervalMs: Long,
    private val initialDelayMs: Long,
    private val jitterMs: (Long) -> Long,
) : AutoCloseable {

    private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, THREAD_NAME).apply { isDaemon = true }
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
                        logger.warn("discovery scheduler tick failed; the scheduler will still re-arm", failure)
                    }
                    arm(intervalMs + jitterMs(intervalMs / JITTER_SHARE))
                },
                delayMs,
                TimeUnit.MILLISECONDS,
            )
        } catch (rejected: RejectedExecutionException) {
            logger.debug("discovery scheduler is shutting down, no further tick is scheduled")
        }
    }

    /** The slot is taken inside the launched coroutine, for the reason `CrawlScheduler.tick` documents. */
    private fun tick() {
        if (!accepting) return
        val job = scope.launch {
            val passId = runner.tryReserve(PassRequest())
            if (passId == null) {
                val holder = runner.slotHolder()
                logger.warn(
                    "discovery scheduler tick skipped: the run slot is held by {} {}",
                    holder?.kind ?: "another run",
                    holder?.id ?: "",
                )
                return@launch
            }
            try {
                runner.runReserved(passId)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Throwable) {
                logger.warn("discovery pass {} ended with an unhandled failure", passId, failure)
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
            val finished = runBlocking { withTimeoutOrNull(DISCOVERY_SHUTDOWN_GRACE_MS) { job.join() } }
            if (finished == null) {
                logger.warn("discovery pass did not finish within {} ms of being cancelled", DISCOVERY_SHUTDOWN_GRACE_MS)
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
        const val THREAD_NAME: String = "discovery-scheduler"

        /**
         * The scheduler, or **`null` when discovery is disabled or was not constructed** (no
         * runner) -- so "no thread while disabled" is not something a caller can forget.
         */
        fun create(
            config: DiscoveryConfig,
            runner: DiscoveryRunner?,
            intervalMs: Long = TimeUnit.HOURS.toMillis(config.intervalHours),
            initialDelayMs: Long = TimeUnit.MINUTES.toMillis(config.initialDelayMinutes),
            jitterMs: (Long) -> Long = { bound -> ThreadLocalRandom.current().nextLong(bound + 1) },
        ): DiscoveryScheduler? {
            if (!config.enabled || runner == null) {
                logger.info("discovery scheduler not started: discovery is not enabled")
                return null
            }
            logger.info(
                "discovery scheduler armed: first tick in {} ms, then every {} ms + up to {}% jitter",
                initialDelayMs,
                intervalMs,
                JITTER_SHARE,
            )
            return DiscoveryScheduler(runner, intervalMs, initialDelayMs, jitterMs)
        }

        private val logger = LoggerFactory.getLogger(DiscoveryScheduler::class.java)
    }
}
