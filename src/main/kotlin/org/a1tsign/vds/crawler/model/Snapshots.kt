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
 * A null section leaves its catalog facet untouched. An empty collection records an observed empty set.
 * [tariffListComplete] separately claims the provider's full tariff catalog and permits marking missing tariffs out of stock.
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

/** Legal snapshots cannot supply provenance. The writer assigns CRAWLER. */
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
    /** Match uq_location_provider_country_city_dc by lowercasing and coalescing missing city and datacenter names. The provider is fixed by the caller. */
    fun matchKey(): LocationMatchKey = LocationMatchKey(
        countryCode = countryCode,
        city = (city ?: "").lowercase(),
        datacenterName = (datacenterName ?: "").lowercase(),
    )
}

data class LocationMatchKey(
    val countryCode: String,
    val city: String,
    val datacenterName: String,
)

/** Snapshots can express stock changes but have no concept of tariff activation. Prices contain only observed billing periods. */
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

/** Use the advertised amount and ISO 4217 currency. The catalog handles FX conversion. */
data class PriceSnapshot(
    val billingPeriod: BillingPeriod,
    val amount: BigDecimal,
    val currency: String,
)
