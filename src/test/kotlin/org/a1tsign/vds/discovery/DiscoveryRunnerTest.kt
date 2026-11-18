package org.a1tsign.vds.discovery

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.SerializationException
import org.a1tsign.vds.config.DiscoveryConfig
import org.a1tsign.vds.config.DiscoverySearchProvider
import org.a1tsign.vds.crawler.RunKind
import org.a1tsign.vds.crawler.RunSlot
import org.a1tsign.vds.crawler.write.CandidateProviderWrite
import org.a1tsign.vds.crawler.write.CatalogApiException
import org.a1tsign.vds.crawler.write.CatalogPage
import org.a1tsign.vds.crawler.write.CatalogProviderSummary
import org.a1tsign.vds.discovery.model.PASS_HISTORY_SIZE
import org.a1tsign.vds.discovery.model.PassFailureReason
import org.a1tsign.vds.discovery.model.PassStatus
import org.a1tsign.vds.discovery.model.RejectionReason
import org.a1tsign.vds.discovery.support.StubSearchProvider
import org.a1tsign.vds.support.MutableTestClock
import org.a1tsign.vds.support.capturingLogs
import org.junit.jupiter.api.io.TempDir
import org.slf4j.event.Level
import org.slf4j.LoggerFactory
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/** Isolate pass logic with in-memory peers and keep real ledger persistence. */
class DiscoveryRunnerTest {

    @TempDir
    lateinit var dir: Path

    private val clock = MutableTestClock(Instant.parse("2026-11-10T08:00:00Z"))

    private class FakeCatalog(
        val existing: List<CatalogProviderSummary> = emptyList(),
        var onCreate: (CandidateProviderWrite) -> Unit = {},
    ) : CandidateCatalog {
        val created = CopyOnWriteArrayList<CandidateProviderWrite>()

        override suspend fun providersPage(page: Int): CatalogPage<CatalogProviderSummary> {
            val size = 2
            val rows = existing.drop(page * size).take(size)
            return CatalogPage(rows, page, size, existing.size.toLong(), (existing.size + size - 1) / size)
        }

        override suspend fun createCandidate(request: CandidateProviderWrite) {
            onCreate(request)
            created += request
        }
    }

    private fun config(
        queries: List<String> = listOf("q1"),
        maxNew: Int = 20,
        maxQueries: Int = 10,
        maxKnown: Int = 5000,
        maxCredits: Int = 300,
        queryDelayMs: Long = 1,
        denylist: List<String> = listOf("reddit.com"),
    ) = DiscoveryConfig(
        enabled = true, sourcesDir = null, sourcesStrict = false, maxSources = 200,
        searchProvider = DiscoverySearchProvider.TAVILY, searchApiKey = null, searchEndpoint = "http://127.0.0.1:1",
        searchTimeoutSeconds = 10, maxResponseBytes = 524_288, queries = queries, queryDelayMs = queryDelayMs,
        maxQueriesPerPass = maxQueries, maxCreditsPerMonth = maxCredits, maxResultsPerQuery = 10,
        maxNewCandidatesPerPass = maxNew, maxKnownProviders = maxKnown,
        domainDenylist = denylist, requiredKeywords = listOf("vps"),
        intervalHours = 48, initialDelayMinutes = 15, minPassIntervalHours = 24, passTimeoutMinutes = 10, stateFile = null,
    )

    private fun ledger(config: DiscoveryConfig) =
        CreditLedger(dir.resolve("state.json"), clock, config.maxCreditsPerMonth, config.maxQueriesPerPass, config.minPassIntervalHours)

    private fun runner(
        config: DiscoveryConfig,
        search: SearchProvider,
        catalog: CandidateCatalog = FakeCatalog(),
        slot: RunSlot = RunSlot(),
        passTimeoutMs: Long = 10_000,
        ledger: CreditLedger = ledger(config),
    ) = DiscoveryRunner(config, search, ledger, catalog, clock, slot, passTimeoutMs, config.queryDelayMs)

