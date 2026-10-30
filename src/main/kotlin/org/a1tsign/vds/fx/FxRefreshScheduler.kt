package org.a1tsign.vds.fx

import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

class FxRefreshScheduler(service: FxRefreshService, refreshHours: Long) : AutoCloseable {
    private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "fx-rate-refresh").apply { isDaemon = true }
    }

    init {
        executor.scheduleAtFixedRate({
            try {
                runBlocking { service.refresh() }
            } catch (failure: Exception) {
                logger.warn("FX rate scheduler iteration failed", failure)
            }
        }, INITIAL_DELAY_MILLIS, TimeUnit.HOURS.toMillis(refreshHours), TimeUnit.MILLISECONDS)
    }

    override fun close() {
        executor.shutdownNow()
        try {
            executor.awaitTermination(5, TimeUnit.SECONDS)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private companion object {
        const val INITIAL_DELAY_MILLIS = 100L
        val logger = LoggerFactory.getLogger(FxRefreshScheduler::class.java)
    }
}
