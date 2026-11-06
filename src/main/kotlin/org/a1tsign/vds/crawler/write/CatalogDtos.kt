package org.a1tsign.vds.crawler.write

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.math.BigDecimal
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit

/*
 * The crawler's **own** wire mirrors of the vds-001 request/response bodies (ADR-02). They
 * deliberately do not reuse vds-001's own `api/dto` package:
 *
 * - the crawler is a **tolerant reader** ([CATALOG_JSON] has `ignoreUnknownKeys = true`, vds-001's
 *   `ApiJson` has it `false`), so an additive field in a future vds-001 response must not break a
 *   crawl -- sharing the DTOs would share the strict `Json` behaviour expectations too;
 * - every enum-valued field is a **`String`** here for the same reason: `ignoreUnknownKeys` does
 *   nothing about an unknown *enum constant*, and a new `PaymentMethod` in the catalog must not
 *   make a provider unreadable. The reconciler (task 5) converts `…domain` enums to/from these
 *   strings with `.name`;
 * - a separate mirror is what makes the "crawler imports nothing out of vds-001's db, service or
 *   dto packages" grep guard enforceable.
 *
 * Money and coordinates are [BigDecimal] in Kotlin and a plain-decimal **string** on the wire
 * ([PlainDecimalSerializer]); `Double`/`Float` appear nowhere.
 */

/** Money/coordinates: plain-decimal JSON **string** ⇄ [BigDecimal], like vds-001's `BigDecimalSerializer`. */
object PlainDecimalSerializer : KSerializer<BigDecimal> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("org.a1tsign.vds.crawler.write.PlainDecimal", PrimitiveKind.STRING)

    // toPlainString(), never toString(): the latter emits scientific notation at some scales, and
    // vds-001 rejects an exponent (api/Serializers.kt `parsePlainDecimal`).
    override fun serialize(encoder: Encoder, value: BigDecimal) = encoder.encodeString(value.toPlainString())

    override fun deserialize(decoder: Decoder): BigDecimal {
        val raw = decoder.decodeString()
        return try {
            BigDecimal(raw)
        } catch (e: NumberFormatException) {
            throw SerializationException("Expected a decimal number as a JSON string, got '$raw'", e)
        }
    }
}

/**
 * vds-001 writes ISO-8601 with an explicit offset truncated to whole seconds and accepts only an
 * offset-carrying timestamp, so both directions are pinned here instead of relying on
 * `Instant.parse`.
 */
object OffsetInstantSerializer : KSerializer<Instant> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("org.a1tsign.vds.crawler.write.OffsetInstant", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: Instant) =
        encoder.encodeString(DateTimeFormatter.ISO_INSTANT.format(value.truncatedTo(ChronoUnit.SECONDS)))

    override fun deserialize(decoder: Decoder): Instant {
        val raw = decoder.decodeString()
        return try {
            OffsetDateTime.parse(raw, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant()
        } catch (e: DateTimeParseException) {
            throw SerializationException("Expected an ISO-8601 timestamp with an offset, got '$raw'", e)
        }
    }
}

// ---------- auth ----------

@Serializable
data class LoginRequestBody(val username: String, val password: String)

@Serializable
data class LoginResponseBody(
    val token: String,
    @Serializable(with = OffsetInstantSerializer::class) val expiresAt: Instant,
    val username: String = "",
    val roles: List<String> = emptyList(),
)

// ---------- the error envelope ----------

@Serializable
data class CatalogErrorDetail(val field: String = "", val message: String = "")

/**
 * vds-001's error envelope. Every field has a default: this type is parsed off a **failed**
 * response, where a proxy or a non-route failure may answer with something that is not the
 * envelope at all.
 */
@Serializable
data class CatalogErrorEnvelope(
    val status: Int = 0,
    val error: String = "",
    val message: String = "",
    val path: String = "",
    val details: List<CatalogErrorDetail>? = null,
)

// ---------- pagination ----------

@Serializable
data class CatalogPage<T>(
    val content: List<T> = emptyList(),
    val page: Int = 0,
    val size: Int = 0,
    val totalElements: Long = 0,
    val totalPages: Int = 0,
)

// ---------- reads ----------

/** One row of `GET /api/v1/providers` -- only the fields slug matching needs. */
@Serializable
data class CatalogProviderSummary(
    val id: Long,
    val slug: String,
    val name: String,
    val websiteUrl: String = "",
    val status: String = "",
    /** vds-004's second provider axis; a `String` like every other enum-valued field here. */
    val systemStatus: String = "",
)

@Serializable
data class CatalogProvider(
    val id: Long,
    val slug: String,
    val name: String,
    val websiteUrl: String,
    val description: String? = null,
    val status: String,
    /**
     * vds-004's second provider axis. Defaulted (and never sent back on an update) so the crawler
     * reads it without ever rewriting it: the only deliberate change it may make is the promotion
     * through `CrawlWriter.promoteProviderToConfigured`.
     */
    val systemStatus: String = "",
)

@Serializable
data class CatalogLegalInfo(
    val legalEntityName: String? = null,
    val registrationCountry: String? = null,
    val registrationNumber: String? = null,
    val legalAddress: String? = null,
    val dataSource: String = "",
    val confidence: String = "",
    val sourceUrl: String? = null,
)

