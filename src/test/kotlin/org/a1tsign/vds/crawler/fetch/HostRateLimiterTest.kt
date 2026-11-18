package org.a1tsign.vds.crawler.fetch

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Timeout
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals

/** Force eviction pressure while checking that a host keeps a single in-flight request. */
class HostRateLimiterTest {

    @Test
    @Timeout(20)
    fun `the one-in-flight gate holds for a host even while eviction churns the registry`() = runBlocking {
        // A limit of one forces state eviction between requests to distinct hosts.
        val limiter = HostRateLimiter(defaultDelayMs = 0, maxTrackedHosts = 1)
        val inFlight = ConcurrentHashMap<String, AtomicInteger>()
        val peak = ConcurrentHashMap<String, AtomicInteger>()
        val ran = AtomicInteger()

        coroutineScope {
            List(64) { index ->
                val host = if (index % 2 == 0) "a.test" else "b.test"
                launch(Dispatchers.Default) {
                    limiter.withHost(host) {
                        val concurrent = inFlight.getOrPut(host) { AtomicInteger() }.incrementAndGet()
                        peak.getOrPut(host) { AtomicInteger() }
                            .accumulateAndGet(concurrent) { a, b -> maxOf(a, b) }
                        yield()
                        inFlight.getValue(host).decrementAndGet()
                        ran.incrementAndGet()
                    }
                }
            }.joinAll()
        }

        assertEquals(64, ran.get(), "every call must run exactly once, retried gate check or not")
        assertEquals(
            setOf("a.test" to 1, "b.test" to 1),
            peak.map { (host, value) -> host to value.get() }.toSet(),
            "two requests to one host were in flight at the same time",
        )
    }
}
