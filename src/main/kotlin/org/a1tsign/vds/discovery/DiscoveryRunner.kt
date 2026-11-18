package org.a1tsign.vds.discovery

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import org.a1tsign.vds.config.DiscoveryConfig
import org.a1tsign.vds.crawler.RunHolder
import org.a1tsign.vds.crawler.RunKind
import org.a1tsign.vds.crawler.RunSlot
import org.a1tsign.vds.crawler.write.CandidateProviderWrite
import org.a1tsign.vds.crawler.write.CatalogApiClient
import org.a1tsign.vds.crawler.write.CatalogApiException
import org.a1tsign.vds.crawler.write.CatalogPage
import org.a1tsign.vds.crawler.write.CatalogProviderSummary
import org.a1tsign.vds.discovery.model.DiscoveryPassSummary
import org.a1tsign.vds.discovery.model.MAX_PLANNED_CANDIDATES
import org.a1tsign.vds.discovery.model.PASS_HISTORY_SIZE
import org.a1tsign.vds.discovery.model.PassFailureReason
import org.a1tsign.vds.discovery.model.PassStatus
import org.a1tsign.vds.discovery.model.PlannedCandidate
import org.a1tsign.vds.discovery.model.RejectionReason
import org.a1tsign.vds.domain.DomainName
import org.a1tsign.vds.domain.ProviderStatus
import org.a1tsign.vds.domain.SystemStatus
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Instant
import java.util.ArrayDeque
import java.util.EnumMap
import java.util.UUID
import java.util.concurrent.TimeUnit

private const val NAME_MAX_LENGTH = 255

private const val DESCRIPTION_MAX_LENGTH = 1000

/** Cap result URLs because dry-run history retains them. */
internal const val RESULT_URL_MAX_LENGTH = 2048

private const val SNIPPET_MAX_LENGTH = 200

private val WHITESPACE_RUN = Regex("\\s+")

/**
 * What one pass is asked to do. [maxQueries] can only **lower** the configured
 * `VDS_DISCOVERY_MAX_QUERIES_PER_PASS`; [force] overrides only the ledger's minimum interval
 * between passes, never the monthly cap.
 */
data class PassRequest(
    val dryRun: Boolean = false,
    val maxQueries: Int? = null,
    val force: Boolean = false,
)

/**
 * A pass was requested while the shared [RunSlot] is held -- by another pass or by a crawl run.
 * [holder] is who had it when the refusal was built (`null` if it was released in between).
 */
class DiscoveryPassInProgressException(val holder: RunHolder?) :
    Exception("The run slot is held by ${holder?.let { "${it.kind} ${it.id}" } ?: "another run"}")

/** Discovery reads paged providers and can create candidates. The production client uses the crawler's loopback API. */
interface CandidateCatalog {
    /** One page of every provider, all three `systemStatus` values included. */
    suspend fun providersPage(page: Int): CatalogPage<CatalogProviderSummary>

    /** Exactly one `POST /api/v1/providers`; a `409` surfaces as [CatalogApiException.Conflict]. */
    suspend fun createCandidate(request: CandidateProviderWrite)
}

class ApiCandidateCatalog(private val client: CatalogApiClient) : CandidateCatalog {
    override suspend fun providersPage(page: Int): CatalogPage<CatalogProviderSummary> = client.listProvidersPage(page)

    override suspend fun createCandidate(request: CandidateProviderWrite) {
        client.createCandidateProvider(request)
    }
}

/**
 * Search results can create candidates only. Discovery never fetches candidate pages or writes their catalog facets.
 * Close the credit pass before releasing the shared slot so an older pass cannot overwrite newer ledger state.
 * Publish a terminal summary on timeout or shutdown. Propagate shutdown cancellation after cleanup.
 */
