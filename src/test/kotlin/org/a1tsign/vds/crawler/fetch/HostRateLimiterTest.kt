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

/**
 * The one guarantee of [HostRateLimiter] that eviction can break: **at most one in-flight request
 * per host**. The delay side is asserted end-to-end in [PageFetcherTest] against the fixture
 * server's recorded arrival times; what is left here is the registry bound, whose eviction used to
 * be able to hand out a second [HostRateLimiter] gate for a host whose first gate was not locked
 * yet.
 */
class HostRateLimiterTest {

    @Test
    @Timeout(20)
    fun `the one-in-flight gate holds for a host even while eviction churns the registry`() = runBlocking {
        // maxTrackedHosts = 1 with two hosts in flight means every call evicts the other host's
        // state, so the registry churns on every single call instead of after 256 distinct hosts.
        // The race itself is a narrow interleaving this test does not claim to reproduce; what it
        // does pin is the behaviour of the re-check that fixes it, under the churn it reacts to:
        // every call runs exactly once (no call lost or run twice by the retry), the gate still
        // serialises each host, and -- the timeout is part of the assertion -- the retry does not
        // livelock while the two hosts evict each other.
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
