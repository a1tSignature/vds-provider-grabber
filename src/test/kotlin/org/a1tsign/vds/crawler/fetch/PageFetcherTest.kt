package org.a1tsign.vds.crawler.fetch

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.a1tsign.vds.config.CrawlerConfig
import org.a1tsign.vds.crawler.support.FixtureHttpServer
import org.a1tsign.vds.crawler.support.FixtureResponse
import org.a1tsign.vds.crawler.support.RawFramingServer
import org.a1tsign.vds.crawler.support.RawResponseServer
import org.a1tsign.vds.crawler.support.gzipped
import org.a1tsign.vds.support.TEST_CLOCK
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import java.io.IOException
import java.math.BigDecimal
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Exercise the fetcher against a local fixture. Inject timing controls where real delays would make assertions unstable or slow. */
class PageFetcherTest {

    private lateinit var server: FixtureHttpServer

    @BeforeEach
    fun startServer() {
        server = FixtureHttpServer()
    }

    @AfterEach
    fun stopServer() {
        server.close()
    }


    @Test
    fun `get returns the response and getHtml parses it against the final URL`() {
        server.robots(body = "User-agent: *\nDisallow: /secret\n")
        server.html("/plans", "<html><body><h1 class=\"plan\">VPS 1</h1></body></html>")

        withFetcher { fetcher ->
            val page = fetcher.get(server.url("/plans"))
            assertEquals(server.url("/plans"), page.url)
            assertEquals(server.url("/plans"), page.finalUrl)
            assertEquals(200, page.status)
            assertEquals("text/html", page.contentType?.substringBefore(';'))
            assertTrue(page.body.contains("VPS 1"), "body was: ${page.body}")

            val document = fetcher.getHtml(server.url("/plans"))
            assertEquals("VPS 1", document.selectFirst("h1.plan")?.text())
            assertEquals(server.url("/plans"), document.baseUri())

            assertEquals(2, fetcher.pagesFetched, "both calls count as a fetched page")
        }
    }


    @Test
    fun `two requests to one host are spaced by the configured delay and never overlap`() {
        val config = crawlerConfig(hostDelayMs = 300, respectRobots = false)
        server.on("/a", FixtureResponse(delayMs = 150))
        server.on("/b", FixtureResponse(delayMs = 150))

        withFetcher(config) { fetcher ->
            coroutineScope {
                val first = async { fetcher.get(server.url("/a")) }
                val second = async { fetcher.get(server.url("/b")) }
                first.await()
                second.await()
            }
        }

        val arrivals = server.requests().sortedBy { it.arrivedAtNanos }
        assertEquals(listOf("/a", "/b"), arrivals.map { it.path }, "requests should be serialised in order")
        val gapMs = (arrivals[1].arrivedAtNanos - arrivals[0].arrivedAtNanos) / 1_000_000
        assertTrue(gapMs >= config.hostDelayMs, "gap was ${gapMs}ms, expected >= ${config.hostDelayMs}ms")
        assertTrue(
            arrivals[1].arrivedAtNanos >= arrivals[0].completedAtNanos,
            "the second request arrived before the first response was written",
        )
    }

    @Test
    fun `every request carries the configured identity headers`() {
        val config = crawlerConfig(respectRobots = false, userAgent = "vds-provider-grabber/9.9 (+https://example.test)")
        server.html("/plans")

        withFetcher(config) { fetcher -> fetcher.get(server.url("/plans")) }

        val request = server.requestsTo("/plans").single()
        assertEquals(config.userAgent, request.header("User-Agent"))
        assertEquals("gzip", request.header("Accept-Encoding"))
        assertEquals("en", request.header("Accept-Language"))
    }


    @Test
    fun `a disallowed path is never requested, an allowed one is, and robots is fetched once`() {
        server.robots(body = "User-agent: *\nDisallow: /plans\n")
        server.html("/other")

        withFetcher { fetcher ->
            val blocked = assertFailsWith<FetchException.RobotsDisallowed> { fetcher.get(server.url("/plans/x")) }
            assertEquals(server.url("/plans/x"), blocked.url)
            repeat(3) { fetcher.get(server.url("/other")) }
        }

        assertTrue(server.requestsTo("/plans/x").isEmpty(), "a disallowed URL must never be requested")
        assertEquals(3, server.requestsTo("/other").size)
        assertEquals(1, server.requestsTo("/robots.txt").size, "robots.txt is fetched once per host per run")
    }

