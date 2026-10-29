package org.a1tsign.vds.auth

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.LinkedHashMap
import org.a1tsign.vds.api.ServiceUnavailableException
import org.a1tsign.vds.api.TooManyRequestsException

/** Bounded failure counters for the login endpoint, including atomically reserved in-flight attempts. */
class LoginThrottle(private val clock: Clock, private val maxEntries: Int = MAX_ENTRIES) {
    private data class Key(val ip: String, val username: String?)
    private data class Failure(val at: Instant)
    private data class Entry(val failures: MutableList<Failure> = mutableListOf(), var reservations: Int = 0)

    class Reservation internal constructor(internal val ip: String, internal val username: String) {
        internal var completed = false
    }

    private val entries = LinkedHashMap<Key, Entry>(16, 0.75f, true)

    @Synchronized
    fun reserve(ip: String, username: String): Reservation {
        val now = clock.instant()
        prune(now)
        val pairKey = Key(ip, username)
        val ipKey = Key(ip, null)
        if (count(pairKey, now) + reservationCount(pairKey) >= PAIR_LIMIT ||
            count(ipKey, now) + reservationCount(ipKey) >= IP_LIMIT
        ) {
            throw TooManyRequestsException(WINDOW.seconds)
        }

        val missing = listOf(pairKey, ipKey).distinct().count { it !in entries }
        makeRoom(missing)
        entries.getOrPut(pairKey) { Entry() }.reservations++
        entries.getOrPut(ipKey) { Entry() }.reservations++
        return Reservation(ip, username)
    }

    /** A failed verification consumes the reservation and records one failure against both keys. */
    @Synchronized
    fun recordFailure(reservation: Reservation) {
        complete(reservation) { pair, ip, now ->
            pair.failures.add(Failure(now))
            ip.failures.add(Failure(now))
        }
    }

    /** Success clears the pair's prior failures; the independent IP history remains. */
    @Synchronized
    fun recordSuccess(reservation: Reservation) {
        complete(reservation) { pair, _, _ -> pair.failures.clear() }
    }

    /** Releases an admission when semaphore acquisition or another non-authentication operation fails. */
    @Synchronized
    fun release(reservation: Reservation) {
        complete(reservation) { _, _, _ -> }
    }

    @get:Synchronized
    val size: Int get() = entries.size

    private fun complete(reservation: Reservation, action: (Entry, Entry, Instant) -> Unit) {
        check(!reservation.completed) { "Login throttle reservation already completed" }
        val pairKey = Key(reservation.ip, reservation.username)
        val ipKey = Key(reservation.ip, null)
        val pair = checkNotNull(entries[pairKey])
        val ip = checkNotNull(entries[ipKey])
        pair.reservations--
        ip.reservations--
        reservation.completed = true
        action(pair, ip, clock.instant())
        removeIfEmpty(pairKey)
        removeIfEmpty(ipKey)
    }

    private fun count(key: Key, now: Instant): Int = entries[key]?.failures?.count {
        Duration.between(it.at, now) < WINDOW
    } ?: 0

    private fun reservationCount(key: Key): Int = entries[key]?.reservations ?: 0

    private fun prune(now: Instant) {
        val iterator = entries.entries.iterator()
        while (iterator.hasNext()) {
            val (key, entry) = iterator.next()
            entry.failures.removeAll { Duration.between(it.at, now) >= WINDOW }
            if (entry.failures.isEmpty() && entry.reservations == 0) iterator.remove()
        }
    }

    private fun makeRoom(required: Int) {
        var excess = entries.size + required - maxEntries
        if (excess <= 0) return
        val iterator = entries.entries.iterator()
        while (iterator.hasNext() && excess > 0) {
            val entry = iterator.next().value
            if (entry.reservations == 0) {
                iterator.remove()
                excess--
            }
        }
        if (excess > 0) throw ServiceUnavailableException()
    }

    private fun removeIfEmpty(key: Key) {
        val entry = entries[key] ?: return
        if (entry.reservations == 0 && entry.failures.isEmpty()) entries.remove(key)
    }

    companion object {
        const val MAX_ENTRIES = 10_000
        private const val PAIR_LIMIT = 5
        private const val IP_LIMIT = 20
        private val WINDOW: Duration = Duration.ofMinutes(5)
    }
}