    private fun vpsHit(domain: String) = SearchHit("https://$domain/plans", "$domain VPS", "cheap vps")

    private fun creditsThisMonth(ledger: CreditLedger): Int = ledger.readState().getOrThrow().months["2026-11"] ?: 0

    @Test
    fun `the per-pass candidate cap creates exactly that many, defers the rest and stops searching`() = runBlocking {
        val search = StubSearchProvider(next = Result.success(listOf("a.example", "b.example", "c.example", "d.example").map(::vpsHit)))
        val catalog = FakeCatalog()

        val summary = runner(config(queries = listOf("q1", "q2"), maxNew = 2), search, catalog).runOnce()

        assertEquals(listOf("a-example", "b-example"), catalog.created.map { it.slug })
        assertEquals(2, summary.candidatesCreated)
        assertEquals(2, summary.candidatesDeferred)
        assertEquals(listOf("q1"), search.calls().map { it.query }, "no credit is spent once the cap is reached")
    }

    @Test
    fun `maxQueries lowers the per-pass query count, and queries keep the configured gap`() = runBlocking {
        val search = StubSearchProvider()
        val started = System.nanoTime()
        val summary = runner(config(queries = listOf("q1", "q2", "q3", "q4"), queryDelayMs = 150), search)
            .runOnce(PassRequest(maxQueries = 3))

        assertTrue(System.nanoTime() - started >= 300_000_000, "a gap before each query but the first")
        assertEquals(listOf("q1", "q2", "q3"), search.calls().map { it.query })
        assertEquals(3, summary.queriesIssued)
        assertEquals(3, summary.creditsSpent)
        assertEquals(297, summary.creditsRemainingThisMonth)
    }

    @Test
    fun `the monthly cap and the minimum interval end a pass as BUDGET, and force overrides only the interval`() = runBlocking {
        val cfg = config(queries = listOf("q1", "q2"), maxCredits = 3)
        val search = StubSearchProvider()
        val runner = runner(cfg, search)

        assertEquals(2, runner.runOnce().creditsSpent)
        val tooSoon = runner.runOnce()
        assertEquals(PassFailureReason.BUDGET, tooSoon.failureReason)
        assertEquals(0, tooSoon.queriesIssued)
        assertEquals(1, tooSoon.creditsRemainingThisMonth)

        val forced = runner.runOnce(PassRequest(force = true))
        assertEquals(PassFailureReason.BUDGET, forced.failureReason, "the third credit is the last of the month")
        assertEquals(1, forced.creditsSpent)
        assertEquals(0, forced.creditsRemainingThisMonth)
        assertEquals(listOf("q1", "q2", "q1"), search.calls().map { it.query })
    }

    @Test
    fun `the stricter vendor figure lowers the query count, with a WARN`() = runBlocking {
        val search = StubSearchProvider(usage = SearchUsage(usedCredits = 998, creditLimit = 1000))
        lateinit var summary: org.a1tsign.vds.discovery.model.DiscoveryPassSummary
        val logs = capturingLogs {
            summary = runBlocking { runner(config(queries = listOf("q1", "q2", "q3")), search).runOnce() }
        }

        assertEquals(2, summary.vendorCreditsRemaining)
        assertEquals(listOf("q1", "q2"), search.calls().map { it.query })
        assertTrue(logs.messagesAt(Level.WARN).any { it.contains("cross-check disagrees") }, logs.messages().toString())

        val exhausted = StubSearchProvider(usage = SearchUsage(usedCredits = 1000, creditLimit = 1000))
        clock.set(clock.instant().plusSeconds(25 * 3600))
        val none = runner(config(), exhausted).runOnce()
        assertEquals(PassFailureReason.BUDGET, none.failureReason)
        assertEquals(0, none.vendorCreditsRemaining)
        assertEquals(298, none.creditsRemainingThisMonth, "the local figure is still reported")
        assertTrue(exhausted.calls().isEmpty())
    }

