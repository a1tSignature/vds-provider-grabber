package org.a1tsign.vds.db

import org.a1tsign.vds.config.toUtcOffset
import org.a1tsign.vds.domain.BillingPeriod
import org.a1tsign.vds.domain.PriceDataSource
import org.a1tsign.vds.domain.TariffPrice
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.core.vendors.ForUpdateOption
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.math.BigDecimal
import java.time.Instant

/** Callers must lock the tariff row before reading or changing price history to serialize writes for that tariff. */
class PriceRepository {
    /** `true` if the tariff exists -- the row is locked `FOR UPDATE` either way. */
    fun lockTariff(tariffId: Long): Boolean = TariffTable.selectAll()
        .where { TariffTable.id eq tariffId }
        .forUpdate(ForUpdateOption.ForUpdate)
        .firstOrNull() != null

    fun findCurrent(tariffId: Long, billingPeriod: BillingPeriod): TariffPrice? = TariffPriceTable.selectAll()
        .where {
            (TariffPriceTable.tariffId eq tariffId) and
                (TariffPriceTable.billingPeriod eq billingPeriod.name) and
                TariffPriceTable.effectiveTo.isNull()
        }
        .firstOrNull()?.toTariffPrice()

    fun findById(id: Long): TariffPrice? =
        TariffPriceTable.selectAll().where { TariffPriceTable.id eq id }.firstOrNull()?.toTariffPrice()

    /** The row of the same `(tariff, billingPeriod)` whose `effective_to` equals [effectiveTo], if any. */
    fun findPreviousEndingAt(tariffId: Long, billingPeriod: BillingPeriod, effectiveTo: Instant): TariffPrice? =
        TariffPriceTable.selectAll()
            .where {
                (TariffPriceTable.tariffId eq tariffId) and
                    (TariffPriceTable.billingPeriod eq billingPeriod.name) and
                    (TariffPriceTable.effectiveTo eq effectiveTo.toUtcOffset())
            }
            .firstOrNull()?.toTariffPrice()

    fun insert(
        tariffId: Long,
        billingPeriod: BillingPeriod,
        amount: BigDecimal,
        currency: String,
        amountUsd: BigDecimal?,
        usdRate: BigDecimal?,
        usdRateAt: Instant?,
        effectiveFrom: Instant,
        dataSource: PriceDataSource,
        recordedBy: Long?,
        now: Instant,
    ): TariffPrice {
        val id = TariffPriceTable.insert {
            it[TariffPriceTable.tariffId] = tariffId
            it[TariffPriceTable.billingPeriod] = billingPeriod.name
            it[TariffPriceTable.amount] = amount
            it[TariffPriceTable.currency] = currency
            it[TariffPriceTable.amountUsd] = amountUsd
            it[TariffPriceTable.usdRate] = usdRate
            it[TariffPriceTable.usdRateAt] = usdRateAt?.toUtcOffset()
            it[TariffPriceTable.effectiveFrom] = effectiveFrom.toUtcOffset()
            it[TariffPriceTable.effectiveTo] = null
            it[TariffPriceTable.dataSource] = dataSource.name
            it[TariffPriceTable.recordedBy] = recordedBy
            it[TariffPriceTable.createdAt] = now.toUtcOffset()
            it[TariffPriceTable.lastConfirmedAt] = now.toUtcOffset()
        }[TariffPriceTable.id]
        return findById(id)!!
    }

    /** Moves `last_confirmed_at` of [id] forward to [now]; a [now] that is not later leaves it as it is. */
    fun confirm(id: Long, now: Instant) {
        TariffPriceTable.update({ (TariffPriceTable.id eq id) and (TariffPriceTable.lastConfirmedAt less now.toUtcOffset()) }) {
            it[TariffPriceTable.lastConfirmedAt] = now.toUtcOffset()
        }
    }

    fun close(id: Long, effectiveTo: Instant) {
        TariffPriceTable.update({ TariffPriceTable.id eq id }) {
            it[TariffPriceTable.effectiveTo] = effectiveTo.toUtcOffset()
        }
    }

    /** Clears `effective_to`, making [id] current again -- used only by the ADMIN correction. */
    fun reopen(id: Long) {
        TariffPriceTable.update({ TariffPriceTable.id eq id }) {
            it[TariffPriceTable.effectiveTo] = null
        }
    }

    /** The only delete path besides a tariff/provider cascade -- the ADMIN correction. */
    fun delete(id: Long) {
        TariffPriceTable.deleteWhere { TariffPriceTable.id eq id }
    }

    /**
     * `GET /api/v1/tariffs/{id}/price-history` -- every row of [tariffId] (optionally narrowed to
     * one [billingPeriod]) whose validity interval `[effective_from, effective_to)` overlaps
     * `[from, to)`, newest `effective_from` first. An absent bound drops only its own half of the
     * overlap test (`to == null` means no upper bound at all, not "now"), which is what makes a
     * row with `effective_to == null` (still current) overlap any query whose `to` is unset.
     */
    fun history(tariffId: Long, billingPeriod: BillingPeriod?, from: Instant?, to: Instant?): List<TariffPrice> {
        var op: Op<Boolean> = TariffPriceTable.tariffId eq tariffId
        billingPeriod?.let { op = op and (TariffPriceTable.billingPeriod eq it.name) }
        to?.let { op = op and (TariffPriceTable.effectiveFrom less it.toUtcOffset()) }
        from?.let { boundary ->
            op = op and (TariffPriceTable.effectiveTo.isNull() or (TariffPriceTable.effectiveTo greater boundary.toUtcOffset()))
        }
        return TariffPriceTable.selectAll().where { op }
            .orderBy(TariffPriceTable.effectiveFrom, SortOrder.DESC)
            .map { it.toTariffPrice() }
    }
}
