package org.a1tsign.vds.service

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.a1tsign.vds.api.ApiErrorDetail
import org.a1tsign.vds.api.ConflictException
import org.a1tsign.vds.api.NotFoundException
import org.a1tsign.vds.api.ValidationException
import org.a1tsign.vds.config.nowUtc
import org.a1tsign.vds.db.PriceRepository
import org.a1tsign.vds.domain.BillingPeriod
import org.a1tsign.vds.domain.PriceDataSource
import org.a1tsign.vds.domain.TariffPrice
import org.a1tsign.vds.fx.CurrencyConverter
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.postgresql.util.PSQLException
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.time.Clock
import java.time.Duration
import java.time.Instant

private const val CURRENT_PRICE_CONSTRAINT = "uq_tariff_price_current"
private val FUTURE_SKEW: Duration = Duration.ofSeconds(60)
private const val NO_FX_RATE_WARNING = "NO_FX_RATE"

data class PriceWriteResult(val price: TariffPrice, val created: Boolean, val warnings: List<String>)

/**
 * Owns the three price-write operations. Every method opens exactly one transaction that locks
 * the tariff row first ([PriceRepository.lockTariff]) -- see the task's "Transaction rule". That
 * lock is what serialises concurrent writers, including the very first price for a billing
 * period, when no current row exists yet to lock instead.
 */
class PriceService(
    private val database: Database,
    private val prices: PriceRepository,
    private val currencyConverter: CurrencyConverter,
    private val clock: Clock,
) {
    private val logger = LoggerFactory.getLogger(PriceService::class.java)

    /**
     * `PUT .../price`. An idempotent re-submission (same amount + currency as the current row)
     * only advances that row's `lastConfirmedAt` and ignores a supplied `effectiveFrom`/`dataSource`;
     * it is reported as `created = false`. Otherwise the current row (if any) is
     * closed at the new row's `effectiveFrom` and a new row is inserted -- never an in-place update
     * of `amount`/`currency`/the USD snapshot.
     */
    suspend fun setPrice(
        tariffId: Long,
        billingPeriod: BillingPeriod,
        amount: BigDecimal,
        currency: String,
        requestedEffectiveFrom: Instant?,
        dataSource: PriceDataSource,
        recordedBy: Long,
    ): PriceWriteResult = withContext(Dispatchers.IO) {
        mapUniqueViolation {
            transaction(database) {
                if (!prices.lockTariff(tariffId)) throw NotFoundException("Tariff not found")
                val now = clock.nowUtc()
                val current = prices.findCurrent(tariffId, billingPeriod)
                val idempotent = current != null && current.amount.compareTo(amount) == 0 && current.currency == currency
                if (idempotent) {
                    prices.confirm(current!!.id, now)
                    PriceWriteResult(prices.findById(current.id)!!, created = false, warnings = emptyList())
                } else {
                    val effectiveFrom = requestedEffectiveFrom ?: now
                    if (effectiveFrom.isAfter(now.plus(FUTURE_SKEW))) {
                        throw ValidationException(
                            listOf(ApiErrorDetail("effectiveFrom", "must not be more than 60 seconds in the future")),
                        )
                    }
                    if (current != null && !effectiveFrom.isAfter(current.effectiveFrom)) {
                        throw ConflictException("effectiveFrom must be after the current price's effectiveFrom")
                    }
                    val conversion = currencyConverter.convertToUsd(amount, currency, effectiveFrom)
                    val warnings = if (conversion.amountUsd == null) listOf(NO_FX_RATE_WARNING) else emptyList()
                    if (current != null) prices.close(current.id, effectiveFrom)
                    val inserted = prices.insert(
                        tariffId, billingPeriod, amount, currency,
                        conversion.amountUsd, conversion.usdRate, conversion.usdRateAt,
                        effectiveFrom, dataSource, recordedBy, now,
                    )
                    PriceWriteResult(inserted, created = true, warnings)
                }
            }
        }
    }

    /** `POST .../price/close`. Sets `effective_to` of the current row; it never reopens a row. */
    suspend fun close(tariffId: Long, billingPeriod: BillingPeriod, requestedEffectiveTo: Instant?): TariffPrice =
        withContext(Dispatchers.IO) {
            transaction(database) {
                if (!prices.lockTariff(tariffId)) throw NotFoundException("Tariff not found")
                val current = prices.findCurrent(tariffId, billingPeriod)
                    ?: throw NotFoundException("No current price for this billing period")
                val now = clock.nowUtc()
                val effectiveTo = requestedEffectiveTo ?: now
                if (effectiveTo.isAfter(now.plus(FUTURE_SKEW))) {
                    throw ValidationException(
                        listOf(ApiErrorDetail("effectiveTo", "must not be more than 60 seconds in the future")),
                    )
                }
                if (!effectiveTo.isAfter(current.effectiveFrom)) {
                    throw ConflictException("effectiveTo must be after the current price's effectiveFrom")
                }
                prices.close(current.id, effectiveTo)
                prices.findById(current.id)!!
            }
        }

    /**
     * `DELETE .../prices/{priceId}` -- ADMIN only, enforced by the route. The only path that
     * removes a price row besides a tariff/provider cascade delete.
     */
    suspend fun correct(tariffId: Long, priceId: Long, actingUserId: Long) = withContext(Dispatchers.IO) {
        transaction(database) {
            if (!prices.lockTariff(tariffId)) throw NotFoundException("Tariff not found")
            val price = prices.findById(priceId)?.takeIf { it.tariffId == tariffId }
                ?: throw NotFoundException("Price not found")
            if (price.effectiveTo != null) throw ConflictException("Only the current price row can be corrected")
            prices.delete(price.id)
            val previous = prices.findPreviousEndingAt(tariffId, price.billingPeriod, price.effectiveFrom)
            if (previous != null) prices.reopen(previous.id)
            logger.warn(
                "Price correction by user {} on tariff {} billingPeriod {}: deleted {} {}",
                actingUserId, tariffId, price.billingPeriod, price.amount.toPlainString(), price.currency,
            )
        }
    }

    private inline fun <T> mapUniqueViolation(block: () -> T): T = try {
        block()
    } catch (e: Exception) {
        var cause: Throwable? = e
        while (cause != null) {
            val pg = cause as? PSQLException
            if (pg?.sqlState == "23505" && pg.serverErrorMessage?.constraint == CURRENT_PRICE_CONSTRAINT) {
                throw ConflictException("A current price already exists for this billing period")
            }
            cause = cause.cause
        }
        throw e
    }
}
