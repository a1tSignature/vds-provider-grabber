package org.a1tsign.vds.crawler.fetch

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.math.RoundingMode

/** The parsed rules that apply to *our* `User-Agent` on one origin. */
data class RobotsRules(
    val allow: List<String>,
    val disallow: List<String>,
    /** `null` when robots.txt named no usable `Crawl-delay` for us. */
    val crawlDelayMs: Long?,
) {
    /**
     * Longest-match precedence, the de-facto standard: the longest matching prefix decides, and on
     * an equal-length tie `Allow` wins. `Allow: /plans/public` therefore beats `Disallow: /plans`
     * for `/plans/public/x`, while `Disallow: /plans` still blocks `/plans/x`.
     */
    fun allows(pathWithQuery: String): Boolean {
        val blocked = disallow.filter { pathWithQuery.startsWith(it) }.maxOfOrNull { it.length } ?: return true
        val allowed = allow.filter { pathWithQuery.startsWith(it) }.maxOfOrNull { it.length } ?: -1
        return allowed >= blocked
    }

    companion object {
        /** What a missing, `404`, `5xx`, empty or unparsable robots.txt means (spec req. 16). */
        val ALLOW_ALL = RobotsRules(emptyList(), emptyList(), null)
    }
}

/**
 * robots.txt, fetched **once per origin per run** and held for the run (spec req. 16).
 *
 * The cache key is the full origin (`scheme://host:port`), not just the host: robots.txt is a
 * per-origin document, and `http://example.com` and `https://example.com:8443` can legitimately
 * publish different rules. Note that the *politeness* key in [HostRateLimiter] is coarser (host
 * only) on purpose -- see its doc.
 *
 * [load] returns the robots.txt body, or `null` for anything that is not a usable document
 * (missing, `404`, `5xx`, a transport failure or a timeout) -- all of which mean **allowed**.
 * [KtorPageFetcher] supplies it so this class stays free of HTTP concerns and the fetcher keeps
 * ownership of the single client, the 5 s robots timeout and the per-host delay the robots fetch
 * is itself subject to.
 *
 * A run with [maxCrawlDelayMs] exceeded is the one case where robots.txt is partially ignored: a
 * `Crawl-delay` above it would stall the whole run, so it is dropped with a DEBUG line.
 *
 * The cache is bounded at [maxTrackedOrigins] entries, the same way and for the same reason as
 * [HostRateLimiter] bounds its host registry -- see [evictIfNeeded].
 */
class RobotsCache(
    userAgent: String,
    private val maxCrawlDelayMs: Long = DEFAULT_MAX_CRAWL_DELAY_MS,
    private val maxTrackedOrigins: Int = DEFAULT_MAX_TRACKED_ORIGINS,
    private val load: suspend (robotsUrl: String) -> String?,
) {
    /**
     * The product token of our `User-Agent` (`vds-provider-grabber` out of
     * `vds-provider-grabber/1.0 (+https://…)`), lower-cased -- what a robots.txt group names.
     */
    private val agentToken = userAgent.substringBefore('/').trim().lowercase()

    private val registryGuard = Mutex()

    /** Both access-ordered, so [evictIfNeeded] drops the origin untouched for longest. */
    private val rules = LinkedHashMap<String, RobotsRules>(16, 0.75f, true)
    private val origins = LinkedHashMap<String, Mutex>(16, 0.75f, true)

    /** The rules for [origin] (`scheme://host[:port]`), fetching and parsing them at most once. */
    suspend fun rulesFor(origin: String): RobotsRules {
        cached(origin)?.let { return it }
        val gate = registryGuard.withLock {
            origins.getOrPut(origin) { Mutex() }.also { evictIfNeeded(keep = origin) }
        }
        return gate.withLock {
            // A concurrent caller for the same origin may have filled the cache while we waited.
            cached(origin) ?: fetchAndParse(origin).also { parsed ->
                registryGuard.withLock {
                    rules[origin] = parsed
                    evictIfNeeded(keep = origin)
                }
            }
        }
    }

    /**
     * Bounds both maps at [maxTrackedOrigins] entries (256 by default), exactly as
     * [HostRateLimiter] bounds its host registry and for the same reason: a page that redirects
     * through generated host names would otherwise grow `rules` -- and keep one `Mutex` per origin
     * in `origins` -- for the life of the process. Dropping an entry costs one extra robots.txt
     * fetch if that origin is visited again, nothing more, because the document is immutable for
     * the run by construction. An origin whose fetch is in flight is skipped (a second gate would
     * allow a concurrent duplicate fetch), as is [keep], the origin the caller is working on.
     * Unlike [HostRateLimiter.withHost] the caller does **not** re-check its gate after locking it:
     * if a not-yet-locked gate is evicted here, the cost is one duplicate robots.txt fetch (the
     * `rules` check inside the gate still collapses the result), not a broken guarantee.
     *
     * Called under [registryGuard], so plain map mutation is safe here.
     */
    private fun evictIfNeeded(keep: String) {
        while (rules.size > maxTrackedOrigins) {
            val victim = rules.keys.firstOrNull { it != keep } ?: break
            rules.remove(victim)
        }
        while (origins.size > maxTrackedOrigins) {
            val victim = origins.entries.firstOrNull { it.key != keep && !it.value.isLocked }?.key ?: break
            origins.remove(victim)
        }
    }

    private suspend fun cached(origin: String): RobotsRules? = registryGuard.withLock { rules[origin] }

    private suspend fun fetchAndParse(origin: String): RobotsRules {
        val body = load("$origin/robots.txt") ?: return RobotsRules.ALLOW_ALL
        val parsed = parseRobotsTxt(body, agentToken)
        val delay = parsed.crawlDelayMs
        if (delay != null && delay > maxCrawlDelayMs) {
            logger.debug("Ignoring Crawl-delay of {} ms from {} (above the {} ms ceiling)", delay, origin, maxCrawlDelayMs)
            return parsed.copy(crawlDelayMs = null)
        }
        return parsed
    }

    companion object {
        const val DEFAULT_MAX_CRAWL_DELAY_MS = 60_000L
        const val DEFAULT_MAX_TRACKED_ORIGINS = HostRateLimiter.DEFAULT_MAX_TRACKED_HOSTS
        private val logger = LoggerFactory.getLogger(RobotsCache::class.java)
    }
}

