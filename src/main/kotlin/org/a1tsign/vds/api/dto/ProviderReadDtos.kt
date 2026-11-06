package org.a1tsign.vds.api.dto

import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import org.a1tsign.vds.domain.PaymentMethod
import org.a1tsign.vds.domain.ProviderStatus
import org.a1tsign.vds.domain.SystemStatus
import java.math.BigDecimal

/**
 * One row of `GET /api/v1/providers`. `registrationCountry`/`minMonthlyPriceUsd` are omitted on the
 * wire when absent (`ApiJson.explicitNulls = false`) -- a missing `minMonthlyPriceUsd` key means
 * "no current MONTHLY price among this provider's active tariffs", never a JSON `null`.
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
    val paymentMethods: Set<PaymentMethod> = emptySet(),
    val locationCountryCodes: Set<String> = emptySet(),
    val activeTariffCount: Int,
    @Contextual val minMonthlyPriceUsd: BigDecimal? = null,
)

/** One tariff plus its current price rows (every billing period present), nested in [ProviderDetailDto]. */
@Serializable
data class TariffWithPricesDto(
    val tariff: TariffDto,
    val prices: List<PriceDto> = emptyList(),
)

/** `GET /api/v1/providers/{id}` -- the full provider: legal info, payment methods, locations and active tariffs. */
@Serializable
data class ProviderDetailDto(
    val provider: ProviderDto,
    val legalInfo: LegalInfoDto? = null,
    val paymentMethods: List<ProviderPaymentMethodDto> = emptyList(),
    val locations: List<LocationDto> = emptyList(),
    val tariffs: List<TariffWithPricesDto> = emptyList(),
)
