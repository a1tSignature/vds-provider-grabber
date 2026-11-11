package org.a1tsign.vds.api.dto

import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import org.a1tsign.vds.domain.TariffPrice
import java.time.Instant

/**
 * `amount` is a decimal string checked by `Validation.validateAmount`, not by the `BigDecimal`
 * serializer, so an out-of-range value is a field-level `400`. `effectiveFrom` goes through
 * [org.a1tsign.vds.api.InstantSerializer], which requires an explicit offset.
 */
@Serializable
data class PriceWriteRequest(
    val billingPeriod: String,
    val amount: String,
    val currency: String,
    @Contextual val effectiveFrom: Instant? = null,
    val dataSource: String? = null,
)

@Serializable
data class PriceCloseRequest(
    val billingPeriod: String,
    @Contextual val effectiveTo: Instant? = null,
)

/** [warnings] is always present (`[]` when empty), because `ApiJson` encodes defaults. */
@Serializable
data class PriceDto(
    val id: Long,
    val tariffId: Long,
    val billingPeriod: String,
    @Contextual val amount: java.math.BigDecimal,
    val currency: String,
    @Contextual val amountUsd: java.math.BigDecimal? = null,
    @Contextual val usdRate: java.math.BigDecimal? = null,
    @Contextual val usdRateAt: Instant? = null,
    @Contextual val effectiveFrom: Instant,
    @Contextual val effectiveTo: Instant? = null,
    val dataSource: String,
    val recordedBy: Long? = null,
    @Contextual val createdAt: Instant,
    @Contextual val lastConfirmedAt: Instant,
    val warnings: List<String> = emptyList(),
)

fun TariffPrice.toDto(warnings: List<String> = emptyList()) = PriceDto(
    id = id,
    tariffId = tariffId,
    billingPeriod = billingPeriod.name,
    amount = amount,
    currency = currency,
    amountUsd = amountUsd,
    usdRate = usdRate,
    usdRateAt = usdRateAt,
    effectiveFrom = effectiveFrom,
    effectiveTo = effectiveTo,
    dataSource = dataSource.name,
    recordedBy = recordedBy,
    createdAt = createdAt,
    lastConfirmedAt = lastConfirmedAt,
    warnings = warnings,
)
