package org.a1tsign.vds.crawler.fetch

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Serialize attempts by lower-case hostname across ports, including redirects and robots.txt.
 * Measure delay from the previous request's end with monotonic time to tolerate wall-clock changes.
 */
class HostRateLimiter(
    defaultDelayMs: Long,
    private val maxTrackedHosts: Int = DEFAULT_MAX_TRACKED_HOSTS,
) {
    private val defaultDelayNanos = defaultDelayMs * NANOS_PER_MILLI

    /** Guards the registry only; never held while a request runs. */
    private val registryGuard = Mutex()

    /** Access-ordered so the eviction below drops the host untouched for longest. */
    private val hosts = LinkedHashMap<String, HostState>(16, 0.75f, true)

    /** Recheck the registry after acquiring the gate. A waiting state may have been evicted, leaving a different gate for that host. */
    suspend fun <T> withHost(host: String, block: suspend () -> T): T {
        val key = host.lowercase()
        while (true) {
            val state = stateOf(key)
            val outcome: Outcome<T>? = state.gate.withLock {
                if (!isRegistered(key, state)) {
                    null
                } else {
                    awaitTurn(state)
                    try {
                        Outcome(block())
                    } finally {
                        state.lastRequestNanos = System.nanoTime()
                    }
                }
            }
            if (outcome != null) return outcome.value
        }
    }

    /**
     * Raises [host]'s minimum interval to [delayMs] for the rest of the run (a `Crawl-delay` from
     * robots.txt, see [RobotsCache]). Never lowers it: the configured delay is a floor.
     */
    suspend fun raiseDelay(host: String, delayMs: Long) {
        val state = stateOf(host.lowercase())
        val requested = delayMs * NANOS_PER_MILLI
        if (requested > state.delayNanos) {
            state.delayNanos = requested
        }
    }

    private suspend fun awaitTurn(state: HostState) {
        while (true) {
            val last = state.lastRequestNanos ?: return
            val remaining = state.delayNanos - (System.nanoTime() - last)
            if (remaining <= 0) return
            // Round up: delay() may return a hair early, and the loop re-checks anyway, so the
            // observed gap is never shorter than the configured one.
            delay(remaining / NANOS_PER_MILLI + 1)
        }
    }

    private suspend fun stateOf(key: String): HostState = registryGuard.withLock {
        hosts.getOrPut(key) { HostState(defaultDelayNanos) }.also { evictIfNeeded(keep = key) }
    }

    private suspend fun isRegistered(key: String, state: HostState): Boolean =
        registryGuard.withLock { hosts[key] === state }

    /**
     * Evict least-recently-used idle entries only. Keep the pending host and any locked gate so no host gets two in-flight requests.
     * The registry can temporarily grow beyond the cap while requests are active.
     */
    private fun evictIfNeeded(keep: String) {
        if (hosts.size <= maxTrackedHosts) return
        val victims = hosts.entries.asSequence()
            .filter { it.key != keep && !it.value.gate.isLocked }
            .take(hosts.size - maxTrackedHosts)
            .map { it.key }
            .toList()
        victims.forEach(hosts::remove)
    }

    /** Carries [block]'s result, including `null`, apart from "the state was stale, retry". */
    private class Outcome<T>(val value: T)

    private class HostState(initialDelayNanos: Long) {
        val gate = Mutex()

        @Volatile
        var delayNanos: Long = initialDelayNanos

        @Volatile
        var lastRequestNanos: Long? = null
    }

    companion object {
        const val DEFAULT_MAX_TRACKED_HOSTS = 256
        private const val NANOS_PER_MILLI = 1_000_000L
    }
}
