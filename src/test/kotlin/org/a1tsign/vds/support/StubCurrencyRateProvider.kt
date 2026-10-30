package org.a1tsign.vds.support

import org.a1tsign.vds.fx.CurrencyRateProvider
import org.a1tsign.vds.fx.FetchedRates

/** Deterministic external boundary for FX tests; never performs HTTP. */
class StubCurrencyRateProvider(var next: Result<FetchedRates>) : CurrencyRateProvider {
    override suspend fun fetchRates(): FetchedRates = next.getOrThrow()
}
