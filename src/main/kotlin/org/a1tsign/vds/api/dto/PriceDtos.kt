package org.a1tsign.vds.api.dto

import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import org.a1tsign.vds.domain.TariffPrice
import java.time.Instant

/**
 * `amount` is a decimal **string**, validated by `Validation.validateAmount` (not the contextual
 * `BigDecimal` serializer) so an out-of-range value reports a field-level `400` instead of a bare
 * deserialization failure. `effectiveFrom` follows the same pattern as
 * `LegalInfoWriteRequest.verifiedAt`: a plain `@Contextual Instant?`, enforced by
 * [org.a1tsign.vds.api.InstantSerializer] itself (must carry an explicit offset).
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

/**
 * [warnings] is always encoded (`ApiJson.encodeDefaults = true` serializes a field even when it
 * equals its default), so a client always sees either `"warnings":[]` or `"warnings":["NO_FX_RATE"]`
 * -- never an absent key, unlike a nullable field under `explicitNulls = false`.
 */
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
    warnings = warnings,
)
