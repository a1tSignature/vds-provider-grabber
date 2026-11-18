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

/** Basic depth costs one credit, the unit used by the ledger. */
private const val SEARCH_DEPTH_BASIC = "basic"

/** A longer Retry-After ends the pass to bound wait time. */
private const val MAX_RETRY_AFTER_MS = 60_000L

private const val READ_BUFFER_BYTES = 8 * 1024

private const val CONNECT_TIMEOUT_MS = 5_000L

/** The vendor's documented maximum for `exclude_domains`. */
const val TAVILY_MAX_EXCLUDED_DOMAINS: Int = 150

/** Keep these request fields explicit because serialization can omit values equal to defaults. */
@Serializable
private data class TavilySearchRequest(
    val query: String,
    @SerialName("search_depth") val searchDepth: String,
    @SerialName("include_answer") val includeAnswer: Boolean,
    @SerialName("include_raw_content") val includeRawContent: Boolean,
    @SerialName("max_results") val maxResults: Int,
    /** Left out of the body when `null`, i.e. when nothing is excluded. */
    @SerialName("exclude_domains") val excludeDomains: List<String>?,
)

/** Retain only the URL, title and snippet. Ignore other fields the vendor adds. */
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

/** Use a dedicated JSON client so provider-site delays and robots rules do not apply to the authenticated search API. */
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
 * Use basic search at one credit per request, without generated answers or raw page content.
 * Classify failures by HTTP status and headers. Honor an eligible rate-limit retry once and cap response bytes while streaming.
 * Send the key in the authorization header and keep it and all raw response content out of logs.
 */
class TavilySearchProvider(
    endpoint: String,
    private val apiKey: Secret,
    requestTimeoutMs: Long = 10_000,
    private val maxResponseBytes: Long = 512L * 1024,
    val client: HttpClient = tavilySearchHttpClient(requestTimeoutMs),
    /** Canonical domains; only the first [TAVILY_MAX_EXCLUDED_DOMAINS] are sent, the rest is filtered locally. */
    excludeDomains: List<String> = emptyList(),
    private val sleep: suspend (Long) -> Unit = { millis -> delay(millis) },
) : SearchProvider {

    private val searchUrl = endpoint.trimEnd('/') + "/search"

    private val excluded: List<String>? = excludeDomains.distinct().take(TAVILY_MAX_EXCLUDED_DOMAINS).ifEmpty { null }

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
                excludeDomains = excluded,
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
     * Vendor usage is advisory because its billing cycle can differ from the ledger's UTC month. Use the stricter available figure.
     * Call at most once per pass due to the usage endpoint's own rate limit. Return null on failure.
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

    /** Parse Retry-After seconds into milliseconds with saturation so overflow cannot produce a short wait. */
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

        /** Ignore additive vendor fields so an upstream schema extension cannot break a pass. */
        private val json = Json {
            ignoreUnknownKeys = true
            isLenient = false
            allowSpecialFloatingPointValues = false
            coerceInputValues = false
            explicitNulls = false
        }

        private val logger = LoggerFactory.getLogger(TavilySearchProvider::class.java)
    }
}