    @Test
    fun `with no search provider a pass ends at once and leaves the ledger untouched`() = runBlocking {
        val ledger = ledger(config())
        val summary = DiscoveryRunner(config(), null, ledger, FakeCatalog(), clock).runOnce()

        assertEquals(PassStatus.COMPLETED, summary.status)
        assertEquals(PassFailureReason.NO_SEARCH_PROVIDER, summary.failureReason)
        assertNull(ledger.readState().getOrThrow().lastPassAt)
    }

    @Test
    fun `the candidate name and provenance are bounded and never split a character`() {
        val domain = "acme.example"
        assertEquals(domain, DiscoveryRunner.candidateName("  \n ", domain))
        assertEquals(255, DiscoveryRunner.candidateName("V".repeat(300), domain).length)
        assertEquals(254, DiscoveryRunner.candidateName("V".repeat(254) + "\uD83D\uDE00", domain).length, "a pair cut in half is dropped")

        val line = DiscoveryRunner.provenance(
            Instant.parse("2026-11-10T08:00:00Z"),
            "vps hosting",
            SearchHit("https://acme.example/" + "p".repeat(2000), "t", "S".repeat(500)),
        )
        assertEquals(1000, line.length)
        val short = DiscoveryRunner.provenance(Instant.parse("2026-11-10T08:00:00Z"), "vps hosting", SearchHit("https://acme.example/", "t", "S".repeat(500)))
        assertTrue(short.startsWith("Discovered by web search on 2026-11-10T08:00:00Z for query \"vps hosting\" at https://acme.example/"), short)
        assertEquals(200, short.count { it == 'S' })
    }

    @Test
    fun `denylist entries are matched in normalized form, and one that is not a domain is ignored`() = runBlocking {
        val denylist = listOf("www.facebook.com", "reddit.com.", "https://Forum.Example/board", "пример.рф", "not a domain")
        val hits = listOf("www.facebook.com", "old.reddit.com", "forum.example", "xn--e1afmkfd.xn--p1ai", "good.example").map(::vpsHit)
        val catalog = FakeCatalog()

        val summary = runner(config(denylist = denylist), StubSearchProvider(next = Result.success(hits)), catalog).runOnce()

        assertEquals(listOf("good-example"), catalog.created.map { it.slug })
        assertEquals(4, summary.resultsRejected[RejectionReason.DENYLIST])
    }

    @Test
    fun `service and edition subdomains collapse to the parent before dedup and candidate creation`() = runBlocking {
        val known = CatalogProviderSummary(1, "virmach-com", "VirMach", websiteUrl = "https://virmach.com/", systemStatus = "CANDIDATE")
        val hits = listOf("billing.virmach.com", "kb.gozenhost.com", "us.gozenhost.com", "vps.xserver.ne.jp", "en.co.uk").map(::vpsHit)
        val catalog = FakeCatalog(existing = listOf(known))

        val summary = runner(config(), StubSearchProvider(next = Result.success(hits)), catalog).runOnce()

        assertEquals(listOf("gozenhost-com", "vps-xserver-ne-jp", "en-co-uk"), catalog.created.map { it.slug })
        assertEquals("https://gozenhost.com/", catalog.created.first().websiteUrl)
        assertEquals(2, summary.resultsRejected[RejectionReason.ALREADY_KNOWN])
    }

    @Test
    fun `ranking pages are rejected as listicles while a provider's own blog post is kept`() = runBlocking {
        val hits = listOf(
            SearchHit("https://ranker.example/", "Top 10 VPS hosting in Germany 2026", "best vps"),
            SearchHit("https://vergleich.example/", "vServer Vergleich 2026", "vps"),
            SearchHit("https://tags.example/tag/cheap-vps", "Cheap VPS", "vps"),
            SearchHit("https://provider.example/blog/nvme-vps", "NVMe VPS Hosting: the complete guide", "vps"),
        )
        val catalog = FakeCatalog()

        val summary = runner(config(), StubSearchProvider(next = Result.success(hits)), catalog).runOnce()

        assertEquals(listOf("provider-example"), catalog.created.map { it.slug })
        assertEquals(3, summary.resultsRejected[RejectionReason.LISTICLE])
    }

