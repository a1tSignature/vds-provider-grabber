package org.a1tsign.vds.crawler.enrich

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.a1tsign.vds.crawler.enrich.EnrichmentRules.ADDRESS_NEAR_PREFIX
import java.util.regex.Matcher
import java.util.regex.Pattern

private const val MAX_SNIPPET = 220
private const val MAX_URL = 2048
private const val MAX_LABEL = 64
private const val MAX_BASIS = 10
private const val MAX_EVIDENCE = 3
private const val MAX_ALTERNATIVES = 3
private const val MAX_ENTITY_NAME = 70
private const val MAX_PAYMENT_METHODS = 20
private const val MAX_LOCATIONS = 25

/** Distinct values kept per facet and distinct kinds per value: what one hostile site can grow. */
private const val MAX_VALUES_PER_FACET = 500
private const val MAX_KINDS_PER_VALUE = 128

/**
 * Matches of one pattern analysed per page. Analysis cannot be cancelled, so this bounds how long a
 * page crafted with endless repetitions can hold the step past its budget.
 */
private const val MAX_MATCHES_PER_PATTERN = 2_000

/** One signal: [weight] 0 records a mention that must not count (negated, inside an address). */
internal class Signal(val weight: Int, val kind: String, val url: String, val snippet: String)

/**
 * Everything one value collected, reduced as it arrives: the full signal list of a value is never
 * kept, only what the decision functions read.
 */
internal class Tally {
    var score = 0
        private set
    val pages = LinkedHashSet<String>()
    val pagesWithWeight = LinkedHashSet<String>()
    val kindWeights = LinkedHashMap<String, Int>()

    /** The strongest signals, ties in arrival order. */
    val top = ArrayList<Signal>(MAX_EVIDENCE + 1)

    fun add(signal: Signal) {
        score += signal.weight
        pages += signal.url
        if (signal.weight > 0) pagesWithWeight += signal.url
        if (signal.kind in kindWeights || kindWeights.size < MAX_KINDS_PER_VALUE) {
            kindWeights[signal.kind] = (kindWeights[signal.kind] ?: 0) + signal.weight
        }
        val at = top.indexOfFirst { it.weight < signal.weight }.let { if (it < 0) top.size else it }
        if (at < MAX_EVIDENCE) {
            top.add(at, signal)
            if (top.size > MAX_EVIDENCE) top.removeAt(top.lastIndex)
        }
    }
}

/** The four facets' signals for one site, accumulated page by page. */
internal class SiteSignals(val domain: String) {
    val payments = LinkedHashMap<String, Tally>()
    val locations = LinkedHashMap<String, Tally>()
    val companies = LinkedHashMap<String, Tally>()
    val countries = LinkedHashMap<String, Tally>()

    fun isEmpty() = payments.isEmpty() && locations.isEmpty() && companies.isEmpty() && countries.isEmpty()

    fun add(facet: MutableMap<String, Tally>, key: String, weight: Int, kind: String, url: String, snippet: String = "") {
        val tally = facet[key] ?: if (facet.size < MAX_VALUES_PER_FACET) Tally().also { facet[key] = it } else return
        tally.add(Signal(weight, kind, url, clean(snippet, MAX_SNIPPET)))
    }
}

/** Adds one page's signals to [signals]. */
internal object PageAnalyzer {

    private val json = Json { isLenient = true }

    fun analyze(page: PageContent, url: String, kinds: Set<String>, isPricing: Boolean, signals: SiteSignals) {
        payments(page, url, kinds, signals)
        locations(page, url, kinds, isPricing, signals)
        jsonLd(page, url, signals)
        entities(page.text, page.footer, url, kinds, page.siteName, signals)
        registrationCountry(page.text, page.footer, url, kinds, signals)
    }

