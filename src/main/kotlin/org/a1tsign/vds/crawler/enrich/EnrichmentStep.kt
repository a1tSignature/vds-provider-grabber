package org.a1tsign.vds.crawler.enrich

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import org.a1tsign.vds.config.EnrichmentConfig
import org.a1tsign.vds.crawler.fetch.EnrichmentFetcher
import org.a1tsign.vds.crawler.model.EnrichmentReport
import org.a1tsign.vds.crawler.model.ProviderSnapshot
import org.a1tsign.vds.crawler.write.CatalogApiClient
import org.a1tsign.vds.crawler.write.CatalogApiException
import org.a1tsign.vds.crawler.write.CatalogProviderDetail
import org.a1tsign.vds.crawler.write.CrawlWriter
import org.a1tsign.vds.crawler.write.EnrichmentApplyWrite
import org.a1tsign.vds.crawler.write.EvidenceItemWrite
import org.a1tsign.vds.crawler.write.LegalFindingWrite
import org.a1tsign.vds.crawler.write.ValueFindingWrite
import org.a1tsign.vds.domain.EnrichmentFacet
import org.a1tsign.vds.domain.EnrichmentOutcome
import java.net.URI
import java.time.Clock
import java.time.Duration
import java.util.concurrent.TimeUnit

/** The catalog keeps at most this much of an attempt's message. */
private const val MAX_MESSAGE = 500

/**
 * The enrichment step of a crawl run for one provider, after its adapter's reconcile.
 *
 * Reads the provider through [catalog] (both modes read the real catalog), decides whether it is
 * due, crawls its site under its own time budget and hands the result to the run's [CrawlWriter].
 * Everything that goes wrong becomes this provider's [EnrichmentReport]; only
 * [CancellationException] and [CatalogApiException.Unauthorized] leave, and the caller records the
 * report before letting them through.
 */
