package org.a1tsign.vds.crawler.enrich

import org.a1tsign.vds.config.EnrichmentConfig
import org.a1tsign.vds.crawler.fetch.ChallengeMarkers
import org.a1tsign.vds.crawler.fetch.EnrichmentFetcher
import org.a1tsign.vds.crawler.fetch.FetchException
import org.a1tsign.vds.crawler.fetch.FetchKind
import org.a1tsign.vds.crawler.fetch.FetchedPage
import org.jsoup.parser.Parser
import java.net.URI

/** Robots `Sitemap:` lines read per site; the rest come from `/sitemap.xml` or an index. */
private const val MAX_ROBOTS_SITEMAPS = 2
private const val MAX_INDEX_CHILDREN = 3
private const val INDEX_PROBE_ENTRIES = 5
private const val INDEX_PROBE_THRESHOLD = 3
private const val MAX_CANDIDATES = 5_000

/** Visible text below which a first page carrying a CAPTCHA widget is taken for a challenge page. */
private const val CHALLENGE_PAGE_MAX_TEXT = 600

/** When every analysed page has less visible text than this, the site is an SPA shell: its copy lives in JS. */
private const val SPA_SHELL_MAX_TEXT = 600

/** JS files read per SPA site, entry scripts included; each also counts as a page fetch. */
private const val MAX_SCRIPT_FILES = 6
private const val MAX_ENTRY_SCRIPTS = 20
private const val MAX_CHUNK_URLS = 500

private class SiteBlocked(val reason: String) : Exception(reason, null, false, false)

/**
 * Visit robots.txt, the home and pricing pages, then sitemaps and ranked links within the configured budgets.
 * Inspect JS only for a shell with little visible text. A challenge stops all further requests.
 */
