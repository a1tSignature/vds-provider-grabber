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

/** `provider.name`'s column width. */
private const val NAME_MAX_LENGTH = 255

/** The provenance line written as a candidate's `description`. */
private const val DESCRIPTION_MAX_LENGTH = 1000

/**
 * A search hit whose URL is longer than this is refused as `MALFORMED_URL`. No real landing page
 * needs more, and the raw URL is kept in a dry run's plan for the whole pass history, so an
 * unbounded one is retained heap on a 192 MB JVM.
 */
internal const val RESULT_URL_MAX_LENGTH = 2048

/** How much of the search snippet the provenance line keeps. */
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

/**
 * The catalog as a pass sees it: one paged read of every provider and one create. Production goes
 * through [ApiCandidateCatalog], i.e. the crawler's `CatalogApiClient` over loopback HTTP -- this
 * package imports neither `…vds.db` nor `…vds.service`.
 */
interface CandidateCatalog {
    /** One page of every provider, all three `systemStatus` values included. */
    suspend fun providersPage(page: Int): CatalogPage<CatalogProviderSummary>

    /** Exactly one `POST /api/v1/providers`; a `409` surfaces as [CatalogApiException.Conflict]. */
    suspend fun createCandidate(request: CandidateProviderWrite)
}

/** [CandidateCatalog] over the crawler's loopback API client, which the caller owns and closes. */
class ApiCandidateCatalog(private val client: CatalogApiClient) : CandidateCatalog {
    override suspend fun providersPage(page: Int): CatalogPage<CatalogProviderSummary> = client.listProvidersPage(page)

    override suspend fun createCandidate(request: CandidateProviderWrite) {
        client.createCandidateProvider(request)
    }
}