    @Test
    fun `an unusable robots-txt means allowed`() {
        // Two fetchers, two caches: the first sees the 404, the second the garbage document.
        server.on(
            "/robots.txt",
            FixtureResponse(status = 404, body = "nope", contentType = "text/plain"),
            FixtureResponse(body = "<<< not robots at all >>>", contentType = "text/plain"),
        )
        server.html("/plans/x")

        withFetcher { fetcher -> assertEquals(200, fetcher.get(server.url("/plans/x")).status) }
        withFetcher { fetcher -> assertEquals(200, fetcher.get(server.url("/plans/x")).status) }

        assertEquals(2, server.requestsTo("/robots.txt").size)
        assertEquals(2, server.requestsTo("/plans/x").size)
    }

    @Test
    fun `a Crawl-delay raises the interval for that host`() {
        val config = crawlerConfig(hostDelayMs = 0)
        server.robots(body = "User-agent: *\nCrawl-delay: 1\n")
        server.html("/a")
        server.html("/b")

        withFetcher(config) { fetcher ->
            fetcher.get(server.url("/a"))
            fetcher.get(server.url("/b"))
        }

        val gapMs = (server.requestsTo("/b").single().arrivedAtNanos -
            server.requestsTo("/a").single().arrivedAtNanos) / 1_000_000
        assertTrue(gapMs >= 1_000, "gap was ${gapMs}ms, expected >= 1000ms from Crawl-delay: 1")
    }

    @Test
    fun `respect-robots false fetches a disallowed path and asks for no robots-txt at all`() {
        server.robots(body = "User-agent: *\nDisallow: /\n")
        server.html("/plans/x")

        withFetcher(crawlerConfig(respectRobots = false)) { fetcher ->
            assertEquals(200, fetcher.get(server.url("/plans/x")).status)
        }

        assertTrue(server.requestsTo("/robots.txt").isEmpty(), "robots.txt must not be fetched when disabled")
    }

    @Test
    fun `a gzip-encoded robots-txt is decoded and still blocks a disallowed path`() {
        // The fetcher advertises Accept-Encoding: gzip on the robots request too, and nginx's
        // default gzip_types includes text/plain. Read undecoded, the gzip bytes parse as zero
        // directives, i.e. a silently cached "allow all" -- the robots check failing *open*.
        server.on(
            "/robots.txt",
            FixtureResponse(
                rawBody = gzipped("User-agent: *\nDisallow: /plans\n"),
                contentType = "text/plain",
                headers = mapOf("Content-Encoding" to "gzip"),
            ),
        )
        server.html("/other")
        // Register the blocked page so ignoring compressed robots rules would yield a successful fetch.
        server.html("/plans/x")

        withFetcher { fetcher ->
            val blocked = assertFailsWith<FetchException.RobotsDisallowed> { fetcher.get(server.url("/plans/x")) }
            assertEquals(server.url("/plans/x"), blocked.url)
            // Also fetch an allowed page so the blocked result cannot be explained by a blanket refusal.
            assertEquals(200, fetcher.get(server.url("/other")).status)
        }

        assertTrue(server.requestsTo("/plans/x").isEmpty(), "a disallowed URL must never be requested")
    }

    @Test
    fun `a redirect into a path disallowed by the second host's robots is blocked`() {
        // Check the redirect target's rules are enforced as well as fetched.
        server.robots(body = "User-agent: *\nDisallow: /blocked\n")
        server.redirect("/start", server.url("/blocked/x", host = "localhost"))
        server.html("/blocked/x")

        withFetcher(crawlerConfig(hostDelayMs = 0)) { fetcher ->
            val blocked = assertFailsWith<FetchException.RobotsDisallowed> {
                fetcher.get(server.url("/start", host = "127.0.0.1"))
            }
            assertEquals(server.url("/blocked/x", host = "localhost"), blocked.url)
        }

        assertTrue(server.requestsTo("/blocked/x").isEmpty(), "the disallowed redirect target must not be requested")
        assertEquals(1, server.requestsTo("/start").size)
        assertEquals(2, server.requestsTo("/robots.txt").size, "each host's robots.txt is fetched once")
    }