    private fun payments(page: PageContent, url: String, kinds: Set<String>, signals: SiteSignals) {
        for ((tag, attribute) in page.attributes) {
            val lower = attribute.lowercase()
            for (rule in EnrichmentRules.PAYMENT_RULES) {
                if (rule.attribute?.matcher(lower)?.find() == true) {
                    signals.add(signals.payments, rule.code, 3, "logo/attr<$tag>", url, attribute)
                }
            }
        }
        for (resource in page.resourceUrls) {
            for ((pattern, codes, name) in EnrichmentRules.GATEWAYS) {
                if (pattern.matcher(resource).find()) codes.forEach { signals.add(signals.payments, it, 2, "gateway:$name", url, resource) }
            }
        }
        val strongPage = kinds.any { it == "pay" || it == "faq" || it == "legal" }
        for (rule in EnrichmentRules.PAYMENT_RULES) {
            val m = rule.text.matcher(page.text)
            val nextM = capped(m)
            while (nextM()) {
                val snippet = snip(page.text, m, 60)
                val negated = EnrichmentRules.NEGATION.matcher(snippet).find()
                val weight = if (negated) 0 else if (strongPage) 2 else 1
                signals.add(signals.payments, rule.code, weight, if (negated) "text NEGATED" else "text", url, snippet)
            }
        }
    }

    private fun locations(page: PageContent, url: String, kinds: Set<String>, isPricing: Boolean, signals: SiteSignals) {
        val text = page.text
        val locationPage = "loc" in kinds || isPricing
        for ((tag, attribute) in page.attributes) {
            val m = EnrichmentRules.FLAG.matcher(attribute)
            val nextM = capped(m)
            while (nextM()) {
                val code = EnrichmentRules.isoCountry(m.group(1)) ?: continue
                signals.add(signals.locations, code, if (locationPage) 3 else 2, "flag<$tag>", url, attribute)
            }
        }
        for ((code, name, pattern) in EnrichmentRules.CITIES) {
            val m = pattern.matcher(text)
            val nextM = capped(m)
            while (nextM()) {
                val snippet = snip(text, m, 80)
                if (name == "Milan" && EnrichmentRules.MILAN_CPU.matcher(snippet).find()) continue
                val weight = if (EnrichmentRules.ADDRESS_CONTEXT.matcher(snippet).find()) 0 else if (locationPage) 2 else 1
                signals.add(signals.locations, code, weight, "city:$name", url, snippet)
            }
        }
        if (locationPage) {
            for ((code, pattern) in EnrichmentRules.COUNTRY_NAMES) {
                val m = pattern.matcher(text)
                val nextM = capped(m)
                while (nextM()) {
                    val snippet = snip(text, m, 60)
                    if (EnrichmentRules.ADDRESS_CONTEXT.matcher(snippet).find() ||
                        EnrichmentRules.NOT_A_LOCATION_CONTEXT.matcher(snippet).find()
                    ) {
                        continue
                    }
                    signals.add(signals.locations, code, 1, "country name", url, snippet)
                }
            }
        }
        val m = EnrichmentRules.SERVERS_LOCATED_IN.matcher(text)
        val nextM = capped(m)
        while (nextM()) {
            val tail = firstSentence(text.substring(m.end(), minOf(text.length, m.end() + 140)))
            val snippet = snip(text, m, 100)
            for ((code, pattern) in EnrichmentRules.COUNTRY_NAMES) {
                if (pattern.matcher(tail).find()) signals.add(signals.locations, code, 4, "servers located in", url, snippet)
            }
            for ((code, _, pattern) in EnrichmentRules.CITIES) {
                if (pattern.matcher(tail).find()) signals.add(signals.locations, code, 4, "servers located in", url, snippet)
            }
        }
        val js = EnrichmentRules.JS_COUNTRY_OBJECT.matcher(page.inlineScripts)
        val nextJs = capped(js)
        while (nextJs()) {
            val code = js.group(2).takeIf { it in EnrichmentRules.ISO2 } ?: continue
            signals.add(signals.locations, code, 4, "js data {name, code}", url, js.group())
        }
    }

