package org.a1tsign.vds.fx

import org.a1tsign.vds.db.CurrencyRateRepository
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

data class ConversionResult(val amountUsd: BigDecimal?, val usdRate: BigDecimal?, val usdRateAt: Instant?)

/** Synchronous by design: cache misses query through the currently active Exposed transaction. */
class CurrencyConverter(private val repository: CurrencyRateRepository) {
    private val latestRates = ConcurrentHashMap<String, CachedRate>()

    fun convertToUsd(amount: BigDecimal, currency: String, effectiveFrom: Instant): ConversionResult {
        if (currency == "USD") {
            return ConversionResult(amount.setScale(4, RoundingMode.HALF_UP), BigDecimal.ONE, effectiveFrom)
        }
        val latest = latestRates[currency] ?: repository.findLatest(currency)?.let {
            if (latestRates.size >= MAX_CACHED_CURRENCIES) latestRates.clear()
            CachedRate(it.rateToUsd, it.sourceRateAt).also { cached -> latestRates[currency] = cached }
        } ?: return ConversionResult(null, null, null)
        return ConversionResult(
            amount.multiply(latest.rate).setScale(4, RoundingMode.HALF_UP),
            latest.rate,
            latest.sourceRateAt,
        )
    }

    fun clearCache() = latestRates.clear()

    private data class CachedRate(val rate: BigDecimal, val sourceRateAt: Instant)

    private companion object { const val MAX_CACHED_CURRENCIES = 200 }
}
