package org.a1tsign.vds.crawler.config

import org.a1tsign.vds.config.nowUtc
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.file.DirectoryIteratorException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.time.Clock
import java.time.Instant

/** One file that became a registry entry: the parsed config plus where and when it came from. */
data class LoadedSource(
    val config: SourceConfig,
    val file: String,
    val loadedAt: Instant,
)

/** One file that did **not** become a registry entry, with every problem the format found in it. */
data class FailedSource(
    val file: String,
    val problems: List<String>,
)

/**
 * What one startup made of `VDS_DISCOVERY_SOURCES_DIR`. Exposed on `AppComponents` so task 6's
 * `GET /api/v1/admin/discovery/sources` can serve it: after a restart-to-apply load this report is
 * the only way to tell whether a file was picked up, and why not when it was not.
 *
 * [problems] are directory-level refusals (the directory itself is unreadable, or it holds more
 * files than `VDS_DISCOVERY_MAX_SOURCES` allows) as opposed to [failed], which is per file.
 */
data class SourceLoadReport(
    val directory: String?,
    val loaded: List<LoadedSource>,
    val failed: List<FailedSource>,
    val problems: List<String>,
) {
    val hasFailures: Boolean get() = failed.isNotEmpty() || problems.isNotEmpty()

    /** Every refusal on one line, each naming its file -- the message strict mode fails startup with. */
    fun failureSummary(): String =
        (problems + failed.map { "${it.file}: ${it.problems.joinToString("; ")}" }).joinToString("; ")

    companion object {
        /**
         * Nothing was read at all: the crawler is disabled, or `VDS_DISCOVERY_SOURCES_DIR` is unset
         * (the default), which is what makes "discovery disabled => nothing happens" literal.
         */
        val NOT_LOADED = SourceLoadReport(directory = null, loaded = emptyList(), failed = emptyList(), problems = emptyList())
    }
}

/**
 * The **error boundary** between semi-trusted file data and `AdapterRegistry`: it reads a directory
 * of `*.json` source files, validates each one through [SourceConfigFormat.parse], and returns the
 * valid configs *plus* every file that failed, with its error. Nothing it is given can make it
 * throw -- a malformed file, an unreadable directory and an I/O failure are all reported, the file
 * is skipped with one ERROR line, and **the process still starts** (ADR-01 decision 4;
 * `VDS_DISCOVERY_SOURCES_STRICT=true` is what turns that into a startup failure, and the
 * composition root owns that decision, not this class).
 *
 * Because validation happens here, before the registry is constructed, `AdapterRegistry.init{}`'s
 * `require(...)` is never reached by file data at all -- it stays the programming-error guard it was.
 *
 * Bounds, both with their own message: **64 KB per file** ([SourceConfigFormat.MAX_FILE_BYTES],
 * checked on the file's size *before* it is read and enforced again by the read itself, which
 * never takes more than one byte past the limit -- so a file whose reported size is wrong (a
 * pseudo-file) or that grows between the two calls is still never buffered; it also keeps a future
 * bot submission inside the API's existing 256 KB request-body limit) and
 * `VDS_DISCOVERY_MAX_SOURCES` files. A **symbolic link is never followed**: it is reported as a
 * skipped file, so a link cannot point the loader at an arbitrary file outside the directory. Only the **parsed** configs are kept: no raw file content is
 * retained, which matters on a 192 MB heap.
 *
 * Uniqueness is enforced across *all* entries, config-sourced and compiled: a file whose `id` or
 * `providerSlug` is already taken is refused rather than allowed to shadow or collide with
 * `example-static`. Files are read **non-recursively** and in sorted order, so which of two
 * colliding files wins is stable across restarts.
 */
class SourceConfigLoader(private val clock: Clock) {

