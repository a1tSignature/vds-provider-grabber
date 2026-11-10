package org.a1tsign.vds.crawler

import org.a1tsign.vds.config.ConfigurationException
import org.a1tsign.vds.crawler.support.StubAdapter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private fun entry(id: String, providerSlug: String = id, includedByDefault: Boolean = true) =
    AdapterEntry(StubAdapter(id, providerSlug), includedByDefault)

/** What `AppComponents` builds out of one loaded source file: the file's `enabled` is the default flag. */
private fun configEntry(id: String, enabled: Boolean) = AdapterEntry(
    adapter = StubAdapter(id, "$id-slug"),
    includedByDefault = enabled,
    origin = AdapterOrigin.CONFIG,
    file = "$id.json",
)

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
    fun `an empty selection takes the config sources that enabled themselves and no compiled adapter`() {
        val registry = AdapterRegistry.production(listOf(configEntry("on", enabled = true), configEntry("off", enabled = false)))

        assertEquals(listOf("on"), registry.select(emptyList()).map { it.id })
        // Naming an id is the operator's deliberate act: it selects a config source whose own file
        // says "enabled": false, and an unknown id still fails fast.
        assertEquals(listOf("off", "on"), registry.select(listOf("off", "on")).map { it.id })
        assertFailsWith<ConfigurationException> { registry.select(listOf("nope")) }

        val config = registry.entries.single { it.adapter.id == "on" }
        assertEquals(AdapterOrigin.CONFIG, config.origin)
        assertEquals("on.json", config.file)
        val compiled = registry.entries.filter { it.origin == AdapterOrigin.COMPILED }
        assertTrue(compiled.isNotEmpty() && compiled.none { it.includedByDefault }, "no compiled reference adapter is a default")
        assertTrue(compiled.all { it.file == null })
    }

    @Test
    fun `an entry whose origin and source file name disagree is a programming error`() {
        assertFailsWith<IllegalArgumentException> {
            AdapterRegistry(listOf(AdapterEntry(StubAdapter("a", "a"), false, AdapterOrigin.CONFIG, file = null)))
        }
        assertFailsWith<IllegalArgumentException> {
            AdapterRegistry(listOf(AdapterEntry(StubAdapter("a", "a"), false, AdapterOrigin.COMPILED, file = "a.json")))
        }
    }

    @Test
    fun `an unknown configured id fails listing the known ids`() {
        val registry = AdapterRegistry(listOf(entry("a", "slug-a"), entry("b", "slug-b")))

        val exception = assertFailsWith<ConfigurationException> { registry.select(listOf("nope")) }

        assertEquals("Unknown crawler adapter id 'nope'; known ids: a, b", exception.message)
    }
}
