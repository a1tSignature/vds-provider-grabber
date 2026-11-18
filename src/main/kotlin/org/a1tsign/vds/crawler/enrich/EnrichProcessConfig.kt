package org.a1tsign.vds.crawler.enrich

import org.a1tsign.vds.config.ConfigurationException
import org.a1tsign.vds.config.CrawlerConfig
import org.a1tsign.vds.config.EnrichmentConfig
import org.a1tsign.vds.config.Secret
import org.a1tsign.vds.config.defaultCrawlerUserAgent
import java.math.BigDecimal
import java.net.URI

data class EnrichProcessConfig(
    /**
     * Carries the API base URL, the credentials and the shared `VDS_CRAWLER_*` fetch identity to
     * `CatalogApiClient` and `KtorPageFetcher`; its run/adapter fields are unused here.
     */
    val crawler: CrawlerConfig,
    val enrichment: EnrichmentConfig,
    val maxRuntimeMinutes: Long,
    val sourcesDir: String?,
    val maxSources: Int = 200,
)

data class EnrichOptions(
    val dryRun: Boolean = false,
    val force: Boolean = false,
    val maxBatches: Int? = null,
    val attemptedFile: String? = null,
    val newCandidates: Boolean = false,
    val workers: Int = 1,
    val maxProviders: Int? = null,
)

const val ENRICH_USAGE: String =
    "usage: EnrichMainKt [--dry-run] [--force | --new-candidates] [--workers N] [--max-batches N] [--max-providers N] [--attempted-file PATH]"

/** Throws [ConfigurationException] with [ENRICH_USAGE] on an unknown option or a bad value. */
fun parseEnrichOptions(args: Array<String>): EnrichOptions {
    var options = EnrichOptions()
    var i = 0
    fun value(name: String): String =
        args.getOrNull(++i) ?: throw ConfigurationException("$name needs a value; $ENRICH_USAGE")
    while (i < args.size) {
        options = when (val arg = args[i]) {
            "--dry-run" -> options.copy(dryRun = true)
            "--force" -> options.copy(force = true)
            "--new-candidates" -> options.copy(newCandidates = true)
            "--workers" -> {
                val n = value(arg).toIntOrNull()?.takeIf { it in 1..10 }
                    ?: throw ConfigurationException("--workers must be an integer between 1 and 10; $ENRICH_USAGE")
                options.copy(workers = n)
            }
            "--max-batches" -> {
                val n = value(arg).toIntOrNull()?.takeIf { it > 0 }
                    ?: throw ConfigurationException("--max-batches must be a positive integer; $ENRICH_USAGE")
                options.copy(maxBatches = n)
            }
            "--max-providers" -> {
                val n = value(arg).toIntOrNull()?.takeIf { it > 0 }
                    ?: throw ConfigurationException("--max-providers must be a positive integer; $ENRICH_USAGE")
                options.copy(maxProviders = n)
            }
            "--attempted-file" -> options.copy(attemptedFile = value(arg))
            else -> throw ConfigurationException("unknown option '$arg'; $ENRICH_USAGE")
        }
        i++
    }
    if (options.force && options.newCandidates) {
        throw ConfigurationException("--force and --new-candidates are mutually exclusive; $ENRICH_USAGE")
    }
    return options
}

/** Validate all environment settings before starting and report violations together. The process needs catalog credentials only. */
fun loadEnrichProcessConfig(env: Map<String, String> = System.getenv()): EnrichProcessConfig {
    val errors = mutableListOf<String>()

    fun optionalNonBlank(name: String): String? = env[name]?.takeUnless { it.isBlank() }

    fun required(name: String): String? = optionalNonBlank(name).also { if (it == null) errors += "$name is required" }

    fun positiveLong(name: String, default: Long): Long {
        val raw = env[name] ?: return default
        return raw.toLongOrNull()?.takeIf { it > 0 } ?: default.also { errors += "$name must be a positive integer" }
    }

    fun boundedInt(name: String, default: Int, min: Int, max: Int): Int {
        val raw = env[name] ?: return default
        return raw.toIntOrNull()?.takeIf { it in min..max }
            ?: default.also { errors += "$name must be an integer between $min and $max" }
    }

    fun absoluteHttp(name: String, value: String): String {
        val uri = runCatching { URI(value) }.getOrNull()
        if (uri == null || !uri.isAbsolute || uri.scheme !in setOf("http", "https") || uri.host.isNullOrEmpty()) {
            errors += "$name must be an absolute http or https URL"
        }
        return value
    }

    val apiBaseUrl = absoluteHttp("VDS_ENRICH_API_BASE_URL", env["VDS_ENRICH_API_BASE_URL"] ?: DEFAULT_API_BASE_URL)
    val username = required("VDS_ENRICH_USERNAME")
    val password = required("VDS_ENRICH_PASSWORD")
    val enrichment = EnrichmentConfig(
        intervalHours = positiveLong("VDS_ENRICH_INTERVAL_HOURS", 720L),
        providerTimeoutMinutes = positiveLong("VDS_ENRICH_PROVIDER_TIMEOUT_MINUTES", 4L),
        maxPages = boundedInt("VDS_ENRICH_MAX_PAGES", 25, 1, 50),
        maxPagesPerSection = boundedInt("VDS_ENRICH_MAX_PAGES_PER_SECTION", 4, 1, Int.MAX_VALUE),
        maxSitemaps = boundedInt("VDS_ENRICH_MAX_SITEMAPS", 4, 1, Int.MAX_VALUE),
    )
    val maxRuntimeMinutes = positiveLong("VDS_ENRICH_MAX_RUNTIME_MINUTES", 360L)
    val hostDelayMs = positiveLong("VDS_CRAWLER_HOST_DELAY_MS", 2000L)
    val contactUrl = optionalNonBlank("VDS_CRAWLER_CONTACT_URL")?.let { absoluteHttp("VDS_CRAWLER_CONTACT_URL", it) }
    val userAgent = optionalNonBlank("VDS_CRAWLER_USER_AGENT") ?: defaultCrawlerUserAgent(contactUrl)
    val maxBodyBytes = positiveLong("VDS_CRAWLER_MAX_BODY_BYTES", 2_097_152L)

    val maxSources = boundedInt("VDS_DISCOVERY_MAX_SOURCES", 200, 1, Int.MAX_VALUE)

    if (errors.isNotEmpty()) {
        throw ConfigurationException("Invalid configuration: ${errors.joinToString("; ")}")
    }
    return EnrichProcessConfig(
        crawler = CrawlerConfig(
            enabled = true,
            apiBaseUrl = apiBaseUrl,
            username = username,
            password = password?.let { Secret(it) },
            intervalHours = enrichment.intervalHours,
            initialDelayMinutes = 1,
            adapters = emptyList(),
            maxConcurrentAdapters = 1,
            adapterTimeoutMinutes = enrichment.providerTimeoutMinutes,
            runTimeoutMinutes = maxRuntimeMinutes,
            hostDelayMs = hostDelayMs,
            userAgent = userAgent,
            contactUrl = contactUrl,
            respectRobots = true,
            maxBodyBytes = maxBodyBytes,
            maxOutOfStockRatio = BigDecimal.ZERO,
            dryRunDefault = false,
        ),
        enrichment = enrichment,
        maxRuntimeMinutes = maxRuntimeMinutes,
        sourcesDir = optionalNonBlank("VDS_DISCOVERY_SOURCES_DIR"),
        maxSources = maxSources,
    )
}

private const val DEFAULT_API_BASE_URL = "http://127.0.0.1:8080"