    @Test
    fun `a hit with an oversized URL is rejected as malformed and never kept in a dry run's plan`() = runBlocking {
        val oversized = SearchHit("https://long.example/" + "a".repeat(RESULT_URL_MAX_LENGTH), "long VPS", "cheap vps")
        val search = StubSearchProvider(next = Result.success(listOf(oversized, vpsHit("ok.example"))))

        val summary = runner(config(), search).runOnce(PassRequest(dryRun = true))

        assertEquals(listOf("ok-example"), summary.plannedCandidates.map { it.slug })
        assertEquals(1, summary.resultsRejected[RejectionReason.MALFORMED_URL])
    }

    @Test
    fun `an Error still publishes a terminal summary, frees the slot and propagates`() = runBlocking {
        val slot = RunSlot()
        val catalog = FakeCatalog(onCreate = { throw StackOverflowError("boom") })
        val runner = runner(config(), StubSearchProvider(next = Result.success(listOf(vpsHit("a.example")))), catalog, slot)

        assertFailsWith<StackOverflowError> { runner.runOnce() }

        val summary = runner.recentPasses().single()
        assertEquals(PassStatus.CANCELLED, summary.status)
        assertEquals(PassFailureReason.INTERNAL_ERROR, summary.failureReason)
        assertNotNull(summary.finishedAt)
        assertNull(slot.holder())
    }

    @Test
    fun `a dry run plans at most 200 candidates and writes nothing`() = runBlocking {
        val catalog = FakeCatalog()
        val search = StubSearchProvider(next = Result.success((1..201).map { vpsHit("d$it.example") }))

        val summary = runner(config(maxNew = 500), search, catalog).runOnce(PassRequest(dryRun = true))

        assertEquals(200, summary.plannedCandidates.size)
        assertEquals(0, summary.candidatesCreated)
        assertTrue(catalog.created.isEmpty())
        assertTrue(summary.message!!.contains(", 201 candidate(s) planned"), summary.message)

        clock.set(clock.instant().plusSeconds(25 * 3600))
        val capped = runner(config(maxNew = 2), search, catalog).runOnce(PassRequest(dryRun = true))
        assertEquals(2, capped.plannedCandidates.size, "a dry run plans what a real pass would create")
        assertEquals(199, capped.candidatesDeferred)
    }

    @Test
    fun `a 409 is counted as already known and a 4xx is counted as failed, and the pass goes on`() = runBlocking {
        val catalog = FakeCatalog(
            onCreate = { write ->
                when (write.slug) {
                    "raced-example" -> throw CatalogApiException.Conflict("POST", "/api/v1/providers", "Provider slug already exists")
                    "bad-example" -> throw CatalogApiException.ClientError(400, "POST", "/api/v1/providers", "Validation failed")
                }
            },
        )
        val search = StubSearchProvider(
            next = Result.success(listOf("raced.example", "bad.example", "ok.example", "ok.example", "reddit.com").map(::vpsHit)),
        )
        lateinit var summary: org.a1tsign.vds.discovery.model.DiscoveryPassSummary
        val logs = capturingLogs { summary = runBlocking { runner(config(), search, catalog).runOnce() } }

        assertEquals(PassStatus.COMPLETED, summary.status)
        assertNull(summary.failureReason)
        assertEquals(2, summary.resultsRejected[RejectionReason.ALREADY_KNOWN], "the 409 and the second ok.example")
        assertEquals(1, summary.resultsRejected[RejectionReason.DENYLIST])
        assertEquals(1, summary.candidatesFailed)
        assertEquals(listOf("ok-example"), catalog.created.map { it.slug })
        assertTrue(logs.messagesAt(Level.WARN).any { it.contains("category=CANDIDATE_WRITE") }, logs.messages().toString())
    }

