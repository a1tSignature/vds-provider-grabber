package org.a1tsign.vds.discovery

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.a1tsign.vds.config.Secret
import org.slf4j.LoggerFactory
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.min

/** The one depth this build ever asks for: **1 credit**; `advanced` costs 2 (ADR-02). */
private const val SEARCH_DEPTH_BASIC = "basic"

/** `Retry-After` above this ends the pass instead of blocking it (ADR-02 decision 5). */
private const val MAX_RETRY_AFTER_MS = 60_000L

private const val READ_BUFFER_BYTES = 8 * 1024

private const val CONNECT_TIMEOUT_MS = 5_000L

/**
 * The pinned request body. **Declared without default values on purpose**: kotlinx omits a property
 * equal to its default unless `encodeDefaults` is set, and these four fields are acceptance
 * criteria, not conveniences -- they must appear on the wire whatever the `Json` instance is
 * configured to do.
 */
@Serializable
private data class TavilySearchRequest(
    val query: String,
    @SerialName("search_depth") val searchDepth: String,
    @SerialName("include_answer") val includeAnswer: Boolean,
    @SerialName("include_raw_content") val includeRawContent: Boolean,
    @SerialName("max_results") val maxResults: Int,
)

/**
 * Only the three fields vds-004 is allowed to keep. `raw_content`, `score`, `published_date`,
 * `favicon` and whatever the vendor adds next are **not declared**, so a tolerant parser drops them
 * instead of this process storing them.
 */
@Serializable
private data class TavilySearchResult(
    val url: String,
    val title: String? = null,
    val content: String? = null,
)

@Serializable
private data class TavilySearchResponse(
    val results: List<TavilySearchResult> = emptyList(),
)

@Serializable
private data class TavilyUsageResponse(
    val key: TavilyUsageFigures? = null,
    val account: TavilyUsageFigures? = null,
)

@Serializable
private data class TavilyUsageFigures(
    val usage: Int? = null,
    val limit: Int? = null,
    @SerialName("plan_usage") val planUsage: Int? = null,
    @SerialName("plan_limit") val planLimit: Int? = null,
)

/**
 * The provider's own client: a trusted JSON peer, so it must **not** go through
 * `crawler/fetch/PageFetcher` (robots.txt and a 2 s per-host delay are meaningless for an
 * authenticated API, and ADR-04 keeps the two outbound clients separate). The caller owns it and
 * closes it on shutdown exactly as `fxHttpClient` / `crawlerFetchHttpClient` are closed.
 *
 * `expectSuccess = false` is load-bearing: the status **is** the contract here (`432` is the quota
 * signal, `429` carries `Retry-After`), so the client must never turn a non-2xx into an exception
 * before [TavilySearchProvider] has seen it.
 */
fun tavilySearchHttpClient(requestTimeoutMs: Long): HttpClient = HttpClient(CIO) {
    expectSuccess = false
    install(HttpTimeout) {
        connectTimeoutMillis = CONNECT_TIMEOUT_MS
        requestTimeoutMillis = requestTimeoutMs
        socketTimeoutMillis = requestTimeoutMs
    }
    engine {
        endpoint.connectTimeout = CONNECT_TIMEOUT_MS
        endpoint.socketTimeout = requestTimeoutMs
        endpoint.connectAttempts = 1
        requestTimeout = requestTimeoutMs
    }
}

