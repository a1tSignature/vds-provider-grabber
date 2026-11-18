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

data class FetchLimits(
    val connectTimeoutMs: Long = 5_000,
    val requestTimeoutMs: Long = 15_000,
    val socketTimeoutMs: Long = 15_000,
    val maxRedirects: Int = 5,
    val maxRetries: Int = 2,
    val backoffBaseMs: Long = 1_000,
    val backoffCapMs: Long = 8_000,
    /** Requests fail if Retry-After exceeds this bound. */
    val maxRetryAfterMs: Long = 60_000,
    /** Ignore Crawl-delay directives above this bound. */
    val maxCrawlDelayMs: Long = 60_000,
    val robotsTimeoutMs: Long = 5_000,
)

/**
 * Share one client and close it at shutdown. Handle redirects in the fetcher so every hop checks scheme, robots and host delay.
 * Read status codes directly for the retry policy.
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
 * Apply host delay and robots rules on each hop. Detect challenges before retrying.
 * Count streamed bytes independently of Content-Length because CIO can choose chunked framing.
 * Enrichment always honors robots.txt and uses content types and caps by [FetchKind].
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
    private val sleep: suspend (Long) -> Unit = { millis -> delay(millis) },
) : PageFetcher, EnrichmentFetcher {

    private val adapterMode = Mode(ALLOWED_MEDIA_TYPES, config.maxBodyBytes, alwaysCheckRobots = false)

    private val robots = RobotsCache(config.userAgent, limits.maxCrawlDelayMs) { url -> loadRobots(url) }

    private val fetchedPages = AtomicLong()

    /**
     * Successful [get]/[getHtml]/[fetch] calls since this fetcher was created -- the orchestrator's
     * `pagesFetched` without parsing a log line. Redirect hops and `robots.txt` do not count: one
     * page a caller asked for is one page.
     */
    val pagesFetched: Long get() = fetchedPages.get()

    override suspend fun get(url: String): FetchedPage = fetchWithin(url, adapterMode)

    override suspend fun fetch(url: String, kind: FetchKind): FetchedPage = fetchWithin(url, enrichmentModes.getValue(kind))

    override suspend fun robotsSitemaps(url: String): List<String> = robots.rulesFor(parseTarget(url, url).origin).sitemaps

    private val enrichmentModes: Map<FetchKind, Mode> = mapOf(
        FetchKind.PAGE to Mode(HTML_MEDIA_TYPES, config.maxBodyBytes, alwaysCheckRobots = true),
        FetchKind.SITEMAP to Mode(HTML_MEDIA_TYPES + SITEMAP_MEDIA_TYPES, config.maxBodyBytes, alwaysCheckRobots = true),
        FetchKind.SCRIPT to Mode(
            HTML_MEDIA_TYPES + SCRIPT_MEDIA_TYPES,
            min(config.maxBodyBytes, FetchKind.SCRIPT_MAX_BYTES),
            alwaysCheckRobots = true,
        ),
    )

    private suspend fun fetchWithin(url: String, mode: Mode): FetchedPage {
        var target = parseTarget(url, url)
        var redirects = 0
        while (true) {
            when (val hop = fetchHop(url, target, mode)) {
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

    private suspend fun fetchHop(requestedUrl: String, target: Target, mode: Mode): Hop {
        checkRobots(target, mode)
        var attempt = 1
        while (true) {
            when (val outcome = runAttempt(requestedUrl, target, attempt, mode)) {
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

    private suspend fun checkRobots(target: Target, mode: Mode) {
        if (!config.respectRobots && !mode.alwaysCheckRobots) return
        val rules = robots.rulesFor(target.origin)
        rules.crawlDelayMs?.let { rateLimiter.raiseDelay(target.host, it) }
        if (!rules.allows(target.pathWithQuery)) {
            logger.debug("robots.txt of {} disallows {}", target.origin, target.pathWithQuery)
            throw FetchException.RobotsDisallowed(target.url)
        }
    }

    private suspend fun runAttempt(requestedUrl: String, target: Target, attempt: Int, mode: Mode): Attempt =
        try {
            rateLimiter.withHost(target.host) {
                client.prepareGet(target.url) { politeRequest(this, limits.requestTimeoutMs) }
                    .execute { response -> readResponse(requestedUrl, target, attempt, response, mode) }
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (classified: FetchException) {
            // Already decided by readResponse (BodyTooLarge, UnsupportedContentType, Challenged, a
            // terminal status): those short-circuit instead of being retried.
            throw classified
        } catch (failure: Exception) {
            // CIO can report malformed HTTP as ParserException outside the IOException hierarchy. Classify it with retryable read failures.
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
        mode: Mode,
    ): Attempt {
        val status = response.status.value
        detectChallenge(requestedUrl, target, status, response)
        if (status in 300..399) {
            val location = response.headers[HttpHeaders.Location]
                ?: failure(requestedUrl, target, attempt, status, "redirect without a Location")
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
            failure(requestedUrl, target, attempt, status, "HTTP $status")
        }

        val mediaType = response.contentType()
        if (mediaType == null || mediaType.withoutParameters().toString().lowercase() !in mode.allowedMediaTypes) {
            throw FetchException.UnsupportedContentType(requestedUrl, mediaType?.toString())
        }
        val declared = response.contentLength()
        if (declared != null && declared > mode.maxBodyBytes) {
            throw FetchException.BodyTooLarge(requestedUrl, mode.maxBodyBytes)
        }
        val raw = readCapped(response.bodyAsChannel(), requestedUrl, mode.maxBodyBytes)
        val bytes = decodeContentEncoding(raw, response, requestedUrl, mode.maxBodyBytes)
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

    /** Detect challenges before retry classification. Read only a bounded body prefix for eligible error statuses. */
    private suspend fun detectChallenge(requestedUrl: String, target: Target, status: Int, response: HttpResponse) {
        val mitigated = response.headers[ChallengeMarkers.CF_MITIGATED_HEADER]?.trim()
        val reason = when {
            mitigated.equals(ChallengeMarkers.CF_MITIGATED_CHALLENGE, ignoreCase = true) ->
                "Cloudflare challenge on ${target.url} (HTTP $status)"
            status in ChallengeMarkers.CHALLENGE_STATUSES ->
                ChallengeMarkers.challengeMarkerIn(readProbe(response))
                    ?.let { marker -> "Challenge page on ${target.url} (HTTP $status, '$marker')" }
            else -> null
        } ?: return
        logger.warn("GET {} stopped: {}", target.url, reason)
        throw FetchException.Challenged(requestedUrl, reason)
    }

    /**
     * Up to [ChallengeMarkers.BODY_PROBE_BYTES] of the decoded body, never more and never an
     * error: a broken read or a truncated gzip stream yields what arrived before the cut.
     */
    private suspend fun readProbe(response: HttpResponse): String {
        val channel = response.bodyAsChannel()
        val sink = ByteArrayOutputStream()
        val buffer = ByteArray(READ_BUFFER_BYTES)
        try {
            while (sink.size() < ChallengeMarkers.BODY_PROBE_BYTES) {
                val read = channel.readAvailable(buffer, 0, min(buffer.size, ChallengeMarkers.BODY_PROBE_BYTES - sink.size()))
                if (read == -1) break
                sink.write(buffer, 0, read)
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            // A body cut off mid-read is checked as far as it arrived; the status still gets its
            // normal classification and Retry-After instead of turning into a transport failure.
        }
        val raw = sink.toByteArray()
        val encoding = response.headers[HttpHeaders.ContentEncoding]?.trim()?.lowercase()
        if (encoding != "gzip" && encoding != "x-gzip") return String(raw, Charsets.UTF_8)
        val decoded = ByteArrayOutputStream()
        try {
            GZIPInputStream(raw.inputStream()).use { gzip ->
                while (decoded.size() < ChallengeMarkers.BODY_PROBE_BYTES) {
                    val read = gzip.read(buffer)
                    if (read == -1) break
                    decoded.write(buffer, 0, read)
                }
            }
        } catch (_: java.io.IOException) {
        }
        return String(decoded.toByteArray(), Charsets.UTF_8)
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

    /** Abandon the response at the first byte over the cap so an endless response cannot accumulate in memory. */
    private suspend fun readCapped(channel: ByteReadChannel, url: String, cap: Long): ByteArray {
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

    /** Cap inflated gzip bytes separately because a small compressed response can expand past the heap budget. */
    private fun decodeContentEncoding(raw: ByteArray, response: HttpResponse, url: String, cap: Long): ByteArray {
        val encoding = response.headers[HttpHeaders.ContentEncoding]?.trim()?.lowercase()
        if (encoding == null || encoding == "identity") return raw
        if (encoding != "gzip" && encoding != "x-gzip") {
            throw FetchException.UnsupportedContentType(url, "Content-Encoding: $encoding")
        }
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

    /** Fetch robots.txt once under its own timeout and the shared host delay, without retries. */
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
                            // Accept robots.txt regardless of Content-Type. Apply content decoding because the request advertises gzip.
                            val raw = readCapped(response.bodyAsChannel(), robotsUrl, config.maxBodyBytes)
                            String(decodeContentEncoding(raw, response, robotsUrl, config.maxBodyBytes), Charsets.UTF_8)
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

    private data class Mode(
        val allowedMediaTypes: Set<String>,
        val maxBodyBytes: Long,
        val alwaysCheckRobots: Boolean,
    )

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

        data class Retry(
            val cause: Throwable? = null,
            val status: Int? = null,
            val retryAfterMs: Long? = null,
        ) : Attempt {
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

        private val HTML_MEDIA_TYPES = setOf("text/html", "application/xhtml+xml")
        private val SITEMAP_MEDIA_TYPES = setOf("application/xml", "text/xml")
        private val SCRIPT_MEDIA_TYPES = setOf("application/javascript", "text/javascript", "application/x-javascript")

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
