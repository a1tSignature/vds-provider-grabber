package org.a1tsign.vds.crawler

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.a1tsign.vds.crawler.adapters.DeclarativeAdapter
import org.a1tsign.vds.crawler.config.SourceConfigFormat
import org.a1tsign.vds.crawler.config.SourceConfigParse
import org.a1tsign.vds.crawler.enrich.loadEnrichProcessConfig
import org.a1tsign.vds.crawler.fetch.FetchKind
import org.a1tsign.vds.crawler.fetch.HostRateLimiter
import org.a1tsign.vds.crawler.fetch.KtorPageFetcher
import org.a1tsign.vds.crawler.fetch.crawlerHttpClient
import org.a1tsign.vds.crawler.support.StubPageFetcher
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.io.File
import java.time.Clock
import java.util.UUID
import kotlin.test.Test

/**
 * Workbench for writing the source configs in `catalog-seed/sources` against saved pages, not a
 * regression test.
 * It runs only when `PROBE_DIR` is set; the workflow is in `scripts/source-workbench/README.md`.
 *
 * `PROBE_DIR` layout: `pages/<providerSlug>.html` (the page as the crawler fetches it), plus the
 * input of the method being run. Every method writes its report next to the input.
 */
@EnabledIfEnvironmentVariable(named = "PROBE_DIR", matches = ".+")
class ScratchSourceProbeTest {
    private val dir = File(System.getenv("PROBE_DIR") ?: ".")

    private fun page(slug: String): Element = Jsoup.parse(File(dir, "pages/$slug.html").readText(), "https://$slug/")