/**
 * The one shipped [SearchProvider]: `POST <endpoint>/search` with `Authorization: Bearer <key>`.
 *
 * ### What is pinned, and why each element is an acceptance criterion
 *
 * | Field | Value | Why it is not an inherited default |
 * |---|---|---|
 * | `search_depth` | `basic` | 1 credit; `advanced` costs 2 and would silently double the cost the ledger assumes |
 * | `include_answer` | `false` | it would generate an **LLM** answer, which the product owner forbade outright |
 * | `include_raw_content` | `false` | it would carry full page content -- against "URL + snippet only", and unbounded on a 192 MB heap |
 * | `max_results` | configured, 1..20 | the vendor's own maximum; validated in `loadConfig` |
 *
 * Deliberately **absent**: `include_images`, `include_domains`/`exclude_domains`, and
 * `max_age_hours` -- the last one opts into the flex tier and its `434` response.
 *
 * ### The key
 *
 * Travels in a **header**, never in a URL or a query string, and neither the key nor the header
 * value is ever logged. The failure log line carries the category, the status and the credits spent
 * so far, and nothing else; no response body and no snippet is ever logged.
 *
 * ### Status mapping, from status codes and headers only
 *
 * The vendor documents the *message text* as varying by which limit was hit, so nothing here reads
 * it: `432` → [SearchFailureKind.SEARCH_QUOTA], `401`/`403` → [SearchFailureKind.SEARCH_AUTH],
 * `429` → [SearchFailureKind.SEARCH_RATE_LIMIT] (honouring `Retry-After` **once** when it is
 * ≤ 60 s, otherwise ending the pass), `5xx` / `434` / transport failures / timeouts →
 * [SearchFailureKind.SEARCH_UNAVAILABLE], and a `200` whose body does not parse into the expected
 * shape -- including one above [maxResponseBytes] -- → [SearchFailureKind.SEARCH_MALFORMED]. An
 * undocumented status is reported as `SEARCH_UNAVAILABLE` rather than guessed at.
 *
 * ### The body cap
 *
 * [maxResponseBytes] (default 512 KiB) is counted **while streaming** and is the backstop that
 * keeps `include_raw_content: false` safe even if the vendor's default ever changes: at most one
 * 8 KiB buffer above the cap is ever held.
 *
 * ### Construction
 *
 * Explicit parameters, never `AppConfig`: task vds-004.4 owns the `VDS_DISCOVERY_*` block and task
 * vds-004.6 maps it onto this constructor, which keeps every test here a plain unit test.
 */
