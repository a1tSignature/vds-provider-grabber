package org.a1tsign.vds.crawler.fetch

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The politeness gate every outbound request passes through (spec req. 15): **at most one
 * in-flight request per host** and **at least the configured delay between two requests to the
 * same host**. It lives here, not in an adapter, because an adapter must not be able to opt out --
 * [KtorPageFetcher] is the only caller and it wraps *every* attempt, including each redirect hop
 * and each `robots.txt` fetch.
 *
 * A host is the URL's host name as written, lower-cased: `127.0.0.1` and `localhost` are two
 * different hosts here even when they resolve to the same machine, and a port is deliberately not
 * part of the key -- politeness is owed to the machine, not to the listener.
 *
 * ### Why `System.nanoTime()` and not the injected `Clock`
 *
 * The injected `Clock` is for *timestamps* (what time it was) and is routinely fixed or rewound in
 * tests; it says nothing reliable about *elapsed* time and can jump when the host's wall clock is
 * corrected. An interval is measured with the monotonic [System.nanoTime] instead. This is also
 * why `Instant.now()`/`System.currentTimeMillis()` -- both banned in main sources -- are absent:
 * the ban is about timestamps, and a monotonic nano reading is not one.
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

    /**
     * Runs [block] as the only in-flight request for [host], after waiting out whatever is left of
     * that host's minimum interval. The interval is measured from the **end** of the previous
     * request to this host, so a slow response cannot be followed immediately by the next one.
     *
     * The state is re-checked against the registry **after** the gate is acquired: between
     * [stateOf] handing it out and the gate being locked, [evictIfNeeded] may have dropped it (it
     * can only exempt entries that are already locked), after which another caller would hold a
     * *different* [HostState] for the same host and both requests would run at once -- the one
     * guarantee this class exists for. A stale state is therefore discarded and the whole thing
     * retried against whatever is registered now.
     */
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

    /** [key] is the lower-cased host name; the caller normalises it. */
    private suspend fun stateOf(key: String): HostState = registryGuard.withLock {
        hosts.getOrPut(key) { HostState(defaultDelayNanos) }.also { evictIfNeeded(keep = key) }
    }

    /** Still the state registered for [key], i.e. not evicted while we waited for its gate. */
    private suspend fun isRegistered(key: String, state: HostState): Boolean =
        registryGuard.withLock { hosts[key] === state }

    /**
     * Bounds the registry at [maxTrackedHosts] entries (256 by default -- a crawl run visits a
     * handful of provider hosts, so this is never reached in practice; it exists so a run against
     * a page that redirects through generated host names cannot grow the map for the life of the
     * process). The least recently used entry is dropped, which at worst forgets one host's last
     * request time and its raised `Crawl-delay`. An entry whose request is in flight is skipped:
     * dropping it would hand out a second [Mutex] for the same host and break the one-in-flight
     * guarantee, so the map is allowed to exceed the bound by the number of concurrent requests
     * rather than lose a live lock. [keep] -- the host the caller is about to use -- is exempt for
     * the same reason: it is not locked *yet*. That exemption only covers the caller that triggered
     * this eviction, which is why [withHost] re-checks its state after locking the gate instead of
     * trusting that it survived.
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
