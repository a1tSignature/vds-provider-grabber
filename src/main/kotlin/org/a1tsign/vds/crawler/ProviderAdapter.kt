package org.a1tsign.vds.crawler

import org.a1tsign.vds.crawler.fetch.PageFetcher
import org.a1tsign.vds.crawler.model.ProviderSnapshot
import java.time.Clock
import java.util.UUID

/** How an adapter obtains its data. Used for logging and reporting only, never for a decision. */
enum class SourceKind { API, HTML }

/** One adapter owns one provider and reports observed data. Reconciliation and writes belong to the runner. */
interface ProviderAdapter {
    /** Stable adapter id, `^[a-z0-9-]+$`, unique inside an [AdapterRegistry]. */
    val id: String

    /** Treat providerSlug as immutable because changing it points the adapter at another catalog row. */
    val providerSlug: String

    val sourceKind: SourceKind

    /**
     * Fetches and parses the provider's catalog. Throws on an unrecoverable failure (including
     * `FetchException`) -- the runner isolates one adapter's failure from the rest of the run.
     */
    suspend fun fetch(ctx: CrawlContext): ProviderSnapshot
}

/**
 * Everything an adapter is allowed to use, and nothing more: the shared polite [fetcher], the
 * injected [clock] (an adapter must never call `Instant.now()`), the [runId] for log correlation
 * and its own [config].
 *
 * Deliberately absent: any writer, any `DataSource`, any `ApplicationCall` -- `fetch` has no
 * reachable path through which a catalog write is possible.
 */
data class CrawlContext(
    val fetcher: PageFetcher,
    val clock: Clock,
    val runId: UUID,
    /** Adapter-specific environment settings with lower-case suffix keys. See [org.a1tsign.vds.config.crawlerAdapterConfig]. */
    val config: Map<String, String> = emptyMap(),
)
