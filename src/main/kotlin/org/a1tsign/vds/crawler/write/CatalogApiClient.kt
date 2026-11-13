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
import org.a1tsign.vds.domain.SystemStatus
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Instant
import java.util.concurrent.ThreadLocalRandom
import kotlin.math.min

/**
 * Tolerant, unlike the strict `ApiJson`: an additive response field cannot break a crawl, and with
 * `explicitNulls = false` an absent key reads as `null` and a `null` field is omitted from a body.
 */
internal val CATALOG_JSON: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
}

/**
 * Timeouts and the retry budget of the loopback catalog client. Defaults are the production values.
 *
 * Same retry shape as `crawler/fetch`, but deliberately not shared: the catalog client and the
 * external fetch client share no code.
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
    // The status is the contract (200 vs 201 on a price write, 409 on a duplicate), so the client
    // must not turn a non-2xx into an exception before CatalogApiClient sees it.
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

    /** `409`: a unique index refused the row, usually because someone else created it. */
    class Conflict(val method: String, val path: String, val apiMessage: String) :
        CatalogApiException("$method $path -> 409: $apiMessage")

    /** Any other `4xx`: a data or contract bug. Not retried. */
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
     * The login was rejected, or a freshly obtained token was rejected again. Not retryable; fails
     * the whole run, not one adapter.
     */
    class Unauthorized(message: String) : CatalogApiException(message)

    private companion object {
        fun renderDetails(details: List<CatalogErrorDetail>): String =
            if (details.isEmpty()) "" else details.joinToString(prefix = " [", postfix = "]") { "${it.field}: ${it.message}" }
    }
}

data class PriceWriteOutcome(
    val price: CatalogPrice,
    /** `true` on `201` (a new history row), `false` on `200` (unchanged). */
    val appended: Boolean,
    /** The response carried the `NO_FX_RATE` warning: counted, never a failure. */
    val noFxRate: Boolean,
)

/**
 * The crawler's only access to the catalog: HTTP calls to the admin API on loopback as an `EDITOR`
 * service account, so the FX freeze, append-only prices and unique-index `409`s stay server-side.
 *
 * Auth: the token is cached until `expiresAt - 60 s` on [clock]. A `401` triggers one re-login and
 * one retry of the call; a second consecutive `401` is [CatalogApiException.Unauthorized]. The
 * password, the token and the `Authorization` header are never logged or put in a message.
 *
 * Retries: `5xx` and transport failures get up to [CatalogClientLimits.maxRetries] retries with
 * full-jitter exponential backoff; a `4xx` is never retried. The re-login retry is not charged to
 * this budget.
 *
 * There is no `DELETE` method on purpose: `EDITOR` may `DELETE /api/v1/providers/{id}`, which
 * cascades away the provider's whole price history.
 */
