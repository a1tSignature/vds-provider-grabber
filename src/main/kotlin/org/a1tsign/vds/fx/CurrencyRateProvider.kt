package org.a1tsign.vds.fx

import java.math.BigDecimal
import java.time.Instant

data class FetchedRates(
    val sourceName: String,
    val sourceRateAt: Instant,
    /** Upstream units of each currency per one USD; ingestion stores their inverse. */
    val rates: Map<String, BigDecimal>,
)

interface CurrencyRateProvider {
    /** Fetches upstream units per USD or throws; callers must tolerate upstream failures. */
    suspend fun fetchRates(): FetchedRates
}