    private fun jsonLd(page: PageContent, url: String, signals: SiteSignals) {
        for (block in page.jsonLd) {
            val root = try {
                json.parseToJsonElement(block)
            } catch (_: Exception) {
                continue
            }
            for (node in objectsOf(root)) {
                val types = when (val type = node["@type"]) {
                    is JsonArray -> type.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                    is JsonPrimitive -> listOfNotNull(type.contentOrNull)
                    else -> emptyList()
                }
                if (types.none { it in EnrichmentRules.JSON_LD_ORGANIZATION_TYPES }) continue
                val address = node["address"] as? JsonObject
                val legalName = node["legalName"]?.let(::scalar)
                val name = node["name"]?.let(::scalar)
                if (!legalName.isNullOrEmpty()) {
                    signals.add(signals.companies, normaliseName(legalName), 6, "json-ld legalName", url)
                } else if (!name.isNullOrEmpty()) {
                    signals.add(signals.companies, normaliseName(name), 2, "json-ld name", url)
                }
                val country = when (val raw = address?.get("addressCountry")) {
                    is JsonObject -> raw["name"]?.let(::scalar)
                    null -> null
                    else -> scalar(raw)
                }
                if (!country.isNullOrEmpty()) {
                    val code = if (country.length == 2) EnrichmentRules.isoCountry(country) else countryOf(country)
                    if (code != null) signals.add(signals.countries, code, 6, "json-ld", url, address.toString())
                }
            }
        }
    }

    private fun entities(text: String, footer: String, url: String, kinds: Set<String>, siteName: String?, signals: SiteSignals) {
        val legalPage = "legal" in kinds
        val company = EnrichmentRules.COMPANY.matcher(text)
        val nextCompany = capped(company)
        while (nextCompany()) {
            val name = trimShoutedSentence(normaliseName(company.group(1))) ?: continue
            val inFooter = footer.contains(name)
            val strong = inFooter || legalPage
            signals.add(signals.companies, name, if (strong) 3 else 1, if (inFooter) "legal form in footer" else "legal form", url, snip(text, company, 40))
            val near = text.substring(maxOf(0, company.start() - 200), minOf(text.length, company.end() + 250))
            countryOf(near)?.let { code ->
                signals.add(signals.countries, code, if (strong) 2 else 1, "$ADDRESS_NEAR_PREFIX$name'", url, near)
            }
            entityCountrySuffix(text, company.end())?.let { code ->
                signals.add(
                    signals.countries, code, EnrichmentRules.ENTITY_COUNTRY_SUFFIX_WEIGHT, "$ADDRESS_NEAR_PREFIX$name'", url,
                    snip(text, company, 80),
                )
            }
        }
        val russian = EnrichmentRules.RU_COMPANY.matcher(text)
        val nextRussian = capped(russian)
        while (nextRussian()) {
            signals.add(signals.companies, normaliseName(russian.group(1)), 4, "russian legal form", url, snip(text, russian, 40))
        }
        val operated = EnrichmentRules.OPERATED_BY.matcher(text)
        val nextOperated = capped(operated)
        while (nextOperated()) {
            signals.add(signals.companies, normaliseName(operated.group(1)), 4, "operated by", url, snip(text, operated, 40))
        }
        val holderText = footer.ifEmpty { text }
        val copyright = EnrichmentRules.COPYRIGHT.matcher(holderText)
        val nextCopyright = capped(copyright)
        while (nextCopyright()) {
            val name = normaliseName(copyright.group(1))
            if (name.length >= 3 && !EnrichmentRules.NOT_A_COPYRIGHT_HOLDER.matcher(name).lookingAt()) {
                signals.add(signals.companies, name, 2, "© line", url, snip(holderText, copyright, 30))
            }
        }
        if (!siteName.isNullOrBlank()) signals.add(signals.companies, normaliseName(siteName), 1, "og:site_name", url)
    }

    /** `<entity>, Cyprus private limited company` or `<ENTITY> (Cyprus entity)` right after a name. */
    private fun entityCountrySuffix(text: String, nameEnd: Int): String? {
        val m = EnrichmentRules.ENTITY_COUNTRY_SUFFIX.matcher(text)
        m.region(nameEnd, minOf(text.length, nameEnd + 80))
        if (!m.lookingAt()) return null
        return countryOf(m.group(1) ?: m.group(2))
    }

