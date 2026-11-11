package org.a1tsign.vds.api.dto

import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import org.a1tsign.vds.domain.TariffPrice
import java.math.BigDecimal
import java.time.Instant

/**
 * One row of `GET /api/v1/tariffs`: the tariff, its provider and the current price for the requested
 * `billingPeriod`. [currentPrice] is omitted when there is none.
 */
@Serializable
data class TariffSearchItemDto(
    val tariff: TariffDto,
    val provider: ProviderDto,
    val currentPrice: PriceDto? = null,
)

/**
 * `GET /api/v1/tariffs/{id}`: the tariff, its provider, its locations, and the current price of each
 * billing period that has one (at most one per period, by `uq_tariff_price_current`).
 */
@Serializable
data class TariffDetailDto(
    val tariff: TariffDto,
    val provider: ProviderDto,
    val locations: List<LocationDto> = emptyList(),
    val currentPrices: List<PriceDto> = emptyList(),
)

/**
 * One row of `GET /api/v1/tariffs/{id}/price-history`: a raw price row, without the `tariffId`,
 * `recordedBy` and `warnings` of [PriceDto].
 */
@Serializable
data class PriceHistoryItemDto(
    val id: Long,
    val billingPeriod: String,
    @Contextual val amount: BigDecimal,
    val currency: String,
    @Contextual val amountUsd: BigDecimal? = null,
    @Contextual val usdRate: BigDecimal? = null,
    @Contextual val usdRateAt: Instant? = null,
    @Contextual val effectiveFrom: Instant,
    @Contextual val effectiveTo: Instant? = null,
    val dataSource: String,
)

fun TariffPrice.toHistoryDto() = PriceHistoryItemDto(
    id = id,
    billingPeriod = billingPeriod.name,
    amount = amount,
    currency = currency,
    amountUsd = amountUsd,
    usdRate = usdRate,
    usdRateAt = usdRateAt,
    effectiveFrom = effectiveFrom,
    effectiveTo = effectiveTo,
    dataSource = dataSource.name,
)