class DiscoveryRunner(
    private val config: DiscoveryConfig,
    /** `null` when `VDS_DISCOVERY_SEARCH_PROVIDER=none`: every pass then ends immediately. */
    private val searchProvider: SearchProvider?,
    private val ledger: CreditLedger,
    private val catalog: CandidateCatalog,
    private val clock: Clock,
    /** The process-wide slot shared with `CrawlerRunner`; a private one only in isolated tests. */
    private val slot: RunSlot = RunSlot(),
    private val passTimeoutMs: Long = TimeUnit.MINUTES.toMillis(config.passTimeoutMinutes),
    private val queryDelayMs: Long = config.queryDelayMs,
) {

    private val historyLock = Any()
    private val history = ArrayDeque<DiscoveryPassSummary>()

    /** Normalize configured domains with the same rules as search-result hosts so variants still match. */
    private val deniedDomains: Set<String> = canonicalDomains(config.domainDenylist) { entry ->
        logger.warn("discovery denylist entry '{}' is not a domain and is ignored", entry)
    }.toSet()

    @Volatile
    private var active: Active? = null

    /**
     * Takes the shared slot without blocking and publishes the pass's `RUNNING` summary. Returns the
     * new `passId`, or `null` when a pass or a crawl run already holds the slot.
     */
    fun tryReserve(request: PassRequest): UUID? {
        val passId = UUID.randomUUID()
        val holder = slot.tryTake(RunKind.DISCOVERY, passId) ?: return null
        val pass = Active(passId, clock.instant(), System.nanoTime(), request, holder)
        active = pass
        publish(summaryOf(pass, PassStatus.RUNNING, finished = false))
        return passId
    }

    fun slotHolder(): RunHolder? = slot.holder()

    /** Reserve and run; throws [DiscoveryPassInProgressException] instead of waiting. */
    suspend fun runOnce(request: PassRequest = PassRequest()): DiscoveryPassSummary {
        val passId = tryReserve(request) ?: throw DiscoveryPassInProgressException(slot.holder())
        return runReserved(passId)
    }

    /** Runs the pass [tryReserve] handed out. The ledger is closed, then the slot released, however this returns. */
    suspend fun runReserved(passId: UUID): DiscoveryPassSummary {
        val pass = active
        check(pass != null && pass.passId == passId) { "Pass $passId is not the reserved pass" }
        try {
            return execute(pass)
        } catch (cancellation: CancellationException) {
            // Publish the terminal summary and flush credits before propagating shutdown cancellation.
            pass.progress.stop(PassFailureReason.SHUTDOWN, "cancelled: the process is shutting down")
            finish(pass, PassStatus.CANCELLED)
            throw cancellation
        } catch (failure: Exception) {
            logger.debug("discovery pass {} failed unexpectedly", passId, failure)
            pass.progress.stop(PassFailureReason.INTERNAL_ERROR, "${failure.javaClass.simpleName}: ${failure.message}")
            return finish(pass, PassStatus.COMPLETED)
        } catch (error: Throwable) {
            // Publish a terminal summary before propagating a fatal error.
            pass.progress.stop(PassFailureReason.INTERNAL_ERROR, "${error.javaClass.simpleName}: ${error.message}")
            finish(pass, PassStatus.CANCELLED)
            throw error
        } finally {
            // Close the credit pass before another pass can claim the slot and read the ledger.
            pass.budget.close()
            active = null
            slot.release(pass.holder)
        }
    }

    /** The last [PASS_HISTORY_SIZE] summaries, newest first, including the one in flight. */
    fun recentPasses(): List<DiscoveryPassSummary> = synchronized(historyLock) { history.toList() }

    fun pass(passId: UUID): DiscoveryPassSummary? = recentPasses().firstOrNull { it.passId == passId }


    private suspend fun execute(pass: Active): DiscoveryPassSummary {
        logger.info(
            "discovery pass {} starting: dryRun={} force={} maxQueries={} queriesConfigured={}",
            pass.passId,
            pass.request.dryRun,
            pass.request.force,
            pass.request.maxQueries ?: "-",
            config.queries.size,
        )
        val provider = searchProvider
        if (provider == null) {
            pass.progress.stop(PassFailureReason.NO_SEARCH_PROVIDER, "VDS_DISCOVERY_SEARCH_PROVIDER is none: nothing to search with")
            return finish(pass, PassStatus.COMPLETED)
        }
        // Handle only the pass budget here. Shutdown cancellation still unwinds through runReserved.
        val completed = withTimeoutOrNull(passTimeoutMs) {
            runPass(pass, provider)
            true
        } != null
        if (!completed) {
            pass.progress.stop(PassFailureReason.TIMEOUT, "the pass's ${passTimeoutMs} ms budget expired")
            return finish(pass, PassStatus.CANCELLED)
        }
        return finish(pass, PassStatus.COMPLETED)
    }

    private suspend fun runPass(pass: Active, provider: SearchProvider) {
        val progress = pass.progress
        val known = loadKnown(pass) ?: return

        when (val start = ledger.startPass(pass.request.force)) {
            is PassStart.Refused -> {
                progress.creditsRemaining = (start.counters.maxCreditsPerMonth - start.counters.creditsThisMonth).coerceAtLeast(0)
                warnStep(pass, "ledger", start.reason.name, start.message)
                progress.stop(PassFailureReason.BUDGET, "the credit ledger refused the pass: ${start.reason}: ${start.message}")
                return
            }
            is PassStart.Started -> pass.budget.open(start.pass)
        }
        val localRemaining = pass.budget.remaining()
        progress.creditsRemaining = localRemaining

        val vendorRemaining = crossCheck(pass, provider, localRemaining)
        progress.vendorCreditsRemaining = vendorRemaining
        if (vendorRemaining != null && vendorRemaining <= 0) {
            progress.stop(PassFailureReason.BUDGET, "the search vendor reports no credits left")
            return
        }
        // The stricter figure wins: the vendor's remaining credits can only lower the query count.
        val queryLimit = listOfNotNull(config.maxQueriesPerPass, pass.request.maxQueries, vendorRemaining).min()

        for ((index, query) in config.queries.take(queryLimit).withIndex()) {
            if (progress.newCandidates() >= config.maxNewCandidatesPerPass) {
                // Further queries could only add to "deferred": not worth a credit.
                progress.note("stopped searching: the per-pass candidate cap is reached")
                break
            }
            if (index > 0) delay(queryDelayMs)
            currentCoroutineContext().ensureActive()
            when (val decision = pass.budget.request()) {
                is CreditDecision.Refused -> {
                    progress.creditsRemaining = (decision.counters.maxCreditsPerMonth - decision.counters.creditsThisMonth).coerceAtLeast(0)
                    if (decision.reason != BudgetRefusal.PASS_QUERY_CAP_REACHED) {
                        warnStep(pass, "ledger", decision.reason.name, decision.message)
                        progress.stop(PassFailureReason.BUDGET, "the credit ledger refused a query: ${decision.reason}: ${decision.message}")
                    }
                    return
                }
                is CreditDecision.Granted -> {
                    progress.creditsSpent = decision.counters.creditsThisPass
                    progress.creditsRemaining = (decision.counters.maxCreditsPerMonth - decision.counters.creditsThisMonth).coerceAtLeast(0)
                }
            }
            progress.queriesIssued++
            val hits = try {
                provider.search(query, config.maxResultsPerQuery)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: SearchException) {
                // kind + message only, never the exception: a SEARCH_MALFORMED cause quotes the body.
                warnStep(pass, "query ${index + 1}", failure.kind.name, failure.message)
                when (failure.kind) {
                    SearchFailureKind.SEARCH_QUOTA -> {
                        progress.stop(PassFailureReason.SEARCH_QUOTA, failure.message ?: failure.kind.name)
                        return
                    }
                    SearchFailureKind.SEARCH_AUTH -> {
                        progress.stop(PassFailureReason.SEARCH_AUTH, failure.message ?: failure.kind.name)
                        return
                    }
                    else -> {
                        progress.queriesFailed++
                        continue
                    }
                }
            } catch (failure: Exception) {
                // Isolate unexpected search implementation failures and log only the exception class.
                warnStep(pass, "query ${index + 1}", "UNEXPECTED", failure.javaClass.simpleName)
                progress.queriesFailed++
                continue
            }
            for (hit in hits) {
                if (!consider(pass, known, query, hit)) return
            }
        }
    }

    /** Fail if deduplication would need a truncated view of the catalog. */
    private suspend fun loadKnown(pass: Active): KnownSet? {
        val known = KnownSet()
        val max = config.maxKnownProviders
        var providers = 0
        var page = 0
        try {
            while (true) {
                val result = catalog.providersPage(page)
                if (result.totalElements > max) return tooMany(pass, result.totalElements)
                for (row in result.content) {
                    providers++
                    if (providers > max) return tooMany(pass, providers.toLong())
                    val domain = DomainName.normalize(row.websiteUrl)
                    known.add(domain, row.slug)
                    domain?.let { known.add(collapseServiceSubdomain(it), null) }
                }
                page++
                if (page >= result.totalPages || result.content.isEmpty()) break
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (unauthorized: CatalogApiException.Unauthorized) {
            warnStep(pass, "known set", PassFailureReason.CATALOG_AUTH.name, unauthorized.message)
            pass.progress.stop(PassFailureReason.CATALOG_AUTH, unauthorized.message ?: "the catalog rejected the service account")
            return null
        } catch (failure: CatalogApiException) {
            warnStep(pass, "known set", PassFailureReason.CATALOG_UNAVAILABLE.name, failure.message)
            pass.progress.stop(PassFailureReason.CATALOG_UNAVAILABLE, "the catalog could not be read: ${failure.message}")
            return null
        }
        pass.progress.knownProviders = providers
        return known
    }

    private fun tooMany(pass: Active, seen: Long): KnownSet? {
        val message = "the catalog holds more than ${config.maxKnownProviders} providers ($seen seen); " +
            "VDS_DISCOVERY_MAX_KNOWN_PROVIDERS refuses a truncated dedup set"
        warnStep(pass, "known set", PassFailureReason.KNOWN_SET_TOO_LARGE.name, message)
        pass.progress.stop(PassFailureReason.KNOWN_SET_TOO_LARGE, message)
        return null
    }

    /** The advisory `/usage` cross-check, at most once per pass. Its failure never fails the pass. */
    private suspend fun crossCheck(pass: Active, provider: SearchProvider, localRemaining: Int): Int? {
        val usage = try {
            provider.usage()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            warnStep(pass, "usage cross-check", "UNAVAILABLE", failure.javaClass.simpleName)
            null
        }
        val used = usage?.usedCredits
        val limit = usage?.creditLimit
        if (used == null || limit == null) return null
        val vendorRemaining = (limit - used).coerceAtLeast(0)
        if (vendorRemaining < localRemaining) {
            logger.warn(
                "discovery pass {} usage cross-check disagrees: the vendor reports {} credit(s) left, the local ledger {}; the stricter figure applies",
                pass.passId,
                vendorRemaining,
                localRemaining,
            )
        }
        return vendorRemaining
    }

    /**
     * One search hit through the filter and, when it survives, into the catalog. Returns `false`
     * only when the whole pass has to stop (the service account was rejected).
     */
    private suspend fun consider(pass: Active, known: KnownSet, query: String, hit: SearchHit): Boolean {
        val progress = pass.progress
        progress.resultsSeen++
        if (hit.url.length > RESULT_URL_MAX_LENGTH) return progress.reject(RejectionReason.MALFORMED_URL)
        val host = DomainName.normalize(hit.url) ?: return progress.reject(RejectionReason.MALFORMED_URL)
        val domain = collapseServiceSubdomain(host)
        // The host too: an entry such as `docs.microsoft.com` names a subdomain on purpose.
        if (isDenied(host) || isDenied(domain)) return progress.reject(RejectionReason.DENYLIST)
        val slug = DomainName.slug(domain)
        if (known.contains(domain, slug)) return progress.reject(RejectionReason.ALREADY_KNOWN)
        if (isListicle(hit)) return progress.reject(RejectionReason.LISTICLE)
        if (!matchesKeyword(hit)) return progress.reject(RejectionReason.KEYWORD)
        if (slug == null) return progress.reject(RejectionReason.SLUG_TOO_LONG)
        if (progress.newCandidates() >= config.maxNewCandidatesPerPass) {
            progress.candidatesDeferred++
            return true
        }

        val write = CandidateProviderWrite(
            slug = slug,
            name = candidateName(hit.title, domain),
            websiteUrl = "https://$domain/",
            status = ProviderStatus.UNKNOWN.name,
            systemStatus = SystemStatus.CANDIDATE.name,
            description = provenance(pass.startedAt, query, hit),
        )
        // Claim the domain before sending the create so another hit in this pass cannot cause a second POST.
        known.add(domain, slug)

        if (pass.request.dryRun) {
            progress.plannedCount++
            if (progress.planned.size < MAX_PLANNED_CANDIDATES) {
                progress.planned += PlannedCandidate(write.slug, write.name, write.websiteUrl, query, hit.url)
            }
            return true
        }
        try {
            catalog.createCandidate(write)
            progress.candidatesCreated++
            logger.debug("discovery pass {} created candidate {}", pass.passId, slug)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (conflict: CatalogApiException.Conflict) {
            // Another writer or a row the known set did not see won the race: already known.
            progress.reject(RejectionReason.ALREADY_KNOWN)
        } catch (unauthorized: CatalogApiException.Unauthorized) {
            warnStep(pass, "candidate $slug", PassFailureReason.CATALOG_AUTH.name, unauthorized.message)
            progress.stop(PassFailureReason.CATALOG_AUTH, unauthorized.message ?: "the catalog rejected the service account")
            return false
        } catch (failure: CatalogApiException) {
            progress.candidatesFailed++
            warnStep(pass, "candidate $slug", "CANDIDATE_WRITE", failure.message)
        }
        return true
    }

    /** An entry, or any subdomain of one: `old.reddit.com` is as much a forum as `reddit.com`. */
    private fun isDenied(domain: String): Boolean =
        deniedDomains.any { denied -> domain == denied || domain.endsWith(".$denied") }

    private fun matchesKeyword(hit: SearchHit): Boolean {
        val text = "${hit.title.orEmpty()} ${hit.snippet.orEmpty()}".lowercase()
        return config.requiredKeywords.any { text.contains(it) }
    }

    private fun summaryOf(pass: Active, status: PassStatus, finished: Boolean = true): DiscoveryPassSummary {
        val progress = pass.progress
        return DiscoveryPassSummary(
            passId = pass.passId,
            startedAt = pass.startedAt,
            finishedAt = if (finished) clock.instant() else null,
            status = status,
            dryRun = pass.request.dryRun,
            queriesIssued = progress.queriesIssued,
            queriesFailed = progress.queriesFailed,
            creditsSpent = progress.creditsSpent,
            creditsRemainingThisMonth = progress.creditsRemaining,
            vendorCreditsRemaining = progress.vendorCreditsRemaining,
            resultsSeen = progress.resultsSeen,
            resultsRejected = RejectionReason.entries.associateWith { progress.rejected[it] ?: 0 },
            knownProviders = progress.knownProviders,
            candidatesCreated = progress.candidatesCreated,
            candidatesDeferred = progress.candidatesDeferred,
            candidatesFailed = progress.candidatesFailed,
            plannedCandidates = progress.planned.toList(),
            failureReason = progress.failureReason,
            message = progress.message(status, pass.request.dryRun),
        )
    }

    /** Closes the ledger (before the slot is released), publishes the terminal summary and logs it. */
    private fun finish(pass: Active, status: PassStatus): DiscoveryPassSummary {
        pass.budget.close()
        val summary = summaryOf(pass, status)
        publish(summary)
        logger.info(
            "discovery pass {} finished: status={} dryRun={} queries={} failedQueries={} credits={} creditsRemaining={} " +
                "vendorCreditsRemaining={} results={} rejected={} known={} created={} planned={} deferred={} failedWrites={} " +
                "reason={} durationMs={}",
            summary.passId,
            summary.status,
            summary.dryRun,
            summary.queriesIssued,
            summary.queriesFailed,
            summary.creditsSpent,
            summary.creditsRemainingThisMonth ?: "-",
            summary.vendorCreditsRemaining ?: "-",
            summary.resultsSeen,
            summary.resultsRejected,
            summary.knownProviders,
            summary.candidatesCreated,
            pass.progress.plannedCount,
            summary.candidatesDeferred,
            summary.candidatesFailed,
            summary.failureReason ?: "-",
            TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - pass.startedNanos),
        )
        return summary
    }

    private fun publish(summary: DiscoveryPassSummary) = synchronized(historyLock) {
        history.removeIf { it.passId == summary.passId }
        history.addFirst(summary)
        while (history.size > PASS_HISTORY_SIZE) history.removeLast()
    }

    /** One WARN per refused/failed step: the pass, the step, its category and a message that never carries a body or a key. */
    private fun warnStep(pass: Active, step: String, category: String, message: String?) {
        logger.warn("discovery pass {} {} failed: category={} message={}", pass.passId, step, category, message ?: "-")
    }


    /** Close merges persisted counters and prevents further credit requests once the slot can be released. */
    private class PassBudget {
        private var creditPass: CreditPass? = null

        @Volatile
        private var closed = false

        fun open(pass: CreditPass) {
            check(!closed) { "the pass budget is already closed" }
            creditPass = pass
        }

        fun remaining(): Int {
            val counters = checkNotNull(creditPass).counters
            return (counters.maxCreditsPerMonth - counters.creditsThisMonth).coerceAtLeast(0)
        }

        fun request(): CreditDecision {
            check(!closed) { "a closed pass may not request a credit" }
            return checkNotNull(creditPass).requestCredit()
        }

        @Synchronized
        fun close() {
            if (closed) return
            closed = true
            creditPass?.finish()
        }
    }

    private class KnownSet {
        private val domains = HashSet<String>()
        private val slugs = HashSet<String>()

        fun add(domain: String?, slug: String?) {
            domain?.let(domains::add)
            slug?.let(slugs::add)
        }

        fun contains(domain: String, slug: String?): Boolean = domain in domains || (slug != null && slug in slugs)
    }

    private class Progress {
        var queriesIssued = 0
        var queriesFailed = 0
        var creditsSpent = 0
        var creditsRemaining: Int? = null
        var vendorCreditsRemaining: Int? = null
        var resultsSeen = 0
        val rejected = EnumMap<RejectionReason, Int>(RejectionReason::class.java)
        var knownProviders = 0
        var candidatesCreated = 0
        var candidatesDeferred = 0
        var candidatesFailed = 0
        var plannedCount = 0
        val planned = mutableListOf<PlannedCandidate>()
        var failureReason: PassFailureReason? = null
        private var detail: String? = null

        /** Created, or (dry run) planned: what counts against the per-pass candidate cap. */
        fun newCandidates(): Int = candidatesCreated + plannedCount

        /** Always `true`: a rejected hit never stops the pass. */
        fun reject(reason: RejectionReason): Boolean {
            rejected.merge(reason, 1, Int::plus)
            return true
        }

        /** Records why the pass stopped early; the first reason wins. */
        fun stop(reason: PassFailureReason, message: String) {
            if (failureReason != null) return
            failureReason = reason
            detail = message
        }

        fun note(message: String) {
            if (detail == null) detail = message
        }

        fun message(status: PassStatus, dryRun: Boolean): String {
            val what = if (dryRun) "$plannedCount candidate(s) planned (dry run, nothing written)" else "$candidatesCreated candidate(s) created"
            return when (status) {
                PassStatus.RUNNING -> "running"
                else -> listOfNotNull("$queriesIssued quer(y/ies), $what, $candidatesDeferred deferred", detail).joinToString("; ")
            }
        }
    }

    private class Active(
        val passId: UUID,
        val startedAt: Instant,
        val startedNanos: Long,
        val request: PassRequest,
        val holder: RunHolder,
    ) {
        val progress = Progress()
        val budget = PassBudget()
    }

    internal companion object {
        private val logger = LoggerFactory.getLogger(DiscoveryRunner::class.java)

        /** The hit's title, whitespace-collapsed and trimmed to 255 characters, or the domain when it is unusable. */
        fun candidateName(title: String?, domain: String): String {
            val cleaned = title.orEmpty().replace(WHITESPACE_RUN, " ").trim().filterNot(Char::isISOControl)
            if (cleaned.isBlank()) return domain
            return cleaned.safeTake(NAME_MAX_LENGTH).trim()
        }

        /** The provenance line: pass timestamp, the query, the result URL and ≤ 200 characters of snippet; ≤ 1000 in all. */
        fun provenance(passStartedAt: Instant, query: String, hit: SearchHit): String {
            val snippet = hit.snippet.orEmpty().replace(WHITESPACE_RUN, " ").trim().filterNot(Char::isISOControl)
                .safeTake(SNIPPET_MAX_LENGTH)
            val line = "Discovered by web search on $passStartedAt for query \"$query\" at ${hit.url}" +
                if (snippet.isEmpty()) "" else ": $snippet"
            return line.safeTake(DESCRIPTION_MAX_LENGTH)
        }

        /** [take] that never leaves half of a surrogate pair at the end. */
        private fun String.safeTake(n: Int): String {
            if (length <= n) return this
            val cut = take(n)
            return if (cut.last().isHighSurrogate()) cut.dropLast(1) else cut
        }
    }
}