    private fun registrationCountry(text: String, footer: String, url: String, kinds: Set<String>, signals: SiteSignals) {
        val legalPage = "legal" in kinds
        for (rule in EnrichmentRules.STRONG_LEGAL) {
            val m = rule.pattern.matcher(text)
            val nextM = capped(m)
            while (nextM()) {
                val phrase = (1..m.groupCount()).firstNotNullOfOrNull { m.group(it)?.takeIf(String::isNotEmpty) } ?: continue
                val code = countryOf(phrase) ?: continue
                val before = text.substring(maxOf(0, m.start() - 160), m.start())
                if (rule.needsSubject && !EnrichmentRules.WE_ARE.matcher(m.group()).find()) {
                    if (EnrichmentRules.SOMEONE_ELSE.matcher(before.takeLast(70)).find()) continue
                    val hasSubject = EnrichmentRules.COMPANY.matcher(before).find() ||
                        EnrichmentRules.RU_COMPANY.matcher(before).find() ||
                        EnrichmentRules.SUBJECT.matcher(before.takeLast(120) + m.group()).find()
                    if (!hasSubject) continue
                }
                signals.add(signals.countries, code, rule.weight + if (legalPage) 1 else 0, rule.label, url, snip(text, m, 50))
            }
        }
        for ((pattern, fixed) in EnrichmentRules.TAX_IDS) {
            val m = pattern.matcher(text)
            val nextM = capped(m)
            while (nextM()) {
                val code = fixed ?: EnrichmentRules.isoCountry(if (m.group(1) == "EL") "GR" else m.group(1)) ?: continue
                signals.add(signals.countries, code, 5, "tax/registry id", url, snip(text, m, 40))
            }
        }
        if (legalPage || footer.isNotEmpty()) {
            val source = if (footer.isNotEmpty() && !legalPage) footer else text
            val m = EnrichmentRules.PHONE.matcher(source)
            val nextM = capped(m)
            while (nextM()) {
                val digits = m.group(1).filter(Char::isDigit)
                val code = (3 downTo 1).firstNotNullOfOrNull { EnrichmentRules.PHONE_PREFIX[digits.take(it)] } ?: continue
                signals.add(signals.countries, code, 1, "phone prefix", url, m.group())
            }
        }
    }

    /** Every JSON object in [root], walked with an explicit stack: a hostile document can nest deeply. */
    private fun objectsOf(root: JsonElement): Sequence<JsonObject> = sequence {
        val stack = ArrayDeque<JsonElement>()
        stack.addLast(root)
        while (stack.isNotEmpty()) {
            when (val element = stack.removeLast()) {
                is JsonObject -> {
                    yield(element)
                    element.values.reversed().forEach(stack::addLast)
                }
                is JsonArray -> element.reversed().forEach(stack::addLast)
                else -> Unit
            }
        }
    }

    private fun scalar(element: JsonElement): String? = when (element) {
        is JsonPrimitive -> element.contentOrNull
        else -> element.toString()
    }
}


/** Turns a site's signals into findings: the entity, its registration country and the ranked value lists. */
internal object Decisions {

    fun findings(signals: SiteSignals): EnrichmentFindings {
        val entity = decideCompany(signals)
        return EnrichmentFindings(
            legalEntityName = entity,
            registrationCountry = decideCountry(signals, entity.value.takeUnless { entity.basis == BRAND_ONLY }),
            paymentMethods = roll(signals.payments, MAX_PAYMENT_METHODS),
            locations = roll(signals.locations, MAX_LOCATIONS),
            anySignal = !signals.isEmpty(),
        )
    }

    private val BRAND_ONLY = listOf("brand only (domain)")

    /**
     * Spellings that differ only in case or legal-form synonym are one entity: its kinds and pages
     * are the union over the spellings, so `Hostinger International Ltd` from JSON-LD and
     * `HOSTINGER INTERNATIONAL LIMITED` from the terms confirm each other.
     */
    private class Candidate(
        var value: String,
        var score: Double,
        var hasForm: Boolean,
        var evidence: List<Signal>,
        val kinds: MutableSet<String>,
        val pages: MutableSet<String>,
    )

