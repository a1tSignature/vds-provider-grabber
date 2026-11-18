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

data class LoadedSource(
    val config: SourceConfig,
    val file: String,
    val loadedAt: Instant,
)

data class FailedSource(
    val file: String,
    val problems: List<String>,
)

/** Startup snapshot of loaded sources and refusals, served by the admin sources endpoint until restart. */
data class SourceLoadReport(
    val directory: String?,
    val loaded: List<LoadedSource>,
    val failed: List<FailedSource>,
    val problems: List<String>,
) {
    val hasFailures: Boolean get() = failed.isNotEmpty() || problems.isNotEmpty()

    fun failureSummary(): String =
        (problems + failed.map { "${it.file}: ${it.problems.joinToString("; ")}" }).joinToString("; ")

    companion object {
        /** No files were read because the crawler or source directory was unavailable. */
        val NOT_LOADED = SourceLoadReport(directory = null, loaded = emptyList(), failed = emptyList(), problems = emptyList())
    }
}

/**
 * Read bounded JSON files in sorted order from one directory without following symlinks. Report refusals and keep parsed configs only.
 * Enforce uniqueness against both compiled and already accepted entries so collisions cannot shadow an adapter.
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

    private fun readBounded(path: Path): Result<ByteArray> = try {
        val limit = SourceConfigFormat.MAX_FILE_BYTES
        if (Files.isSymbolicLink(path)) {
            Result.failure(IllegalStateException("is a symbolic link; a declarative source must be a regular file"))
        } else {
            val size = Files.size(path)
            if (size > limit) {
                Result.failure(IllegalStateException("the file is $size bytes, above the $limit byte limit"))
            } else {
                // Cap reads independently of reported size. Use NOFOLLOW_LINKS when opening in case the file changed after listing.
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
