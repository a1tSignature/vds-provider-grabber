package org.a1tsign.vds.api.dto

import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import org.a1tsign.vds.domain.TariffPrice
import java.math.BigDecimal
import java.time.Instant

/**
 * One row of `GET /api/v1/tariffs` -- the tariff, a provider summary (reusing [ProviderDto], which
 * already carries at least `id`/`slug`/`name`) and the *current* price for the requested
 * `billingPeriod` ([currentPrice], absent -- not `null` -- when there is none for that period).
 */
@Serializable
data class TariffSearchItemDto(
    val tariff: TariffDto,
    val provider: ProviderDto,
    val currentPrice: PriceDto? = null,
)

/**
 * `GET /api/v1/tariffs/{id}` -- the tariff, its provider, every linked location and **one current
 * price per billing period that has one** (never more than one per period, by the
 * `uq_tariff_price_current` constraint).
 */
@Serializable
data class TariffDetailDto(
    val tariff: TariffDto,
    val provider: ProviderDto,
    val locations: List<LocationDto> = emptyList(),
    val currentPrices: List<PriceDto> = emptyList(),
)

/**
 * One row of `GET /api/v1/tariffs/{id}/price-history` -- deliberately a narrower field set than
 * [PriceDto] (no `tariffId`, `recordedBy` or `warnings`): the task fixes this exact set, and `/
 * price-history` is purely a read of raw rows, not a mirror of the write response.
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