@Serializable
data class CatalogPaymentMethod(val method: String, val note: String? = null)

@Serializable
data class CatalogLocation(
    val id: Long,
    val providerId: Long = 0,
    val countryCode: String,
    val region: String? = null,
    val city: String? = null,
    val datacenterName: String? = null,
    @Serializable(with = PlainDecimalSerializer::class) val latitude: BigDecimal? = null,
    @Serializable(with = PlainDecimalSerializer::class) val longitude: BigDecimal? = null,
)

/**
 * `isActive` is read-only state for the crawler: the reconciler echoes it back verbatim on every
 * update, because `TariffWriteRequest.isActive` defaults to `true` on the vds-001 side and an
 * omitted value would silently re-activate a deactivated tariff.
 */
@Serializable
data class CatalogTariff(
    val id: Long,
    val providerId: Long = 0,
    val externalId: String? = null,
    val name: String,
    val url: String? = null,
    val vcpuCount: Int,
    val cpuModel: String? = null,
    val ramMb: Int,
    val diskGb: Int,
    val diskType: String,
    val bandwidthMbps: Int? = null,
    val trafficLimitGb: Int? = null,
    val ipv4Count: Int,
    val ipv6Supported: Boolean? = null,
    val virtualization: String,
    val isActive: Boolean,
    val inStock: Boolean,
)

@Serializable
data class CatalogPrice(
    val id: Long = 0,
    val tariffId: Long = 0,
    val billingPeriod: String,
    @Serializable(with = PlainDecimalSerializer::class) val amount: BigDecimal,
    val currency: String,
    @Serializable(with = OffsetInstantSerializer::class) val effectiveFrom: Instant,
    @Serializable(with = OffsetInstantSerializer::class) val effectiveTo: Instant? = null,
    val dataSource: String = "",
    val warnings: List<String> = emptyList(),
)

@Serializable
data class CatalogTariffWithPrices(
    val tariff: CatalogTariff,
    val prices: List<CatalogPrice> = emptyList(),
)

/**
 * `GET /api/v1/providers/{id}`. [tariffs] carries the **active** tariffs only -- which is why
 * `CatalogApiClient.listAllTariffs` exists as a separate call.
 */
@Serializable
data class CatalogProviderDetail(
    val provider: CatalogProvider,
    val legalInfo: CatalogLegalInfo? = null,
    val paymentMethods: List<CatalogPaymentMethod> = emptyList(),
    val locations: List<CatalogLocation> = emptyList(),
    val tariffs: List<CatalogTariffWithPrices> = emptyList(),
)

// ---------- writes ----------

/** Body of `PUT /api/v1/providers/{id}/system-status`. */
@Serializable
data class SystemStatusWrite(val systemStatus: String)

@Serializable
data class ProviderWrite(
    val slug: String,
    val name: String,
    val websiteUrl: String,
    val status: String,
    val description: String? = null,
)

/**
 * `dataSource` is a caller-supplied string, but the only value the crawler may ever send is
 * `CRAWLER` (spec req. 30); `verifiedAt` is deliberately absent -- the crawler verifies nothing.
 */
@Serializable
data class LegalInfoWrite(
    val dataSource: String,
    val confidence: String,
    val legalEntityName: String? = null,
    val registrationCountry: String? = null,
    val registrationNumber: String? = null,
    val legalAddress: String? = null,
    val sourceUrl: String? = null,
)

@Serializable
data class PaymentMethodWrite(val method: String, val note: String? = null)

@Serializable
data class LocationWrite(
    val countryCode: String,
    val region: String? = null,
    val city: String? = null,
    val datacenterName: String? = null,
    @Serializable(with = PlainDecimalSerializer::class) val latitude: BigDecimal? = null,
    @Serializable(with = PlainDecimalSerializer::class) val longitude: BigDecimal? = null,
)

/**
 * [isActive] and [inStock] have **no defaults on purpose**: vds-001's `TariffWriteRequest` defaults
 * both to `true`, so a caller that forgets `isActive` resurrects a deactivated tariff. Here the
 * caller is forced to pass a value, and the only value it may pass for [isActive] is the one it
 * just read from the catalog ([CatalogTariff.isActive]) -- the crawler never deactivates anything
 * (ADR-02/ADR-03).
 */
@Serializable
data class TariffWrite(
    val name: String,
    val vcpuCount: Int,
    val ramMb: Int,
    val diskGb: Int,
    val diskType: String,
    val ipv4Count: Int,
    val virtualization: String,
    val isActive: Boolean,
    val inStock: Boolean,
    val externalId: String? = null,
    val url: String? = null,
    val cpuModel: String? = null,
    val bandwidthMbps: Int? = null,
    val trafficLimitGb: Int? = null,
    val ipv6Supported: Boolean? = null,
)

/**
 * `effectiveFrom` is deliberately absent: the crawler never back-dates a price row, so the server
 * always stamps it with its own "now" (spec req. 29/34).
 */
@Serializable
data class PriceWrite(
    val billingPeriod: String,
    @Serializable(with = PlainDecimalSerializer::class) val amount: BigDecimal,
    val currency: String,
    val dataSource: String,
)
