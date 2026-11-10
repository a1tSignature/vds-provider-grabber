package org.a1tsign.vds.crawler.config

import org.a1tsign.vds.support.TEST_CLOCK
import org.a1tsign.vds.support.TEST_NOW
import org.a1tsign.vds.support.capturingLogs
import org.junit.jupiter.api.io.TempDir
import org.slf4j.event.Level
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The error boundary itself: everything in here is semi-trusted **file** data, so the one property
 * every case shares is that the loader *returns a report* instead of throwing -- an
 * `IllegalArgumentException` escaping to `main()` is precisely what ADR-01 forbids.
 */
class SourceConfigLoaderTest {

    private val loader = SourceConfigLoader(TEST_CLOCK)

    @Test
    fun `a valid file is loaded while a malformed one is reported and skipped`(@TempDir dir: Path) {
        dir.resolve("acme.json").writeText(source("acme", enabled = true))
        dir.resolve("broken.json").writeText("""{"schemaVersion": 1, "id": "broken"""")

        var result: SourceLoadReport? = null
        val logs = capturingLogs { result = loader.load(dir.toString(), maxSources = 10) }
        val report = result!!

        val source = report.loaded.single()
        assertEquals("acme", source.config.id)
        assertEquals("acme-example", source.config.providerSlug)
        assertTrue(source.config.enabled)
        assertEquals("acme.json", source.file)
        assertEquals(TEST_NOW, source.loadedAt)

        val failure = report.failed.single()
        assertEquals("broken.json", failure.file)
        assertTrue(failure.problems.isNotEmpty(), "a refused file must carry its error")
        assertTrue(failure.problems.all { it.contains("broken.json") }, failure.problems.toString())
        assertTrue(report.hasFailures)
        assertTrue(report.problems.isEmpty(), "a bad file is not a bad directory")
        assertTrue(
            logs.messagesAt(Level.ERROR).any { it.contains("broken.json") },
            "a refused file gets one ERROR line naming it: ${logs.messages()}",
        )
    }

    @Test
    fun `a symbolic link is refused by name and never followed`(@TempDir root: Path) {
        val outside = root.resolve("outside").createDirectory()
        val dir = root.resolve("sources").createDirectory()
        val target = outside.resolve("elsewhere.json")
        target.writeText(source("linked", domain = "linked.example", slug = "linked-example"))
        dir.resolve("acme.json").writeText(source("acme"))
        Files.createSymbolicLink(dir.resolve("link.json"), target)

        val report = loader.load(dir.toString(), maxSources = 10)

        assertEquals(listOf("acme"), report.loaded.map { it.config.id }, "the link's target is not loaded")
        val failure = report.failed.single()
        assertEquals("link.json", failure.file)
        assertTrue(failure.problems.single().contains("symbolic link"), failure.problems.toString())
    }

    @Test
    fun `each bound and uniqueness rule refuses the offending file with its own message`(@TempDir root: Path) {
        data class Case(
            val description: String,
            val files: Map<String, String>,
            val expected: String,
            val maxSources: Int = 10,
            val refusedFile: String? = null,
        )

        val cases = listOf(
            Case(
                "a file above 64 KB",
                mapOf("big.json" to source("big", padding = SourceConfigFormat.MAX_FILE_BYTES)),
                "above the ${SourceConfigFormat.MAX_FILE_BYTES} byte limit",
                refusedFile = "big.json",
            ),
            Case(
                "more files than VDS_DISCOVERY_MAX_SOURCES",
                mapOf(
                    "a.json" to source("a"),
                    "b.json" to source("b"),
                    "c.json" to source("c"),
                ),
                "VDS_DISCOVERY_MAX_SOURCES",
                maxSources = 2,
            ),
            Case(
                "a duplicate id",
                mapOf(
                    "first.json" to source("same", domain = "one.example", slug = "one-example"),
                    "second.json" to source("same", domain = "two.example", slug = "two-example"),
                ),
                "id 'same' is already taken by the file 'first.json'",
                refusedFile = "second.json",
            ),
            Case(
                "a duplicate providerSlug",
                mapOf(
                    "first.json" to source("alpha", domain = "dup.example", slug = "dup-example"),
                    "second.json" to source("bravo", domain = "dup.example", slug = "dup-example"),
                ),
                "providerSlug 'dup-example' is already taken by the file 'first.json'",
                refusedFile = "second.json",
            ),
            Case(
                "a collision with the compiled example-static adapter's id",
                mapOf("shadow.json" to source("example-static")),
                "id 'example-static' is already taken by a compiled adapter",
                refusedFile = "shadow.json",
            ),
            Case(
                "a collision with a compiled adapter's providerSlug",
                // "example.static" is the one domain whose slug is the compiled adapter's own, so
                // the format's slug rule lets the file through and the loader is what refuses it.
                mapOf("shadow.json" to source("other", domain = "example.static", slug = "example-static")),
                "providerSlug 'example-static' is already taken by a compiled adapter",
                refusedFile = "shadow.json",
            ),
        )

        cases.forEachIndexed { index, case ->
            val dir = root.resolve("case-$index").createDirectory()
            case.files.forEach { (name, content) -> dir.resolve(name).writeText(content) }

            val report = loader.load(
                dir.toString(),
                maxSources = case.maxSources,
                reservedIds = setOf("example-static"),
                reservedSlugs = setOf("example-static"),
            )

            assertTrue(
                report.failureSummary().contains(case.expected),
                "${case.description}: no refusal says '${case.expected}': ${report.failureSummary()}",
            )
            case.refusedFile?.let { refused ->
                assertFalse(
                    report.loaded.any { it.file == refused },
                    "${case.description}: '$refused' must not be registered",
                )
                assertEquals(refused, report.failed.single().file, case.description)
            }
            if (case.maxSources < case.files.size) {
                assertEquals(emptyList(), report.loaded, "${case.description}: the whole directory is refused")
            }
        }

        // Both bounds are inclusive: exactly MAX_SOURCES files, one of them exactly 64 KB, load.
        val atLimit = root.resolve("at-limit").createDirectory()
        atLimit.resolve("a.json").writeText(source("alpha", domain = "alpha.example", slug = "alpha-example"))
        atLimit.resolve("b.json").writeText(exactlyMaxBytes("bravo", domain = "bravo.example", slug = "bravo-example"))

        val accepted = loader.load(atLimit.toString(), maxSources = 2)

        assertEquals(listOf("a.json", "b.json"), accepted.loaded.map { it.file }, accepted.failureSummary())
    }

    @Test
    fun `only json files in the directory itself are read, in a stable sorted order`(@TempDir dir: Path) {
        dir.resolve("b.json").writeText(source("bravo", domain = "bravo.example", slug = "bravo-example"))
        dir.resolve("a.json").writeText(source("alpha", domain = "alpha.example", slug = "alpha-example"))
        dir.resolve("notes.txt").writeText("not a source")
        val nested = dir.resolve("nested").createDirectory()
        nested.resolve("deep.json").writeText(source("deep", domain = "deep.example", slug = "deep-example"))

        val report = loader.load(dir.toString(), maxSources = 10)

        assertEquals(listOf("a.json", "b.json"), report.loaded.map { it.file })
        assertFalse(report.hasFailures, report.failureSummary())
    }

    @Test
    fun `an absent or unreadable directory is a reported problem, never a thrown exception`(@TempDir dir: Path) {
        val missing = loader.load(dir.resolve("nope").toString(), maxSources = 10)

        assertEquals(emptyList(), missing.loaded)
        assertTrue(missing.problems.single().contains("not a readable directory"), missing.problems.toString())

        val notADirectory = dir.resolve("plain.json").also { it.writeText("{}") }
        val file = loader.load(notADirectory.toString(), maxSources = 10)

        assertTrue(file.problems.single().contains("not a readable directory"), file.problems.toString())
    }

    private companion object {
        /**
         * A valid source file for [id]. [padding] appends that many spaces *inside* the JSON object,
         * so an oversized file is still syntactically valid -- which is what pins "refused on its
         * size, before its content is even read".
         */
        fun source(
            id: String,
            domain: String = "acme.example",
            slug: String = "acme-example",
            enabled: Boolean = false,
            padding: Int = 0,
        ): String = """
            {
              "schemaVersion": 1,
              "id": "$id",
              "providerSlug": "$slug",
              "baseUrl": "https://$domain",
              "pagePath": "/vps",
              "enabled": $enabled,
              "provider": {
                "name": {"selector": "h1.name", "text": true},
                "websiteUrl": {"selector": "a.site", "attr": "href"}
              }${" ".repeat(padding)}
            }
        """.trimIndent()

        /** The same file padded to **exactly** [SourceConfigFormat.MAX_FILE_BYTES] UTF-8 bytes. */
        fun exactlyMaxBytes(id: String, domain: String, slug: String): String {
            val unpadded = source(id, domain, slug).toByteArray(Charsets.UTF_8).size
            return source(id, domain, slug, padding = SourceConfigFormat.MAX_FILE_BYTES - unpadded)
        }
    }
}