/**
 * One discovery pass = **load the known-domain set → for each configured query: search → filter →
 * create candidates → publish a summary.** The same shape as `CrawlerRunner`, one layer earlier.
 *
 * ### What a pass never does
 *
 * It never fetches a page of a candidate (a candidate is created on the search result alone), never
 * writes legal info, payment methods, locations or tariffs, never deletes or deactivates anything,
 * and never touches a row it did not create.
 *
 * ### One pass at a time, and never alongside a crawl run
 *
 * The [RunSlot] is shared with `CrawlerRunner` (one instance, created in `AppComponents`). That
 * slot is also what makes the [CreditLedger] correct: `CreditPass.requestCredit()` writes the
 * pass's own copy of the state file, so **only one live `CreditPass` may exist**. Therefore:
 *
 * - every credit request happens inside the pass's own coroutine, after an `ensureActive()`, so a
 *   pass whose budget expired or that is being cancelled asks for no further credit;
 * - the `CreditPass` is closed -- `finish()`, which merges with the file -- **before** the slot
 *   is released, and a closed pass refuses every later request ([PassBudget]);
 * - so the next pass's `startPass()` always reads a file no older pass will write over again.
 *
 * ### Budgets and failure isolation
 *
 * `withTimeoutOrNull(VDS_DISCOVERY_PASS_TIMEOUT_MINUTES)` around the whole pass: expiry ends it
 * `CANCELLED` with the counters gathered so far and a summary still published -- never an exception
 * instead of one. A shutdown's [CancellationException] is re-thrown **after** the summary is
 * published (the `CrawlerRunner.runReserved` precedent). `SEARCH_QUOTA`/`SEARCH_AUTH` end the pass;
 * every other search failure is counted and the next query runs. A candidate whose create fails is
 * counted and the next one is tried; only a rejected service account ends the pass.
 *
 * ### Logging
 *
 * One INFO line opening and one closing a pass, one WARN per failed or refused step with its
 * category. A [SearchException]'s **kind and message only** -- never the exception itself: a
 * `SEARCH_MALFORMED` one carries a `SerializationException` whose message quotes the vendor's
 * response body. Never the API key, never a response body, never a full snippet.
 *
 * ### No Ktor server type, by ADR-01
 *
 * Nothing here imports a Ktor server type, `AppComponents` or an `ApplicationCall`: moving
 * discovery out of process costs one new `main()` that calls [runOnce].
 *
 * ### Time
 *
 * `startedAt`/`finishedAt` and the provenance timestamp come from the injected [clock]; durations
 * from `System.nanoTime()`; the wall clock is never read directly.
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
    /** Defaults are the configured production values; parameters so a test needs no real minutes. */
    private val passTimeoutMs: Long = TimeUnit.MINUTES.toMillis(config.passTimeoutMinutes),
    private val queryDelayMs: Long = config.queryDelayMs,
) {

    private val historyLock = Any()
    private val history = ArrayDeque<DiscoveryPassSummary>()

    /**
     * The configured denylist in the same canonical form a search hit is compared in
     * ([DomainName.normalize]): without it `www.facebook.com`, `reddit.com.` or `пример.рф` would
     * sit in the configuration and never match anything. An entry may be a bare host or a full
     * URL; one that is not a domain at all is dropped with a WARN rather than failing the start.
     */
    private val deniedDomains: Set<String> = config.domainDenylist.mapNotNullTo(LinkedHashSet()) { entry ->
        val normalized = DomainName.normalize(if (entry.contains("://")) entry else "https://$entry/")
        if (normalized == null) {
            logger.warn("discovery denylist entry '{}' is not a domain and is ignored", entry)
        }
        normalized
    }

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

    /** Who holds the shared slot right now -- for a WARN or a `409` message. */
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
            // Shutdown. Close the ledger, publish, and only then re-throw -- never swallow it.
            pass.progress.stop(PassFailureReason.SHUTDOWN, "cancelled: the process is shutting down")
            finish(pass, PassStatus.CANCELLED)
            throw cancellation
        } catch (failure: Exception) {
            // A bug, not a pass outcome: the operator gets a summary, the stack trace is DEBUG.
            logger.debug("discovery pass {} failed unexpectedly", passId, failure)
            pass.progress.stop(PassFailureReason.INTERNAL_ERROR, "${failure.javaClass.simpleName}: ${failure.message}")
            return finish(pass, PassStatus.COMPLETED)
        } catch (error: Throwable) {
            // An Error (StackOverflowError, a failed initializer, ...) is not ours to handle, but
            // the pass must not stay RUNNING in the history for ever: publish, then re-throw.
            pass.progress.stop(PassFailureReason.INTERNAL_ERROR, "${error.javaClass.simpleName}: ${error.message}")
            finish(pass, PassStatus.CANCELLED)
            throw error
        } finally {
            // Order matters (the ledger invariant above): the CreditPass is closed before the slot
            // is free for the next pass. Every branch above already closed it via finish(); this
            // is the backstop should finish() itself throw.
            pass.budget.close()
            active = null
            slot.release(pass.holder)
        }
    }

    /** The last [PASS_HISTORY_SIZE] summaries, newest first, including the one in flight. */
    fun recentPasses(): List<DiscoveryPassSummary> = synchronized(historyLock) { history.toList() }

    fun pass(passId: UUID): DiscoveryPassSummary? = recentPasses().firstOrNull { it.passId == passId }

    // ---------- the pass ----------

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
        // withTimeoutOrNull returns null for its own expiry only; a shutdown's cancellation is
        // re-thrown and handled by runReserved.
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
                // The SearchProvider contract allows only SearchException; anything else is a bug in
                // a provider and is isolated like one broken adapter. Class name only.
                warnStep(pass, "query ${index + 1}", "UNEXPECTED", failure.javaClass.simpleName)
                progress.queriesFailed++
                continue
            }
            for (hit in hits) {
                if (!consider(pass, known, query, hit)) return
            }
        }
    }

    /**
     * The known set, or `null` when the pass has to stop (the reason is recorded). Bounded by
     * `VDS_DISCOVERY_MAX_KNOWN_PROVIDERS`: exceeding it fails the pass, because a truncated set
     * would let the pass create duplicates.
     */
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
                    known.add(DomainName.normalize(row.websiteUrl), row.slug)
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
        val domain = DomainName.normalize(hit.url) ?: return progress.reject(RejectionReason.MALFORMED_URL)
        if (isDenied(domain)) return progress.reject(RejectionReason.DENYLIST)
        val slug = DomainName.slug(domain)
        if (known.contains(domain, slug)) return progress.reject(RejectionReason.ALREADY_KNOWN)
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
        // Known from now on, whatever happens below: a second hit on the same domain in this pass
        // is a counted no-op, not a second POST.
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

    /** Keywords are lower-cased by `loadConfig`; the text is lower-cased here. */
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

    // ---------- state of the pass in flight ----------

    /**
     * The one [CreditPass] of a pass. [close] flushes it (merging with the file) and makes every
     * later [request] an `IllegalStateException`, so a pass that has let go of the slot can never write
     * its stale copy of the ledger over a newer pass's.
     */
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

    /** Normalized hosts and slugs of every provider in the catalog, plus what this pass created. */
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