    fun decideCompany(signals: SiteSignals): LegalFinding {
        val brand = signals.domain.substringBefore('.').lowercase().filter { it in 'a'..'z' || it in '0'..'9' }
        val scored = LinkedHashMap<String, Candidate>()
        for ((name, tally) in signals.companies) {
            val key = flatKey(name)
            if (key.isEmpty() || name.length > MAX_ENTITY_NAME) continue
            var score = tally.score.toDouble()
            if (brand.isNotEmpty() && brand in key) score += 4
            val hasForm = hasLegalForm(name)
            if (hasForm) score += 2
            val k = if (hasForm) EnrichmentRules.FORM_SUFFIX.matcher(key).replaceFirst("") else key
            val previous = scored[k]
            if (previous == null) {
                scored[k] = Candidate(name, score, hasForm, tally.top.toList(), tally.kindWeights.keys.toMutableSet(), tally.pages.toMutableSet())
                continue
            }
            previous.kinds += tally.kindWeights.keys
            previous.pages += tally.pages
            if ((hasForm && !previous.hasForm) || (hasForm == previous.hasForm && score > previous.score)) {
                previous.score = score + previous.score * 0.5
                if (!(isShouted(name) && !isShouted(previous.value) && previous.hasForm)) previous.value = name
                previous.hasForm = hasForm
                previous.evidence = tally.top.toList()
            } else {
                previous.score += score * 0.5
                if (isShouted(previous.value) && !isShouted(name) && hasForm == previous.hasForm) previous.value = name
            }
        }
        if (scored.isEmpty()) {
            return LegalFinding(signals.domain, LegalStatus.PRESUMED, BRAND_ONLY, emptyList(), emptyList())
        }
        val ranked = scored.values.sortedByDescending { it.score }
        fun carriesBrand(name: String): Boolean {
            val flat = name.lowercase().filter { it in 'a'..'z' || it in '0'..'9' }
            val first = name.split(' ').firstOrNull { it.isNotEmpty() }.orEmpty().lowercase().filter { it in 'a'..'z' || it in '0'..'9' }
            return brand.isNotEmpty() && (brand in flat || (first.length >= 3 && brand.startsWith(first)))
        }
        val top = ranked.firstOrNull { it.hasForm && carriesBrand(it.value) } ?: ranked.first()
        val kinds = top.kinds.sorted()
        val strongKind = kinds.any { it == "json-ld legalName" || it == "operated by" || it == "russian legal form" || it.startsWith("legal form") }
        val confirmed = top.hasForm && strongKind && (top.pages.size >= 2 || "json-ld legalName" in kinds)
        return LegalFinding(
            value = top.value,
            status = if (confirmed) LegalStatus.CONFIRMED else LegalStatus.PRESUMED,
            basis = labels(kinds),
            alternatives = ranked.filter { it !== top }.take(MAX_ALTERNATIVES).map { it.value },
            evidence = evidence(top.evidence),
        )
    }

