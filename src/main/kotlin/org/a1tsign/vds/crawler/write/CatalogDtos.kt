package org.a1tsign.vds.crawler.write

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
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
 * The crawler's own mirrors of the catalog API bodies, not the server's `api/dto`: the crawler is a
 * tolerant reader ([CATALOG_JSON]) and must keep working when a response gains fields. Enum-valued
 * fields are `String` because `ignoreUnknownKeys` does not cover an unknown enum constant, and a
 * new `PaymentMethod` must not make a provider unreadable.
 */

/** Money and coordinates: a plain-decimal JSON string to and from [BigDecimal]. */
object PlainDecimalSerializer : KSerializer<BigDecimal> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("org.a1tsign.vds.crawler.write.PlainDecimal", PrimitiveKind.STRING)

    // toString() emits scientific notation at some scales, and the server rejects an exponent.
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
 * The server writes ISO-8601 with an explicit offset, truncated to seconds, and accepts only a
 * timestamp with an offset, so both directions are pinned instead of relying on `Instant.parse`.
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

/**
 * Every crawler write is automatic: the catalog then never lets it overwrite a `MANUAL` legal field
 * or touch `verified_at`, and its rows are tagged `AUTO`.
 */
const val AUTOMATIC_WRITE_MODE = "AUTOMATIC"

@Serializable
data class LoginRequestBody(val username: String, val password: String)

@Serializable
data class LoginResponseBody(
    val token: String,
    @Serializable(with = OffsetInstantSerializer::class) val expiresAt: Instant,
    val username: String = "",
    val roles: List<String> = emptyList(),
)

@Serializable
data class CatalogErrorDetail(val field: String = "", val message: String = "")

/**
 * Every field has a default: a failed response may come from a proxy or a non-route failure and
 * not be the envelope at all.
 */
@Serializable
data class CatalogErrorEnvelope(
    val status: Int = 0,
    val error: String = "",
    val message: String = "",
    val path: String = "",
    val details: List<CatalogErrorDetail>? = null,
)

@Serializable
data class CatalogPage<T>(
    val content: List<T> = emptyList(),
    val page: Int = 0,
    val size: Int = 0,
    val totalElements: Long = 0,
    val totalPages: Int = 0,
)

/** One row of `GET /api/v1/providers`. */
@Serializable
data class CatalogProviderSummary(
    val id: Long,
    val slug: String,
    val name: String,
    val websiteUrl: String = "",
    val status: String = "",
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
    /** Read-only for the crawler except through [CrawlWriter.promoteProviderToConfigured]. */
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
    val legalEntityNameOrigin: String? = null,
    val registrationCountryOrigin: String? = null,
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

/** `GET /api/v1/providers/{id}`. [tariffs] holds active tariffs only; see [CatalogApiClient.listAllTariffs]. */
@Serializable
data class CatalogProviderDetail(
    val provider: CatalogProvider,
    val legalInfo: CatalogLegalInfo? = null,
    val paymentMethods: List<CatalogPaymentMethod> = emptyList(),
    val locations: List<CatalogLocation> = emptyList(),
    val tariffs: List<CatalogTariffWithPrices> = emptyList(),
    val enrichment: CatalogEnrichmentState? = null,
)

/** The `enrichment` block of the provider detail; absent until the first attempt was recorded. */
@Serializable
data class CatalogEnrichmentState(
    @Serializable(with = OffsetInstantSerializer::class) val lastAttemptAt: Instant,
    val lastOutcome: String = "",
)

@Serializable
data class EvidenceItemWrite(val url: String, val kind: String, val snippet: String)

/** [status] is `CONFIRMED` or `PRESUMED`. */
@Serializable
data class LegalFindingWrite(
    val value: String,
    val status: String,
    val basis: List<String> = emptyList(),
    val alternatives: List<String> = emptyList(),
    val evidence: List<EvidenceItemWrite> = emptyList(),
)

/** [confidence] is `HIGH`, `MEDIUM` or `LOW`; the catalog writes only the first two. */
@Serializable
data class ValueFindingWrite(
    val value: String,
    val confidence: String,
    val pages: Int = 0,
    val evidence: List<EvidenceItemWrite> = emptyList(),
)

/**
 * Body of `PUT /api/v1/providers/{id}/enrichment`. The catalog applies the "manual wins" rules
 * itself, under the provider row lock; facets are accepted only with `outcome = SUCCESS`.
 */
@Serializable
data class EnrichmentApplyWrite(
    val outcome: String,
    val message: String? = null,
    val pagesFetched: Int = 0,
    val observedFacets: List<String> = emptyList(),
    val legalEntityName: LegalFindingWrite? = null,
    val registrationCountry: LegalFindingWrite? = null,
    val paymentMethods: List<ValueFindingWrite> = emptyList(),
    val locations: List<ValueFindingWrite> = emptyList(),
)

@Serializable
data class EnrichmentApplyResult(val valuesApplied: Int = 0)

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

/** Discovery's `POST /api/v1/providers`: [ProviderWrite] plus an explicit [systemStatus]. */
@Serializable
data class CandidateProviderWrite(
    val slug: String,
    val name: String,
    val websiteUrl: String,
    val status: String,
    val systemStatus: String,
    val description: String? = null,
)

/**
 * The crawler only ever sends `dataSource = CRAWLER`. There is no `verifiedAt`: the catalog rejects
 * it in an automatic write.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class LegalInfoWrite(
    val dataSource: String,
    val confidence: String,
    val legalEntityName: String? = null,
    val registrationCountry: String? = null,
    val registrationNumber: String? = null,
    val legalAddress: String? = null,
    val sourceUrl: String? = null,
    val legalEntityNameOrigin: String? = null,
    val registrationCountryOrigin: String? = null,
    @EncodeDefault val writeMode: String = AUTOMATIC_WRITE_MODE,
)

@Serializable
data class PaymentMethodWrite(val method: String, val note: String? = null)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class LocationWrite(
    val countryCode: String,
    val region: String? = null,
    val city: String? = null,
    val datacenterName: String? = null,
    @Serializable(with = PlainDecimalSerializer::class) val latitude: BigDecimal? = null,
    @Serializable(with = PlainDecimalSerializer::class) val longitude: BigDecimal? = null,
    @EncodeDefault val writeMode: String = AUTOMATIC_WRITE_MODE,
)

/**
 * [isActive] and [inStock] have no defaults because the server defaults both to `true`, and an
 * omitted `isActive` would resurrect a deactivated tariff. [isActive] must be the value just read
 * from [CatalogTariff.isActive]: the crawler never deactivates anything.
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

/** No `effectiveFrom`: the crawler never back-dates a price, so the server stamps its own "now". */
@Serializable
data class PriceWrite(
    val billingPeriod: String,
    @Serializable(with = PlainDecimalSerializer::class) val amount: BigDecimal,
    val currency: String,
    val dataSource: String,
)
