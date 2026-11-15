package org.a1tsign.vds.crawler.enrich

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import org.a1tsign.vds.config.EnrichmentConfig
import org.a1tsign.vds.crawler.fetch.EnrichmentFetcher
import org.a1tsign.vds.crawler.write.CatalogApiException
import org.a1tsign.vds.crawler.write.CatalogProviderDetail
import org.a1tsign.vds.crawler.write.CrawlWriter
import org.a1tsign.vds.crawler.write.EnrichmentApplyWrite
import org.a1tsign.vds.crawler.write.EvidenceItemWrite
import org.a1tsign.vds.crawler.write.LegalFindingWrite
import org.a1tsign.vds.crawler.write.ValueFindingWrite
import org.a1tsign.vds.domain.EnrichmentOutcome
import java.net.URI
import java.util.concurrent.TimeUnit

/** The catalog keeps at most this much of an attempt's message. */
private const val MAX_MESSAGE = 500

private const val FORBIDDEN = 403

/**
 * The catalog refused the write itself: the account lacks `EDITOR`, so every further provider would
 * be crawled in full only to be refused the same way.
 */
class ForbiddenSubmit(cause: CatalogApiException.ClientError) : Exception(cause.message, cause)

/**
 * Enriches one provider: crawls its site under its own time budget and hands the result to the
 * [CrawlWriter]. Everything that goes wrong becomes this provider's [EnrichmentReport]; only
 * [CancellationException], [CatalogApiException.Unauthorized] and [ForbiddenSubmit] leave.
 */
class EnrichmentStep(
    config: EnrichmentConfig,
    fetcher: EnrichmentFetcher,
    /** The fetcher's cumulative page counter; a provider's pages are the difference. */
    private val pagesFetched: () -> Long,
    val providerTimeoutMs: Long = TimeUnit.MINUTES.toMillis(config.providerTimeoutMinutes),
) {
    private val crawler = SiteCrawler(fetcher, config)

    /** [detail] is the provider as just re-read from the catalog; the dry-run writer plans against it. */
    suspend fun enrich(target: EnrichmentTarget, detail: CatalogProviderDetail, writer: CrawlWriter): EnrichmentReport {
        val startedNanos = System.nanoTime()
        val pagesBefore = pagesFetched()
        fun report(outcome: EnrichmentOutcome, message: String?, valuesApplied: Int = 0) = EnrichmentReport(
            outcome = outcome,
            durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos),
            pagesFetched = (pagesFetched() - pagesBefore).toInt(),
            valuesApplied = valuesApplied,
            message = message?.take(MAX_MESSAGE),
        )
        val slug = target.slug
        val providerId = target.id

        val site = siteTarget(slug, detail.provider.websiteUrl, listOfNotNull(target.pricingUrl))
            ?: return report(EnrichmentOutcome.FAILED, "Website URL of $slug is not an absolute http(s) URL")

        val (outcome, message, findings) = try {
            when (val crawl = withTimeoutOrNull(providerTimeoutMs) { crawler.crawl(site) }) {
                null -> Triple(EnrichmentOutcome.TIMEOUT, "Timed out after ${providerTimeoutMs}ms", null)
                is CrawlOutcome.Blocked -> Triple(EnrichmentOutcome.BLOCKED, crawl.reason, null)
                is CrawlOutcome.Unreachable -> Triple(EnrichmentOutcome.UNREACHABLE, crawl.reason, null)
                CrawlOutcome.RobotsDisallowed ->
                    Triple(EnrichmentOutcome.ROBOTS_DISALLOWED, "robots.txt disallows ${site.origin}/ and ${site.pricingUrl}", null)
                is CrawlOutcome.Analysed -> if (crawl.findings.anySignal) {
                    Triple(EnrichmentOutcome.SUCCESS, summary(crawl.findings), crawl.findings)
                } else {
                    Triple(EnrichmentOutcome.NOTHING_FOUND, "No legal, payment or location signal on ${site.origin}", null)
                }
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            // Including a StackOverflowError from hostile input: one site never ends the launch.
            Triple(EnrichmentOutcome.FAILED, "${failure.javaClass.simpleName}: ${failure.message}", null)
        }

        val pages = (pagesFetched() - pagesBefore).toInt()
        val request = EnrichmentApplyWrite(
            outcome = outcome.name,
            message = message?.take(MAX_MESSAGE),
            pagesFetched = pages,
            observedFacets = target.observedFacets.map { it.name },
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
            if (failure is CatalogApiException.ClientError && failure.status == FORBIDDEN) throw ForbiddenSubmit(failure)
            report(EnrichmentOutcome.FAILED, "Submitting $outcome failed: ${failure.message}")
        }
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
