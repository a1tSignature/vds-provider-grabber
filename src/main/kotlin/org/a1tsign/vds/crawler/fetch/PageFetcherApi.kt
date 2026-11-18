package org.a1tsign.vds.crawler.fetch

import org.jsoup.nodes.Document

/** Adapter HTTP operations enforce the shared timeouts, redirects, host delay, robots rules and body limits. */
interface PageFetcher {
    suspend fun get(url: String): FetchedPage

    /** Parse the response as HTML with the final URL as its base. */
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

/** Typed fetch failures distinguish disallowed requests from unreachable or invalid responses. Messages omit secrets. */
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

    class UnsupportedContentType(url: String, val contentType: String?) :
        FetchException(url, "Unsupported content type '${contentType ?: "<absent>"}' for $url")

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
