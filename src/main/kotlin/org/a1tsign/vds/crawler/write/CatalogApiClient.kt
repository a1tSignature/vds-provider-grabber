package org.a1tsign.vds.crawler.write

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.a1tsign.vds.config.CrawlerConfig
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Instant
import java.util.concurrent.ThreadLocalRandom
import kotlin.math.min

/**
 * The crawler's tolerant `Json`: `ignoreUnknownKeys = true` so an additive field in a future
 * vds-001 response cannot break a crawl, `explicitNulls = false` so an absent key reads as `null`
 * and a `null` field is omitted from a request body (the same convention vds-001's `ApiJson` uses
 * for responses). **Not** `ApiJson`, which is strict on purpose (ADR-02).
 */
internal val CATALOG_JSON: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
}

/**
 * Timeouts and the retry budget of the loopback catalog client. Defaults are the production
 * values; they are parameters only so a test can shorten them instead of sleeping for seconds.
 *
 * The retry policy is **the same shape as `crawler/fetch`'s** (≤ 2 retries, exponential backoff
 * with full jitter, base 1 s, cap 8 s) but implemented here rather than in a shared helper: the two
 * clients share no code at all by ADR-04, `crawler/fetch` applies it to redirects/`Retry-After`/
 * robots as well, and extracting a helper would mean editing that package.
 */
data class CatalogClientLimits(
    val connectTimeoutMs: Long = 3_000,
    val requestTimeoutMs: Long = 10_000,
    val socketTimeoutMs: Long = 10_000,
    val maxRetries: Int = 2,
    val backoffBaseMs: Long = 1_000,
    val backoffCapMs: Long = 8_000,
)

