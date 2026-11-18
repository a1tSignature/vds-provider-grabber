package org.a1tsign.vds.domain

import java.net.IDN
import java.net.URI

/**
 * Normalize domains and build provider slugs without I/O. Preserve subdomains as distinct sites.
 * Discovery separately filters common service and edition labels for deduplication.
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
     * Return a lower-case ASCII domain without www or a trailing dot. Reject non-HTTP(S), unqualified hosts and IP literals.
     * Read the URI authority because getHost returns null for internationalized hostnames.
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
