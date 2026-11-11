package org.a1tsign.vds.discovery.model

import java.time.Instant
import java.util.UUID

/** How many pass summaries the in-memory history keeps. Nothing is persisted, as with crawl runs. */
const val PASS_HISTORY_SIZE: Int = 20

/** A dry run's plan is capped, like the crawler's `plannedOperations`. */
const val MAX_PLANNED_CANDIDATES: Int = 200

/**
 * `RUNNING` while in flight; `COMPLETED` when the pass ended by itself -- normally, or stopped early
 * with a [DiscoveryPassSummary.failureReason]; `CANCELLED` when its time budget expired or the
 * process is shutting down (the `CrawlerRunner` precedent: a run cut short by the service account
 * is `COMPLETED`, one cut short by its budget is `CANCELLED`).
 */
enum class PassStatus { RUNNING, COMPLETED, CANCELLED }

/** Why one search hit did not become a candidate. Every rejection is counted under exactly one. */
enum class RejectionReason {
    /** The normalized domain is on the configured denylist (or is a subdomain of an entry). */
    DENYLIST,

    /** Neither title nor snippet contains any of `VDS_DISCOVERY_REQUIRED_KEYWORDS`. */
    KEYWORD,

    /** Not an absolute http(s) URL with a dotted, non-IP host. */
    MALFORMED_URL,

    /** The domain-derived slug would exceed 64 characters or fails the slug charset: skipped, never truncated. */
    SLUG_TOO_LONG,

    /** Already in the catalog (any `systemStatus`), already created by this pass, or a `409` on create. */
    ALREADY_KNOWN,
}

/** Why a pass stopped before running through its queries. `null` on a pass that did not stop early. */
enum class PassFailureReason {
    /** `VDS_DISCOVERY_SEARCH_PROVIDER=none`: there is nothing to search with. */
    NO_SEARCH_PROVIDER,

    /** The credit ledger refused the pass or a query (cap, minimum interval, unreadable/unwritable state). */
    BUDGET,

    /** The search vendor reports the plan's credits as used up. */
    SEARCH_QUOTA,

    /** The search vendor rejected the key; every further query would be rejected too. */
    SEARCH_AUTH,

    /** The catalog holds more providers than `VDS_DISCOVERY_MAX_KNOWN_PROVIDERS`; a truncated dedup set is refused. */
    KNOWN_SET_TOO_LARGE,

    /** The catalog could not be read (or written to) at all. */
    CATALOG_UNAVAILABLE,

    /** The crawler service account was rejected by the catalog. */
    CATALOG_AUTH,

    /** `VDS_DISCOVERY_PASS_TIMEOUT_MINUTES` expired. */
    TIMEOUT,

    /** The process is shutting down. */
    SHUTDOWN,

    /** Anything else -- a bug; the stack trace is at DEBUG. */
    INTERNAL_ERROR,
}

/** One candidate a dry run *would* have created. */
data class PlannedCandidate(
    val slug: String,
    val name: String,
    val websiteUrl: String,
    val query: String,
    val resultUrl: String,
)

/**
 * One discovery pass, as served by `GET /api/v1/admin/discovery/passes`. Bounded by construction:
 * [plannedCandidates] holds at most [MAX_PLANNED_CANDIDATES] entries and the history at most
 * [PASS_HISTORY_SIZE] summaries.
 */
data class DiscoveryPassSummary(
    val passId: UUID,
    val startedAt: Instant,
    val finishedAt: Instant? = null,
    val status: PassStatus,
    val dryRun: Boolean,
    val queriesIssued: Int = 0,
    /** Queries that failed with a search category that does not end the pass. */
    val queriesFailed: Int = 0,
    val creditsSpent: Int = 0,
    /** The **local** ledger's figure: `VDS_DISCOVERY_MAX_CREDITS_PER_MONTH` minus this month's spend. */
    val creditsRemainingThisMonth: Int? = null,
    /** The vendor's own figure; `null` when the `/usage` cross-check was skipped or failed. */
    val vendorCreditsRemaining: Int? = null,
    val resultsSeen: Int = 0,
    /** Every reason is present, zero included. */
    val resultsRejected: Map<RejectionReason, Int> = RejectionReason.entries.associateWith { 0 },
    val knownProviders: Int = 0,
    val candidatesCreated: Int = 0,
    /** Valid new domains beyond `VDS_DISCOVERY_MAX_NEW_CANDIDATES_PER_PASS`; found again by a later pass. */
    val candidatesDeferred: Int = 0,
    /** Candidates whose create call failed (a `4xx` other than `409`, or the catalog unavailable). */
    val candidatesFailed: Int = 0,
    val plannedCandidates: List<PlannedCandidate> = emptyList(),
    val failureReason: PassFailureReason? = null,
    val message: String? = null,
)
