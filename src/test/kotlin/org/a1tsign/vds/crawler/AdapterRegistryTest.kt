package org.a1tsign.vds.crawler

import org.a1tsign.vds.config.ConfigurationException
import org.a1tsign.vds.crawler.support.StubAdapter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private fun entry(id: String, providerSlug: String = id, includedByDefault: Boolean = true) =
    AdapterEntry(StubAdapter(id, providerSlug), includedByDefault)

class AdapterRegistryTest {

    @Test
    fun `a duplicate adapter id is rejected naming the offender`() {
        val exception = assertFailsWith<IllegalArgumentException> {
            AdapterRegistry(listOf(entry("a", "slug-a"), entry("a", "slug-b")))
        }

        assertTrue(exception.message!!.contains("a"), exception.message)
        assertTrue(exception.message!!.contains("Duplicate adapter id"), exception.message)
    }

    @Test
    fun `a duplicate provider slug is rejected naming the offender`() {
        val exception = assertFailsWith<IllegalArgumentException> {
            AdapterRegistry(listOf(entry("a", "same-slug"), entry("b", "same-slug")))
        }

        assertTrue(exception.message!!.contains("same-slug"), exception.message)
    }

    @Test
    fun `a malformed adapter id or provider slug is rejected`() {
        val badId = assertFailsWith<IllegalArgumentException> {
            AdapterRegistry(listOf(entry("Bad_Id", "slug-a")))
        }
        val badSlug = assertFailsWith<IllegalArgumentException> {
            AdapterRegistry(listOf(entry("a", "Bad_Slug")))
        }

        assertTrue(badId.message!!.contains("Bad_Id"), badId.message)
        assertTrue(badSlug.message!!.contains("Bad_Slug"), badSlug.message)
    }

    @Test
    fun `an empty selection picks exactly the default entries`() {
        val registry = AdapterRegistry(
            listOf(
                entry("a", "slug-a", includedByDefault = true),
                entry("b", "slug-b", includedByDefault = false),
                entry("c", "slug-c", includedByDefault = true),
            ),
        )

        assertEquals(listOf("a", "c"), registry.select(emptyList()).map { it.id })
    }

    @Test
    fun `a configured selection keeps the configured order and may name a non-default entry`() {
        val registry = AdapterRegistry(
            listOf(
                entry("a", "slug-a", includedByDefault = true),
                entry("b", "slug-b", includedByDefault = false),
            ),
        )

        assertEquals(listOf("b", "a"), registry.select(listOf("b", "a")).map { it.id })
    }

    @Test
    fun `an unknown configured id fails listing the known ids`() {
        val registry = AdapterRegistry(listOf(entry("a", "slug-a"), entry("b", "slug-b")))

        val exception = assertFailsWith<ConfigurationException> { registry.select(listOf("nope")) }

        assertEquals("Unknown crawler adapter id 'nope'; known ids: a, b", exception.message)
    }
}