/** Builds the one client [CatalogApiClient] owns; it is closed by `CatalogApiClient.close()`. */
fun catalogHttpClient(limits: CatalogClientLimits = CatalogClientLimits()): HttpClient = HttpClient(CIO) {
    // The status is the contract here (200 vs 201 on a price write, 409 on a duplicate), so the
    // client must never turn a non-2xx into an exception before this class has seen it.
    expectSuccess = false
    install(ContentNegotiation) { json(CATALOG_JSON) }
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

/** What a failed catalog call tells the reconciler. Nothing here ever carries a token or a body. */
sealed class CatalogApiException(message: String, cause: Throwable? = null) : Exception(message, cause) {

    /** `409` -- somebody else already created the row (or the unique index refused it): re-read and continue. */
    class Conflict(val method: String, val path: String, val apiMessage: String) :
        CatalogApiException("$method $path -> 409: $apiMessage")

    /** Any other `4xx` -- a data or contract bug; the run becomes `PARTIAL`, the call is not retried. */
    class ClientError(
        val status: Int,
        val method: String,
        val path: String,
        val apiMessage: String,
        val details: List<CatalogErrorDetail> = emptyList(),
    ) : CatalogApiException("$method $path -> $status: $apiMessage${renderDetails(details)}")

    /** `5xx`, after the retry budget is spent. */
    class ServerError(val status: Int, val method: String, val path: String, val apiMessage: String) :
        CatalogApiException("$method $path -> $status: $apiMessage")

    /** Connect/read/timeout failure, after the retry budget is spent. */
    class TransportFailure(val method: String, val path: String, override val cause: Throwable) :
        CatalogApiException("$method $path failed: ${cause.javaClass.simpleName}", cause)

    /**
     * The service account cannot be used: the login itself was rejected, or a freshly obtained
     * token was rejected again. **Non-retryable** -- the orchestrator turns this into a whole-run
     * failure, not a per-adapter one (spec req. 23).
     */
    class Unauthorized(message: String) : CatalogApiException(message)

    private companion object {
        fun renderDetails(details: List<CatalogErrorDetail>): String =
            if (details.isEmpty()) "" else details.joinToString(prefix = " [", postfix = "]") { "${it.field}: ${it.message}" }
    }
}

/** A price write reports both facts the run summary needs. */
data class PriceWriteOutcome(
    val price: CatalogPrice,
    /** `true` when the response was `201` (a new history row), `false` on `200` (unchanged). */
    val appended: Boolean,
    /** `true` when the response carried the `NO_FX_RATE` warning -- a counter, never a failure. */
    val noFxRate: Boolean,
)

/**
 * The crawler's **only** door into the catalog (ADR-02): every read and every write is an HTTP call
 * to the already-shipped vds-001 API on loopback, authenticated as a plain `EDITOR` service
 * account. No Exposed, no `…service`, no second write path -- the FX freeze, the append-only price
 * invariant and the `409` semantics of the unique indexes all stay in vds-001.
 *
 * ### Auth (spec req. 23)
 *
 * One login per token lifetime: the token is cached until `expiresAt − 60 s` measured on the
 * injected [clock]. A `401` on a call triggers **exactly one** re-login and one retry of that call;
 * a second consecutive `401` is [CatalogApiException.Unauthorized], which is not retried and is
 * meant to fail the whole run. The password, the token and the `Authorization` header value are
 * never logged and never appear in an exception message.
 *
 * ### Retries (spec req. 33)
 *
 * `5xx` and transport failures: ≤ 2 retries, exponential backoff with full jitter (base 1 s, cap
 * 8 s). A `4xx` is never retried -- it cannot become true by repetition. The re-login retry is
 * **not** charged to this budget (it is one extra attempt of the same call, at most once).
 *
 * ### What it deliberately cannot do
 *
 * There is no `DELETE` method and no way to send a deactivating `isActive`: [TariffWrite] takes
 * that flag from its caller and the only value the reconciler may pass is the one it just read from
 * the catalog. `EDITOR` *does* permit `DELETE /api/v1/providers/{id}`, which would cascade away a
 * provider with its whole price history -- so the absence is enforced by a grep guard over this
 * package, not by a permission.
 */
class CatalogApiClient(
    config: CrawlerConfig,
    private val clock: Clock,
    private val limits: CatalogClientLimits = CatalogClientLimits(),
    /**
     * Owned by this instance: [close] closes it. The parameter exists for substitution, not for
     * sharing -- a caller that injects a client hands over its ownership. Nothing injects one today.
     */
    private val client: HttpClient = catalogHttpClient(limits),
    /**
     * Page size of the two paged reads. 100 is vds-001's maximum; a test shrinks it to force
     * paging without inserting hundreds of rows.
     */
    private val pageSize: Int = MAX_PAGE_SIZE,
    /** Full jitter: given the capped exponential bound, the actual wait in `[0, bound]`. */
    private val backoffJitter: (Long) -> Long = { bound -> ThreadLocalRandom.current().nextLong(bound + 1) },
    /** The retry sleep. Injectable so a retry test does not add seconds to the suite. */
    private val sleep: suspend (Long) -> Unit = { millis -> delay(millis) },
) : AutoCloseable {

    private val baseUrl = config.apiBaseUrl.trimEnd('/')
    private val username = config.username
    private val password = config.password

    private val tokenMutex = Mutex()
    private var cachedToken: CachedToken? = null

    // ---------- reads (spec req. 24) ----------

    /**
     * The provider whose `slug` is exactly [slug], or `null`.
     *
     * `GET /api/v1/providers?q=` matches `lower(name) LIKE %q%` **OR** `lower(slug) LIKE %q%`, so
     * the exact match has to be picked here, and a short slug can match many rows: the search pages
     * through at most [MAX_SLUG_SEARCH_PAGES] pages. Hitting that bound returns `null` with a WARN
     * -- "not found within the bound", which the reconciler will treat as "create", possibly
     * getting a `409` it then absorbs. That is deliberate: an unbounded loop over a hostile result
     * set is worse.
     */
    suspend fun findProviderBySlug(slug: String): CatalogProviderSummary? {
        var page = 0
        while (page < MAX_SLUG_SEARCH_PAGES) {
            val result = call(
                HttpMethod.Get,
                PROVIDERS_PATH,
                CatalogPage.serializer(CatalogProviderSummary.serializer()),
                query = listOf("q" to slug, "page" to page.toString(), "size" to pageSize.toString()),
            ).value
            result.content.firstOrNull { it.slug == slug }?.let { return it }
            page++
            if (page >= result.totalPages) return null
        }
        logger.warn(
            "Provider slug '{}' was not among the first {} pages of ?q= matches; treating it as absent",
            slug,
            MAX_SLUG_SEARCH_PAGES,
        )
        return null
    }

    /** `GET /api/v1/providers/{id}` -- the detail, whose `tariffs` are the **active** ones only. */
    suspend fun getProvider(providerId: Long): CatalogProviderDetail =
        call(HttpMethod.Get, "$PROVIDERS_PATH/$providerId", CatalogProviderDetail.serializer()).value

    /**
     * **Every** tariff of the provider, deactivated ones included
     * (`GET /api/v1/providers/{id}/tariffs?activeOnly=false`, paged to completion).
     *
     * Mandatory for matching: [getProvider] returns active tariffs only, so a reconciler working
     * off the detail alone would re-create a deactivated tariff and get a `409` from
     * `uq_tariff_provider_name` forever.
     */
    suspend fun listAllTariffs(providerId: Long): List<CatalogTariff> {
        val path = "$PROVIDERS_PATH/$providerId/tariffs"
        val tariffs = mutableListOf<CatalogTariff>()
        var page = 0
        while (page < MAX_TARIFF_PAGES) {
            val result = call(
                HttpMethod.Get,
                path,
                CatalogPage.serializer(CatalogTariff.serializer()),
                query = listOf(
                    "activeOnly" to "false",
                    "page" to page.toString(),
                    "size" to pageSize.toString(),
                ),
            ).value
            tariffs += result.content
            page++
            if (page >= result.totalPages) return tariffs
        }
        // Returning a truncated list would be read as "these are all the tariffs" and could flip
        // live tariffs out of stock, so this is a hard failure rather than a WARN. Unreachable with
        // a sane catalog: the bound is MAX_TARIFF_PAGES * pageSize rows for one provider.
        error("Provider $providerId reports more than ${MAX_TARIFF_PAGES * pageSize} tariffs; refusing a truncated list")
    }

    // ---------- writes, one method per vds-001 write endpoint ----------

    suspend fun createProvider(request: ProviderWrite): CatalogProvider =
        call(
            HttpMethod.Post,
            PROVIDERS_PATH,
            CatalogProvider.serializer(),
            body = encode(ProviderWrite.serializer(), request),
        ).value

    suspend fun updateProvider(providerId: Long, request: ProviderWrite): CatalogProvider =
        call(
            HttpMethod.Put,
            "$PROVIDERS_PATH/$providerId",
            CatalogProvider.serializer(),
            body = encode(ProviderWrite.serializer(), request),
        ).value

    suspend fun putLegalInfo(providerId: Long, request: LegalInfoWrite): CatalogLegalInfo =
        call(
            HttpMethod.Put,
            "$PROVIDERS_PATH/$providerId/legal-info",
            CatalogLegalInfo.serializer(),
            body = encode(LegalInfoWrite.serializer(), request),
        ).value

    /** Replaces the whole set -- call it only for an **observed** (non-null) payment-method set. */
    suspend fun putPaymentMethods(providerId: Long, methods: List<PaymentMethodWrite>): List<CatalogPaymentMethod> =
        call(
            HttpMethod.Put,
            "$PROVIDERS_PATH/$providerId/payment-methods",
            ListSerializer(CatalogPaymentMethod.serializer()),
            body = encode(ListSerializer(PaymentMethodWrite.serializer()), methods),
        ).value

    suspend fun createLocation(providerId: Long, request: LocationWrite): CatalogLocation =
        call(
            HttpMethod.Post,
            "$PROVIDERS_PATH/$providerId/locations",
            CatalogLocation.serializer(),
            body = encode(LocationWrite.serializer(), request),
        ).value

    suspend fun updateLocation(locationId: Long, request: LocationWrite): CatalogLocation =
        call(
            HttpMethod.Put,
            "/api/v1/locations/$locationId",
            CatalogLocation.serializer(),
            body = encode(LocationWrite.serializer(), request),
        ).value

    suspend fun createTariff(providerId: Long, request: TariffWrite): CatalogTariff =
        call(
            HttpMethod.Post,
            "$PROVIDERS_PATH/$providerId/tariffs",
            CatalogTariff.serializer(),
            body = encode(TariffWrite.serializer(), request),
        ).value

    /** [TariffWrite.isActive] must be the value just read from the catalog -- see that type's doc. */
    suspend fun updateTariff(tariffId: Long, request: TariffWrite): CatalogTariff =
        call(
            HttpMethod.Put,
            "$TARIFFS_PATH/$tariffId",
            CatalogTariff.serializer(),
            body = encode(TariffWrite.serializer(), request),
        ).value

    /** Replaces the whole link set -- call it only when the tariff's locations were observed. */
    suspend fun replaceTariffLocations(tariffId: Long, locationIds: List<Long>): List<Long> =
        call(
            HttpMethod.Put,
            "$TARIFFS_PATH/$tariffId/locations",
            ListSerializer(Long.serializer()),
            body = encode(ListSerializer(Long.serializer()), locationIds),
        ).value

    /**
     * `PUT /api/v1/tariffs/{id}/price`, already idempotent on the vds-001 side: `200` means the
     * current row equals the submitted price and nothing was written, `201` means a row was
     * appended. Both facts, plus the `NO_FX_RATE` warning, are reported in [PriceWriteOutcome].
     */
    suspend fun putPrice(tariffId: Long, request: PriceWrite): PriceWriteOutcome {
        val result = call(
            HttpMethod.Put,
            "$TARIFFS_PATH/$tariffId/price",
            CatalogPrice.serializer(),
            body = encode(PriceWrite.serializer(), request),
        )
        return PriceWriteOutcome(
            price = result.value,
            appended = result.status == 201,
            noFxRate = result.value.warnings.contains(NO_FX_RATE_WARNING),
        )
    }

    override fun close() = client.close()

    // ---------- the one request path everything above goes through ----------

    private data class CallResult<T>(val status: Int, val value: T)

    private data class CachedToken(val token: String, val renewAfter: Instant)

    private fun <T> encode(serializer: kotlinx.serialization.SerializationStrategy<T>, value: T): JsonElement =
        CATALOG_JSON.encodeToJsonElement(serializer, value)

    private suspend fun <T> call(
        method: HttpMethod,
        path: String,
        deserializer: DeserializationStrategy<T>,
        query: List<Pair<String, String>> = emptyList(),
        body: JsonElement? = null,
        authorize: Boolean = true,
    ): CallResult<T> {
        var attempt = 1
        var reloginUsed = false
        while (true) {
            val bearer = if (authorize) authToken(rejected = null) else null
            val response = try {
                client.request("$baseUrl$path") {
                    this.method = method
                    query.forEach { (name, value) -> parameter(name, value) }
                    if (bearer != null) header(HttpHeaders.Authorization, "Bearer $bearer")
                    if (body != null) {
                        contentType(ContentType.Application.Json)
                        setBody(body)
                    }
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                // Class name only: the message of a connect failure can carry the whole request.
                logger.warn("{} {} attempt {} failed: {}", method.value, path, attempt, failure.javaClass.simpleName)
                if (attempt > limits.maxRetries) throw CatalogApiException.TransportFailure(method.value, path, failure)
                sleep(backoffMs(attempt))
                attempt++
                continue
            }

            val status = response.status.value
            if (status in 200..299) {
                return CallResult(status, CATALOG_JSON.decodeFromJsonElement(deserializer, response.body<JsonElement>()))
            }

            val envelope = errorEnvelope(response)
            when {
                status == UNAUTHORIZED && authorize && !reloginUsed -> {
                    // No WARN and no retry budget spent: one re-login is the designed reaction to
                    // an expired/revoked token, not a failure. Nothing about the token is logged.
                    logger.debug("{} {} -> 401; logging in again once", method.value, path)
                    reloginUsed = true
                    authToken(rejected = bearer)
                }
                status == UNAUTHORIZED -> {
                    logFailure(method, path, status, envelope)
                    throw CatalogApiException.Unauthorized(
                        if (authorize) {
                            "The crawler service account was rejected twice in a row on $path; the run cannot continue"
                        } else {
                            "Login as '${username ?: ""}' was rejected: ${envelope.message}"
                        },
                    )
                }
                status == CONFLICT -> {
                    logFailure(method, path, status, envelope)
                    throw CatalogApiException.Conflict(method.value, path, envelope.message)
                }
                status in 400..499 -> {
                    logFailure(method, path, status, envelope)
                    throw CatalogApiException.ClientError(
                        status, method.value, path, envelope.message, envelope.details.orEmpty(),
                    )
                }
                else -> {
                    logFailure(method, path, status, envelope)
                    if (attempt > limits.maxRetries) {
                        throw CatalogApiException.ServerError(status, method.value, path, envelope.message)
                    }
                    sleep(backoffMs(attempt))
                    attempt++
                }
            }
        }
    }

    /**
     * The cached bearer token, logging in when there is none, when it is within 60 s of expiry, or
     * when [rejected] is the very token the server just refused. Passing [rejected] is what makes
     * the re-login idempotent under concurrency: if another coroutine already replaced it, its
     * fresh token is reused instead of logging in a second time.
     */
    private suspend fun authToken(rejected: String?): String = tokenMutex.withLock {
        val cached = cachedToken
        if (cached != null && cached.token != rejected && clock.instant().isBefore(cached.renewAfter)) {
            return@withLock cached.token
        }
        val fresh = performLogin()
        cachedToken = fresh
        fresh.token
    }

    private suspend fun performLogin(): CachedToken {
        val user = username
        val secret = password
        if (user == null || secret == null) {
            throw CatalogApiException.Unauthorized(
                "VDS_CRAWLER_USERNAME/VDS_CRAWLER_PASSWORD are required to call the catalog API",
            )
        }
        val login = call(
            HttpMethod.Post,
            LOGIN_PATH,
            LoginResponseBody.serializer(),
            body = encode(LoginRequestBody.serializer(), LoginRequestBody(user, secret.reveal())),
            authorize = false,
        ).value
        return CachedToken(login.token, login.expiresAt.minusSeconds(TOKEN_RENEW_MARGIN_SECONDS))
    }

    /** Exponential backoff with **full** jitter: a uniform wait in `[0, min(cap, base·2^(n-1))]`. */
    private fun backoffMs(attempt: Int): Long {
        val exponential = limits.backoffBaseMs shl (attempt - 1).coerceAtMost(MAX_BACKOFF_SHIFT)
        return backoffJitter(min(exponential, limits.backoffCapMs))
    }

    /**
     * The envelope of a failed response, or a synthetic one when the body is not the envelope at
     * all (an infrastructure 401, a proxy's HTML 502).
     */
    private suspend fun errorEnvelope(response: HttpResponse): CatalogErrorEnvelope {
        val text = try {
            response.bodyAsText()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            ""
        }
        return try {
            CATALOG_JSON.decodeFromString(CatalogErrorEnvelope.serializer(), text)
        } catch (_: SerializationException) {
            CatalogErrorEnvelope(status = response.status.value, message = response.status.description)
        } catch (_: IllegalArgumentException) {
            CatalogErrorEnvelope(status = response.status.value, message = response.status.description)
        }
    }

    /**
     * Method, path, status and the envelope's `message`/`details` -- never the request body (which
     * may be large and, on the login call, carries the password) and never a token.
     */
    private fun logFailure(method: HttpMethod, path: String, status: Int, envelope: CatalogErrorEnvelope) {
        val details = envelope.details.orEmpty()
        if (details.isEmpty()) {
            logger.warn("{} {} -> {}: {}", method.value, path, status, envelope.message)
        } else {
            logger.warn(
                "{} {} -> {}: {} {}",
                method.value,
                path,
                status,
                envelope.message,
                details.joinToString(prefix = "[", postfix = "]") { "${it.field}: ${it.message}" },
            )
        }
    }

    companion object {
        const val NO_FX_RATE_WARNING = "NO_FX_RATE"

        /** vds-001's `MAX_PAGE_SIZE`; a bigger `size` is a `400`, never clamped. */
        private const val MAX_PAGE_SIZE = 100

        /** Spec req. 24: the `?q=` walk gives up after ten pages. */
        private const val MAX_SLUG_SEARCH_PAGES = 10

        private const val MAX_TARIFF_PAGES = 100
        private const val TOKEN_RENEW_MARGIN_SECONDS = 60L
        private const val MAX_BACKOFF_SHIFT = 16
        private const val UNAUTHORIZED = 401
        private const val CONFLICT = 409

        private const val PROVIDERS_PATH = "/api/v1/providers"
        private const val TARIFFS_PATH = "/api/v1/tariffs"
        private const val LOGIN_PATH = "/api/v1/auth/login"

        private val logger = LoggerFactory.getLogger(CatalogApiClient::class.java)
    }
}