class CatalogApiClient(
    config: CrawlerConfig,
    private val clock: Clock,
    private val limits: CatalogClientLimits = CatalogClientLimits(),
    /** Ownership passes to this instance: [close] closes it. */
    private val client: HttpClient = catalogHttpClient(limits),
    /** Page size of the paged reads; the server's maximum is 100. */
    private val pageSize: Int = MAX_PAGE_SIZE,
    /** Maps the capped exponential bound to the actual wait in `[0, bound]`. */
    private val backoffJitter: (Long) -> Long = { bound -> ThreadLocalRandom.current().nextLong(bound + 1) },
    private val sleep: suspend (Long) -> Unit = { millis -> delay(millis) },
) : AutoCloseable {

    private val baseUrl = config.apiBaseUrl.trimEnd('/')
    private val username = config.username
    private val password = config.password

    private val tokenMutex = Mutex()
    private var cachedToken: CachedToken? = null

    /**
     * The provider whose `slug` is exactly [slug], or `null`.
     *
     * `?q=` is a substring match on name or slug, so a short slug can match many rows. After
     * [MAX_SLUG_SEARCH_PAGES] pages this returns `null` with a WARN: the reconciler then creates and
     * absorbs the `409`, which beats an unbounded walk.
     */
    suspend fun findProviderBySlug(slug: String): CatalogProviderSummary? {
        var page = 0
        while (page < MAX_SLUG_SEARCH_PAGES) {
            val result = call(
                HttpMethod.Get,
                PROVIDERS_PATH,
                CatalogPage.serializer(CatalogProviderSummary.serializer()),
                // The listing hides CANDIDATE and REJECTED by default; without them a candidate row
                // would be missed, the POST would hit uq_provider_slug, and the re-read would miss again.
                query = listOf("q" to slug, "page" to page.toString(), "size" to pageSize.toString()) +
                    SystemStatus.entries.map { "systemStatus" to it.name },
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

    /**
     * One page of providers of every `systemStatus`, ordered by `id` so consecutive pages neither
     * skip nor repeat a row that existed when the walk started. The caller bounds the walk.
     */
    suspend fun listProvidersPage(page: Int): CatalogPage<CatalogProviderSummary> =
        call(
            HttpMethod.Get,
            PROVIDERS_PATH,
            CatalogPage.serializer(CatalogProviderSummary.serializer()),
            query = listOf("page" to page.toString(), "size" to pageSize.toString(), "sort" to "id,asc") +
                SystemStatus.entries.map { "systemStatus" to it.name },
        ).value

    /** `GET /api/v1/providers/{id}`; its `tariffs` are the active ones only. */
    suspend fun getProvider(providerId: Long): CatalogProviderDetail =
        call(HttpMethod.Get, "$PROVIDERS_PATH/$providerId", CatalogProviderDetail.serializer()).value

    /**
     * Every tariff of the provider, deactivated ones included, paged to completion. Matching needs
     * it: working off [getProvider] alone would re-create a deactivated tariff and hit
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
        // A truncated list would read as complete and could flip live tariffs out of stock.
        error("Provider $providerId reports more than ${MAX_TARIFF_PAGES * pageSize} tariffs; refusing a truncated list")
    }

    suspend fun createProvider(request: ProviderWrite): CatalogProvider =
        call(
            HttpMethod.Post,
            PROVIDERS_PATH,
            CatalogProvider.serializer(),
            body = encode(ProviderWrite.serializer(), request),
        ).value

    /**
     * `POST /api/v1/providers` with an explicit `systemStatus`, used by discovery. A separate type
     * from [ProviderWrite] so the crawler's own bodies never carry `systemStatus`.
     */
    suspend fun createCandidateProvider(request: CandidateProviderWrite): CatalogProvider =
        call(
            HttpMethod.Post,
            PROVIDERS_PATH,
            CatalogProvider.serializer(),
            body = encode(CandidateProviderWrite.serializer(), request),
        ).value

    suspend fun setSystemStatus(providerId: Long, systemStatus: String): CatalogProvider =
        call(
            HttpMethod.Put,
            "$PROVIDERS_PATH/$providerId/system-status",
            CatalogProvider.serializer(),
            body = encode(SystemStatusWrite.serializer(), SystemStatusWrite(systemStatus)),
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

    /**
     * Replaces the whole set, so call it only for an observed set. Sent as an automatic write: new
     * rows are `AUTO` and removals never become dismissals.
     */
    suspend fun putPaymentMethods(providerId: Long, methods: List<PaymentMethodWrite>): List<CatalogPaymentMethod> =
        call(
            HttpMethod.Put,
            "$PROVIDERS_PATH/$providerId/payment-methods",
            ListSerializer(CatalogPaymentMethod.serializer()),
            body = encode(ListSerializer(PaymentMethodWrite.serializer()), methods),
            query = listOf("writeMode" to AUTOMATIC_WRITE_MODE),
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

    /** [TariffWrite.isActive] must be the value just read from the catalog. */
    suspend fun updateTariff(tariffId: Long, request: TariffWrite): CatalogTariff =
        call(
            HttpMethod.Put,
            "$TARIFFS_PATH/$tariffId",
            CatalogTariff.serializer(),
            body = encode(TariffWrite.serializer(), request),
        ).value

    /** Replaces the whole link set, so call it only when the tariff's locations were observed. */
    suspend fun replaceTariffLocations(tariffId: Long, locationIds: List<Long>): List<Long> =
        call(
            HttpMethod.Put,
            "$TARIFFS_PATH/$tariffId/locations",
            ListSerializer(Long.serializer()),
            body = encode(ListSerializer(Long.serializer()), locationIds),
        ).value

    /**
     * Idempotent server-side: `200` means the current row equals the submitted price and only its
     * `lastConfirmedAt` advanced; `201` means a row was appended.
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
                    // An expired or revoked token is expected: no WARN, no retry budget spent.
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
     * The cached token, logging in when there is none, it is near expiry, or it equals [rejected].
     * Comparing with [rejected] makes concurrent re-logins collapse: a token another coroutine
     * already replaced is reused.
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

    /** Full jitter: a uniform wait in `[0, min(cap, base * 2^(n-1))]`. */
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

    /** Never logs the request body (on login it carries the password) or a token. */
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

        /** The server's maximum; a bigger `size` is a `400`, not clamped. */
        private const val MAX_PAGE_SIZE = 100

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
