package org.a1tsign.vds.crawler

import org.a1tsign.vds.config.ConfigurationException
import org.a1tsign.vds.crawler.adapters.ExampleFailingAdapter
import org.a1tsign.vds.crawler.adapters.ExampleStaticAdapter

/** Where a registered adapter came from; reported by `GET /api/v1/admin/crawl/adapters`. */
enum class AdapterOrigin {
    /** Written in Kotlin and shipped in this build -- the only origin vds-002 had. */
    COMPILED,

    /** A `DeclarativeAdapter` built from one validated JSON file in `VDS_DISCOVERY_SOURCES_DIR`. */
    CONFIG,
}

/**
 * One registered adapter plus whether an empty `VDS_CRAWLER_ADAPTERS` picks it up.
 *
 * For a [AdapterOrigin.CONFIG] entry [includedByDefault] **is** the file's own `"enabled"` flag,
 * which defaults to `false` -- that is how a dropped-in file stays inert until somebody turns it on
 * (ADR-01 decision 5), without [select] needing a second rule.
 *
 * [file] is the source file's name, present exactly for a [AdapterOrigin.CONFIG] entry.
 */
data class AdapterEntry(
    val adapter: ProviderAdapter,
    val includedByDefault: Boolean,
    val origin: AdapterOrigin = AdapterOrigin.COMPILED,
    val file: String? = null,
)

private val ID_PATTERN = Regex("^[a-z0-9-]+$")

/**
 * The adapters this process knows about, built **once at startup** from two origins (vault
 * `06-decisions/vds/vds-004-adr-01-config-sourced-adapter-registry-and-restart-to-apply`, which
 * amends vds-002 requirement 10 and the "a static list written in code" rule this comment used to
 * state):
 *
 * 1. the **static, compiled list** of [production] -- unchanged;
 * 2. **config-sourced** entries, each one a `DeclarativeAdapter` over a JSON file validated by
 *    `crawler/config/SourceConfigLoader.kt` *before* this class is constructed.
 *
 * Still banned, unchanged: `ServiceLoader`, classpath scanning, reflection. What changed is only
 * that an entry may be *described by data* instead of *compiled*; a config-sourced entry is an
 * instance of one compiled class parameterised by a parsed config object.
 *
 * The registry remains an **immutable startup artifact**: nothing replaces [entries] while the
 * process runs, which is what keeps `api/routes/CrawlerRoutes.kt`'s "config does not change at
 * runtime" invariant true. A new or edited source file takes effect on the next restart
 * (restart-to-apply); hot-reload is deferred in the open by the ADR above.
 *
 * Construction is where a **programming** error surfaces: a malformed or duplicated [AdapterEntry]
 * is an [IllegalArgumentException], not a configuration problem. File data never reaches these
 * `require`s -- the loader validates and reports it first -- so they stay exactly as strict as they
 * were. A bad *configuration* -- an unknown id in `VDS_CRAWLER_ADAPTERS` -- is a
 * [ConfigurationException] thrown by [select], which `AppComponents.buildComponents` turns into
 * fail-fast at startup.
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
     * The adapters to run. An **empty** [configured] means "every entry registered as a default":
     * compiled entries with `includedByDefault = true` (today: none) **plus** config-sourced entries
     * whose own file says `"enabled": true`. Since that flag defaults to `false`, a newly dropped-in
     * file never starts crawling by itself and a deployment with no source files behaves exactly as
     * vds-002's does.
     *
     * A non-empty [configured] selects exactly those ids, **in the given order**, even a config
     * source whose file says `"enabled": false` -- naming an id is the operator's deliberate act.
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
        /**
         * The adapters this build ships in Kotlin: the two reference ones of spec req. 42 and
         * nothing else -- no adapter for a real hosting company is part of this increment.
         *
         * Both carry `includedByDefault = false`, so an empty `VDS_CRAWLER_ADAPTERS` selects
         * **nothing** compiled on a production box and a run has to name `example-static` /
         * `example-failing` explicitly (the plan's resolution of spec req. 10 vs req. 42).
         *
         * Exposed separately from [production] so the composition root can reserve these ids and
         * slugs for the source loader's cross-origin uniqueness check (a file must not be able to
         * shadow or collide with `example-static`) without building the registry twice.
         */
        fun compiledEntries(): List<AdapterEntry> = listOf(
            AdapterEntry(ExampleStaticAdapter(), includedByDefault = false),
            AdapterEntry(ExampleFailingAdapter(), includedByDefault = false),
        )

        /**
         * The compiled list plus [configSourced], the entries `SourceConfigLoader` produced from
         * `VDS_DISCOVERY_SOURCES_DIR`. With no argument this is vds-002's registry unchanged.
         */
        fun production(configSourced: List<AdapterEntry> = emptyList()): AdapterRegistry =
            AdapterRegistry(compiledEntries() + configSourced)
    }
}