class EnrichmentStep(
    private val config: EnrichmentConfig,
    fetcher: EnrichmentFetcher,
    private val catalog: CatalogApiClient,
    private val clock: Clock,
    /** The shared fetcher's cumulative page counter; a provider's pages are the difference. */
    private val pagesFetched: () -> Long,
    val providerTimeoutMs: Long = TimeUnit.MINUTES.toMillis(config.providerTimeoutMinutes),
) {
    private val crawler = SiteCrawler(fetcher, config)

    /**
     * [snapshot] is what the adapter observed this run, `null` when it produced none.
     * [remainingRunMs] is what is left of the run's budget.
     */
    suspend fun enrich(
        slug: String,
        snapshot: ProviderSnapshot?,
        writer: CrawlWriter,
        mode: EnrichmentMode,
        remainingRunMs: Long,
    ): EnrichmentReport {
        val startedNanos = System.nanoTime()
        val pagesBefore = pagesFetched()
        fun report(outcome: EnrichmentOutcome, message: String?, valuesApplied: Int = 0) = EnrichmentReport(
            outcome = outcome,
            durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos),
            pagesFetched = (pagesFetched() - pagesBefore).toInt(),
            valuesApplied = valuesApplied,
            message = message?.take(MAX_MESSAGE),
        )

        if (mode == EnrichmentMode.SKIP) return report(EnrichmentOutcome.SKIPPED, "Skipped by the run's trigger")
        if (remainingRunMs < providerTimeoutMs) {
            return report(EnrichmentOutcome.DEFERRED, "Not started: ${remainingRunMs}ms of the run budget left, ${providerTimeoutMs}ms needed")
        }

        val providerId: Long
        val detail: CatalogProviderDetail
        try {
            providerId = catalog.findProviderBySlug(slug)?.id
                ?: return report(EnrichmentOutcome.NO_PROVIDER, "Provider $slug is not in the catalog")
            detail = catalog.getProvider(providerId)
        } catch (unauthorized: CatalogApiException.Unauthorized) {
            throw unauthorized
        } catch (failure: CatalogApiException) {
            return report(EnrichmentOutcome.FAILED, "Catalog read failed: ${failure.message}")
        }
        if (mode == EnrichmentMode.DUE && !isDue(detail)) {
            return report(EnrichmentOutcome.NOT_DUE, "Last attempt ${detail.enrichment?.lastAttemptAt}")
        }

        val target = siteTarget(slug, snapshot?.provider?.websiteUrl ?: detail.provider.websiteUrl, snapshot?.sourceUrls.orEmpty())
            ?: return report(EnrichmentOutcome.FAILED, "Website URL of $slug is not an absolute http(s) URL")

        val (outcome, message, findings) = try {
            when (val crawl = withTimeoutOrNull(providerTimeoutMs) { crawler.crawl(target) }) {
                null -> Triple(EnrichmentOutcome.TIMEOUT, "Timed out after ${providerTimeoutMs}ms", null)
                is CrawlOutcome.Blocked -> Triple(EnrichmentOutcome.BLOCKED, crawl.reason, null)
                is CrawlOutcome.Unreachable -> Triple(EnrichmentOutcome.UNREACHABLE, crawl.reason, null)
                CrawlOutcome.RobotsDisallowed ->
                    Triple(EnrichmentOutcome.ROBOTS_DISALLOWED, "robots.txt disallows ${target.origin}/ and ${target.pricingUrl}", null)
                is CrawlOutcome.Analysed -> if (crawl.findings.anySignal) {
                    Triple(EnrichmentOutcome.SUCCESS, summary(crawl.findings), crawl.findings)
                } else {
                    Triple(EnrichmentOutcome.NOTHING_FOUND, "No legal, payment or location signal on ${target.origin}", null)
                }
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            // Including a StackOverflowError from hostile input: one site never ends the run.
            Triple(EnrichmentOutcome.FAILED, "${failure.javaClass.simpleName}: ${failure.message}", null)
        }

        val pages = (pagesFetched() - pagesBefore).toInt()
        val request = EnrichmentApplyWrite(
            outcome = outcome.name,
            message = message?.take(MAX_MESSAGE),
            pagesFetched = pages,
            observedFacets = observedFacets(snapshot).map { it.name },
            legalEntityName = findings?.legalEntityName?.toWrite(),
            registrationCountry = findings?.registrationCountry?.toWrite(),
            paymentMethods = findings?.paymentMethods.orEmpty().map { it.toWrite() },
            locations = findings?.locations.orEmpty().map { it.toWrite() },
        )
        return try {
            writer.beginProvider(slug)
            val applied = writer.submitEnrichment(providerId, request, detail)
            report(outcome, message, applied)
        } catch (unauthorized: CatalogApiException.Unauthorized) {
            throw unauthorized
        } catch (failure: CatalogApiException) {
            report(EnrichmentOutcome.FAILED, "Submitting $outcome failed: ${failure.message}")
        }
    }

    private fun isDue(detail: CatalogProviderDetail): Boolean {
        val last = detail.enrichment?.lastAttemptAt ?: return true
        return !last.plus(Duration.ofHours(config.intervalHours)).isAfter(clock.instant())
    }

    /** A non-null snapshot section is a facet the adapter observes; enrichment leaves it alone. */
    private fun observedFacets(snapshot: ProviderSnapshot?): Set<EnrichmentFacet> = buildSet {
        if (snapshot == null) return@buildSet
        if (snapshot.legalInfo != null) {
            add(EnrichmentFacet.LEGAL_ENTITY_NAME)
            add(EnrichmentFacet.REGISTRATION_COUNTRY)
        }
        if (snapshot.paymentMethods != null) add(EnrichmentFacet.PAYMENT_METHOD)
        if (snapshot.locations != null) add(EnrichmentFacet.LOCATION)
    }

    private fun summary(findings: EnrichmentFindings): String =
        "entity=${findings.legalEntityName?.let { "${it.value} (${it.status})" } ?: "-"} " +
            "country=${findings.registrationCountry?.let { "${it.value} (${it.status})" } ?: "-"} " +
            "paymentMethods=${findings.paymentMethods.size} locations=${findings.locations.size}"

    private fun LegalFinding.toWrite() = LegalFindingWrite(value, status.name, basis, alternatives, evidence.map { it.toWrite() })

    private fun ValueFinding.toWrite() = ValueFindingWrite(value, confidence.name, pages, evidence.map { it.toWrite() })

    private fun EvidenceItem.toWrite() = EvidenceItemWrite(url, kind, snippet)

    companion object {
        /**
         * The site to crawl: the origin of [websiteUrl] and, as the pricing page, the first of
         * [sourceUrls] on the same site (ignoring a leading `www.`), else the origin's `/`.
         */
        internal fun siteTarget(slug: String, websiteUrl: String, sourceUrls: List<String>): SiteTarget? {
            val website = absoluteHttp(websiteUrl) ?: return null
            val origin = originOf(website)
            val pricing = sourceUrls.firstNotNullOfOrNull { url ->
                absoluteHttp(url)?.takeIf { sameSite(it, website) }?.let { originOf(it) + it.rawPath.orEmpty().ifEmpty { "/" } + (it.rawQuery?.let { q -> "?$q" } ?: "") }
            } ?: "$origin/"
            return SiteTarget(slug, origin, pricing)
        }

        private fun absoluteHttp(url: String): URI? = try {
            URI(url.trim()).takeIf { it.scheme?.lowercase() in setOf("http", "https") && !it.host.isNullOrEmpty() }
        } catch (_: Exception) {
            null
        }

        private fun originOf(uri: URI): String =
            "${uri.scheme.lowercase()}://${uri.host.lowercase()}" + if (uri.port >= 0) ":${uri.port}" else ""

        private fun sameSite(a: URI, b: URI): Boolean =
            a.host.lowercase().removePrefix("www.") == b.host.lowercase().removePrefix("www.") && a.port == b.port
    }
}
