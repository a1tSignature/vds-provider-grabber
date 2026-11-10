package org.a1tsign.vds.discovery

/**
 * One search result, reduced to what the discovery pass is allowed to keep: the URL plus the two
 * display strings. No page content, no vendor score, no vendor field name -- see [SearchProvider].
 */
data class SearchHit(
    val url: String,
    val title: String? = null,
    val snippet: String? = null,
)

/**
 * This key's and the account's credit usage as the provider reports it, for the **advisory**
 * cross-check of [SearchProvider.usage]. Both values are nullable because a provider may not
 * report them at all, and because the window they describe is the vendor's, not ours.
 */
data class SearchUsage(
    val usedCredits: Int?,
    val creditLimit: Int?,
)

/**
 * Why a search did not return hits. Deliberately **distinct categories**, never folded into the
 * crawler's generic `ADAPTER_ERROR`: each one implies a different reaction (stop spending, fix the
 * key, back off, retry later, look at the vendor's response shape), and an operator must be able to
 * tell "we are out of credits" from "the vendor is down" without reading a stack trace.
 */
enum class SearchFailureKind {
    /** The plan's usage limit is reached. Stop: every further query is either refused or billed. */
    SEARCH_QUOTA,

    /** The key is missing, wrong or revoked. Ends the pass immediately; retrying cannot help. */
    SEARCH_AUTH,

    /** Too many requests. Honoured once when the provider says how long to wait, else end the pass. */
    SEARCH_RATE_LIMIT,

    /** The provider did not answer usefully: a server error, a transport failure or a timeout. */
    SEARCH_UNAVAILABLE,

    /** The provider answered `200` with a body this build cannot read into the expected shape. */
    SEARCH_MALFORMED,
}

/**
 * A search that failed, with its [kind]. The message names the category and, where there is one,
 * the HTTP status -- and **never** the API key, a request header or a response body.
 */
class SearchException(
    val kind: SearchFailureKind,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * The boundary between the discovery pass and whichever web-search API is current. Shaped on the
 * `fx/CurrencyRateProvider` precedent -- an interface, exactly **one** real implementation
 * ([TavilySearchProvider]), one test stub, bounded timeouts -- and for the same reason, made sharper
 * by this category's history: of the three incumbent search APIs, two were withdrawn inside 18
 * months (vault `07-knowledge/vds-provider-grabber-search-api-landscape`). This interface *is* the
 * migration plan, so **no vendor type, status code, parameter or field name may appear in its
 * signature**; everything vendor-specific stays behind it, configured through `VDS_DISCOVERY_*`.
 */
interface SearchProvider {

    /**
     * At most [limit] results for [query], as a whole-web search.
     *
     * @throws SearchException with a [SearchFailureKind]; implementations throw nothing else.
     */
    suspend fun search(query: String, limit: Int): List<SearchHit>

    /**
     * The provider's own view of credit usage, or `null` when it cannot or will not say.
     *
     * **Advisory only**: a failure here never fails anything, which is why the result is nullable
     * instead of an exception. The local credit ledger stays authoritative for refusals, and on a
     * disagreement the stricter of the two wins.
     */
    suspend fun usage(): SearchUsage? = null
}
