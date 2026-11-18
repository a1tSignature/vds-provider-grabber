package org.a1tsign.vds.discovery.support

import org.a1tsign.vds.discovery.SearchHit
import org.a1tsign.vds.discovery.SearchProvider
import org.a1tsign.vds.discovery.SearchUsage
import java.util.concurrent.CopyOnWriteArrayList

data class RecordedSearch(val query: String, val limit: Int)

/** Replay search replies in order and record queries without HTTP. */
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
