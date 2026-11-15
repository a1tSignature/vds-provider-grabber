package org.a1tsign.vds.crawler.enrich

import org.a1tsign.vds.domain.EnrichmentFacet

/** How a run treats the enrichment cadence. The scheduler always uses [DUE]. */
enum class EnrichmentMode {
    /** Only providers whose last attempt is older than the configured interval. */
    DUE,

    /** Every provider, ignoring the cadence. */
    FORCE,

    /** No enrichment in this run. */
    SKIP,
}

/**
 * What the crawl starts from. [origin] is `scheme://host[:port]`; [pricingUrl] is an absolute URL on
 * the same site (the origin's `/` when the adapter observed none).
 */
data class SiteTarget(
    val slug: String,
    val origin: String,
    val pricingUrl: String,
)

/** One piece of evidence: the page it was found on, what kind of signal it is and a short snippet. */
data class EvidenceItem(val url: String, val kind: String, val snippet: String)

enum class LegalStatus { CONFIRMED, PRESUMED }

enum class ValueConfidence { HIGH, MEDIUM, LOW }

data class LegalFinding(
    val value: String,
    val status: LegalStatus,
    val basis: List<String>,
    val alternatives: List<String>,
    val evidence: List<EvidenceItem>,
)

data class ValueFinding(
    val value: String,
    val confidence: ValueConfidence,
    val pages: Int,
    val evidence: List<EvidenceItem>,
)

/**
 * The findings of one enrichment. Never mapped onto a `ProviderSnapshot`: a heuristic crawl cannot
 * claim that a set it saw is complete.
 */
data class EnrichmentFindings(
    val legalEntityName: LegalFinding?,
    val registrationCountry: LegalFinding?,
    /** Codes of `PaymentMethod`, strongest first. */
    val paymentMethods: List<ValueFinding>,
    /** ISO 3166-1 alpha-2 codes, strongest first. */
    val locations: List<ValueFinding>,
    /** `false` when no analysed page yielded any signal; the entity is then only the domain. */
    val anySignal: Boolean,
)

/** How a crawl of one site ended. */
sealed interface CrawlOutcome {
    data class Analysed(val findings: EnrichmentFindings) : CrawlOutcome

    /** An anti-bot challenge: no further request was made to the site. */
    data class Blocked(val reason: String) : CrawlOutcome

    /** Neither `/` nor the pricing page could be fetched; [reason] names the first failure. */
    data class Unreachable(val reason: String) : CrawlOutcome

    data object RobotsDisallowed : CrawlOutcome
}

/** The facets an adapter's snapshot already observes; enrichment does not write them. */
typealias ObservedFacets = Set<EnrichmentFacet>
