package org.a1tsign.vds.domain

import java.net.IDN
import java.net.URI

/**
 * The one place that turns a URL into a canonical domain and a domain into a `provider.slug`.
 *
 * It lives in `domain/` on purpose: both `crawler` and (later) `discovery` import `domain`
 * legitimately, while putting it in either of them would create a package cycle. The declarative
 * source format checks its `providerSlug` against it, and the discovery leg will use the very same
 * functions for its dedup set and its candidate inserts -- one definition of "the same site", not
 * three.
 *
 * Both functions are **pure**: no DNS lookup, no public-suffix list, no I/O.
 *
 * ### Subdomains are distinct sites
 *
 * `vps.example.com` and `example.com` normalize to different domains and therefore to different
 * slugs. Reducing them to one registrable domain would need a public-suffix list -- a new dependency
 * plus a data file that goes stale -- and per-domain granularity is an accepted product decision: a
 * reseller on a subdomain really is a different catalog row. Discovery narrows this for service and
 * edition labels (`docs.`, `billing.`, `us.`) only; see `discovery/HitFilter.kt`.
 */
object DomainName {

    /** `provider.slug`'s column width; a slug longer than this is refused, never truncated. */
    const val SLUG_MAX_LENGTH: Int = 64

    private val SLUG_PATTERN = Regex("^[a-z0-9-]+$")

    /**
     * The last label of a hostname is never all digits (RFC 3696), so a host whose last label is
     * all digits is an IPv4 literal -- including the shorthand forms `inet_aton` accepts
     * (`127.1`, `10.1.2`), which a four-group pattern would wave through as a domain.
     */
    private val NUMERIC_LABEL = Regex("^\\d+$")

    /**
     * The canonical, ASCII (punycode) host of an absolute `http`/`https` [url]: lower-cased, without
     * scheme, credentials, port, path, query or fragment, without a leading `www.` and without a
     * trailing dot.
     *
     * `null` -- i.e. "this is not a site we can key a provider by" -- when [url] is not an absolute
     * `http`/`https` URL, when the host has no dot (`localhost`, an intranet name) or when the host
     * is an IP literal (v4 or v6): a bare address identifies a machine, not a provider.
     *
     * The host is read off [URI.getAuthority], not [URI.getHost]: `java.net.URI` returns a **null**
     * host for an internationalized authority (`http://пример.рф/` → `host = null`,
     * `authority = "пример.рф"`, verified against the running JDK), so a `getHost()`-based
     * implementation would silently reject every IDN instead of punycoding it.
     */
    fun normalize(url: String): String? {
        val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return null
        if (!uri.isAbsolute) return null
        if (uri.scheme?.lowercase() !in HTTP_SCHEMES) return null
        val authority = uri.authority ?: return null
        // Credentials first, then the port; a bracketed authority is an IPv6 literal, i.e. a
        // machine rather than a provider, and is refused before anything else looks at it.
        val hostAndPort = authority.substringAfterLast('@')
        if (hostAndPort.startsWith("[")) return null
        val host = hostAndPort.substringBefore(':').lowercase().trimEnd('.').removePrefix("www.")
        if (!host.contains('.')) return null
        if (NUMERIC_LABEL.matches(host.substringAfterLast('.'))) return null
        return runCatching { IDN.toASCII(host) }.getOrNull()
    }

    /**
     * The `provider.slug` of a [normalize]d domain: every `.` becomes `-`.
     *
     * `null` when the result would be longer than [SLUG_MAX_LENGTH] or would still not match
     * `^[a-z0-9-]+$`. **Never truncated**: two different 70-character domains would truncate to the
     * same slug, and a manufactured collision points an adapter at somebody else's catalog row.
     */
    fun slug(domain: String): String? {
        val slug = domain.replace('.', '-')
        if (slug.length > SLUG_MAX_LENGTH || !SLUG_PATTERN.matches(slug)) return null
        return slug
    }

    private val HTTP_SCHEMES = setOf("http", "https")
}