class TavilySearchProvider(
    endpoint: String,
    private val apiKey: Secret,
    requestTimeoutMs: Long = 10_000,
    private val maxResponseBytes: Long = 512L * 1024,
    val client: HttpClient = tavilySearchHttpClient(requestTimeoutMs),
    /** The `Retry-After` wait. Injectable so a rate-limit test does not add seconds to the suite. */
    private val sleep: suspend (Long) -> Unit = { millis -> delay(millis) },
) : SearchProvider {

    private val searchUrl = endpoint.trimEnd('/') + "/search"

    private val usageUrl = endpoint.trimEnd('/') + "/usage"

    private val requestsIssued = AtomicInteger()

    /**
     * Requests this instance has sent, i.e. credits it has most likely spent -- one per request at
     * `basic` depth. Counted for *every* request issued, including one the vendor may not have
     * charged (a `429`), because over-reporting a spend is the safe direction. `CreditLedger` stays
     * the authority for refusals; this figure exists so a failure log line can say what it cost.
     */
    val creditsSpent: Int get() = requestsIssued.get()

    override suspend fun search(query: String, limit: Int): List<SearchHit> {
        val body = json.encodeToString(
            TavilySearchRequest.serializer(),
            TavilySearchRequest(
                query = query,
                searchDepth = SEARCH_DEPTH_BASIC,
                includeAnswer = false,
                includeRawContent = false,
                maxResults = limit,
            ),
        )
        var retried = false
        while (true) {
            when (val outcome = attempt(body)) {
                is Outcome.Hits -> return outcome.hits
                is Outcome.Failed -> throw outcome.failure
                is Outcome.WaitAndRetry -> {
                    if (retried) {
                        // One retry only: a second 429 means the pass, not the query, has to end.
                        throw failure(SearchFailureKind.SEARCH_RATE_LIMIT, 429, "rate limited again after waiting")
                    }
                    retried = true
                    sleep(outcome.waitMs)
                }
            }
        }
    }

    /**
     * `GET <endpoint>/usage`, the **advisory** cross-check (ADR-02 decision 6). Three caveats, all
     * of them reasons this is not a refusal mechanism:
     *
     * 1. The vendor's window is a **billing cycle**; ours is a **calendar month**. A disagreement is
     *    therefore legitimate, and the **stricter** of the two figures wins -- which is also why the
     *    local `CreditLedger` stays authoritative for refusing a query.
     * 2. Whether this endpoint itself **costs a credit is undocumented**. If a measurement ever
     *    shows that it does, the cross-check is **dropped** rather than paid for.
     * 3. It is rate-limited to 10 calls per 10 minutes, so a caller may make **at most one per
     *    pass**.
     *
     * Any failure -- a non-200, a transport error, an unreadable body -- is `null` and one WARN line.
     */
    override suspend fun usage(): SearchUsage? = try {
        client.prepareGet(usageUrl) { header(HttpHeaders.Authorization, bearer()) }.execute { response ->
            if (response.status.value != 200) {
                logger.warn("tavily /usage answered {}; the cross-check is skipped", response.status.value)
                null
            } else {
                val figures = json.decodeFromString(
                    TavilyUsageResponse.serializer(),
                    readCapped(response.bodyAsChannel()).toString(Charsets.UTF_8),
                )
                SearchUsage(
                    usedCredits = figures.key?.usage ?: figures.account?.planUsage,
                    creditLimit = figures.key?.limit ?: figures.account?.planLimit,
                )
            }
        }
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (failure: Exception) {
        // Advisory by contract: a failed cross-check must never fail a pass.
        logger.warn("tavily /usage failed ({}); the cross-check is skipped", failure.javaClass.simpleName)
        null
    }

    private suspend fun attempt(body: String): Outcome {
        requestsIssued.incrementAndGet()
        return try {
            client.preparePost(searchUrl) {
                header(HttpHeaders.Authorization, bearer())
                contentType(ContentType.Application.Json)
                setBody(body)
            }.execute { response -> classify(response) }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (transport: Exception) {
            // Connect, DNS, TLS, read and request-timeout failures all arrive here; the exception's
            // own message is not logged, because it can carry the request URL and headers.
            Outcome.Failed(
                failure(
                    SearchFailureKind.SEARCH_UNAVAILABLE,
                    null,
                    "transport failure: ${transport.javaClass.simpleName}",
                    transport,
                ),
            )
        }
    }

    private suspend fun classify(response: HttpResponse): Outcome {
        val status = response.status.value
        if (status in 200..299) {
            val text = try {
                readCapped(response.bodyAsChannel()).toString(Charsets.UTF_8)
            } catch (oversized: ResponseTooLargeException) {
                return Outcome.Failed(
                    failure(SearchFailureKind.SEARCH_MALFORMED, status, oversized.message!!),
                )
            }
            val parsed = try {
                json.decodeFromString(TavilySearchResponse.serializer(), text)
            } catch (malformed: SerializationException) {
                // The body itself is never logged: it is a third party's text.
                return Outcome.Failed(
                    failure(SearchFailureKind.SEARCH_MALFORMED, status, "the response body did not parse", malformed),
                )
            }
            val hits = parsed.results.map { SearchHit(url = it.url, title = it.title, snippet = it.content) }
            logger.debug("tavily search returned {} hit(s), credits spent {}", hits.size, creditsSpent)
            return Outcome.Hits(hits)
        }
        if (status == 429) {
            val waitMs = retryAfterMs(response)
            if (waitMs != null && waitMs <= MAX_RETRY_AFTER_MS) {
                logger.warn(
                    "tavily search failed: kind={} status={} creditsSpent={}; retrying once in {} ms",
                    SearchFailureKind.SEARCH_RATE_LIMIT, status, creditsSpent, waitMs,
                )
                return Outcome.WaitAndRetry(waitMs)
            }
            return Outcome.Failed(
                failure(
                    SearchFailureKind.SEARCH_RATE_LIMIT,
                    status,
                    if (waitMs == null) {
                        "no usable Retry-After, so the pass ends"
                    } else {
                        "Retry-After $waitMs ms is above the $MAX_RETRY_AFTER_MS ms ceiling, so the pass ends"
                    },
                ),
            )
        }
        val kind = when {
            status == 432 -> SearchFailureKind.SEARCH_QUOTA
            status == 401 || status == 403 -> SearchFailureKind.SEARCH_AUTH
            else -> SearchFailureKind.SEARCH_UNAVAILABLE
        }
        return Outcome.Failed(failure(kind, status, "the provider refused the request"))
    }

    /**
     * `Retry-After` in milliseconds when it is given in seconds; the HTTP-date form is not used.
     * The conversion saturates: a value too large for milliseconds stays "too large" (and so ends
     * the pass above the ceiling) instead of overflowing into an immediate retry.
     */
    private fun retryAfterMs(response: HttpResponse): Long? =
        response.headers[HttpHeaders.RetryAfter]?.trim()?.toLongOrNull()?.let { seconds ->
            when {
                seconds <= 0L -> 0L
                seconds > Long.MAX_VALUE / 1_000L -> Long.MAX_VALUE
                else -> seconds * 1_000L
            }
        }

    private fun bearer(): String = "Bearer ${apiKey.reveal()}"

    /**
     * Reads [channel] **while counting**, abandoning the response at the first byte past
     * [maxResponseBytes]. Returning from `execute { }` cancels the response, so an oversized body is
     * never buffered in full.
     */
    private suspend fun readCapped(channel: ByteReadChannel): ByteArray {
        val sink = ByteArrayOutputStream(min(maxResponseBytes, INITIAL_SINK_BYTES).toInt())
        val buffer = ByteArray(READ_BUFFER_BYTES)
        var total = 0L
        while (true) {
            val read = channel.readAvailable(buffer, 0, buffer.size)
            if (read == -1) break
            total += read
            if (total > maxResponseBytes) {
                throw ResponseTooLargeException("the response body is above the $maxResponseBytes byte cap")
            }
            sink.write(buffer, 0, read)
        }
        return sink.toByteArray()
    }

    /** One WARN line per failure: the category, the status and the credits spent so far. Nothing else. */
    private fun failure(
        kind: SearchFailureKind,
        status: Int?,
        detail: String,
        cause: Throwable? = null,
    ): SearchException {
        logger.warn(
            "tavily search failed: kind={} status={} creditsSpent={} ({})",
            kind,
            status ?: "-",
            creditsSpent,
            detail,
        )
        return SearchException(kind, "$kind: $detail (HTTP ${status ?: "-"})", cause)
    }

    private sealed interface Outcome {
        data class Hits(val hits: List<SearchHit>) : Outcome
        data class WaitAndRetry(val waitMs: Long) : Outcome
        data class Failed(val failure: SearchException) : Outcome
    }

    private class ResponseTooLargeException(message: String) : Exception(message)

    private companion object {
        private const val INITIAL_SINK_BYTES = 32L * 1024

        /**
         * Tolerant on purpose, and this is the **one** place in this project where that is correct:
         * the vendor adds result fields over time and an additive field must not break a pass.
         * Unlike the source configs, which are strict because a typo there would silence a section
         * of a catalog. Its own instance, never `api/Serializers.kt`'s strict `ApiJson`.
         */
        private val json = Json {
            ignoreUnknownKeys = true
            isLenient = false
            allowSpecialFloatingPointValues = false
            coerceInputValues = false
        }

        private val logger = LoggerFactory.getLogger(TavilySearchProvider::class.java)
    }
}
