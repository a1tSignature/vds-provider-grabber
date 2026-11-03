package org.a1tsign.vds.crawler.model

import org.a1tsign.vds.domain.BillingPeriod
import org.a1tsign.vds.domain.Confidence
import org.a1tsign.vds.domain.DiskType
import org.a1tsign.vds.domain.PaymentMethod
import org.a1tsign.vds.domain.ProviderStatus
import org.a1tsign.vds.domain.Virtualization
import java.math.BigDecimal
import java.time.Instant

/**
 * What one adapter observed about one provider during one run -- a read-only description, never a
 * command: the reconciler decides which catalog calls it implies.
 *
 * **`null` means "not observed", an empty collection means "observed and empty"** (ADR-03). A
 * `null` section leaves that part of the catalog untouched; a non-null collection is the complete
 * observed set and may therefore drive a replace-the-whole-set endpoint. [tariffListComplete] is
 * the separate, explicit claim that [tariffs] is the provider's full catalog -- only such a
 * snapshot may ever flip a tariff to out of stock.
 *
 * Plain immutable data classes on purpose: no serialization/Exposed annotation belongs in this
 * package, money and coordinates are [BigDecimal] (`Double`/`Float` appear nowhere) and time is
 * [Instant] taken from the injected `Clock`.
 */
data class ProviderSnapshot(
    val provider: ProviderInfo,
    val legalInfo: LegalInfoSnapshot? = null,
    val paymentMethods: Set<PaymentMethod>? = null,
    val locations: List<LocationSnapshot>? = null,
    val tariffs: List<TariffSnapshot>? = null,
    val tariffListComplete: Boolean = false,
    val observedAt: Instant,
    /** Every page the snapshot was built from, for the audit trail in the run summary. */
    val sourceUrls: List<String>,
)

data class ProviderInfo(
    val slug: String,
    val name: String,
    val websiteUrl: String,
    val description: String? = null,
    val status: ProviderStatus,
)

/**
 * `dataSource` is deliberately **not** a field: everything the crawler writes is `CRAWLER`, and an
 * adapter may not claim otherwise.
 */
data class LegalInfoSnapshot(
    val legalEntityName: String? = null,
    val registrationCountry: String? = null,
    val registrationNumber: String? = null,
    val legalAddress: String? = null,
    val confidence: Confidence = Confidence.LOW,
    val sourceUrl: String? = null,
)

data class LocationSnapshot(
    val key: LocationKey,
    val region: String? = null,
    val latitude: BigDecimal? = null,
    val longitude: BigDecimal? = null,
)

/**
 * Identifies a provider location the way the catalog does, so a second run matches the row the
 * first run created instead of duplicating it.
 */
data class LocationKey(
    val countryCode: String,
    val city: String? = null,
    val datacenterName: String? = null,
) {
    /**
     * The comparison form of this key, mirroring vds-001's unique index
     * `uq_location_provider_country_city_dc`:
     * `(provider_id, country_code, lower(coalesce(city, '')), lower(coalesce(datacenter_name, '')))`
     * -- a missing city/datacenter collapses to `""` and both are compared case-insensitively.
     *
     * `countryCode` is compared verbatim, like the index does: the column carries
     * `ck_location_country_code` (`^[A-Z]{2}$`) and snapshot validation enforces the same shape, so
     * there is no case to fold. Two keys with the same match form are the same catalog row.
     */
    fun matchKey(): LocationMatchKey = LocationMatchKey(
        countryCode = countryCode,
        city = (city ?: "").lowercase(),
        datacenterName = (datacenterName ?: "").lowercase(),
    )
}

/** Normalised [LocationKey] -- equality here is "the same `location` row". */
data class LocationMatchKey(
    val countryCode: String,
    val city: String,
    val datacenterName: String,
)

/**
 * One observed tariff. There is deliberately **no `isActive`**: an adapter may never express
 * deactivation (ADR-03). [inStock] `false` is the only disappearance signal, and the reconciler
 * honours it only from a snapshot with `tariffListComplete = true`.
 *
 * [locationKeys] `null` means the adapter did not observe the tariff's locations, so the
 * tariff-to-location assignment is left alone.
 */
data class TariffSnapshot(
    val externalId: String? = null,
    val name: String,
    val url: String? = null,
    val vcpuCount: Int,
    val cpuModel: String? = null,
    val ramMb: Int,
    val diskGb: Int,
    val diskType: DiskType,
    val bandwidthMbps: Int? = null,
    val trafficLimitGb: Int? = null,
    val ipv4Count: Int,
    val ipv6Supported: Boolean? = null,
    val virtualization: Virtualization,
    val inStock: Boolean = true,
    val locationKeys: List<LocationKey>? = null,
    val prices: List<PriceSnapshot> = emptyList(),
)

/** [amount] is the price as advertised, in [currency] (ISO-4217); FX conversion stays in vds-001. */
data class PriceSnapshot(
    val billingPeriod: BillingPeriod,
    val amount: BigDecimal,
    val currency: String,
)
