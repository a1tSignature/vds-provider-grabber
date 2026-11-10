package org.a1tsign.vds.crawler

import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/** What can hold the [RunSlot]: a crawl run (vds-002) or a discovery pass (vds-004). */
enum class RunKind { CRAWL, DISCOVERY }

/** The current holder of the [RunSlot]: its kind and its run/pass id. */
data class RunHolder(val kind: RunKind, val id: UUID)

/**
 * The one process-wide "something heavy is in flight" slot, shared by `CrawlerRunner` and the
 * discovery pass of vds-004: **a crawl run and a discovery pass are never in flight at the same
 * time**, which is what keeps two unmeasured memory profiles from stacking on a box whose crawl peak
 * already sits a few MB under its ceiling.
 *
 * Non-blocking by design -- [tryTake] either takes it or says who has it, and the caller answers
 * `409` / skips a tick with a WARN. Nothing ever waits for it.
 *
 * It deliberately knows nothing about summaries or history: publishing a `RUNNING` summary is each
 * runner's own business, which is why this was extracted out of `CrawlerRunner.tryReserve`.
 */
class RunSlot {

    private val holder = AtomicReference<RunHolder?>(null)

    /** Takes the slot for [kind]/[id] without blocking; the returned holder is the release token, `null` when taken. */
    fun tryTake(kind: RunKind, id: UUID): RunHolder? {
        val candidate = RunHolder(kind, id)
        return if (holder.compareAndSet(null, candidate)) candidate else null
    }

    /**
     * Releases the slot **only if [taken] still holds it** -- a stale or double release can never
     * free a slot somebody else took in the meantime.
     */
    fun release(taken: RunHolder) {
        holder.compareAndSet(taken, null)
    }

    /** Who holds the slot right now, or `null` when it is free. A snapshot, not a decision. */
    fun holder(): RunHolder? = holder.get()
}
