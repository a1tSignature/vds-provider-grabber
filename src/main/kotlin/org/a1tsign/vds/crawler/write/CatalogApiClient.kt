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

/** Ignore additive response fields and read omitted optional fields as null. */
internal val CATALOG_JSON: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
}

/** Retry loopback calls with bounded jitter. Provider-site politeness is applied only to outbound fetches. */
data class CatalogClientLimits(
    val connectTimeoutMs: Long = 3_000,
    val requestTimeoutMs: Long = 10_000,
    val socketTimeoutMs: Long = 10_000,
    val maxRetries: Int = 2,
    val backoffBaseMs: Long = 1_000,
    val backoffCapMs: Long = 8_000,
)

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

    class ServerError(val status: Int, val method: String, val path: String, val apiMessage: String) :
        CatalogApiException("$method $path -> $status: $apiMessage")

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
 * Access the catalog through the loopback API to share validation, price history and FX rules with other writers.
 * Refresh a rejected token once. A second 401 or a rejected login stops the run.
 */
class CatalogApiClient(
    config: CrawlerConfig,
    private val clock: Clock,
    private val limits: CatalogClientLimits = CatalogClientLimits(),
    /** Ownership passes to this instance: [close] closes it. */
    private val client: HttpClient = catalogHttpClient(limits),
    /** Page size of the paged reads; the server's maximum is 100. */
    private val pageSize: Int = MAX_PAGE_SIZE,
    private val backoffJitter: (Long) -> Long = { bound -> ThreadLocalRandom.current().nextLong(bound + 1) },
    private val sleep: suspend (Long) -> Unit = { millis -> delay(millis) },
) : AutoCloseable {

    private val baseUrl = config.apiBaseUrl.trimEnd('/')
    private val username = config.username
    private val password = config.password

    private val tokenMutex = Mutex()
    private var cachedToken: CachedToken? = null

    /** The catalog's q filter is a substring search. Page through it and require the exact slug. */
    suspend fun findProviderBySlug(slug: String): CatalogProviderSummary? {
        var page = 0
        while (page < MAX_SLUG_SEARCH_PAGES) {
            val result = call(
                HttpMethod.Get,
                INTERNAL_PROVIDERS_PATH,
                CatalogPage.serializer(CatalogProviderSummary.serializer()),
                // The service-only index includes every status so an existing slug is reused.
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
     * One page of providers with one of [systemStatuses], ordered by `id` so consecutive pages
     * neither skip nor repeat a row that existed when the walk started. The caller bounds the walk.
     */
    suspend fun listProvidersPage(
        page: Int,
        systemStatuses: Collection<SystemStatus> = SystemStatus.entries,
    ): CatalogPage<CatalogProviderSummary> =
        call(
            HttpMethod.Get,
            INTERNAL_PROVIDERS_PATH,
            CatalogPage.serializer(CatalogProviderSummary.serializer()),
            query = listOf("page" to page.toString(), "size" to pageSize.toString(), "sort" to "id,asc") +
                systemStatuses.map { "systemStatus" to it.name },
        ).value

    /** Internal catalog read; its `tariffs` are the active ones only. */
    suspend fun getProvider(providerId: Long): CatalogProviderDetail =
        call(HttpMethod.Get, "$INTERNAL_PROVIDERS_PATH/$providerId", CatalogProviderDetail.serializer()).value

    /** Include deactivated tariffs in matching so an existing row can be reused without reactivating it. */
    suspend fun listAllTariffs(providerId: Long): List<CatalogTariff> {
        val path = "$INTERNAL_PROVIDERS_PATH/$providerId/tariffs"
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

    /** Use a separate request type so only discovery can set systemStatus on creation. */
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

    suspend fun putEnrichment(providerId: Long, request: EnrichmentApplyWrite): EnrichmentApplyResult =
        call(
            HttpMethod.Put,
            "$PROVIDERS_PATH/$providerId/enrichment",
            EnrichmentApplyResult.serializer(),
            body = encode(EnrichmentApplyWrite.serializer(), request),
        ).value

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

    /** Compare the rejected token under the refresh lock. Reuse a token another coroutine already refreshed. */
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

    /** Synthesize an error envelope for infrastructure responses that lack one. */
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
        private const val INTERNAL_PROVIDERS_PATH = "/api/v1/internal/catalog/providers"
        private const val TARIFFS_PATH = "/api/v1/tariffs"
        private const val LOGIN_PATH = "/api/v1/auth/login"

        private val logger = LoggerFactory.getLogger(CatalogApiClient::class.java)
    }
}
