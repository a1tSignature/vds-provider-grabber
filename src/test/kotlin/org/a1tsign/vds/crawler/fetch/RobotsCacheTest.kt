package org.a1tsign.vds.crawler.fetch

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The robots.txt rules themselves, at unit level: the HTTP side (a `404`, a garbage body, "fetched
 * once per host", `VDS_CRAWLER_RESPECT_ROBOTS=false`) is covered through the real client in
 * [PageFetcherTest], so what is left here is the parsing and precedence a prefix-matching bug
 * would silently get wrong in the *permissive* direction.
 */
class RobotsCacheTest {

    private val agent = "vds-provider-grabber"

    @Test
    fun `the longest matching rule decides and an equal-length Allow wins`() {
        val rules = parseRobotsTxt(
            """
            User-agent: *
            Disallow: /plans
            Allow: /plans/public
            Disallow: /private
            """.trimIndent(),
            agent,
        )

        assertFalse(rules.allows("/plans/x"), "the Disallow prefix blocks")
        assertTrue(rules.allows("/plans/public/vps-1"), "the longer Allow wins")
        assertFalse(rules.allows("/private"))
        assertTrue(rules.allows("/other"))
        assertTrue(rules.allows("/plans/public"), "an exact-length tie goes to Allow")
    }

    @Test
    fun `a group naming our product token replaces the wildcard group instead of adding to it`() {
        val rules = parseRobotsTxt(
            """
            User-agent: *
            Disallow: /

            User-agent: vds-provider
            Disallow: /admin
            Crawl-delay: 2
            """.trimIndent(),
            agent,
        )

        assertTrue(rules.allows("/plans"), "the wildcard Disallow: / must not apply once we are named")
        assertFalse(rules.allows("/admin/users"))
        assertEquals(2_000L, rules.crawlDelayMs)
    }

    @Test
    fun `an empty Disallow, a garbage document and an unrelated group all allow everything`() {
        val emptyDisallow = parseRobotsTxt("User-agent: *\nDisallow:", agent)
        val garbage = parseRobotsTxt("{\"json\": true} not robots at all\n<<<>>>", agent)
        val otherAgent = parseRobotsTxt("User-agent: googlebot\nDisallow: /", agent)

        listOf(emptyDisallow, garbage, otherAgent).forEach { rules ->
            assertTrue(rules.allows("/plans/x"), "rules were $rules")
            assertNull(rules.crawlDelayMs)
        }
    }

    @Test
    fun `a Crawl-delay within the ceiling is kept and one above it is dropped`() = runBlocking {
        val kept = RobotsCache(agent, maxCrawlDelayMs = 60_000) { "User-agent: *\nCrawl-delay: 0.5" }
        val dropped = RobotsCache(agent, maxCrawlDelayMs = 60_000) { "User-agent: *\nCrawl-delay: 120" }

        assertEquals(500L, kept.rulesFor("http://a.test").crawlDelayMs)
        assertNull(dropped.rulesFor("http://a.test").crawlDelayMs)
    }

    @Test
    fun `a Crawl-delay too large for a Long is dropped instead of wrapping under the ceiling`() = runBlocking {
        // 2^64 + 1000 milliseconds: a silent BigDecimal.toLong() keeps only the low 64 bits, so
        // this absurd value would come back as a perfectly plausible 1 s delay and be applied.
        val wrapping = RobotsCache(agent, maxCrawlDelayMs = 60_000) {
            "User-agent: *\nCrawl-delay: 18446744073709552.616"
        }
        val huge = RobotsCache(agent, maxCrawlDelayMs = 60_000) { "User-agent: *\nCrawl-delay: 1e19" }

        assertNull(wrapping.rulesFor("http://a.test").crawlDelayMs, "a narrowed value must not be applied")
        assertNull(huge.rulesFor("http://a.test").crawlDelayMs)
    }

    @Test
    fun `robots is loaded at most once per origin, even for concurrent callers`() = runBlocking {
        val loads = ConcurrentHashMap<String, AtomicInteger>()
        val cache = RobotsCache(agent) { url ->
            loads.getOrPut(url) { AtomicInteger() }.incrementAndGet()
            "User-agent: *\nDisallow: /admin"
        }

        coroutineScope {
            List(4) { async { cache.rulesFor("http://a.test") } }.forEach { it.await() }
        }
        cache.rulesFor("http://a.test")
        cache.rulesFor("http://b.test:8080")

        assertEquals(1, loads["http://a.test/robots.txt"]?.get())
        assertEquals(1, loads["http://b.test:8080/robots.txt"]?.get())
    }
}