internal class SiteCrawler(
    private val fetcher: EnrichmentFetcher,
    private val limits: EnrichmentConfig,
) {

    suspend fun crawl(target: SiteTarget): CrawlOutcome {
        val run = Run(target)
        return try {
            run.execute()
        } catch (blocked: SiteBlocked) {
            CrawlOutcome.Blocked(blocked.reason)
        }
    }

    private inner class Run(private val target: SiteTarget) {
        private val originUri = URI(target.origin)
        private val siteHost = bareHost(originUri.host)
        private val sitePort = originUri.port
        private val signals = SiteSignals(siteHost)

        private var primaryLocale: String? = null
        private val visited = HashSet<String>()
        private val candidates = HashMap<String, Double>()
        private val perSection = HashMap<String, Int>()
        private var pagesCounted = 0
        private var pagesAnalysed = 0
        private var firstFetched = true
        private var firstFailure: String? = null
        private var disallowed = 0
        private var allPagesSparse = true
        private val entryScripts = LinkedHashSet<String>()
        private var scriptsRead = 0

        suspend fun execute(): CrawlOutcome {
            val robotsSitemaps = fetcher.robotsSitemaps(target.origin + "/")
            primaryLocale = localeOf(URI(target.pricingUrl).rawPath.orEmpty()).ifEmpty { null }

            val home = target.origin + "/"
            val starters = listOf(home, target.pricingUrl).distinctBy { it.trimEnd('/') }
            visit(home)
            if (primaryLocale == null) primaryLocale = ""
            if (starters.size > 1) visit(target.pricingUrl)
            starters.forEach { perSection.merge(sectionOf(it), 1, Int::plus) }
            if (pagesAnalysed == 0) {
                return if (disallowed >= starters.size) {
                    CrawlOutcome.RobotsDisallowed
                } else {
                    CrawlOutcome.Unreachable(firstFailure ?: "Neither ${target.origin}/ nor ${target.pricingUrl} could be analysed")
                }
            }

            readSitemaps(robotsSitemaps)

            if (allPagesSparse) {
                readScripts()
                return CrawlOutcome.Analysed(Decisions.findings(signals))
            }

            while (pagesCounted < limits.maxPages) {
                val next = candidates.entries
                    .filter { (url, _) -> url !in visited && (perSection[sectionOf(url)] ?: 0) < limits.maxPagesPerSection }
                    .maxWithOrNull(compareBy<Map.Entry<String, Double>> { it.value }.thenBy { it.key })
                    ?: break
                perSection.merge(sectionOf(next.key), 1, Int::plus)
                visit(next.key)
            }
            return CrawlOutcome.Analysed(Decisions.findings(signals))
        }

        private suspend fun visit(url: String) {
            val key = url.trimEnd('/')
            if (!visited.add(key)) return
            val page = try {
                fetcher.fetch(url, FetchKind.PAGE)
            } catch (_: FetchException.RobotsDisallowed) {
                disallowed++
                return
            } catch (challenge: FetchException.Challenged) {
                throw SiteBlocked(challenge.reason)
            } catch (failure: FetchException) {
                pagesCounted++
                if (firstFailure == null) firstFailure = failure.message
                return
            }
            pagesCounted++
            val captchaMarker = if (firstFetched) ChallengeMarkers.captchaWidgetIn(page.body) else null
            firstFetched = false
            val finalUri = parse(page.finalUrl)
            if (finalUri == null || !isSameSite(finalUri)) {
                if (firstFailure == null) firstFailure = "$url redirected off-site to ${finalUri?.host}"
                return
            }
            val content = PageReader.read(page.body, page.finalUrl)
            // Many ordinary pages embed a CAPTCHA script for their forms; only a page that is
            // little more than the widget is a challenge.
            if (captchaMarker != null && content.text.length < CHALLENGE_PAGE_MAX_TEXT) {
                throw SiteBlocked("CAPTCHA widget '$captchaMarker' on ${page.finalUrl}")
            }
            if (primaryLocale == null) primaryLocale = localeOf(finalUri.rawPath.orEmpty())
            pagesAnalysed++
            if (content.text.length >= SPA_SHELL_MAX_TEXT) allPagesSparse = false
            content.scriptUrls.forEach { script ->
                if (entryScripts.size < MAX_ENTRY_SCRIPTS && parse(script)?.let(::isSameSite) == true) entryScripts += script
            }
            PageAnalyzer.analyze(content, url, kindsOf(url), isPricing = url == target.pricingUrl, signals)
            content.links.forEach { consider(it.url, it.text) }
        }

        /**
         * Reads the entry scripts, then the chunks they import, best-ranked first, until
         * [MAX_SCRIPT_FILES] JS files have been requested.
         */
        private suspend fun readScripts() {
            val chunks = LinkedHashSet<String>()
            for (entry in entryScripts) {
                val script = fetchScript(entry) ?: continue
                for (ref in analyseScript(entry, script.body)) {
                    if (chunks.size >= MAX_CHUNK_URLS) break
                    val chunk = parse(script.finalUrl)?.let { base -> runCatching { base.resolve(ref) }.getOrNull() } ?: continue
                    if (isSameSite(chunk)) chunks += normalised(chunk)
                }
            }
            val locale = primaryLocale.orEmpty().ifEmpty { "en" }
            val ranked = chunks.map { it to chunkRank(it, locale) }.filter { it.second >= 0 }.sortedByDescending { it.second }
            for ((chunk, _) in ranked) {
                val script = fetchScript(chunk) ?: continue
                analyseScript(chunk, script.body)
            }
        }

        /** `null` when the file was not read; every request made counts against both budgets. */
        private suspend fun fetchScript(url: String): FetchedPage? {
            if (scriptsRead >= MAX_SCRIPT_FILES || pagesCounted >= limits.maxPages) return null
            if (!visited.add(url.trimEnd('/'))) return null
            return try {
                fetcher.fetch(url, FetchKind.SCRIPT)
            } catch (_: FetchException.RobotsDisallowed) {
                return null
            } catch (challenge: FetchException.Challenged) {
                throw SiteBlocked(challenge.reason)
            } catch (_: FetchException) {
                null
            }.also {
                scriptsRead++
                pagesCounted++
            }
        }

        private fun analyseScript(url: String, body: String): List<String> {
            val script = ScriptReader.read(body)
            val content = PageContent(
                text = script.text,
                footer = "",
                attributes = script.assets.map { "asset" to it },
                resourceUrls = emptyList(),
                scriptUrls = emptyList(),
                jsonLd = emptyList(),
                inlineScripts = PageReader.unescapeJs(body),
                siteName = null,
                links = emptyList(),
            )
            val kinds = pageKinds("/" + fileName(url).substringBefore('.'))
            PageAnalyzer.analyze(content, url, kinds, isPricing = false, signals, spa = true)
            return script.chunkRefs
        }

        private suspend fun readSitemaps(robotsSitemaps: List<String>) {
            val sameSite = robotsSitemaps.filter { url -> parse(url)?.let(::isSameSite) == true }.take(MAX_ROBOTS_SITEMAPS)
            var documents = 0
            for (sitemap in sameSite.ifEmpty { listOf(target.origin + "/sitemap.xml") }) {
                if (documents >= limits.maxSitemaps) return
                documents++
                val locs = readSitemap(sitemap) ?: continue
                if (locs.take(INDEX_PROBE_ENTRIES).count { it.endsWith(".xml", ignoreCase = true) } >= INDEX_PROBE_THRESHOLD) {
                    for (child in locs.take(MAX_INDEX_CHILDREN)) {
                        if (documents >= limits.maxSitemaps) return
                        if (parse(child)?.let(::isSameSite) != true) continue
                        documents++
                        readSitemap(child)?.forEach { consider(it, "") }
                    }
                } else {
                    locs.forEach { consider(it, "") }
                }
            }
        }

        /** The `<loc>` entries of a sitemap; `null` when it cannot be read, which is not a failure. */
        private suspend fun readSitemap(url: String): List<String>? {
            val body = try {
                fetcher.fetch(url, FetchKind.SITEMAP).body
            } catch (challenge: FetchException.Challenged) {
                throw SiteBlocked(challenge.reason)
            } catch (_: FetchException) {
                return null
            }
            return locEntries(body)
        }

        private fun consider(href: String, anchorText: String) {
            val resolved = Parser.unescapeEntities(href.trim(), false).substringBefore('#')
            val uri = parse(resolved) ?: return
            if (!isSameSite(uri) || !uri.rawQuery.isNullOrEmpty()) return
            val rawPath = uri.rawPath.orEmpty().ifEmpty { "/" }
            if (EnrichmentRules.SKIP_PATH.matcher(rawPath).find()) return
            val locale = localeOf(rawPath)
            if (locale.isNotEmpty() && locale != primaryLocale) return
            val hay = ((uri.path ?: rawPath) + " " + anchorText).lowercase()
            var score = EnrichmentRules.LINK_HINTS.values.sumOf { (pattern, weight) -> if (pattern.matcher(hay).find()) weight else 0 }.toDouble()
            if (EnrichmentRules.LEGAL_PAGE_BONUS.matcher(rawPath).find()) score += EnrichmentRules.LEGAL_PAGE_BONUS_WEIGHT
            if (EnrichmentRules.OUTDATED_PAGE.matcher(rawPath).find()) score -= EnrichmentRules.OUTDATED_PAGE_PENALTY
            if (score <= 0) return
            val key = normalised(uri).trimEnd('/')
            val ranked = score - rawPath.count { it == '/' } * EnrichmentRules.DEPTH_PENALTY_PER_SLASH
            val current = candidates[key]
            if (current == null && candidates.size >= MAX_CANDIDATES) return
            if (current == null || ranked > current) candidates[key] = ranked
        }

        private fun isSameSite(uri: URI): Boolean {
            val scheme = uri.scheme?.lowercase()
            if (scheme != "http" && scheme != "https") return false
            val host = uri.host ?: return false
            return bareHost(host) == siteHost && uri.port == sitePort
        }

        /** The first path segment after the locale; a one-segment path is its own bucket. */
        private fun sectionOf(url: String): String {
            val segments = parse(url)?.rawPath.orEmpty().split('/').filter { it.isNotEmpty() }
                .let { if (it.isNotEmpty() && localeOf("/" + it[0]).isNotEmpty()) it.drop(1) else it }
            return if (segments.size > 1) segments[0] else "/" + segments.joinToString("/")
        }

        private fun kindsOf(url: String): Set<String> = pageKinds(parse(url)?.path.orEmpty())
    }

    /**
     * Rank of a JS chunk by its file name; negative for another locale's dictionary chunk, which is
     * never read.
     */
    private fun chunkRank(url: String, locale: String): Int {
        val name = fileName(url)
        val primary = name.startsWith("$locale.")
        if (!primary && EnrichmentRules.LOCALE_CHUNK.matcher(name).find()) return -1
        var score = if (primary) EnrichmentRules.PRIMARY_LOCALE_CHUNK_BONUS else 0
        score += EnrichmentRules.LINK_HINTS.values.sumOf { (pattern, weight) -> if (pattern.matcher(name).find()) weight else 0 }
        if (EnrichmentRules.CHUNK_NAME_HINT.matcher(name).find()) score += EnrichmentRules.CHUNK_NAME_HINT_WEIGHT
        return score
    }

    private fun fileName(url: String): String = parse(url)?.path.orEmpty().substringAfterLast('/').lowercase()

    private fun localeOf(path: String): String {
        val m = EnrichmentRules.LOCALE_SEGMENT.matcher(path)
        return if (m.lookingAt()) m.group(1).lowercase() else ""
    }

    /** Scheme and host lower-cased, no query or fragment: the catalog accepts only `http(s)://` evidence URLs. */
    private fun normalised(uri: URI): String {
        val port = if (uri.port >= 0) ":${uri.port}" else ""
        return "${uri.scheme.lowercase()}://${uri.host.lowercase()}$port${uri.rawPath.orEmpty().ifEmpty { "/" }}"
    }

    private fun bareHost(host: String): String = host.lowercase().removePrefix("www.")

    private fun parse(url: String): URI? = try {
        URI(url).takeIf { it.isAbsolute }
    } catch (_: Exception) {
        null
    }

    /** `<loc>` values found with `indexOf`, so a broken or hostile document costs one linear pass. */
    private fun locEntries(body: String): List<String> {
        val entries = mutableListOf<String>()
        var from = 0
        while (true) {
            val open = body.indexOf("<loc>", from, ignoreCase = true)
            if (open < 0) break
            val close = body.indexOf("</loc>", open + 5, ignoreCase = true)
            if (close < 0) break
            body.substring(open + 5, close).trim().takeIf { it.isNotEmpty() && it.none(Char::isWhitespace) }?.let(entries::add)
            from = close + 6
        }
        return entries
    }
}
