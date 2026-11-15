package org.a1tsign.vds.crawler.enrich

import org.jsoup.Jsoup
import org.jsoup.nodes.DataNode
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.select.NodeTraversor
import org.jsoup.select.NodeVisitor

/** Visible text is capped per page; together with the footer cap this bounds one page's analysis. */
internal const val MAX_TEXT_CHARS = 448 * 1024
internal const val MAX_FOOTER_CHARS = 64 * 1024

/** One JSON-LD block or the inline scripts of a page beyond this size are not analysed. */
private const val MAX_JSON_LD_CHARS = 256 * 1024
private const val MAX_INLINE_SCRIPT_CHARS = 1024 * 1024
private const val MAX_ANCHOR_TEXT_CHARS = 200
private const val MAX_LINKS = 2_000

/** A link of the page: the resolved URL and its anchor text. */
internal data class PageLink(val url: String, val text: String)

/**
 * What the analysis needs from one HTML page. Built from the jsoup DOM, which is dropped as soon as
 * this is built; this in turn is dropped after the page is analysed.
 */
internal class PageContent(
    val text: String,
    val footer: String,
    /** (tag, joined presentational attributes) of every element that has any. */
    val attributes: List<Pair<String, String>>,
    /** `src` of scripts and iframes, `href` of `<link>`: where payment gateways show up. */
    val resourceUrls: List<String>,
    val jsonLd: List<String>,
    /** Inline (non-`src`) script bodies with `\uXXXX` escapes resolved. */
    val inlineScripts: String,
    val siteName: String?,
    val links: List<PageLink>,
)

internal object PageReader {

    private val SKIPPED_TAGS = setOf("script", "style", "noscript", "template", "option", "select", "svg")
    private val FOOTER_MARKER = rx("\\bfooter|site-footer|page-footer")
    private val ATTRIBUTE_KEYS = listOf(
        "class", "id", "alt", "title", "aria-label", "src", "data-src", "srcset", "href", "xlink:href", "data-icon", "style",
    )

    fun read(html: String, baseUrl: String): PageContent {
        val document = Jsoup.parse(html, baseUrl)
        val text = CappedJoin(MAX_TEXT_CHARS)
        val footer = CappedJoin(MAX_FOOTER_CHARS)
        val attributes = mutableListOf<Pair<String, String>>()
        val resources = mutableListOf<String>()
        val jsonLd = mutableListOf<String>()
        val inline = StringBuilder()
        var siteName: String? = null

        NodeTraversor.traverse(
            object : NodeVisitor {
                var skipDepth = 0
                var footerDepth = 0

                override fun head(node: Node, depth: Int) {
                    when (node) {
                        is Element -> {
                            val tag = node.normalName()
                            if (tag in SKIPPED_TAGS) skipDepth++
                            if (isFooter(node)) footerDepth++
                            collectElement(node, tag)
                        }
                        is TextNode -> if (skipDepth == 0) {
                            val chunk = node.wholeText.trim()
                            if (chunk.isNotEmpty()) {
                                text.add(chunk)
                                if (footerDepth > 0) footer.add(chunk)
                            }
                        }
                        is DataNode -> Unit
                        else -> Unit
                    }
                }

                override fun tail(node: Node, depth: Int) {
                    if (node is Element) {
                        if (node.normalName() in SKIPPED_TAGS) skipDepth--
                        if (isFooter(node)) footerDepth--
                    }
                }

                private fun collectElement(element: Element, tag: String) {
                    when (tag) {
                        "a" -> {
                            val joined = listOf("class", "aria-label", "title").map { element.attr(it) }.filter { it.isNotEmpty() }
                            if (joined.isNotEmpty()) attributes += tag to joined.joinToString(" ")
                        }
                        "script" -> {
                            val src = element.attr("src")
                            if (src.isNotEmpty()) {
                                resources += src
                            } else {
                                val data = element.data()
                                if (element.attr("type").equals("application/ld+json", ignoreCase = true) && data.length <= MAX_JSON_LD_CHARS) {
                                    jsonLd += data
                                }
                                if (inline.length + data.length <= MAX_INLINE_SCRIPT_CHARS) inline.append(data).append(' ')
                            }
                        }
                        "link" -> element.attr("href").takeIf { it.isNotEmpty() }?.let { resources += it }
                        else -> {
                            if (tag == "iframe") element.attr("src").takeIf { it.isNotEmpty() }?.let { resources += it }
                            if (tag == "meta" && element.attr("property") == "og:site_name") siteName = element.attr("content")
                            val joined = ATTRIBUTE_KEYS.map { element.attr(it) }.filter { it.isNotEmpty() }
                            if (joined.isNotEmpty()) attributes += tag to joined.joinToString(" ")
                        }
                    }
                }
            },
            document,
        )

        val links = document.select("a[href]").asSequence()
            .mapNotNull { anchor ->
                val url = anchor.absUrl("href").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                PageLink(url, anchor.text().take(MAX_ANCHOR_TEXT_CHARS))
            }
            .take(MAX_LINKS)
            .toList()

        return PageContent(
            text = text.toString(),
            footer = footer.toString(),
            attributes = attributes,
            resourceUrls = resources,
            jsonLd = jsonLd,
            inlineScripts = unescapeJs(inline),
            siteName = siteName,
            links = links,
        )
    }

    private fun isFooter(element: Element): Boolean {
        if (element.normalName() == "footer") return true
        val marker = (element.attr("class") + " " + element.attr("id")).lowercase()
        return FOOTER_MARKER.matcher(marker).find()
    }

    /** Resolves `\uXXXX`, `\u{X…}` and `\xXX` escapes in one linear pass. */
    internal fun unescapeJs(source: CharSequence): String {
        val out = StringBuilder(source.length)
        var i = 0
        while (i < source.length) {
            val c = source[i]
            if (c == '\\' && i + 1 < source.length) {
                val decoded = decodeEscape(source, i)
                if (decoded != null) {
                    out.appendCodePoint(decoded.first)
                    i = decoded.second
                    continue
                }
            }
            out.append(c)
            i++
        }
        return out.toString()
    }

    /** (code point, index after the escape) for an escape starting at [start], or `null`. */
    private fun decodeEscape(source: CharSequence, start: Int): Pair<Int, Int>? {
        val kind = source[start + 1]
        fun hex(from: Int, to: Int): Int? {
            if (to > source.length || to <= from) return null
            if (!(from until to).all { Character.digit(source[it], 16) >= 0 }) return null
            return source.subSequence(from, to).toString().toInt(16)
        }
        return when (kind) {
            'u' -> if (start + 2 < source.length && source[start + 2] == '{') {
                val close = (start + 3 until minOf(source.length, start + 10)).firstOrNull { source[it] == '}' } ?: return null
                hex(start + 3, close)?.takeIf { Character.isValidCodePoint(it) }?.let { it to close + 1 }
            } else {
                hex(start + 2, start + 6)?.let { it to start + 6 }
            }
            'x' -> hex(start + 2, start + 4)?.let { it to start + 4 }
            else -> null
        }
    }
}

/** `" \n".join(chunks)`, stopping once [limit] characters are reached. */
private class CappedJoin(private val limit: Int) {
    private val builder = StringBuilder()

    fun add(chunk: String) {
        if (builder.length >= limit) return
        if (builder.isNotEmpty()) builder.append(" \n")
        val room = limit - builder.length
        builder.append(if (chunk.length <= room) chunk else chunk.substring(0, room.coerceAtLeast(0)))
    }

    override fun toString(): String = builder.toString()
}
