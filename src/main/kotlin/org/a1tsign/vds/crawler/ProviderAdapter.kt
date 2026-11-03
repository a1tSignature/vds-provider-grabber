package org.a1tsign.vds.crawler

import org.a1tsign.vds.crawler.fetch.PageFetcher
import org.a1tsign.vds.crawler.model.ProviderSnapshot
import java.time.Clock
import java.util.UUID

/** How an adapter obtains its data. Used for logging and reporting only, never for a decision. */
enum class SourceKind { API, HTML }

/**
 * The crawler plugin SPI: one adapter owns exactly one provider and returns a read-only
 * [ProviderSnapshot] of what it observed. Reconciling that snapshot into the catalog happens
 * elsewhere, through the vds-001 admin HTTP API (ADR-02), so an adapter has **no** write path at
 * all -- see [CrawlContext] for the complete set of capabilities it is given.
 */
interface ProviderAdapter {
    /** Stable adapter id, `^[a-z0-9-]+$`, unique inside an [AdapterRegistry]. */
    val id: String

    /**
     * The `provider.slug` this adapter owns, same charset as [id]. Immutable: changing it would
     * re-point the adapter at a different catalog row.
     */
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
    /**
     * The adapter's own `VDS_CRAWLER_ADAPTER_<ID>_*` settings, keyed by the lower-cased suffix;
     * built by `org.a1tsign.vds.config.crawlerAdapterConfig`.
     */
    val config: Map<String, String> = emptyMap(),
)