    @Test
    fun `a rejected service account ends the pass, and an unreadable catalog fails it before any credit`() = runBlocking {
        var attempts = 0
        val catalog = FakeCatalog(onCreate = { attempts++; throw CatalogApiException.Unauthorized("rejected twice") })
        val search = StubSearchProvider(next = Result.success(listOf("a.example", "b.example").map(::vpsHit)))
        val summary = runner(config(queries = listOf("q1", "q2")), search, catalog).runOnce()
        assertEquals(PassFailureReason.CATALOG_AUTH, summary.failureReason)
        assertEquals(1, attempts)
        assertEquals(listOf("q1"), search.calls().map { it.query })

        val rejecting = object : CandidateCatalog {
            override suspend fun providersPage(page: Int): CatalogPage<CatalogProviderSummary> =
                throw CatalogApiException.Unauthorized("rejected twice")
            override suspend fun createCandidate(request: CandidateProviderWrite) = fail("no write without a known set")
        }
        assertEquals(PassFailureReason.CATALOG_AUTH, runner(config(), StubSearchProvider(), rejecting).runOnce().failureReason)

        val down = object : CandidateCatalog {
            override suspend fun providersPage(page: Int): CatalogPage<CatalogProviderSummary> =
                throw CatalogApiException.ServerError(503, "GET", "/api/v1/providers", "Service Unavailable")
            override suspend fun createCandidate(request: CandidateProviderWrite) = fail("no write without a known set")
        }
        val ledger = ledger(config())
        clock.set(clock.instant().plusSeconds(25 * 3600))
        val failed = runner(config(), StubSearchProvider(), down, ledger = ledger).runOnce()
        assertEquals(PassFailureReason.CATALOG_UNAVAILABLE, failed.failureReason)
        assertEquals(1, creditsThisMonth(ledger), "only the first pass's credit")
    }

    @Test
    fun `exceeding MAX_KNOWN_PROVIDERS fails the pass before any credit is spent`() = runBlocking {
        val existing = (1..3L).map { CatalogProviderSummary(it, "p$it", "P$it", "https://p$it.example") }
        val search = StubSearchProvider()
        val ledger = ledger(config(maxKnown = 2))

        val summary = runner(config(maxKnown = 2), search, FakeCatalog(existing), ledger = ledger).runOnce()

        assertEquals(PassFailureReason.KNOWN_SET_TOO_LARGE, summary.failureReason)
        assertTrue(search.calls().isEmpty())
        assertEquals(0, creditsThisMonth(ledger))

        // Exactly at the bound is fine, read over two pages; both keys of every row are known.
        val search2 = StubSearchProvider(next = Result.success(listOf(vpsHit("p3.example"), SearchHit("https://other.example/", "p1 vps", "vps"))))
        val atBound = runner(config(maxKnown = 3), search2, FakeCatalog(existing)).runOnce(PassRequest(force = true))
        assertNull(atBound.failureReason, atBound.message)
        assertEquals(3, atBound.knownProviders)
        assertEquals(1, atBound.resultsRejected[RejectionReason.ALREADY_KNOWN])
    }