    fun decideCountry(signals: SiteSignals, topCompany: String?): LegalFinding? {
        val totals = LinkedHashMap<String, Double>()
        val kinds = LinkedHashMap<String, MutableSet<String>>()
        val ownKey = topCompany?.let(::comparisonKey).orEmpty()
        for ((code, tally) in signals.countries) {
            for ((kind, weight) in tally.kindWeights) {
                var label = kind
                var counted = weight.toDouble()
                if (kind.startsWith(ADDRESS_NEAR_PREFIX)) {
                    val other = comparisonKey(kind.removePrefix(ADDRESS_NEAR_PREFIX).removeSuffix("'"))
                    val own = ownKey.isNotEmpty() && other.isNotEmpty() && (ownKey in other || other in ownKey)
                    label = if (own) EnrichmentRules.ADDRESS_OF_THE_COMPANY else EnrichmentRules.ADDRESS_OF_ANOTHER_COMPANY
                    if (!own) counted *= EnrichmentRules.OTHER_COMPANY_ADDRESS_FACTOR
                }
                totals[code] = (totals[code] ?: 0.0) + counted
                kinds.getOrPut(code) { LinkedHashSet() } += label
            }
        }
        if (topCompany != null) {
            EnrichmentRules.FORM_COUNTRY.firstOrNull { (pattern, _) -> pattern.matcher(topCompany).find() }?.let { (_, code) ->
                val russian = code == "RU" || code == "KZ"
                totals[code] = (totals[code] ?: 0.0) + if (russian) 4 else 1
                kinds.getOrPut(code) { LinkedHashSet() } += if (russian) "russian legal form" else "legal form of '$topCompany'"
            }
        }
        val tld = signals.domain.substringAfterLast('.')
        EnrichmentRules.TLD_COUNTRY[tld]?.let { code ->
            totals[code] = (totals[code] ?: 0.0) + 1
            kinds.getOrPut(code) { LinkedHashSet() } += "ccTLD .$tld"
        }
        if (totals.isEmpty()) return null
        val ranked = totals.entries.sortedByDescending { it.value }
        val (top, score) = ranked.first()
        val runnerUp = ranked.getOrNull(1)?.value ?: 0.0
        val topKinds = kinds[top].orEmpty()
        val confirmed = topKinds.any { it in EnrichmentRules.STRONG_COUNTRY_KINDS } && runnerUp * 2 <= score
        return LegalFinding(
            value = top,
            status = if (confirmed) LegalStatus.CONFIRMED else LegalStatus.PRESUMED,
            basis = labels(topKinds.sorted()),
            alternatives = ranked.drop(1).take(MAX_ALTERNATIVES).map { "${it.key}(${formatScore(it.value)})" },
            evidence = signals.countries[top]?.let { evidence(it.top) }.orEmpty(),
        )
    }

    fun roll(facet: Map<String, Tally>, limit: Int, minScore: Int = 2): List<ValueFinding> =
        facet.entries
            .filter { it.value.score >= minScore }
            .sortedByDescending { it.value.score }
            .take(limit)
            .map { (value, tally) ->
                val best = tally.top.firstOrNull()?.weight ?: 0
                val confidence = when {
                    best >= 3 && tally.score >= 6 -> ValueConfidence.HIGH
                    tally.score >= 3 -> ValueConfidence.MEDIUM
                    else -> ValueConfidence.LOW
                }
                ValueFinding(value, confidence, tally.pagesWithWeight.size, evidence(tally.top))
            }

    private fun hasLegalForm(name: String) =
        EnrichmentRules.HAS_LEGAL_FORM.matcher(name).find() || EnrichmentRules.RU_COMPANY.matcher(name).lookingAt()

    /** Lower-case word characters only. */
    private fun flatKey(name: String) = name.lowercase().filter { Character.isLetterOrDigit(it) || it == '_' }

    /** [flatKey] without a trailing legal form, so `Hostinger International Ltd` ≡ `… LIMITED`. */
    private fun comparisonKey(name: String) = EnrichmentRules.FORM_SUFFIX.matcher(flatKey(name)).replaceFirst("")

    private fun labels(kinds: List<String>) = kinds.take(MAX_BASIS).map { clean(it, MAX_LABEL) }

    private fun evidence(signals: List<Signal>): List<EvidenceItem> =
        signals.filter { it.url.length <= MAX_URL }.map { EvidenceItem(it.url, clean(it.kind, MAX_LABEL), it.snippet) }

    private fun formatScore(value: Double): String =
        if (value == Math.floor(value)) value.toLong().toString() else "%.1f".format(java.util.Locale.ROOT, value)
}


/** A name written in capitals only, as legal boilerplate often is. */
internal fun isShouted(name: String): Boolean = name.any(Char::isLetter) && name.none(Char::isLowerCase)

