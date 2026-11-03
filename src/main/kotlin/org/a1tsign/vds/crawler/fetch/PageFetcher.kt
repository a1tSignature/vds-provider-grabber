package org.a1tsign.vds.crawler.fetch

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.charset
import io.ktor.http.contentLength
import io.ktor.http.contentType
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import org.a1tsign.vds.config.CrawlerConfig
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.slf4j.LoggerFactory
import java.io.ByteArrayOutputStream
import java.net.URI
import java.time.Clock
import java.time.Duration
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.GZIPInputStream
import kotlin.math.min

/**
 * The limits of spec req. 13/14 that [CrawlerConfig] does not carry (it has only `hostDelayMs`,
 * `maxBodyBytes`, `userAgent` and `respectRobots`). Defaults **are** the required values; they are
 * a parameter solely so a test can shorten a timeout or a retry budget without sleeping for
 * seconds. Nothing here is per-adapter or per-call: one instance configures the whole crawler.
 */
data class FetchLimits(
    val connectTimeoutMs: Long = 5_000,
    val requestTimeoutMs: Long = 15_000,
    val socketTimeoutMs: Long = 15_000,
    val maxRedirects: Int = 5,
    val maxRetries: Int = 2,
    val backoffBaseMs: Long = 1_000,
    val backoffCapMs: Long = 8_000,
    /** A `Retry-After` above this fails the request instead of blocking the run (spec req. 14). */
    val maxRetryAfterMs: Long = 60_000,
    /** A `Crawl-delay` above this is ignored with a DEBUG line (spec req. 16). */
    val maxCrawlDelayMs: Long = 60_000,
    val robotsTimeoutMs: Long = 5_000,
)

/**
 * Builds the one `HttpClient` the crawler uses outbound (ADR-04). The caller owns it, shares it
 * across every [KtorPageFetcher] and `close()`s it on shutdown exactly as `fx/` does -- a client
 * per request would re-open a connection pool per page and defeat the per-host limits.
 *
 * Two settings here are load-bearing and must not be "simplified":
 * - `followRedirects = false`, because every hop has to be re-checked against robots.txt, the
 *   scheme allowlist and the per-host delay (spec req. 13); Ktor's own redirect plugin would do
 *   none of that.
 * - `expectSuccess = false`, because the retry/`Retry-After` policy needs the status, not an
 *   exception thrown by the client before we see it.
 */
fun crawlerHttpClient(limits: FetchLimits = FetchLimits()): HttpClient = HttpClient(CIO) {
    expectSuccess = false
    followRedirects = false
    install(HttpTimeout) {
        connectTimeoutMillis = limits.connectTimeoutMs
        requestTimeoutMillis = limits.requestTimeoutMs
        socketTimeoutMillis = limits.socketTimeoutMs
    }
    engine {
        endpoint.connectTimeout = limits.connectTimeoutMs
        endpoint.socketTimeout = limits.socketTimeoutMs
        endpoint.connectAttempts = 1
        requestTimeout = limits.requestTimeoutMs
    }
}

/**
 * The only way out of the process for a crawler adapter (spec req. 11-16, ADR-04), and therefore
 * the only place every limit has to hold:
 *
 * | Limit | Value | Source |
 * |---|---|---|
 * | connect / request / socket timeout | 5 s / 15 s / 15 s | [FetchLimits] |
 * | redirects, followed one hop at a time | ≤ 5, `http`/`https` only | [FetchLimits] |
 * | response body | `VDS_CRAWLER_MAX_BODY_BYTES`, **counted while streaming** | [CrawlerConfig] |
 * | content type | `text/html`, `application/xhtml+xml`, `application/json`, `text/plain` | here |
 * | retries | ≤ 2, connect/read failures + `408`/`429`/`5xx`, full-jitter backoff | [FetchLimits] |
 * | politeness | ≥ `VDS_CRAWLER_HOST_DELAY_MS` between, 1 in flight per host | [HostRateLimiter] |
 * | robots.txt | once per origin per run, `Disallow` → [FetchException.RobotsDisallowed] | [RobotsCache] |
 *
 * ### The body cap never trusts a header
 *
 * `Content-Length` over the cap is only a fast path; the enforcement is [readCapped], which counts
 * bytes as they arrive and abandons the response the moment the running total passes the cap. This
 * is not defensive decoration: a size guard that trusted `Content-Length` was already bypassed
 * once in this project by a response that declared a small length and sent a chunked body (see
 * [[07-knowledge/rework-log]], vds-001.6 -- `io.ktor.http.cio.parseHttpBody` prefers
 * `Transfer-Encoding: chunked` over `Content-Length` and nothing rejects the conflict). With
 * `-Xmx192m -XX:+ExitOnOutOfMemoryError`, one page buffered in full takes the API down with it.
 *
 * ### What it deliberately does not do
 *
 * No cookie jar, no proxy, no header spoofing, no anti-bot handling: the crawler identifies itself
 * honestly with the configured `User-Agent`. `Jsoup.connect` is never used -- jsoup only parses the
 * string we already read under the cap, via [Jsoup.parse] with the **final** URL as base URI.
 *
 * [clock] is used for one thing: resolving a `Retry-After` given as an HTTP date into a wait. All
 * *elapsed-time* measurement lives in [HostRateLimiter] on `System.nanoTime()`.
 */