    @Test
    fun `SEARCH_QUOTA ends the pass, other categories do not, and a malformed body is never logged`() = runBlocking {
        val bodyExcerpt = "JSON input: {\"secret vendor body\""
        val failures = mapOf(
            "q1" to SearchException(SearchFailureKind.SEARCH_MALFORMED, "SEARCH_MALFORMED: the response body did not parse", SerializationException(bodyExcerpt)),
            "q2" to SearchException(SearchFailureKind.SEARCH_UNAVAILABLE, "SEARCH_UNAVAILABLE: HTTP 503"),
            "q3" to IllegalStateException("a provider bug"),
            "q4" to SearchException(SearchFailureKind.SEARCH_QUOTA, "SEARCH_QUOTA: HTTP 432"),
        )
        val search = StubSearchProvider(answers = { query -> failures[query]?.let { Result.failure(it) } })
        lateinit var summary: org.a1tsign.vds.discovery.model.DiscoveryPassSummary
        val logs = capturingLogs {
            summary = runBlocking { runner(config(queries = listOf("q1", "q2", "q3", "q4", "q5")), search).runOnce() }
        }

        assertEquals(listOf("q1", "q2", "q3", "q4"), search.calls().map { it.query })
        assertEquals(PassFailureReason.SEARCH_QUOTA, summary.failureReason)
        assertEquals(3, summary.queriesFailed)
        assertTrue(logs.messagesAt(Level.WARN).any { it.contains("SEARCH_MALFORMED") })
        assertTrue(logs.messages().none { it.contains("secret vendor body") })
        assertTrue(logs.throwablesAt(Level.WARN).isEmpty(), "no stack trace / cause: ${logs.throwablesAt(Level.WARN)}")

        val auth = StubSearchProvider(next = Result.failure(SearchException(SearchFailureKind.SEARCH_AUTH, "SEARCH_AUTH: HTTP 401")))
        clock.set(clock.instant().plusSeconds(25 * 3600))
        val authSummary = runner(config(queries = listOf("q1", "q2")), auth).runOnce()
        assertEquals(PassFailureReason.SEARCH_AUTH, authSummary.failureReason)
        assertEquals(1, auth.calls().size)
    }

    @Test
    fun `a pass that outlives its budget ends CANCELLED with a published summary and its credits persisted`() = runBlocking {
        val search = object : SearchProvider {
            override suspend fun search(query: String, limit: Int): List<SearchHit> = awaitCancellation()
        }
        val ledger = ledger(config())
        val slot = RunSlot()
        val runner = runner(config(), search, slot = slot, passTimeoutMs = 100, ledger = ledger)

        val summary = withTimeout(5_000) { runner.runOnce() }

        assertEquals(PassStatus.CANCELLED, summary.status)
        assertEquals(PassFailureReason.TIMEOUT, summary.failureReason)
        assertEquals(summary, runner.pass(summary.passId))
        assertEquals(1, creditsThisMonth(ledger))
        assertNull(slot.holder())
    }

    @Test
    fun `shutdown cancels a pass, which still publishes its summary, persists its credits and re-throws`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val search = object : SearchProvider {
            override suspend fun search(query: String, limit: Int): List<SearchHit> {
                started.complete(Unit)
                awaitCancellation()
            }
        }
        val ledger = ledger(config())
        val slot = RunSlot()
        val runner = runner(config(), search, slot = slot, ledger = ledger)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val pass = scope.async { runner.runOnce() }
        started.await()
        scope.cancel()

