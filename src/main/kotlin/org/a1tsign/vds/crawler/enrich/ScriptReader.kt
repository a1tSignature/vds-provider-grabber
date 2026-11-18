package org.a1tsign.vds.crawler.enrich

import org.jsoup.parser.Parser

private const val MIN_LITERAL_CHARS = 3
private const val MAX_CHUNK_REF_CHARS = 200
private const val MAX_CHUNK_REFS = 500
private const val MAX_ASSET_CHARS = 200
private const val MAX_ASSETS = 2_000
private val ASSET_EXTENSIONS = listOf(".svg", ".png", ".webp")

internal class ScriptContent(
    /** Human-readable string literals, joined like page text. */
    val text: String,
    /** Literals shaped like a chunk import (`./x.js`, `/assets/x.js`), unresolved. */
    val chunkRefs: List<String>,
    /** Image paths (`…/flags/de.svg`): flag icons are location signals. */
    val assets: List<String>,
)

/** Scan minified JS in linear time. Repeated regex alternatives on a large bundle can overflow the JVM stack. */
internal object ScriptReader {

    fun read(source: String): ScriptContent {
        val text = CappedJoin(MAX_TEXT_CHARS)
        val refs = LinkedHashSet<String>()
        // Per quote kind: the index the last failed scan reached. An opener before it has no closer
        // either, so it is not rescanned; this keeps a run like `'\'\'\'…` linear.
        val noCloserUntil = IntArray(3) { -1 }
        var i = 0
        while (i < source.length) {
            val kind = QUOTES.indexOf(source[i])
            if (kind < 0 || i < noCloserUntil[kind]) {
                i++
                continue
            }
            val end = closingQuote(source, i + 1, source[i])
            if (end < 0) {
                noCloserUntil[kind] = -end - 1
                i++
                continue
            }
            val literal = source.substring(i + 1, end)
            if (refs.size < MAX_CHUNK_REFS && isChunkRef(literal)) refs += literal
            if (literal.length >= MIN_LITERAL_CHARS && !text.isFull) visibleText(literal)?.let(text::add)
            i = end + 1
        }
        return ScriptContent(text.toString(), refs.toList(), assets(source))
    }

    private const val QUOTES = "\"'`"

    /**
     * Index of the quote closing a literal opened just before [from], or `-(stop + 1)` when there is
     * none: a `"`/`'` literal ends at an unescaped newline, a template literal only at the end.
     */
    private fun closingQuote(source: String, from: Int, quote: Char): Int {
        var j = from
        while (j < source.length) {
            val c = source[j]
            when {
                c == '\\' -> j += 2
                c == quote -> return j
                c == '\n' && quote != '`' -> return -(j + 1)
                else -> j++
            }
        }
        return -(source.length + 1)
    }

    private fun isChunkRef(literal: String): Boolean {
        if (literal.length > MAX_CHUNK_REF_CHARS || !literal.endsWith(".js")) return false
        val prefix = when {
            literal.startsWith("./") -> 2
            literal.startsWith("/assets/") -> 8
            literal.startsWith("assets/") -> 7
            else -> return false
        }
        if (literal.length - 3 <= prefix) return false
        return literal.all { isWordChar(it) || it == '.' || it == '-' || it == '/' }
    }

    /** The literal as display text, or `null` for code-like strings (identifiers, paths, selectors). */
    private fun visibleText(literal: String): String? {
        val unescaped = PageReader.unescapeJs(literal)
        if (!hasLetterRun(unescaped)) return null
        if (unescaped.all { isWordChar(it) || it in "$.-/:#" } && unescaped.none(::isCyrillic)) return null
        val plain = unescaped.replace("\\n", "\n").replace("\\t", " ").replace("\\\"", "\"").replace("\\'", "'")
        val unescapedEntities = Parser.unescapeEntities(withoutTags(plain), false)
        return collapseBlanks(unescapedEntities).trim().ifEmpty { null }
    }

    /** Three Latin or Cyrillic letters in a row. */
    private fun hasLetterRun(s: String): Boolean {
        var run = 0
        for (c in s) {
            run = if (c in 'A'..'Z' || c in 'a'..'z' || isCyrillic(c)) run + 1 else 0
            if (run >= 3) return true
        }
        return false
    }

    private fun isCyrillic(c: Char) = c in 'А'..'я' || c == 'Ё' || c == 'ё'

    private fun isWordChar(c: Char) = c.isLetterOrDigit() || c == '_'

    /** Every `<…>` replaced by a space; an unclosed `<` is kept as text. */
    private fun withoutTags(s: String): String {
        val out = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val open = s.indexOf('<', i)
            if (open >= 0 && open + 1 < s.length && s[open + 1] == '>') {
                out.append(s, i, open + 1)
                i = open + 1
                continue
            }
            val close = if (open < 0) -1 else s.indexOf('>', open + 2)
            if (close < 0) {
                out.append(s, i, s.length)
                break
            }
            out.append(s, i, open).append(' ')
            i = close + 1
        }
        return out.toString()
    }

    private fun collapseBlanks(s: String): String {
        val out = StringBuilder(s.length)
        var blank = false
        for (c in s) {
            if (c == ' ' || c == '\t') {
                if (!blank) out.append(' ')
                blank = true
            } else {
                out.append(c)
                blank = false
            }
        }
        return out.toString()
    }

    /** Non-overlapping `[\w\-/.]+\.(svg|png|webp)` runs, found by walking back from each extension. */
    private fun assets(source: String): List<String> {
        val found = mutableListOf<String>()
        var lastEnd = 0
        var dot = source.indexOf('.')
        while (dot >= 0 && found.size < MAX_ASSETS) {
            val extension = ASSET_EXTENSIONS.firstOrNull { source.startsWith(it, dot) }
            if (extension != null && dot > lastEnd) {
                var start = dot
                while (start > lastEnd && dot - start < MAX_ASSET_CHARS) {
                    val c = source[start - 1]
                    if (!(isWordChar(c) || c == '-' || c == '/' || c == '.')) break
                    start--
                }
                if (start < dot) {
                    lastEnd = dot + extension.length
                    found += source.substring(start, lastEnd)
                }
            }
            dot = source.indexOf('.', maxOf(dot + 1, lastEnd))
        }
        return found
    }
}