    @Test
    fun `a 503 503 200 sequence succeeds after exactly two retries with exponential backoff`() {
        server.on(
            "/plans",
            FixtureResponse(status = 503, body = "down"),
            FixtureResponse(status = 503, body = "down"),
            FixtureResponse(),
        )
        val sleeps = mutableListOf<Long>()

        withFetcher(crawlerConfig(respectRobots = false), sleeps = sleeps) { fetcher ->
            assertEquals(200, fetcher.get(server.url("/plans")).status)
        }

        assertEquals(3, server.requestsTo("/plans").size)
        // backoffJitter is the identity in tests, so these are the undiluted bounds: base, base*2.
        assertEquals(listOf(TEST_LIMITS.backoffBaseMs, TEST_LIMITS.backoffBaseMs * 2), sleeps)
    }

    @Test
    fun `a 404 is requested exactly once`() {
        server.on("/plans", FixtureResponse(status = 404, body = "gone"))
        val sleeps = mutableListOf<Long>()

        withFetcher(crawlerConfig(respectRobots = false), sleeps = sleeps) { fetcher ->
            val failure = assertFailsWith<FetchException.HttpStatusError> { fetcher.get(server.url("/plans")) }
            assertEquals(404, failure.status)
        }

        assertEquals(1, server.requestsTo("/plans").size, "a 4xx other than 408/429 is never retried")
        assertTrue(sleeps.isEmpty())
    }

    @Test
    fun `a 429 with a Retry-After within the ceiling is retried after exactly that wait`() {
        server.on(
            "/plans",
            FixtureResponse(status = 429, body = "slow down", headers = mapOf("Retry-After" to "1")),
            FixtureResponse(),
        )
        val sleeps = mutableListOf<Long>()

        withFetcher(crawlerConfig(respectRobots = false), sleeps = sleeps) { fetcher ->
            assertEquals(200, fetcher.get(server.url("/plans")).status)
        }

        assertEquals(2, server.requestsTo("/plans").size)
        assertEquals(listOf(1_000L), sleeps)
    }

    @Test
    fun `a Retry-After above the ceiling fails immediately in both wire forms`() {
        val httpDate = DateTimeFormatter.RFC_1123_DATE_TIME.format(
            ZonedDateTime.ofInstant(TEST_CLOCK.instant().plusSeconds(120), ZoneOffset.UTC),
        )
        server.on("/seconds", FixtureResponse(status = 429, headers = mapOf("Retry-After" to "120")))
        server.on("/date", FixtureResponse(status = 503, headers = mapOf("Retry-After" to httpDate)))
        val sleeps = mutableListOf<Long>()

        withFetcher(crawlerConfig(respectRobots = false), sleeps = sleeps) { fetcher ->
            assertEquals(429, assertFailsWith<FetchException.HttpStatusError> { fetcher.get(server.url("/seconds")) }.status)
            assertEquals(503, assertFailsWith<FetchException.HttpStatusError> { fetcher.get(server.url("/date")) }.status)
        }

        assertEquals(1, server.requestsTo("/seconds").size)
        assertEquals(1, server.requestsTo("/date").size)
        assertTrue(sleeps.isEmpty(), "a wait above the ceiling must fail the request, not block the run")
    }


    @Test
    fun `an oversized streamed body fails with BodyTooLarge and is abandoned mid-stream`() {
        val config = crawlerConfig(respectRobots = false, maxBodyBytes = 64 * 1024)
        // Stream beyond socket buffer capacity so an abandoned response is observable at the server.
        val streamedBytes = 8L * 1024 * 1024
        server.stream("/huge", totalBytes = streamedBytes)

        withFetcher(config) { fetcher ->
            val failure = assertFailsWith<FetchException.BodyTooLarge> { fetcher.get(server.url("/huge")) }
            assertEquals(config.maxBodyBytes, failure.limitBytes)
        }

        val served = server.requestsTo("/huge").single()
        assertTrue(
            served.bytesWritten < streamedBytes,
            "the server wrote ${served.bytesWritten} of $streamedBytes bytes: the body was buffered in full",
        )
    }

