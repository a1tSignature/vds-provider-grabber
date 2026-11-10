package org.a1tsign.vds.discovery.support

import org.a1tsign.vds.discovery.SearchHit
import org.a1tsign.vds.discovery.SearchProvider
import org.a1tsign.vds.discovery.SearchUsage
import java.util.concurrent.CopyOnWriteArrayList

/** One recorded call, so a pass test can assert which queries were issued, in which order. */
data class RecordedSearch(val query: String, val limit: Int)

/**
 * Deterministic external boundary for discovery tests, mirroring
 * `support/StubCurrencyRateProvider`: never performs HTTP, never spends a credit.
 *
 * [answers] decides per query (a `SearchException` in a `Result.failure` is rethrown by
 * [search], which is how a pass test exercises a failure category without a fixture server);
 * [next] is the simpler "every query answers this" form.
 */
class StubSearchProvider(
    var next: Result<List<SearchHit>> = Result.success(emptyList()),
    var answers: (String) -> Result<List<SearchHit>>? = { null },
    var usage: SearchUsage? = null,
) : SearchProvider {

    private val recorded = CopyOnWriteArrayList<RecordedSearch>()

    fun calls(): List<RecordedSearch> = recorded.toList()

    override suspend fun search(query: String, limit: Int): List<SearchHit> {
        recorded += RecordedSearch(query, limit)
        return (answers(query) ?: next).getOrThrow()
    }

    override suspend fun usage(): SearchUsage? = usage
}
