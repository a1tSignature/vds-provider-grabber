package org.a1tsign.vds.crawler.fetch

import org.jsoup.nodes.Document

/**
 * The single outbound HTTP handle an adapter has (ADR-04): an adapter never constructs an
 * `HttpClient` and never calls `Jsoup.connect`, so the politeness and safety limits -- timeouts,
 * redirect cap, robots.txt, per-host delay, the streamed body cap, the content-type allowlist and
 * the configured `User-Agent` -- cannot be opted out of.
 *
 * **Task vds-002.3 owns the behaviour; this file owns only the names**, so the SPI (task 1), the
 * fetcher (task 3) and the adapters (task 8) compile against the same surface.
 *
 * Every method throws a [FetchException]; nothing here reaches the catalog, the database or any
 * write path.
 */
interface PageFetcher {
    /** Fetches [url] as text. */
    suspend fun get(url: String): FetchedPage

    /** Fetches [url] and parses it as HTML; the parse is jsoup's only use in this project. */
    suspend fun getHtml(url: String): Document
}

/** What an enrichment request expects back; it selects the allowed content types and the body cap. */
enum class FetchKind {
    /** HTML only, under the crawler-wide body cap. */
    PAGE,

    /** HTML plus `application/xml` and `text/xml`, under the crawler-wide body cap. */
    SITEMAP,

    /** HTML plus the JavaScript media types, under [SCRIPT_MAX_BYTES]. */
    SCRIPT,
    ;

    companion object {
        const val SCRIPT_MAX_BYTES: Long = 1024L * 1024
    }
}

/**
 * The enrichment side of the same fetcher: same client, per-host delay, `User-Agent`, timeouts,
 * redirects, retries and challenge handling as [PageFetcher], but robots.txt is honoured even when
 * `VDS_CRAWLER_RESPECT_ROBOTS=false` turns it off for tariff adapters.
 */
interface EnrichmentFetcher {
    suspend fun fetch(url: String, kind: FetchKind): FetchedPage

    /** The `Sitemap:` URLs of the robots.txt of [url]'s origin; empty when there is none. */
    suspend fun robotsSitemaps(url: String): List<String>
}

/**
 * One fetched response. [url] is what the caller asked for, [finalUrl] what answered after any
 * redirect -- an adapter that resolves relative links must use [finalUrl].
 */
data class FetchedPage(
    val url: String,
    val finalUrl: String,
    val status: Int,
    val contentType: String?,
    val body: String,
)

/**
 * Every failure a [PageFetcher] reports, as one closed hierarchy so an adapter (or the runner)
 * can tell "this page is off limits" from "this page is broken" without string matching.
 * [url] is always the request the failure belongs to; the message never carries a secret.
 *
 * Task vds-002.3 decides when each one is thrown.
 */
sealed class FetchException(
    val url: String,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {

    /** `robots.txt` of the host disallows [url] for our `User-Agent`. */
    class RobotsDisallowed(url: String) : FetchException(url, "robots.txt disallows $url")

    /** The response body exceeded [limitBytes] and was abandoned mid-stream. */
    class BodyTooLarge(url: String, val limitBytes: Long) :
        FetchException(url, "Response body of $url exceeds $limitBytes bytes")

    /** The response `Content-Type` is outside the allowlist. */
    class UnsupportedContentType(url: String, val contentType: String?) :
        FetchException(url, "Unsupported content type '${contentType ?: "<absent>"}' for $url")

    /** More than the allowed number of redirects was followed. */
    class TooManyRedirects(url: String, val redirects: Int) :
        FetchException(url, "More than $redirects redirects while fetching $url")

    /** A non-success HTTP status that retrying did not resolve. */
    class HttpStatusError(url: String, val status: Int) :
        FetchException(url, "HTTP $status for $url")

    /** The site answered with an anti-bot challenge; never retried. [reason] is one line. */
    class Challenged(url: String, val reason: String) : FetchException(url, reason)

    /** Connect/read/DNS/TLS failure, after the configured retries. */
    class TransportFailure(url: String, cause: Throwable) :
        FetchException(url, "Transport failure for $url: ${cause.javaClass.simpleName}", cause)
}