    @Test
    fun `a lying Content-Length followed by a large chunked body is still BodyTooLarge`() {
        // Send overlapping framing headers to exercise CIO's chunked body handling.
        val config = crawlerConfig(respectRobots = false, maxBodyBytes = 64 * 1024)
        val bodyBytes = 8L * 1024 * 1024

        RawFramingServer(declaredContentLength = 10, bodyBytes = bodyBytes).use { raw ->
            withFetcher(config) { fetcher ->
                val failure = assertFailsWith<FetchException.BodyTooLarge> { fetcher.get(raw.url("/liar")) }
                assertEquals(config.maxBodyBytes, failure.limitBytes)
            }
            assertEquals(1, raw.requestCount)
            assertTrue(
                raw.bytesWritten < bodyBytes,
                "the fixture wrote ${raw.bytesWritten} of $bodyBytes bytes: the cap did not abort the stream",
            )
        }
    }

    @Test
    fun `a gzip body is decoded and the cap still applies to the decompressed bytes`() {
        // The request advertises Accept-Encoding: gzip, so the response has to be decodable --
        // and the cap has to hold on the *decompressed* size, or a kilobyte on the wire becomes a
        // megabyte of heap.
        val config = crawlerConfig(respectRobots = false, maxBodyBytes = 64 * 1024)
        server.on(
            "/packed",
            FixtureResponse(
                rawBody = gzipped("<html><body><p>compressed plans</p></body></html>"),
                headers = mapOf("Content-Encoding" to "gzip"),
            ),
        )
        server.on(
            "/bomb",
            FixtureResponse(
                rawBody = gzipped("x".repeat(1024 * 1024)),
                headers = mapOf("Content-Encoding" to "gzip"),
            ),
        )

        withFetcher(config) { fetcher ->
            assertTrue(fetcher.get(server.url("/packed")).body.contains("compressed plans"))
            val failure = assertFailsWith<FetchException.BodyTooLarge> { fetcher.get(server.url("/bomb")) }
            assertEquals(config.maxBodyBytes, failure.limitBytes)
        }
    }


    @Test
    fun `a content type outside the allowlist fails without reading the body`() {
        server.on("/logo", FixtureResponse(body = "PNG-ish bytes", contentType = "image/png"))

        withFetcher(crawlerConfig(respectRobots = false)) { fetcher ->
            val failure = assertFailsWith<FetchException.UnsupportedContentType> { fetcher.get(server.url("/logo")) }
            assertEquals("image/png", failure.contentType)
        }
    }


    @Test
    fun `three hops are followed and the final URL is the last one`() {
        server.redirect("/one", "/two")
        server.redirect("/two", "/three", status = 301)
        server.redirect("/three", "/final", status = 307)
        server.html("/final")

        withFetcher(crawlerConfig(respectRobots = false)) { fetcher ->
            val page = fetcher.get(server.url("/one"))
            assertEquals(server.url("/one"), page.url)
            assertEquals(server.url("/final"), page.finalUrl)
            assertEquals(200, page.status)
        }
    }

    @Test
    fun `more hops than the configured maximum fails with TooManyRedirects`() {
        (1..6).forEach { hop -> server.redirect("/hop$hop", "/hop${hop + 1}") }
        server.html("/hop7")

        withFetcher(crawlerConfig(respectRobots = false)) { fetcher ->
            val failure = assertFailsWith<FetchException.TooManyRedirects> { fetcher.get(server.url("/hop1")) }
            assertEquals(TEST_LIMITS.maxRedirects, failure.redirects)
        }

        assertEquals(
            TEST_LIMITS.maxRedirects + 1,
            (1..7).count { server.requestsTo("/hop$it").isNotEmpty() },
            "the hop after the last allowed redirect must not be requested",
        )
    }

    @Test
    fun `a redirect to a non-http scheme is an error`() {
        server.redirect("/out", "ftp://files.example.test/plans.txt")

        withFetcher(crawlerConfig(respectRobots = false)) { fetcher ->
            val failure = assertFailsWith<FetchException.TransportFailure> { fetcher.get(server.url("/out")) }
            assertTrue(
                failure.cause is UnsupportedUrlException,
                "cause was ${failure.cause}",
            )
        }
    }

