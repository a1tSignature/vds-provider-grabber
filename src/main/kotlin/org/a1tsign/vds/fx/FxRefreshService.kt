package org.a1tsign.vds.fx

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.a1tsign.vds.config.nowUtc
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.slf4j.LoggerFactory
import java.time.Clock
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

/** Fetches outside the DB transaction, then appends the complete snapshot atomically. */
class FxRefreshService(
    private val provider: CurrencyRateProvider,
    private val database: Database,
    private val repository: org.a1tsign.vds.db.CurrencyRateRepository,
    private val converter: CurrencyConverter,
    private val clock: Clock,
) {
    suspend fun refresh(): Int? {
        val fetched = try {
            provider.fetchRates()
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            logger.warn("FX rate refresh failed; existing rates remain available", failure)
            return null
        }
        val ratesToUsd = fetched.rates.mapNotNull { (currency, upstreamRate) ->
            if (currency == "USD") return@mapNotNull null
            if (!CURRENCY_CODE_PATTERN.matches(currency)) {
                logger.warn("Skipping malformed FX currency code {} from {}", currency, fetched.sourceName)
                return@mapNotNull null
            }
            if (upstreamRate.signum() <= 0) {
                logger.warn("Skipping invalid FX rate for {} from {}", currency, fetched.sourceName)
                return@mapNotNull null
            }
            currency to BigDecimal.ONE.divide(upstreamRate, MathContext(20)).setScale(12, RoundingMode.HALF_UP)
        }.toMap()
        val inserted = withContext(Dispatchers.IO) {
            transaction(database) {
                repository.insertRates(ratesToUsd, fetched.sourceName, fetched.sourceRateAt, clock.nowUtc())
            }
        }
        converter.clearCache()
        return inserted
    }

    private companion object {
        val CURRENCY_CODE_PATTERN = Regex("^[A-Z]{3}$")
        val logger = LoggerFactory.getLogger(FxRefreshService::class.java)
    }
}
