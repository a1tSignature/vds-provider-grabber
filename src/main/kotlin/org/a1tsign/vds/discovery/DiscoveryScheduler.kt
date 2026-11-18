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

private const val JITTER_SHARE = 10

/**
 * Delay the first tick so discovery does not block readiness. Skip busy ticks without queuing.
 * [close] cancels an in-flight pass and gives it a bounded wait to publish its summary and flush credits.
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

    /** Reserve inside the launched coroutine so cancellation before it starts cannot leak the slot. */
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

        /** Return null without creating a thread when discovery is disabled or no runner is available. */
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