    @Test
    fun `a redirect to another host is subject to that host's robots and delay`() {
        // Same fixture process, two host *names*: a different host for the rate limiter and a
        // different origin for the robots cache.
        server.robots(body = "User-agent: *\nDisallow: /nothing\n")
        server.redirect("/start", server.url("/final", host = "localhost"))
        server.html("/final")

        withFetcher(crawlerConfig(hostDelayMs = 0)) { fetcher ->
            val page = fetcher.get(server.url("/start", host = "127.0.0.1"))
            assertEquals(server.url("/final", host = "localhost"), page.finalUrl)
        }

        val robotsRequests = server.requestsTo("/robots.txt")
        assertEquals(2, robotsRequests.size, "each host's robots.txt must be fetched")
        assertEquals(
            setOf("127.0.0.1:${server.port}", "localhost:${server.port}"),
            robotsRequests.mapNotNull { it.header("Host") }.toSet(),
        )
    }


    @Test
    fun `a response slower than the configured request timeout fails as a transport failure`() {
        server.on("/slow", FixtureResponse(delayMs = TEST_LIMITS.requestTimeoutMs * 4))

        withFetcher(crawlerConfig(respectRobots = false)) { fetcher ->
            val failure = assertFailsWith<FetchException.TransportFailure> { fetcher.get(server.url("/slow")) }
            assertTrue(failure.cause is IOException, "cause was ${failure.cause}")
        }

        assertEquals(
            TEST_LIMITS.maxRetries + 1,
            server.requestsTo("/slow").size,
            "a read timeout is retried up to the retry budget",
        )
    }

    @Test
    fun `a malformed response is a TransportFailure and is retried like any transport failure`() {
        // Exercise CIO's parser failure outside the IOException hierarchy.
        val sleeps = mutableListOf<Long>()

        RawResponseServer("NOT-HTTP-AT-ALL garbage\r\n\r\n").use { raw ->
            withFetcher(crawlerConfig(respectRobots = false), sleeps = sleeps) { fetcher ->
                val failure = assertFailsWith<FetchException.TransportFailure> { fetcher.get(raw.url("/garbage")) }
                assertTrue(
                    failure.cause != null && failure.cause !is FetchException,
                    "cause was ${failure.cause}",
                )
            }

            assertEquals(
                TEST_LIMITS.maxRetries + 1,
                raw.requestCount,
                "a malformed response is retried up to the retry budget",
            )
        }
        assertEquals(listOf(TEST_LIMITS.backoffBaseMs, TEST_LIMITS.backoffBaseMs * 2), sleeps)
    }


    private fun withFetcher(
        config: CrawlerConfig = crawlerConfig(),
        limits: FetchLimits = TEST_LIMITS,
        sleeps: MutableList<Long> = mutableListOf(),
        block: suspend (KtorPageFetcher) -> Unit,
    ) = runBlocking {
        crawlerHttpClient(limits).use { client ->
            val fetcher = KtorPageFetcher(
                config = config,
                clock = TEST_CLOCK,
                client = client,
                limits = limits,
                rateLimiter = HostRateLimiter(config.hostDelayMs),
                // Identity jitter + a recording no-op sleep: the backoff is asserted, never waited for.
                backoffJitter = { bound -> bound },
                sleep = { millis -> sleeps += millis },
            )
            block(fetcher)
        }
    }

    private companion object {
        val TEST_LIMITS = FetchLimits(
            connectTimeoutMs = 1_000,
            requestTimeoutMs = 500,
            socketTimeoutMs = 500,
            robotsTimeoutMs = 1_000,
        )

        fun crawlerConfig(
            hostDelayMs: Long = 0,
            maxBodyBytes: Long = 2L * 1024 * 1024,
            respectRobots: Boolean = true,
            userAgent: String = "vds-provider-grabber/test (+https://example.test/bot)",
        ) = CrawlerConfig(
            enabled = true,
            apiBaseUrl = "http://127.0.0.1:8080",
            username = "crawler",
            password = null,
            intervalHours = 24,
            initialDelayMinutes = 10,
            adapters = emptyList(),
            maxConcurrentAdapters = 1,
            adapterTimeoutMinutes = 5,
            runTimeoutMinutes = 30,
            hostDelayMs = hostDelayMs,
            userAgent = userAgent,
            contactUrl = null,
            respectRobots = respectRobots,
            maxBodyBytes = maxBodyBytes,
            maxOutOfStockRatio = BigDecimal("0.5"),
            dryRunDefault = false,
        )
    }
}
