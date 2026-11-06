package org.a1tsign.vds.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The one definition of "the same site": a wrong answer here either points a source config at
 * somebody else's catalog row (too lenient) or makes the discovery leg insert the same provider
 * twice (too strict), so the table is the specification.
 */
class DomainNameTest {

    @Test
    fun `normalize reduces a URL to the one canonical ASCII domain of its site`() {
        val cases = mapOf(
            "HTTPS://WWW.Example.COM/vps/?a=1" to "example.com",
            // The trailing dot and the explicit port are the same site as the plain domain.
            "https://example.com." to "example.com",
            "https://example.com:8443" to "example.com",
            "http://user:secret@example.com/vps#plans" to "example.com",
            "https://www.example.com." to "example.com",
            // A subdomain is a site of its own: reducing it to the registrable domain would need a
            // public-suffix list, and per-domain granularity is an accepted product decision.
            "https://vps.example.com" to "vps.example.com",
            "https://пример.рф/" to "xn--e1afmkfd.xn--p1ai",
        )

        cases.forEach { (url, expected) -> assertEquals(expected, DomainName.normalize(url), url) }
    }

    @Test
    fun `normalize refuses every URL that does not identify a provider's site`() {
        val refused = listOf(
            "mailto:sales@example.com",
            "/vps",
            "example.com/vps",
            "ftp://example.com",
            "http://localhost:8080",
            "http://intranet",
            "http://1.2.3.4",
            // inet_aton shorthand: still a machine, not a provider.
            "http://127.1",
            "https://[2001:db8::1]/vps",
            "",
        )

        refused.forEach { assertNull(DomainName.normalize(it), it) }
    }

    @Test
    fun `slug replaces every dot and refuses anything it would otherwise have to truncate`() {
        assertEquals("example-com", DomainName.slug("example.com"))
        assertEquals("vps-example-com", DomainName.slug("vps.example.com"))

        // 64 characters is provider.slug's full width, so it is accepted; 65 is not.
        val sixtyFour = "a".repeat(60) + ".com"
        assertEquals("a".repeat(60) + "-com", DomainName.slug(sixtyFour))
        assertNull(DomainName.slug("x$sixtyFour"))

        val seventyChars = "a".repeat(33) + "." + "b".repeat(32) + ".com"
        assertEquals(70, seventyChars.length)
        assertEquals(seventyChars, DomainName.normalize("https://$seventyChars"), "a long domain still normalizes")
        assertNull(
            DomainName.slug(seventyChars),
            "a slug is never truncated: two different long domains would collide on one catalog row",
        )
        assertNull(DomainName.slug("example_com"), "an underscore is outside provider.slug's charset")
    }
}
