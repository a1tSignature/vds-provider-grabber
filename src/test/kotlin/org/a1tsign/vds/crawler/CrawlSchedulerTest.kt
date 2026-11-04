package org.a1tsign.vds.crawler

import org.a1tsign.vds.crawler.model.RunStatus
import org.a1tsign.vds.crawler.support.RecordingCrawlWriter
import org.a1tsign.vds.crawler.support.StubPageFetcher
import org.a1tsign.vds.crawler.support.closedLoopbackPort
import org.a1tsign.vds.crawler.support.sleepingAdapter
import org.a1tsign.vds.crawler.support.stubSnapshot
import org.a1tsign.vds.crawler.support.testCrawlerConfig
import org.a1tsign.vds.crawler.write.CatalogApiClient
import org.a1tsign.vds.crawler.write.Reconciler
import org.a1tsign.vds.support.MutableTestClock
import org.a1tsign.vds.support.capturingLogs
import org.junit.jupiter.api.AfterEach
import org.slf4j.event.Level
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The timer, with intervals in milliseconds instead of hours. No database and no catalog: the one
 * adapter sleeps far past every assertion, so the scheduler's own behaviour -- when it ticks, what a
 * tick does while a run is in flight, and what `close()` does to a run -- is all that is observed.
 */
class CrawlSchedulerTest {

    private val clock = MutableTestClock()

    private val clients = mutableListOf<CatalogApiClient>()

    @AfterEach
    fun closeClients() {
        clients.forEach { it.close() }
        clients.clear()
    }

    @Test
    fun `a disabled crawler gets no scheduler at all`() {
        assertNull(
            CrawlScheduler.create(testCrawlerConfig(enabled = false), runner()),
            "a disabled crawler must be impossible to start by accident -- no thread, nothing constructed",
        )
    }

    @Test
    fun `no tick fires before the configured initial delay, and the first one then starts a run`() {
        val runner = runner()
        CrawlScheduler.create(
            config = testCrawlerConfig(),
            runner = runner,
            intervalMs = 10_000,
            initialDelayMs = 400,
            jitterMs = { 0 },
        )!!.use {
            Thread.sleep(150)
            assertTrue(runner.recentRuns().isEmpty(), "the initial delay is what keeps a restart loop polite")

            awaitUntil { runner.recentRuns().isNotEmpty() }
            assertEquals(RunStatus.RUNNING, runner.recentRuns().single().status)
        }
    }

    @Test
    fun `a tick arriving while a run is in progress is skipped with a WARN and queues no second run`() {
        val runner = runner()
        val logs = capturingLogs {
            CrawlScheduler.create(
                config = testCrawlerConfig(),
                runner = runner,
                intervalMs = 50,
                initialDelayMs = 20,
                jitterMs = { 0 },
            )!!.use {
                awaitUntil { runner.isRunning() }
                Thread.sleep(300)
                assertEquals(1, runner.recentRuns().size, "several ticks must still produce exactly one run")
            }
        }

        assertTrue(
            logs.messagesAt(Level.WARN).any { it.contains("already in progress") },
            "a skipped tick is one WARN: ${logs.messagesAt(Level.WARN)}",
        )
    }

    @Test
    fun `close cancels the run in flight within the grace period and stops further ticks`() {
        val runner = runner()
        val scheduler = CrawlScheduler.create(
            config = testCrawlerConfig(),
            runner = runner,
            intervalMs = 50,
            initialDelayMs = 20,
            jitterMs = { 0 },
        )!!
        awaitUntil { runner.isRunning() }

        scheduler.close()

        assertFalse(runner.isRunning(), "the run slot must be free after a shutdown")
        assertEquals(RunStatus.CANCELLED, runner.recentRuns().single().status)
        Thread.sleep(200)
        assertEquals(1, runner.recentRuns().size, "close() stops accepting ticks")
    }

    // ---------- helpers ----------

    /**
     * A runner whose single adapter sleeps for a minute, so a run started by a tick is still in
     * flight when the assertions run. Its catalog client points at a closed port and is never
     * reached: the adapter never returns a snapshot within the test.
     */
    private fun runner(): CrawlerRunner {
        val config = testCrawlerConfig()
        val client = CatalogApiClient(
            config = config.copy(apiBaseUrl = "http://127.0.0.1:${closedLoopbackPort()}"),
            clock = clock,
        ).also { clients += it }
        val adapter = sleepingAdapter("alpha", Duration.ofMinutes(1), stubSnapshot("alpha", clock.instant()))
        return CrawlerRunner(
            config = config,
            registry = AdapterRegistry(listOf(AdapterEntry(adapter, includedByDefault = true))),
            fetcher = StubPageFetcher(),
            validator = SnapshotValidator(clock),
            reconciler = Reconciler(client),
            clock = clock,
            writerFactory = { RecordingCrawlWriter() },
            adapterTimeoutMs = 60_000,
            runTimeoutMs = 60_000,
        )
    }

    private fun awaitUntil(timeoutMs: Long = 3_000, condition: () -> Boolean) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (System.nanoTime() < deadline) {
            if (condition()) return
            Thread.sleep(5)
        }
        fail("the condition was not met within $timeoutMs ms")
    }
}
