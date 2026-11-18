package org.a1tsign.vds.domain

import java.math.BigDecimal
import java.time.Instant

data class Provider(
    val id: Long,
    val slug: String,
    val name: String,
    val websiteUrl: String,
    val description: String?,
    val status: ProviderStatus,
    /** Our own relationship to this row; independent of [status] -- see [SystemStatus]. */
    val systemStatus: SystemStatus,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class LegalInfo(
    val providerId: Long,
    val legalEntityName: String?,
    val registrationCountry: String?,
    val registrationNumber: String?,
    val legalAddress: String?,
    val dataSource: LegalDataSource,
    val confidence: Confidence,
    val sourceUrl: String?,
    val verifiedAt: Instant?,
    val updatedAt: Instant,
    val legalEntityNameOrigin: FieldOrigin? = null,
    val registrationCountryOrigin: FieldOrigin? = null,
)

data class ProviderPaymentMethod(
    val providerId: Long,
    val method: PaymentMethod,
    val note: String?,
    val origin: RowOrigin = RowOrigin.MANUAL,
)

data class Location(
    val id: Long,
    val providerId: Long,
    val countryCode: String,
    val region: String?,
    val city: String?,
    val datacenterName: String?,
    val latitude: BigDecimal?,
    val longitude: BigDecimal?,
    val createdAt: Instant,
    val updatedAt: Instant,
    val origin: RowOrigin = RowOrigin.MANUAL,
)

/** 0..1 per [Provider]. [evidenceJson] is the raw stored document of the latest successful run. */
data class ProviderEnrichment(
    val providerId: Long,
    val lastAttemptAt: Instant,
    val lastOutcome: EnrichmentOutcome,
    val lastMessage: String?,
    val lastPagesFetched: Int,
    val lastSuccessAt: Instant?,
    val evidenceJson: String?,
)

data class Tariff(
    val id: Long,
    val providerId: Long,
    val externalId: String?,
    val name: String,
    val url: String?,
    val vcpuCount: Short,
    val cpuModel: String?,
    val ramMb: Int,
    val diskGb: Int,
    val diskType: DiskType,
    val bandwidthMbps: Int?,
    val trafficLimitGb: Int?,
    val ipv4Count: Short,
    val ipv6Supported: Boolean?,
    val virtualization: Virtualization,
    val isActive: Boolean,
    val inStock: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant,
)

/** One row of the append-only price history (SCD type 2) for a [Tariff]. */
data class TariffPrice(
    val id: Long,
    val tariffId: Long,
    val billingPeriod: BillingPeriod,
    val amount: BigDecimal,
    val currency: String,
    val amountUsd: BigDecimal?,
    val usdRate: BigDecimal?,
    val usdRateAt: Instant?,
    val effectiveFrom: Instant,
    val effectiveTo: Instant?,
    val dataSource: PriceDataSource,
    val recordedBy: Long?,
    val createdAt: Instant,
    /** When a writer last asserted this row's amount and currency; never earlier than a previous value. */
    val lastConfirmedAt: Instant,
)

data class CurrencyRate(
    val id: Long,
    val currency: String,
    val rateToUsd: BigDecimal,
    val sourceRateAt: Instant,
    val fetchedAt: Instant,
    val source: String,
)

data class AppUser(
    val id: Long,
    val username: String,
    val email: String?,
    val passwordHash: String,
    val status: UserStatus,
    val tokenVersion: Int,
    val createdAt: Instant,
    val updatedAt: Instant,
    val lastLoginAt: Instant?,
)
