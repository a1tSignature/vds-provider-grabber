package org.a1tsign.vds.crawler

import org.a1tsign.vds.config.ConfigurationException
import org.a1tsign.vds.crawler.adapters.ExampleFailingAdapter
import org.a1tsign.vds.crawler.adapters.ExampleStaticAdapter

/** Where a registered adapter came from; reported by `GET /api/v1/admin/crawl/adapters`. */
enum class AdapterOrigin {
    COMPILED,

    /** A `DeclarativeAdapter` built from one validated JSON file in `VDS_DISCOVERY_SOURCES_DIR`. */
    CONFIG,
}

/** A JSON entry takes its default selection from the file's enabled flag and carries the source filename. */
data class AdapterEntry(
    val adapter: ProviderAdapter,
    val includedByDefault: Boolean,
    val origin: AdapterOrigin = AdapterOrigin.COMPILED,
    val file: String? = null,
)

private val ID_PATTERN = Regex("^[a-z0-9-]+$")

/**
 * Registry of compiled and validated JSON-backed adapters. Changes apply on restart.
 * Invalid entries are programmer errors. Unknown selections in [select] are configuration errors.
 */
class AdapterRegistry(entries: List<AdapterEntry>) {

    val entries: List<AdapterEntry> = entries.toList()

    init {
        this.entries.forEach { entry ->
            require(ID_PATTERN.matches(entry.adapter.id)) {
                "Adapter id '${entry.adapter.id}' must match ${ID_PATTERN.pattern}"
            }
            require(ID_PATTERN.matches(entry.adapter.providerSlug)) {
                "Adapter '${entry.adapter.id}' has providerSlug '${entry.adapter.providerSlug}', " +
                    "which must match ${ID_PATTERN.pattern}"
            }
            require((entry.origin == AdapterOrigin.CONFIG) == (entry.file != null)) {
                "Adapter '${entry.adapter.id}' is ${entry.origin} but " +
                    if (entry.file == null) "carries no source file name" else "carries a source file name"
            }
        }
        this.entries.groupBy { it.adapter.id }
            .filterValues { it.size > 1 }
            .keys
            .let { duplicates ->
                require(duplicates.isEmpty()) { "Duplicate adapter id(s): ${duplicates.joinToString()}" }
            }
        this.entries.groupBy { it.adapter.providerSlug }
            .filterValues { it.size > 1 }
            .keys
            .let { duplicates ->
                require(duplicates.isEmpty()) { "Duplicate adapter providerSlug(s): ${duplicates.joinToString()}" }
            }
    }

    /**
     * An empty selection uses the registered defaults. Explicit IDs are used in order, including sources disabled by default.
     * @throws ConfigurationException if any ID is unknown.
     */
    fun select(configured: List<String>): List<ProviderAdapter> {
        if (configured.isEmpty()) {
            return entries.filter { it.includedByDefault }.map { it.adapter }
        }
        val byId = entries.associate { it.adapter.id to it.adapter }
        return configured.map { id ->
            byId[id] ?: throw ConfigurationException(
                "Unknown crawler adapter id '$id'; known ids: " +
                    if (entries.isEmpty()) "<none>" else entries.joinToString { it.adapter.id },
            )
        }
    }

    companion object {
        /** Reference adapters run only when selected explicitly. */
        fun compiledEntries(): List<AdapterEntry> = listOf(
            AdapterEntry(ExampleStaticAdapter(), includedByDefault = false),
            AdapterEntry(ExampleFailingAdapter(), includedByDefault = false),
        )

        fun production(configSourced: List<AdapterEntry> = emptyList()): AdapterRegistry =
            AdapterRegistry(compiledEntries() + configSourced)
    }
}