        assertFailsWith<CancellationException> { pass.await() }
        val summary = runner.recentPasses().single()
        assertEquals(PassStatus.CANCELLED, summary.status)
        assertEquals(PassFailureReason.SHUTDOWN, summary.failureReason)
        assertEquals(1, creditsThisMonth(ledger))
        assertNull(slot.holder())
    }

    @Test
    fun `a timed-out pass keeps the slot until its ledger is flushed, so a forced pass never loses its credits`() = runBlocking {
        // Pass A's first search ignores cancellation for a while, as a stuck vendor call might.
        val searches = CopyOnWriteArrayList<String>()
        val stuck = java.util.concurrent.atomic.AtomicBoolean(true)
        val search = object : SearchProvider {
            override suspend fun search(query: String, limit: Int): List<SearchHit> {
                searches += query
                if (stuck.getAndSet(false)) {
                    withContext(NonCancellable) { delay(600) }
                }
                return emptyList()
            }
        }
        val cfg = config(queries = listOf("q1", "q2", "q3"))
        val ledger = ledger(cfg)
        val runner = runner(cfg, search, passTimeoutMs = 100, ledger = ledger)

        val passA = async(Dispatchers.Default) { runner.runOnce() }
        delay(300) // A's budget has expired, A is still unwinding
        assertFailsWith<DiscoveryPassInProgressException>("A still holds the slot while it unwinds") {
            runner.runOnce(PassRequest(force = true))
        }
        val a = passA.await()
        assertEquals(PassStatus.CANCELLED, a.status)
        assertEquals(1, a.creditsSpent, "A requested no credit after its budget expired")

        clock.set(Instant.parse("2026-11-10T09:00:00Z"))
        val b = runner.runOnce(PassRequest(force = true))

        assertEquals(3, b.creditsSpent)
        assertEquals(listOf("q1", "q1", "q2", "q3"), searches)
        val state = ledger.readState().getOrThrow()
        assertEquals(4, state.months["2026-11"], "A's credit plus B's three")
        assertEquals("2026-11-10T09:00:00Z", state.lastPassAt)
    }

    @Test
    fun `a pass is refused while a crawl run holds the shared slot, and a scheduled tick is skipped with a WARN`() {
        val slot = RunSlot()
        val crawl = assertNotNull(slot.tryTake(RunKind.CRAWL, UUID.randomUUID()))
        val search = StubSearchProvider()
        val runner = runner(config(), search, slot = slot)

        assertNull(runner.tryReserve(PassRequest()))
        val root = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        root.addAppender(appender)
        val scheduler = assertNotNull(DiscoveryScheduler.create(config(), runner, intervalMs = 30, initialDelayMs = 0, jitterMs = { 0 }))
        try {
            awaitUntil {
                appender.list.toList().count { it.level.toString() == "WARN" && it.formattedMessage.contains("tick skipped") && it.formattedMessage.contains("CRAWL") } >= 2
            }
            val thread = Thread.getAllStackTraces().keys.single { it.name == DiscoveryScheduler.THREAD_NAME }
            assertTrue(thread.isDaemon)
        } finally {
            scheduler.close()
            root.detachAppender(appender)
        }
        assertTrue(search.calls().isEmpty())
        assertTrue(runner.recentPasses().isEmpty())
        slot.release(crawl)
    }

    @Test
    fun `closing the scheduler cancels its in-flight pass within the grace period, with summary and credits kept`() {
        val search = object : SearchProvider {
            override suspend fun search(query: String, limit: Int): List<SearchHit> = awaitCancellation()
        }
        val slot = RunSlot()
        val ledger = ledger(config())
        val runner = runner(config(), search, slot = slot, ledger = ledger)
        val scheduler = assertNotNull(DiscoveryScheduler.create(config(), runner, intervalMs = 60_000, initialDelayMs = 0))
        awaitUntil { creditsThisMonth(ledger) == 1 }

        val started = System.nanoTime()
        scheduler.close()

        assertTrue(System.nanoTime() - started < DISCOVERY_SHUTDOWN_GRACE_MS * 1_000_000)
        assertNull(slot.holder())
        val summary = runner.recentPasses().single()
        assertEquals(PassStatus.CANCELLED, summary.status)
        assertEquals(PassFailureReason.SHUTDOWN, summary.failureReason)
        assertEquals(1, creditsThisMonth(ledger))
    }

    @Test
    fun `the pass history keeps the last 20 passes, newest first`() = runBlocking {
        val runner = runner(config(), StubSearchProvider())
        val reserved = assertNotNull(runner.tryReserve(PassRequest()))
        val running = assertNotNull(runner.pass(reserved))
        assertEquals(PassStatus.RUNNING, running.status)
        assertNull(running.finishedAt)
        runner.runReserved(reserved)
        val ids = (1..PASS_HISTORY_SIZE + 1).map {
            clock.set(clock.instant().plusSeconds(25 * 3600))
            runner.runOnce().passId
        }

        assertEquals(ids.drop(1).reversed(), runner.recentPasses().map { it.passId })
        assertNull(runner.pass(ids.first()), "the oldest pass is evicted")
    }

    private fun awaitUntil(timeoutMs: Long = 3_000, condition: () -> Boolean) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (System.nanoTime() < deadline) {
            if (condition()) return
            Thread.sleep(10)
        }
        fail("the condition was not met within $timeoutMs ms")
    }
}