class KtorPageFetcher(
    private val config: CrawlerConfig,
    private val clock: Clock,
    private val client: HttpClient,
    private val limits: FetchLimits = FetchLimits(),
    private val rateLimiter: HostRateLimiter = HostRateLimiter(config.hostDelayMs),
    /**
     * Full jitter: given the capped exponential bound, returns the actual wait in `[0, bound]`.
     * Injectable so a retry test is deterministic.
     */
    private val backoffJitter: (Long) -> Long = { bound -> ThreadLocalRandom.current().nextLong(bound + 1) },
    /** The retry/`Retry-After` sleep. Injectable so a retry test does not add seconds to the suite. */
    private val sleep: suspend (Long) -> Unit = { millis -> delay(millis) },
) : PageFetcher {

    private val robots = RobotsCache(config.userAgent, limits.maxCrawlDelayMs) { url -> loadRobots(url) }

    private val fetchedPages = AtomicLong()

    /**
     * Successful [get]/[getHtml] calls since this fetcher was created -- the orchestrator's
     * `pagesFetched` without parsing a log line. Redirect hops and `robots.txt` do not count: one
     * page an adapter asked for is one page.
     */
    val pagesFetched: Long get() = fetchedPages.get()

    override suspend fun get(url: String): FetchedPage {
        var target = parseTarget(url, url)
        var redirects = 0
        while (true) {
            when (val hop = fetchHop(url, target)) {
                is Hop.Done -> {
                    fetchedPages.incrementAndGet()
                    return hop.page
                }
                is Hop.Redirect -> {
                    if (redirects >= limits.maxRedirects) {
                        throw FetchException.TooManyRedirects(url, limits.maxRedirects)
                    }
                    redirects++
                    target = resolveRedirect(url, target, hop.location)
                }
            }
        }
    }

    override suspend fun getHtml(url: String): Document {
        val page = get(url)
        // The final URL, so a relative link in the document resolves against what actually answered.
        return Jsoup.parse(page.body, page.finalUrl)
    }

    /** One hop: robots first (so a disallowed URL is never requested), then the attempt loop. */
    private suspend fun fetchHop(requestedUrl: String, target: Target): Hop {
        checkRobots(requestedUrl, target)
        var attempt = 1
        while (true) {
            when (val outcome = runAttempt(requestedUrl, target, attempt)) {
                is Attempt.Ok -> return Hop.Done(outcome.page)
                is Attempt.Redirect -> return Hop.Redirect(outcome.location)
                is Attempt.Retry -> {
                    if (attempt > limits.maxRetries) throw outcome.terminal(requestedUrl)
                    sleep(outcome.retryAfterMs ?: backoffMs(attempt))
                    attempt++
                }
            }
        }
    }

    private suspend fun checkRobots(requestedUrl: String, target: Target) {
        if (!config.respectRobots) return
        val rules = robots.rulesFor(target.origin)
        rules.crawlDelayMs?.let { rateLimiter.raiseDelay(target.host, it) }
        if (!rules.allows(target.pathWithQuery)) {
            // DEBUG, not WARN: a disallowed page is a skip for the orchestrator, not a failure.
            logger.debug("robots.txt of {} disallows {}", target.origin, target.pathWithQuery)
            throw FetchException.RobotsDisallowed(target.url)
        }
    }

    private suspend fun runAttempt(requestedUrl: String, target: Target, attempt: Int): Attempt =
        try {
            rateLimiter.withHost(target.host) {
                client.prepareGet(target.url) { politeRequest(this, limits.requestTimeoutMs) }
                    .execute { response -> readResponse(requestedUrl, target, attempt, response) }
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (classified: FetchException) {
            // Already decided by readResponse (BodyTooLarge, UnsupportedContentType, a terminal
            // status): those short-circuit instead of being retried.
            throw classified
        } catch (failure: Exception) {
            // Connect, DNS, TLS, read and request-timeout failures arrive as IOException (Ktor's
            // HttpRequestTimeoutException and ConnectTimeoutException included), but a hostile or
            // broken peer also produces non-IOException failures -- a garbage status line surfaces
            // as io.ktor.http.cio.ParserException, an IllegalStateException. Those are classified
            // here too, because "every method throws a FetchException" (PageFetcherApi) must hold
            // for the component whose whole job is to absorb hostile input, and a protocol glitch
            // is as worth retrying as a read failure.
            logger.warn(
                "GET {} attempt {} failed: {}",
                target.url,
                attempt,
                failure.javaClass.simpleName,
            )
            Attempt.Retry(cause = failure)
        }

    private suspend fun readResponse(
        requestedUrl: String,
        target: Target,
        attempt: Int,
        response: HttpResponse,
    ): Attempt {
        val status = response.status.value
        if (status in 300..399) {
            val location = response.headers[HttpHeaders.Location]
                ?: return failure(requestedUrl, target, attempt, status, "redirect without a Location")
            return Attempt.Redirect(location)
        }
        if (status !in 200..299) {
            val retryAfterMs = retryAfterMs(response)
            if (retryAfterMs != null && retryAfterMs > limits.maxRetryAfterMs) {
                logger.warn(
                    "GET {} attempt {} failed: HTTP {} with Retry-After {} ms, above the {} ms ceiling",
                    target.url, attempt, status, retryAfterMs, limits.maxRetryAfterMs,
                )
                throw FetchException.HttpStatusError(requestedUrl, status)
            }
            if (status in RETRYABLE_STATUSES || status >= 500) {
                logger.warn("GET {} attempt {} failed: HTTP {}", target.url, attempt, status)
                return Attempt.Retry(status = status, retryAfterMs = retryAfterMs)
            }
            return failure(requestedUrl, target, attempt, status, "HTTP $status")
        }

        val mediaType = response.contentType()
        if (mediaType == null || mediaType.withoutParameters().toString().lowercase() !in ALLOWED_MEDIA_TYPES) {
            // Thrown before the body is touched: an unexpected payload is never read, let alone parsed.
            throw FetchException.UnsupportedContentType(requestedUrl, mediaType?.toString())
        }
        val declared = response.contentLength()
        if (declared != null && declared > config.maxBodyBytes) {
            throw FetchException.BodyTooLarge(requestedUrl, config.maxBodyBytes)
        }
        val raw = readCapped(response.bodyAsChannel(), requestedUrl)
        val bytes = decodeContentEncoding(raw, response, requestedUrl)
        val body = String(bytes, mediaType.charset() ?: Charsets.UTF_8)
        logger.debug(
            "GET {} -> {} ({} bytes, attempt {})",
            target.url, status, bytes.size, attempt,
        )
        return Attempt.Ok(
            FetchedPage(
                url = requestedUrl,
                finalUrl = target.url,
                status = status,
                contentType = mediaType.toString(),
                body = body,
            ),
        )
    }

    private fun failure(
        requestedUrl: String,
        target: Target,
        attempt: Int,
        status: Int,
        reason: String,
    ): Nothing {
        logger.warn("GET {} attempt {} failed: {}", target.url, attempt, reason)
        throw FetchException.HttpStatusError(requestedUrl, status)
    }

    /**
     * Reads [channel] into memory **while counting**, aborting at the first byte past
     * `maxBodyBytes`. Returning from `execute { }` with this exception cancels the response, so an
     * endless or oversized body is never buffered in full -- at most one [READ_BUFFER_BYTES]
     * buffer on top of the cap is ever held.
     */
    private suspend fun readCapped(channel: ByteReadChannel, url: String): ByteArray {
        val cap = config.maxBodyBytes
        val sink = ByteArrayOutputStream(min(cap, INITIAL_SINK_BYTES).toInt())
        val buffer = ByteArray(READ_BUFFER_BYTES)
        var total = 0L
        while (true) {
            val read = channel.readAvailable(buffer, 0, buffer.size)
            if (read == -1) break
            total += read
            if (total > cap) throw FetchException.BodyTooLarge(url, cap)
            sink.write(buffer, 0, read)
        }
        return sink.toByteArray()
    }

    /**
     * Decompresses a `gzip`-encoded body, because the request advertises `Accept-Encoding: gzip`
     * (spec req. 15) and no Ktor content-encoding plugin is installed -- the crawler must add no
     * dependency beyond jsoup, so the five lines below replace `ktor-client-encoding`.
     *
     * The cap is applied **again** to the decompressed stream: capping only the compressed bytes
     * would let a 2 MiB archive expand into gigabytes of heap, which is the same OOM the streamed
     * cap exists to prevent, wearing a different hat.
     */
    private fun decodeContentEncoding(raw: ByteArray, response: HttpResponse, url: String): ByteArray {
        val encoding = response.headers[HttpHeaders.ContentEncoding]?.trim()?.lowercase()
        if (encoding == null || encoding == "identity") return raw
        if (encoding != "gzip" && encoding != "x-gzip") {
            throw FetchException.UnsupportedContentType(url, "Content-Encoding: $encoding")
        }
        val cap = config.maxBodyBytes
        val sink = ByteArrayOutputStream(raw.size)
        val buffer = ByteArray(READ_BUFFER_BYTES)
        var total = 0L
        GZIPInputStream(raw.inputStream()).use { gzip ->
            while (true) {
                val read = gzip.read(buffer)
                if (read == -1) break
                total += read
                if (total > cap) throw FetchException.BodyTooLarge(url, cap)
                sink.write(buffer, 0, read)
            }
        }
        return sink.toByteArray()
    }

    /** `Retry-After` in milliseconds, in both wire forms; `null` when absent or unparsable. */
    private fun retryAfterMs(response: HttpResponse): Long? {
        val raw = response.headers[HttpHeaders.RetryAfter]?.trim() ?: return null
        raw.toLongOrNull()?.let { return (it * 1_000L).coerceAtLeast(0L) }
        return try {
            val until = ZonedDateTime.parse(raw, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()
            Duration.between(clock.instant(), until).toMillis().coerceAtLeast(0L)
        } catch (_: DateTimeParseException) {
            null
        }
    }

    /** Exponential backoff with **full** jitter: a uniform wait in `[0, min(cap, base·2^(n-1))]`. */
    private fun backoffMs(attempt: Int): Long {
        val exponential = limits.backoffBaseMs shl (attempt - 1).coerceAtMost(MAX_BACKOFF_SHIFT)
        return backoffJitter(min(exponential, limits.backoffCapMs))
    }

    /**
     * `robots.txt` for [robotsUrl]: one try, no retries, its own short timeout, but still behind
     * the per-host delay -- it is a request to somebody else's server like any other. Anything that
     * is not a `200` is `null`, i.e. "allowed" (spec req. 16).
     */
    private suspend fun loadRobots(robotsUrl: String): String? {
        val target = parseTarget(robotsUrl, robotsUrl)
        return try {
            rateLimiter.withHost(target.host) {
                client.prepareGet(robotsUrl) { politeRequest(this, limits.robotsTimeoutMs) }
                    .execute { response ->
                        if (response.status.value != 200) {
                            logger.debug("robots.txt of {} answered {}; treating as allowed", target.origin, response.status.value)
                            null
                        } else {
                            // Not content-type checked: robots.txt is served as anything in the
                            // wild. It *is* content-encoding decoded, because politeRequest
                            // advertises `Accept-Encoding: gzip` on this request too -- reading the
                            // gzip bytes as text yields zero directives, i.e. a silently cached
                            // "allow all" on every host that honours the header (nginx's default
                            // gzip_types includes text/plain).
                            val raw = readCapped(response.bodyAsChannel(), robotsUrl)
                            String(decodeContentEncoding(raw, response, robotsUrl), Charsets.UTF_8)
                        }
                    }
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            logger.debug(
                "robots.txt of {} unavailable ({}); treating as allowed",
                target.origin,
                failure.javaClass.simpleName,
            )
            null
        }
    }

    private fun politeRequest(builder: HttpRequestBuilder, requestTimeoutMs: Long) {
        builder.header(HttpHeaders.UserAgent, config.userAgent)
        builder.header(HttpHeaders.AcceptEncoding, "gzip")
        builder.header(HttpHeaders.AcceptLanguage, "en")
        builder.timeout {
            connectTimeoutMillis = limits.connectTimeoutMs
            socketTimeoutMillis = limits.socketTimeoutMs
            requestTimeoutMillis = requestTimeoutMs
        }
    }

    private fun resolveRedirect(requestedUrl: String, from: Target, location: String): Target {
        val resolved = try {
            URI(from.url).resolve(location)
        } catch (malformed: IllegalArgumentException) {
            throw FetchException.TransportFailure(requestedUrl, malformed)
        }
        return parseTarget(requestedUrl, resolved.toString())
    }

    /**
     * Validates and decomposes [url]. A scheme other than `http`/`https` (`ftp:`, `file:`,
     * `javascript:`) or a URL without a host is rejected here, which is the single place both the
     * entry URL and every redirect target pass through.
     *
     * It is reported as [FetchException.TransportFailure] with an [UnsupportedUrlException] cause:
     * the task-1 hierarchy is `sealed` and has no case for "the URL itself is unusable", and
     * widening it would force every later `when` over [FetchException] to change.
     */
    private fun parseTarget(requestedUrl: String, url: String): Target {
        val uri = try {
            URI(url)
        } catch (malformed: Exception) {
            throw FetchException.TransportFailure(requestedUrl, UnsupportedUrlException("Malformed URL: $url"))
        }
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") {
            throw FetchException.TransportFailure(
                requestedUrl,
                UnsupportedUrlException("Unsupported scheme '${uri.scheme}' in $url"),
            )
        }
        val host = uri.host?.lowercase()
            ?: throw FetchException.TransportFailure(requestedUrl, UnsupportedUrlException("No host in $url"))
        val authority = if (uri.port >= 0) "$host:${uri.port}" else host
        val path = (uri.rawPath ?: "").ifEmpty { "/" }
        return Target(
            url = url,
            host = host,
            origin = "$scheme://$authority",
            pathWithQuery = path + (uri.rawQuery?.let { "?$it" } ?: ""),
        )
    }

    private data class Target(
        val url: String,
        val host: String,
        val origin: String,
        val pathWithQuery: String,
    )

    private sealed interface Hop {
        data class Done(val page: FetchedPage) : Hop
        data class Redirect(val location: String) : Hop
    }

    private sealed interface Attempt {
        data class Ok(val page: FetchedPage) : Attempt
        data class Redirect(val location: String) : Attempt

        /** Worth another try: a transport failure or a retryable status. */
        data class Retry(
            val cause: Throwable? = null,
            val status: Int? = null,
            val retryAfterMs: Long? = null,
        ) : Attempt {
            /** What the caller sees once the retry budget is spent. */
            fun terminal(url: String): FetchException = when {
                cause != null -> FetchException.TransportFailure(url, cause)
                else -> FetchException.HttpStatusError(url, status ?: 0)
            }
        }
    }

    companion object {
        /** `Content-Type` allowlist, media type only -- a `charset` parameter is irrelevant here. */
        private val ALLOWED_MEDIA_TYPES = setOf(
            ContentType.Text.Html.toString(),
            "application/xhtml+xml",
            ContentType.Application.Json.toString(),
            ContentType.Text.Plain.toString(),
        ).map { it.lowercase() }.toSet()

        /** `408` and `429`; `5xx` is handled as a range, `4xx` otherwise is never retried. */
        private val RETRYABLE_STATUSES = setOf(408, 429)

        private const val READ_BUFFER_BYTES = 8 * 1024
        private const val INITIAL_SINK_BYTES = 32L * 1024
        private const val MAX_BACKOFF_SHIFT = 16

        private val logger = LoggerFactory.getLogger(KtorPageFetcher::class.java)
    }
}

/** Why a URL was refused before any socket was opened; see `KtorPageFetcher.parseTarget`. */
class UnsupportedUrlException(message: String) : Exception(message)
