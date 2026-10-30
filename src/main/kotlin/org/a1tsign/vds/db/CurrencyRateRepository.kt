package org.a1tsign.vds.db

import org.a1tsign.vds.config.toUtcOffset
import org.a1tsign.vds.domain.CurrencyRate
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.time.Instant

/** Repository methods run in the surrounding service/caller transaction. */
class CurrencyRateRepository {
    fun insertRates(rates: Map<String, java.math.BigDecimal>, source: String, sourceRateAt: Instant, fetchedAt: Instant): Int {
        var inserted = 0
        rates.filterKeys { it != "USD" }.forEach { (currencyCode, rate) ->
            val result = CurrencyRateTable.insertIgnore {
                it[currency] = currencyCode
                it[rateToUsd] = rate
                it[this.sourceRateAt] = sourceRateAt.toUtcOffset()
                it[this.fetchedAt] = fetchedAt.toUtcOffset()
                it[sourceName] = source
            }
            if (result.insertedCount > 0) inserted += result.insertedCount
        }
        return inserted
    }

    fun findLatest(currency: String): CurrencyRate? = CurrencyRateTable.selectAll()
        .where { CurrencyRateTable.currency eq currency }
        .orderBy(CurrencyRateTable.sourceRateAt to SortOrder.DESC, CurrencyRateTable.fetchedAt to SortOrder.DESC)
        .limit(1)
        .firstOrNull()
        ?.toCurrencyRate()
}