    @Test
    fun fetch() {
        val input = File(dir, System.getenv("FETCH_INPUT") ?: "rest.tsv")
        val output = File(dir, System.getenv("FETCH_OUTPUT") ?: "fetch.log")
        val targets = input.readLines().filter { it.isNotBlank() }.map { line ->
            val fields = line.split('\t')
            require(fields.size == 2 && fields[0].matches(Regex("[a-z0-9-]+(?:~[a-z0-9-]+)?"))) {
                "Expected a page key and URL separated by a tab in ${input.name}"
            }
            fields[0] to fields[1]
        }
        require(targets.distinctBy { it.first }.size == targets.size) { "Duplicate page keys in ${input.name}" }
        val pages = File(dir, "pages").apply { mkdirs() }
        val env = System.getenv() + mapOf(
            "VDS_ENRICH_USERNAME" to "source-workbench",
            "VDS_ENRICH_PASSWORD" to "source-workbench",
        )
        val config = loadEnrichProcessConfig(env).crawler
        crawlerHttpClient().use { client ->
            val fetcher = KtorPageFetcher(config, Clock.systemUTC(), client, rateLimiter = HostRateLimiter(config.hostDelayMs, 4096))
            val limit = Semaphore(12)
            output.printWriter().use { log ->
                runBlocking {
                    targets.map { (key, url) ->
                        async {
                            limit.withPermit {
                                try {
                                    val page = fetcher.fetch(url, FetchKind.PAGE)
                                    File(pages, "$key.html").writeText(page.body)
                                    log.println("PAGE\t$key\t$url\t${page.finalUrl}")
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (failure: Exception) {
                                    log.println("SKIP\t$key\t$url\t${failure.message}")
                                }
                                log.flush()
                            }
                        }
                    }.awaitAll()
                }
            }
        }
    }

    private fun clean(el: Element): String =
        el.outerHtml()
            .replace(Regex("(?s)<(style|svg|path|script)\\b.*?</\\1>"), "")
            .replace(Regex("\\s+"), " ")
            .replace(Regex(" (data-astro-cid-\\w+|style=\"[^\"]*\")"), "")

    private fun path(el: Element): String =
        (listOf(el) + el.parents()).take(7).joinToString(" < ") { p ->
            p.tagName() + (if (p.id().isNotEmpty()) "#" + p.id() else "") + p.classNames().take(4).joinToString("") { ".$it" }
        }

    /**
     * `probe.txt` -> `probe.out`. Lines `slug|selector` print the matches (`PROBE_N` of them, text
     * only unless `PROBE_HTML` is set); `slug|@text` prints the ancestor path of elements whose own
     * text contains `text`.
     */
    @Test
    fun probe() {
        val limit = System.getenv("PROBE_N")?.toInt()
        val withHtml = limit == null || System.getenv("PROBE_HTML") != null
        val out = StringBuilder()
        File(dir, "probe.txt").readLines().filter { it.isNotBlank() && !it.startsWith("#") }.forEach { line ->
            val (slug, selector) = line.split("|", limit = 2)
            val doc = page(slug)
            if (selector.startsWith("@")) {
                val hits = doc.select("*:containsOwn(${selector.drop(1)})")
                out.appendLine("== $slug | $selector -> ${hits.size}")
                hits.take(4).forEach { el ->
                    out.appendLine("  OWN: ${el.ownText().take(120)}")
                    out.appendLine("  PATH: ${path(el)}")
                }
                return@forEach
            }
            val found = runCatching { doc.select(selector) }
                .getOrElse { out.appendLine("== $slug | $selector -> ERROR $it"); return@forEach }
            out.appendLine("== $slug | $selector -> ${found.size}")
            found.take(limit ?: 2).forEach { el ->
                out.appendLine("  TEXT: ${el.text().take(400)}")
                if (withHtml) out.appendLine("  HTML: ${clean(el).take(4000)}")
            }
        }
        File(dir, "probe.out").writeText(out.toString())
    }

    /**
     * `auto.txt` (one slug per line) -> `auto.out`: the largest group of >= 3 same-tag-and-class
     * siblings that each mention a memory size and a currency, i.e. the likely tariff cards.
     */
    @Test
    fun auto() {
        val ram = Regex(
            "(?i)(\\d+(\\.\\d+)?\\s?(GB|GiB|MB|ГБ|Гб|Go)\\b.{0,25}(RAM|Memory|ОЗУ|DDR|Speicher|mémoire|Pamięć|Geheugen|Memoria))" +
                "|((RAM|Memory|ОЗУ|Speicher|Memoria)\\D{0,25}\\d+(\\.\\d+)?\\s?(GB|GiB|MB|ГБ|Гб|Go))",
        )
        val money = Regex("[$€£₽₴₪¥₹₺]|руб|грн|zł|PLN|USD|EUR|Kč|kr|R\\$|RM|Rp|TL")
        val out = StringBuilder()
        File(dir, "auto.txt").readLines().filter { it.isNotBlank() }.forEach { slug ->
            if (!File(dir, "pages/$slug.html").exists()) return@forEach
            val doc = page(slug)
            doc.select("script, style, noscript, svg").remove()
            val byParent = LinkedHashMap<Element, MutableList<Element>>()
            doc.getAllElements().forEach { el ->
                val parent = el.parent() ?: return@forEach
                val text = el.text()
                if (text.length in 15..2500 && ram.containsMatchIn(text) && money.containsMatchIn(text)) {
                    byParent.getOrPut(parent) { mutableListOf() }.add(el)
                }
            }
            val best = byParent.entries
                .flatMap { (parent, kids) ->
                    kids.groupBy { it.tagName() + "." + it.classNames().sorted().joinToString(".") }
                        .map { Triple(parent, it.key, it.value) }
                }
                .filter { it.third.size >= 3 }
                .sortedWith(compareByDescending<Triple<Element, String, List<Element>>> { it.third.size }.thenBy { it.third.first().text().length })
                .firstOrNull()
            if (best == null) {
                out.appendLine("#### $slug: NONE")
                return@forEach
            }
            out.appendLine("#### $slug: ${best.third.size} x ${best.second} in ${path(best.first)}")
            best.third.take(2).forEach { out.appendLine("  CARD: ${clean(it).take(3500)}") }
        }
        File(dir, "auto.out").writeText(out.toString())
    }

    /**
     * Every JSON file in `SRC_SUBDIR` (default `sources`) through the format, [DeclarativeAdapter] and
     * [SnapshotValidator] -- the same three gates a crawler run applies -- into `VALIDATE_OUT`
     * (default `validate.out`), one line per tariff.
     */
    @Test
    fun validate() {
        val sources = File(dir, System.getenv("SRC_SUBDIR") ?: "sources")
        val out = StringBuilder()
        sources.listFiles { f -> f.extension == "json" }!!.sortedBy { it.name }.forEach { file ->
            val parsed = SourceConfigFormat.parse(file.name, file.readText())
            if (parsed is SourceConfigParse.Invalid) {
                out.appendLine("## ${file.name}: INVALID ${parsed.problems}")
                return@forEach
            }
            val config = (parsed as SourceConfigParse.Valid).config
            val adapter = DeclarativeAdapter(config)
            val html = File(dir, "pages/${config.providerSlug}.html").readText()
            val result = runCatching {
                runBlocking { adapter.fetch(CrawlContext(StubPageFetcher(html), Clock.systemUTC(), UUID.randomUUID())) }
            }
            result.onFailure { out.appendLine("## ${file.name}: FAIL ${it.message}") }
            result.onSuccess { snap ->
                val problems = SnapshotValidator(Clock.systemUTC()).validate(adapter, snap)
                if (problems.isNotEmpty()) out.appendLine("## ${file.name}: SNAPSHOT-INVALID $problems")
                val tariffs = snap.tariffs.orEmpty()
                out.appendLine("## ${file.name}: OK ${tariffs.size} tariffs ${parsed.warnings}")
                val priceInputs = config.tariffs?.let { section ->
                    Jsoup.parse(html).selectFirst(section.rootSelector)!!.select(section.rowSelector).map { row ->
                        section.prices.joinToString(" | ") { spec ->
                            val element = row.selectFirst(spec.selector)
                            val raw = spec.value ?: element?.let { spec.attr?.let(it::attr) ?: it.text() }
                            "${spec.billingPeriod} '${spec.selector}' = '$raw'"
                        }
                    }
                }.orEmpty()
                tariffs.forEachIndexed { index, t ->
                    out.appendLine(
                        "  ${t.externalId?.take(40)} | ${t.name} | cpu=${t.vcpuCount} ram=${t.ramMb} disk=${t.diskGb} ${t.diskType} " +
                            "bw=${t.bandwidthMbps} tr=${t.trafficLimitGb} ip4=${t.ipv4Count} ip6=${t.ipv6Supported} " +
                            "${t.virtualization} stock=${t.inStock} " +
                            t.prices.joinToString { "${it.billingPeriod}:${it.amount.toPlainString()} ${it.currency}" },
                    )
                    out.appendLine("  PRICE INPUTS | ${priceInputs.getOrNull(index).orEmpty()}")
                }
            }
        }
        File(dir, System.getenv("VALIDATE_OUT") ?: "validate.out").writeText(out.toString())
    }
}
