package org.a1tsign.vds.crawler

import org.a1tsign.vds.config.ConfigurationException

/** One registered adapter plus whether an empty `VDS_CRAWLER_ADAPTERS` picks it up. */
data class AdapterEntry(
    val adapter: ProviderAdapter,
    val includedByDefault: Boolean,
)

private val ID_PATTERN = Regex("^[a-z0-9-]+$")

/**
 * The adapters this build knows about -- a static list written in code: no `ServiceLoader`, no
 * classpath scanning, no reflection (they cost startup time and memory this service does not have,
 * and they hide which adapters ship).
 *
 * Construction is where a programming error surfaces: a malformed or duplicated [AdapterEntry] is
 * an [IllegalArgumentException], not a configuration problem. A bad *configuration* -- an unknown
 * id in `VDS_CRAWLER_ADAPTERS` -- is a [ConfigurationException] thrown by [select], which task
 * vds-002.7 turns into fail-fast at startup.
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
     * The adapters to run. An **empty** [configured] means "every entry registered as a default"
     * (the resolution of the spec's req. 10 / req. 42 contradiction: the reference adapters are
     * registered but not default, so an empty selection runs nothing in production). A non-empty
     * [configured] selects exactly those ids, **in the given order**.
     *
     * @throws ConfigurationException naming the unknown id and listing the known ones.
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
        /** The adapters this build ships: empty until task vds-002.8 adds the two reference ones. */
        fun production(): AdapterRegistry = AdapterRegistry(emptyList())
    }
}
