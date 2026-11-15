package org.a1tsign.vds.api.dto

import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import org.a1tsign.vds.domain.FieldOrigin
import org.a1tsign.vds.domain.PaymentMethod
import org.a1tsign.vds.domain.ProviderStatus
import org.a1tsign.vds.domain.SystemStatus
import java.math.BigDecimal
import java.time.Instant

/**
 * One row of `GET /api/v1/providers`. Optional fields are omitted when absent. The two minimum
 * prices are taken over tariffs that are active and in stock: `minMonthlyPriceUsd` over current
 * MONTHLY prices, `minAnnualPriceUsdPerMonth` over current ANNUAL prices divided by 12.
 */
@Serializable
data class ProviderSummaryDto(
    val id: Long,
    val slug: String,
    val name: String,
    val websiteUrl: String,
    val status: ProviderStatus,
    /** Always `CONFIGURED` unless the request asked for other values with `?systemStatus=`. */
    val systemStatus: SystemStatus,
    val registrationCountry: String? = null,
    val legalEntityName: String? = null,
    val legalEntityNameOrigin: FieldOrigin? = null,
    val registrationCountryOrigin: FieldOrigin? = null,
    val paymentMethods: Set<PaymentMethod> = emptySet(),
    val locationCountryCodes: Set<String> = emptySet(),
    val activeTariffCount: Int,
    @Contextual val minMonthlyPriceUsd: BigDecimal? = null,
    @Contextual val minAnnualPriceUsdPerMonth: BigDecimal? = null,
    /** Absent when the provider was never attempted; the enrichment process orders its queue by it. */
    @Contextual val enrichmentLastAttemptAt: Instant? = null,
)

/** One tariff plus its current price rows (every billing period present), nested in [ProviderDetailDto]. */
@Serializable
data class TariffWithPricesDto(
    val tariff: TariffDto,
    val prices: List<PriceDto> = emptyList(),
)

/**
 * `GET /api/v1/providers/{id}` -- the full provider: legal info, payment methods, locations, active
 * tariffs and the state of its latest enrichment (absent when it was never attempted).
 */
@Serializable
data class ProviderDetailDto(
    val provider: ProviderDto,
    val legalInfo: LegalInfoDto? = null,
    val paymentMethods: List<ProviderPaymentMethodDto> = emptyList(),
    val locations: List<LocationDto> = emptyList(),
    val tariffs: List<TariffWithPricesDto> = emptyList(),
    val enrichment: ProviderEnrichmentDto? = null,
)
