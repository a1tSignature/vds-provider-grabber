package org.a1tsign.vds.crawler.enrich

import org.a1tsign.vds.domain.EnrichmentFacet
import org.a1tsign.vds.domain.EnrichmentOutcome

/**
 * A provider as the enrichment queue holds it. [pricingUrl] comes from a declarative source of the
 * same slug, if any; [observedFacets] are the facets an enabled source of that slug writes itself.
 */
data class EnrichmentTarget(
    val id: Long,
    val slug: String,
    val systemStatus: String,
    val websiteUrl: String,
    val pricingUrl: String?,
    val observedFacets: Set<EnrichmentFacet>,
)

/**
 * What enriching one provider did. [valuesApplied] counts fields and rows the catalog changed; in a
 * dry run it is the intended count, computed against the catalog read.
 */
data class EnrichmentReport(
    val outcome: EnrichmentOutcome,
    val durationMs: Long = 0,
    val pagesFetched: Int = 0,
    val valuesApplied: Int = 0,
    /** One line: no page body, no secret. */
    val message: String? = null,
)

/**
 * What the crawl starts from. [origin] is `scheme://host[:port]`; [pricingUrl] is an absolute URL on
 * the same site (the origin's `/` when the adapter observed none).
 */
data class SiteTarget(
    val slug: String,
    val origin: String,
    val pricingUrl: String,
)

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

/** Heuristic findings are never treated as a complete snapshot. */
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

sealed interface CrawlOutcome {
    data class Analysed(val findings: EnrichmentFindings) : CrawlOutcome

    /** An anti-bot challenge: no further request was made to the site. */
    data class Blocked(val reason: String) : CrawlOutcome

    /** Neither `/` nor the pricing page could be fetched; [reason] names the first failure. */
    data class Unreachable(val reason: String) : CrawlOutcome

    data object RobotsDisallowed : CrawlOutcome
}