private val SHOUTED_FILLER = setOf(
    "A", "AN", "THE", "TO", "IS", "ARE", "BE", "BEEN", "WILL", "SHALL", "MAY", "FOR", "ANY", "OF", "OR", "AND", "NOT", "BY", "IN",
    "ON", "WITH", "OUR", "YOUR", "YOU", "WE", "US", "ALL", "NO", "BETWEEN", "THAT", "THIS", "AS", "AT", "FROM", "SUCH",
)

/**
 * A shouted [name] cut after its last function word: `AGREEMENT BETWEEN YOU AND HOSTINGER
 * INTERNATIONAL LIMITED` becomes the entity, while `LIABILITY IS LIMITED` leaves only the form and
 * is dropped (`null`). Any other name is returned as is.
 */
internal fun trimShoutedSentence(name: String): String? {
    if (!isShouted(name)) return name
    val words = name.split(' ').filter { it.isNotEmpty() }
    val start = words.indexOfLast { it.trimEnd(',', '.') in SHOUTED_FILLER } + 1
    val kept = words.drop(start)
    if (kept.size < 2) return null
    val trimmed = kept.joinToString(" ")
    return trimmed.takeIf { EnrichmentRules.HAS_LEGAL_FORM.matcher(it).find() && !EnrichmentRules.HAS_LEGAL_FORM.matcher(kept.first()).matches() }
}

/** `find()` on [m] that reports no further match after [MAX_MATCHES_PER_PATTERN] of them. */
private fun capped(m: Matcher): () -> Boolean {
    var found = 0
    return { found < MAX_MATCHES_PER_PATTERN && m.find().also { if (it) found++ } }
}

/** Whitespace collapsed, a following sentence and "All rights" cut off. */
internal fun normaliseName(raw: String): String {
    var name = collapseWhitespace(raw)
    val tail = EnrichmentRules.NAME_TAIL.matcher(name)
    if (tail.find()) name = name.substring(0, tail.start())
    name = name.trim(' ', ',', '.', ';', ':', '-', '–')
    return EnrichmentRules.NAME_PREFIX.matcher(name).replaceFirst("")
}

/** [pad] characters either side of the match, whitespace collapsed. */
internal fun snip(text: String, m: Matcher, pad: Int): String =
    collapseWhitespace(text.substring(maxOf(0, m.start() - pad), minOf(text.length, m.end() + pad)))

internal fun clean(value: String, max: Int): String = collapseWhitespace(value).take(max)

internal fun collapseWhitespace(value: String): String {
    val out = StringBuilder(value.length)
    var pendingSpace = false
    for (c in value) {
        if (c.isWhitespace()) {
            pendingSpace = out.isNotEmpty()
        } else {
            if (pendingSpace) out.append(' ')
            pendingSpace = false
            out.append(c)
        }
    }
    return out.toString()
}

/** Shouted words title-cased, then the first country whose words appear. */
internal fun countryOf(phrase: String): String? {
    val m = EnrichmentRules.SHOUTED_WORD.matcher(phrase)
    val titled = StringBuilder(phrase.length)
    var last = 0
    val nextM = capped(m)
    while (nextM()) {
        titled.append(phrase, last, m.start())
        titled.append(m.group()[0]).append(m.group().substring(1).lowercase())
        last = m.end()
    }
    titled.append(phrase, last, phrase.length)
    return EnrichmentRules.COUNTRY_WORDS.firstOrNull { (_, pattern) -> pattern.matcher(titled).find() }?.first
}

/** The text up to the first sentence end (`.`, `;`, `!` or `?` followed by whitespace). */
internal fun firstSentence(text: String): String {
    for (i in 0 until text.length - 1) {
        if (text[i] in ".;!?" && text[i + 1].isWhitespace()) return text.substring(0, i)
    }
    return text
}

/** Lower-cased path/anchor hint kinds of a page URL path. */
internal fun pageKinds(path: String): Set<String> {
    val lower = path.lowercase()
    return EnrichmentRules.LINK_HINTS.filterValues { (pattern, _) -> pattern.matcher(lower).find() }.keys
}
