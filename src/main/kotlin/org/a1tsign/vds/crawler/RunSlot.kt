package org.a1tsign.vds.crawler

import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

enum class RunKind { CRAWL, DISCOVERY }

data class RunHolder(val kind: RunKind, val id: UUID)

/** Crawl and discovery share one nonblocking reservation. The holder itself is the token required for release. */
class RunSlot {

    private val holder = AtomicReference<RunHolder?>(null)

    /** Takes the slot for [kind]/[id] without blocking; the returned holder is the release token, `null` when taken. */
    fun tryTake(kind: RunKind, id: UUID): RunHolder? {
        val candidate = RunHolder(kind, id)
        return if (holder.compareAndSet(null, candidate)) candidate else null
    }

    /** Release only the current token so a stale or repeated release cannot free a later reservation. */
    fun release(taken: RunHolder) {
        holder.compareAndSet(taken, null)
    }

    /** Who holds the slot right now, or `null` when it is free. A snapshot, not a decision. */
    fun holder(): RunHolder? = holder.get()
}
