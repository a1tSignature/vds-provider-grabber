package org.a1tsign.vds.discovery

import org.a1tsign.vds.domain.DomainName
import java.net.URI

/** First labels that name a part of a site, not a site: `billing.example.com` is `example.com`. */
private val SERVICE_LABELS = setOf(
    "blog", "docs", "doc", "kb", "wiki", "help", "support", "billing", "portal", "console", "my", "cdn",
    "client", "clients", "panel", "store", "shop", "forum", "forums", "community", "discuss", "status",
)

/** A two-letter first label is a language or country edition: `us.`, `kr.`, `en.`, `fr.`. */
private val EDITION_LABEL = Regex("^[a-z]{2}$")

/** Keep edition labels when removing them would leave only a public suffix such as co.uk. */
private val SECOND_LEVEL_SUFFIX = Regex("^(co|com|net|org|gov|edu|ac|or|ne|go|in)\\.[a-z]{2}$")

/** Keep roundup detection narrow because providers also publish their own guides and comparisons. */
private val LISTICLE_TITLE = Regex(
    "(?iU)\\btop[ -]?\\d+\\b|\\b\\d+\\s+(best|cheapest|top)\\b|\\bcompare\\b|\\breviews\\b|\\branking\\b" +
        "|рейтинг|\\bтоп[- ]?\\d+|\\d+\\s+лучш|лучших|подборк" +
        "|vergleich|meilleur|classement|comparati|vergelijk|cele mai bune|jak wybra" +
        "|总整理|推荐|测评|评价|笔记",
)

private val LISTICLE_PATH = Regex("(?i)/(tags?|top|rating[^/]*|reviews|vergleich|comparison|comparativas|ranking)(/|$)")

/**
 * [domain] without leading service or edition labels, while what remains is still a registrable-looking
 * domain. Other subdomains stay distinct sites: `vps.xserver.ne.jp` is not `xserver.ne.jp`.
 */
fun collapseServiceSubdomain(domain: String): String {
    var current = domain
    while (true) {
        val first = current.substringBefore('.')
        val rest = current.substringAfter('.')
        val collapsible = first in SERVICE_LABELS || EDITION_LABEL.matches(first)
        if (!collapsible || !rest.contains('.') || SECOND_LEVEL_SUFFIX.matches(rest)) return current
        current = rest
    }
}

fun isListicle(hit: SearchHit): Boolean {
    if (hit.title != null && LISTICLE_TITLE.containsMatchIn(hit.title)) return true
    val path = runCatching { URI(hit.url.trim()).rawPath }.getOrNull() ?: return false
    return LISTICLE_PATH.containsMatchIn(path)
}

/**
 * Denylist entries in the form a search hit is compared in ([DomainName.normalize]), in file order
 * and without duplicates. An entry may be a bare host or a full URL; [onInvalid] gets each entry
 * that is not a domain at all.
 */
fun canonicalDomains(entries: List<String>, onInvalid: (String) -> Unit = {}): List<String> =
    entries.mapNotNullTo(LinkedHashSet()) { entry ->
        DomainName.normalize(if (entry.contains("://")) entry else "https://$entry/") ?: run {
            onInvalid(entry)
            null
        }
    }.toList()