/**
 * Parses [body] into the rules that apply to [agentToken].
 *
 * Group handling follows the usual reading of the original specification:
 *
 * - consecutive `User-agent` lines open one group together; the first rule line closes the group's
 *   agent list, so the next `User-agent` starts a new group;
 * - a group matches us when its agent value is a case-insensitive prefix of [agentToken];
 * - **every** group that names us is merged, and the `*` groups are used **only** when no group
 *   names us -- a site that writes rules for us does not also get the wildcard rules applied;
 * - an empty `Disallow:` value means "nothing is disallowed" and is dropped rather than recorded as
 *   the empty prefix (which would match every path);
 * - an unknown directive, a line without `:` and anything after `#` is ignored, so a garbage
 *   document yields no rules at all, i.e. "allowed".
 *
 * **Wildcards are not interpreted.** `*` and `$` in a rule path are matched literally, so a rule
 * written as a glob (ending in `.pdf$`, say) blocks nothing here. That is the permissive direction,
 * accepted because the crawler fetches a handful of known catalogue pages per provider rather than
 * exploring a site; it is the one documented deviation from Google's extension of the format.
 */
internal fun parseRobotsTxt(body: String, agentToken: String): RobotsRules {
    val groups = mutableListOf<Group>()
    var current: Group? = null
    body.lineSequence().forEach { rawLine ->
        val line = rawLine.substringBefore('#').trim()
        if (line.isEmpty()) return@forEach
        val separator = line.indexOf(':')
        if (separator <= 0) return@forEach
        val directive = line.substring(0, separator).trim().lowercase()
        val value = line.substring(separator + 1).trim()
        when (directive) {
            "user-agent" -> {
                val group = current?.takeIf { it.rulesEmpty } ?: Group().also { groups += it }
                group.agents += value.lowercase()
                current = group
            }
            "disallow" -> current?.let { group -> if (value.isNotEmpty()) group.disallow += value }
            "allow" -> current?.let { group -> if (value.isNotEmpty()) group.allow += value }
            "crawl-delay" -> current?.let { group -> group.crawlDelayMs = parseCrawlDelayMs(value) }
            else -> Unit
        }
    }

    val named = groups.filter { group -> group.agents.any { it != "*" && agentToken.startsWith(it) } }
    val applicable = named.ifEmpty { groups.filter { group -> group.agents.contains("*") } }
    if (applicable.isEmpty()) return RobotsRules.ALLOW_ALL
    return RobotsRules(
        allow = applicable.flatMap { it.allow },
        disallow = applicable.flatMap { it.disallow },
        crawlDelayMs = applicable.mapNotNull { it.crawlDelayMs }.maxOrNull(),
    )
}

/**
 * Seconds, integral or fractional (`Crawl-delay: 0.5` occurs in the wild); `null` if unparsable.
 *
 * [BigDecimal.longValueExact], not `toLong()`, because `toLong()` **narrows silently**: a hostile
 * `Crawl-delay: 1e19` would wrap to an arbitrary value that can land back under the ceiling and be
 * applied as a real delay. A value too large for a `Long` is reported as [Long.MAX_VALUE] so the
 * ceiling check in [RobotsCache] drops it with its DEBUG line, like any other oversized delay.
 */
private fun parseCrawlDelayMs(value: String): Long? {
    val millis = try {
        BigDecimal(value).takeIf { it.signum() > 0 }?.movePointRight(3) ?: return null
    } catch (_: NumberFormatException) {
        return null
    }
    // Rounded up first (a sub-millisecond delay becomes 1 ms, the polite direction), so the only
    // ArithmeticException left is "does not fit a Long".
    return try {
        millis.setScale(0, RoundingMode.UP).longValueExact()
    } catch (_: ArithmeticException) {
        Long.MAX_VALUE
    }
}

private class Group {
    val agents = mutableListOf<String>()
    val allow = mutableListOf<String>()
    val disallow = mutableListOf<String>()
    var crawlDelayMs: Long? = null
    val rulesEmpty: Boolean get() = allow.isEmpty() && disallow.isEmpty() && crawlDelayMs == null
}