    /**
     * @param directory `VDS_DISCOVERY_SOURCES_DIR` as configured.
     * @param maxSources `VDS_DISCOVERY_MAX_SOURCES`.
     * @param reservedIds adapter ids already taken by compiled entries.
     * @param reservedSlugs provider slugs already taken by compiled entries.
     */
    fun load(
        directory: String,
        maxSources: Int,
        reservedIds: Set<String> = emptySet(),
        reservedSlugs: Set<String> = emptySet(),
    ): SourceLoadReport {
        val dir = runCatching { Path.of(directory) }.getOrNull()
        if (dir == null || !Files.isDirectory(dir) || !Files.isReadable(dir)) {
            return report(directory, problems = listOf("'$directory' is not a readable directory; no declarative source was loaded"))
        }
        val files = try {
            Files.newDirectoryStream(dir, "*.json").use { stream ->
                // A symlink is kept in the listing (and refused by name below) rather than
                // silently dropped; everything else must be a regular file without following links.
                stream
                    .filter { Files.isSymbolicLink(it) || Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }
                    .sortedBy { it.fileName.toString() }
            }
        } catch (failure: IOException) {
            return report(directory, problems = listOf("'$directory' could not be listed: ${failure.message}"))
        } catch (failure: DirectoryIteratorException) {
            // Thrown by the iteration itself (not an IOException), with the I/O failure as its cause.
            return report(directory, problems = listOf("'$directory' could not be listed: ${failure.cause?.message}"))
        }
        if (files.size > maxSources) {
            // The whole directory is refused rather than an arbitrary prefix of it: there is no
            // defensible way to pick which sources an over-full directory meant to keep.
            return report(
                directory,
                problems = listOf(
                    "'$directory' holds ${files.size} *.json files, above the limit of $maxSources " +
                        "(VDS_DISCOVERY_MAX_SOURCES); no declarative source was loaded",
                ),
            )
        }

        val loaded = mutableListOf<LoadedSource>()
        val failed = mutableListOf<FailedSource>()
        val idOwners = reservedIds.associateWith { COMPILED_OWNER }.toMutableMap()
        val slugOwners = reservedSlugs.associateWith { COMPILED_OWNER }.toMutableMap()

        for (path in files) {
            val name = path.fileName.toString()
            val read = readBounded(path)
            val readFailure = read.exceptionOrNull()
            if (readFailure != null) {
                failed += FailedSource(name, listOf("$name: ${readFailure.message}"))
                continue
            }
            when (val parsed = SourceConfigFormat.parse(name, read.getOrThrow())) {
                is SourceConfigParse.Invalid -> failed += FailedSource(name, parsed.problems)
                is SourceConfigParse.Valid -> {
                    val config = parsed.config
                    val collisions = buildList {
                        idOwners[config.id]?.let { add("$name: id '${config.id}' is already taken by $it") }
                        slugOwners[config.providerSlug]?.let {
                            add("$name: providerSlug '${config.providerSlug}' is already taken by $it")
                        }
                    }
                    if (collisions.isNotEmpty()) {
                        failed += FailedSource(name, collisions)
                    } else {
                        idOwners[config.id] = "the file '$name'"
                        slugOwners[config.providerSlug] = "the file '$name'"
                        loaded += LoadedSource(config, name, clock.nowUtc())
                    }
                }
            }
        }
        return report(directory, loaded = loaded, failed = failed)
    }

    /** The file's bytes, or the one problem that stops it from becoming an entry. */
    private fun readBounded(path: Path): Result<ByteArray> = try {
        val limit = SourceConfigFormat.MAX_FILE_BYTES
        if (Files.isSymbolicLink(path)) {
            Result.failure(IllegalStateException("is a symbolic link; a declarative source must be a regular file"))
        } else {
            val size = Files.size(path)
            if (size > limit) {
                // Refused on the size alone: an oversized file is never read into the heap.
                Result.failure(IllegalStateException("the file is $size bytes, above the $limit byte limit"))
            } else {
                // The reported size is not trusted: at most one byte past the limit is ever read, and
                // NOFOLLOW_LINKS makes the open itself fail if the file became a link meanwhile.
                val bytes = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS).use { it.readNBytes(limit + 1) }
                if (bytes.size > limit) {
                    Result.failure(IllegalStateException("the file is above the $limit byte limit"))
                } else {
                    Result.success(bytes)
                }
            }
        }
    } catch (failure: IOException) {
        Result.failure(IllegalStateException("could not be read: ${failure.message}"))
    }

    private fun report(
        directory: String,
        loaded: List<LoadedSource> = emptyList(),
        failed: List<FailedSource> = emptyList(),
        problems: List<String> = emptyList(),
    ): SourceLoadReport {
        problems.forEach { logger.error("declarative source directory refused: {}", it) }
        failed.forEach { failure ->
            logger.error(
                "declarative source file '{}' was skipped: {}",
                failure.file,
                failure.problems.joinToString("; "),
            )
        }
        logger.info(
            "loaded {} declarative source(s) from '{}' ({} enabled), skipped {}",
            loaded.size,
            directory,
            loaded.count { it.config.enabled },
            failed.size,
        )
        return SourceLoadReport(directory = directory, loaded = loaded, failed = failed, problems = problems)
    }

    private companion object {
        const val COMPILED_OWNER = "a compiled adapter"

        val logger = LoggerFactory.getLogger(SourceConfigLoader::class.java)
    }
}
