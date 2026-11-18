package org.a1tsign.vds.config

import org.a1tsign.vds.api.parsePlainDecimal
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties

/**
 * Wraps a secret value so it never leaks through a `toString()` call (logs, exception messages,
 * accidental `println`). Use [reveal] only where the real value is actually needed (JDBC driver,
 * JWT signer, bcrypt).
 */
data class Secret(private val value: String) {
    override fun toString(): String = "***"

    fun reveal(): String = value
}

/**
 * The whole `VDS_CRAWLER_ADAPTER_*` slice of the process environment, carried from [loadConfig] --
 * the one place allowed to read the real environment -- to the composition root, which splits it per
 * adapter id with [crawlerAdapterConfig]. [loadConfig] cannot do the split itself: the adapter ids
 * live in the registry, not in the environment.
 *
 * [toString] masks the content for the same reason [Secret]'s does -- an adapter's own settings may
 * legitimately carry a credential (say an API key for a provider's price endpoint) and this value is
 * reachable from `AppConfig.toString()`. Use [reveal] only where the real map is needed.
 */
data class AdapterEnv(private val values: Map<String, String>) {
    override fun toString(): String = "***(${values.size} $ADAPTER_ENV_PREFIX* variables)"

    fun reveal(): Map<String, String> = values
}

data class DbConfig(
    val url: String,
    val user: String,
    val password: Secret,
    val poolMax: Int,
    val poolMinIdle: Int,
    val connectionTimeoutMs: Long,
    val leakDetectionMs: Long,
)

data class HttpConfig(
    val port: Int,
    val corsAllowedOrigins: List<String>,
    /**
     * Take the login throttle's client address from the last `X-Forwarded-For` entry instead of the
     * TCP peer. Safe only when every connection comes through a reverse proxy that overwrites the
     * header; otherwise a client picks its own throttle key.
     */
    val trustForwardedFor: Boolean = false,
)

data class JwtConfig(
    val secret: Secret,
    val ttlHours: Long,
)

data class FxConfig(
    val apiUrl: String,
    val apiKey: Secret?,
    val refreshHours: Long,
)

data class BootstrapAdminConfig(
    val username: String,
    val password: Secret,
)

/** Enabled crawling requires both service-account credentials. Other settings are validated even when disabled. */
data class CrawlerConfig(
    val enabled: Boolean,
    val apiBaseUrl: String,
    val username: String?,
    val password: Secret?,
    val intervalHours: Long,
    val initialDelayMinutes: Long,
    /** Adapter ids selected for a run; empty means "every adapter registered as default". */
    val adapters: List<String>,
    val maxConcurrentAdapters: Int,
    val adapterTimeoutMinutes: Long,
    val runTimeoutMinutes: Long,
    val hostDelayMs: Long,
    val userAgent: String,
    val contactUrl: String?,
    /** Disabling robots enforcement is logged at startup. */
    val respectRobots: Boolean,
    val maxBodyBytes: Long,
    /** In `[0, 1]`, always a [BigDecimal] -- `Double` must not appear in this value's path. */
    val maxOutOfStockRatio: BigDecimal,
    val dryRunDefault: Boolean,
    /** The composition root selects each adapter's settings from this captured environment slice. */
    val adapterEnv: AdapterEnv = AdapterEnv(emptyMap()),
    val schedulerEnabled: Boolean = true,
)

/**
 * The `VDS_ENRICH_*` budgets of the site enrichment process (`crawler.enrich.EnrichMain`), which
 * loads them itself; the service does not read them. The per-host delay, `User-Agent` and body cap
 * are the `VDS_CRAWLER_*` ones.
 */
data class EnrichmentConfig(
    /** A provider is due again once its last attempt is older than this. */
    val intervalHours: Long = 720,
    val providerTimeoutMinutes: Long = 4,
    /** HTML page fetches per provider; a robots-disallowed URL does not count. */
    val maxPages: Int = 25,
    /** Page fetches per first path segment after the locale. */
    val maxPagesPerSection: Int = 4,
    /** Sitemap documents read per provider, index children included. */
    val maxSitemaps: Int = 4,
)

/** NONE disables the search API while leaving declarative source loading available. */
enum class DiscoverySearchProvider(val envValue: String) {
    TAVILY("tavily"),
    NONE("none"),
    ;

    companion object {
        fun fromEnv(value: String): DiscoverySearchProvider? = entries.firstOrNull { it.envValue == value }
    }
}

/**
 * Disabled discovery logs invalid settings and uses defaults. Enabled discovery treats errors as startup failures.
 * Declarative sources load when the crawler has a source directory, independently of search.
 */
data class DiscoveryConfig(
    val enabled: Boolean,
    /** Directory of `*.json` source files, read non-recursively at startup; `null` reads nothing. */
    val sourcesDir: String?,
    /** Turns an invalid source file into a startup failure -- only while [enabled] (see the class doc). */
    val sourcesStrict: Boolean,
    val maxSources: Int,
    val searchProvider: DiscoverySearchProvider,
    val searchApiKey: Secret?,
    val searchEndpoint: String,
    val searchTimeoutSeconds: Long,
    val maxResponseBytes: Long,
    /** Literal query strings, never templates: nothing in this project interpolates into them. */
    val queries: List<String>,
    val queryDelayMs: Long,
    val maxQueriesPerPass: Int,
    val maxCreditsPerMonth: Int,
    /** The search API accepts at most 20 results per request. */
    val maxResultsPerQuery: Int,
    val maxNewCandidatesPerPass: Int,
    val maxKnownProviders: Int,
    val domainDenylist: List<String>,
    val requiredKeywords: List<String>,
    val intervalHours: Long,
    val initialDelayMinutes: Long,
    val minPassIntervalHours: Long,
    val passTimeoutMinutes: Long,
    /** The credit ledger's JSON state file; required while [enabled], its directory must be writable. */
    val stateFile: String?,
    val retryRateLimit: Boolean = true,
)

data class AppConfig(
    val db: DbConfig,
    val http: HttpConfig,
    val jwt: JwtConfig,
    val fx: FxConfig,
    // Null when both bootstrap-admin variables are absent.
    val bootstrapAdmin: BootstrapAdminConfig?,
    val crawler: CrawlerConfig,
    val discovery: DiscoveryConfig,
)

/**
 * Thrown by [loadConfig] when the environment is misconfigured. The message names every
 * offending variable on one line, never a secret's value.
 */
class ConfigurationException(message: String) : Exception(message)

private const val DEFAULT_FX_API_URL = "https://open.er-api.com/v6/latest/USD"

private const val DEFAULT_CRAWLER_API_BASE_URL = "http://127.0.0.1:8080"

private val logger = LoggerFactory.getLogger("org.a1tsign.vds.config.AppConfig")

private const val DEFAULT_DISCOVERY_SEARCH_ENDPOINT = "https://api.tavily.com"

/** Split queries on pipe or newline because a query may contain commas. */
private val DISCOVERY_QUERY_SEPARATOR = Regex("[|\n]")

/** Comma, `|` or newline separated -- a domain and a keyword never contain any of the three. */
private val DISCOVERY_LIST_SEPARATOR = Regex("[,|\n]")

private const val DEFAULT_DISCOVERY_QUERIES =
    "vps hosting | cheap vps nvme | аренда vps | vds хостинг | kvm vps hosting"

private const val DEFAULT_DISCOVERY_DOMAIN_DENYLIST_RESOURCE = "/discovery/domain-denylist.txt"

/** A denylist file larger than this is refused: it is read whole into memory at startup. */
private const val DISCOVERY_DOMAIN_DENYLIST_FILE_MAX_BYTES = 64 * 1024

/** One domain per line; `#` starts a comment; blank lines are ignored. */
internal fun parseDomainListFile(text: String): List<String> =
    text.lineSequence()
        .map { it.substringBefore('#').trim().lowercase() }
        .filter { it.isNotEmpty() }
        .distinct()
        .toList()

private val DEFAULT_DISCOVERY_DOMAIN_DENYLIST: List<String> by lazy {
    val stream = checkNotNull(object {}.javaClass.getResourceAsStream(DEFAULT_DISCOVERY_DOMAIN_DENYLIST_RESOURCE)) {
        "$DEFAULT_DISCOVERY_DOMAIN_DENYLIST_RESOURCE is missing from the classpath"
    }
    parseDomainListFile(stream.use { it.readAllBytes().toString(Charsets.UTF_8) })
}

private fun readDomainDenylistFile(file: String, errors: MutableList<String>): List<String>? {
    val name = "VDS_DISCOVERY_DOMAIN_DENYLIST_FILE"
    val path = pathOrNull(file)
    if (path == null || !Files.isRegularFile(path) || !Files.isReadable(path)) {
        errors += "$name must be a readable file, which '$file' is not"
        return null
    }
    val limit = DISCOVERY_DOMAIN_DENYLIST_FILE_MAX_BYTES
    val bytes = try {
        Files.newInputStream(path).use { it.readNBytes(limit + 1) }
    } catch (failure: java.io.IOException) {
        errors += "$name could not be read: ${failure.javaClass.simpleName}"
        return null
    }
    if (bytes.size > limit) {
        errors += "$name must not be larger than $limit bytes"
        return null
    }
    return parseDomainListFile(bytes.toString(Charsets.UTF_8))
}

private val DEFAULT_DISCOVERY_REQUIRED_KEYWORDS = listOf(
    "vps", "vds", "virtual server", "virtual private server", "vps hosting",
    "виртуальный сервер", "виртуальный выделенный сервер",
)

const val ADAPTER_ENV_PREFIX: String = "VDS_CRAWLER_ADAPTER_"

const val CRAWLER_USER_AGENT_PRODUCT = "vds-provider-grabber"

/**
 * Parses and validates [env] into an [AppConfig], or throws [ConfigurationException] naming every
 * violation found. Takes the environment as a parameter (defaulting to the real process
 * environment below) so it is unit-testable without touching the real one — this default
 * argument is the only place in the codebase allowed to read it.
 */
fun loadConfig(env: Map<String, String> = System.getenv()): AppConfig {
    val errors = mutableListOf<String>()

    fun requiredNonBlank(name: String): String {
        val value = env[name]
        if (value == null || value.isBlank()) {
            errors += "$name is required"
            return ""
        }
        return value
    }

    fun optionalNonBlank(name: String): String? = env[name]?.takeUnless { it.isBlank() }

    fun positiveInt(name: String, default: Int, into: MutableList<String> = errors): Int {
        val raw = env[name] ?: return default
        val parsed = raw.toIntOrNull()
        if (parsed == null || parsed <= 0) {
            into += "$name must be a positive integer"
            return default
        }
        return parsed
    }

    fun boundedInt(name: String, default: Int, min: Int, max: Int, into: MutableList<String> = errors): Int {
        val raw = env[name] ?: return default
        val parsed = raw.toIntOrNull()
        if (parsed == null || parsed < min || parsed > max) {
            into += "$name must be an integer between $min and $max"
            return default
        }
        return parsed
    }

    fun nonNegativeInt(name: String, default: Int): Int {
        val raw = env[name] ?: return default
        val parsed = raw.toIntOrNull()
        if (parsed == null || parsed < 0) {
            errors += "$name must be zero or greater"
            return default
        }
        return parsed
    }

    fun positiveLong(name: String, default: Long, into: MutableList<String> = errors): Long {
        val raw = env[name] ?: return default
        val parsed = raw.toLongOrNull()
        if (parsed == null || parsed <= 0) {
            into += "$name must be a positive integer"
            return default
        }
        return parsed
    }

    fun nonNegativeLong(name: String, default: Long): Long {
        val raw = env[name] ?: return default
        val parsed = raw.toLongOrNull()
        if (parsed == null || parsed < 0) {
            errors += "$name must be zero or greater"
            return default
        }
        return parsed
    }

    fun booleanFlag(name: String, default: Boolean, into: MutableList<String> = errors): Boolean {
        val raw = env[name] ?: return default
        return when (raw) {
            "true" -> true
            "false" -> false
            else -> {
                into += "$name must be true or false"
                default
            }
        }
    }

    fun ratio(name: String, default: BigDecimal): BigDecimal {
        val raw = env[name] ?: return default
        // Use the wire decimal syntax so the ratio never passes through floating-point parsing.
        val parsed = parsePlainDecimal(raw)
        if (parsed == null || parsed.signum() < 0 || parsed > BigDecimal.ONE) {
            errors += "$name must be a decimal number in plain notation between 0 and 1"
            return default
        }
        return parsed
    }

    val dbUrl = requiredNonBlank("VDS_DB_URL")
    val dbUser = requiredNonBlank("VDS_DB_USER")
    val dbPassword = requiredNonBlank("VDS_DB_PASSWORD")
    val dbPoolMax = positiveInt("VDS_DB_POOL_MAX", 5)
    val dbPoolMinIdle = nonNegativeInt("VDS_DB_POOL_MIN_IDLE", 1)
    val dbConnectionTimeoutMs = positiveLong("VDS_DB_CONNECTION_TIMEOUT_MS", 5000L)
    val dbLeakDetectionMs = nonNegativeLong("VDS_DB_LEAK_DETECTION_MS", 30000L)

    val httpPortRaw = env["VDS_HTTP_PORT"]
    val httpPort = if (httpPortRaw == null) {
        8080
    } else {
        val parsed = httpPortRaw.toIntOrNull()
        if (parsed == null || parsed < 1 || parsed > 65535) {
            errors += "VDS_HTTP_PORT must be an integer between 1 and 65535"
            8080
        } else {
            parsed
        }
    }
    val corsAllowedOrigins = env["VDS_CORS_ALLOWED_ORIGINS"]
        ?.split(",")
        ?.map { it.trim() }
        ?.filter { it.isNotEmpty() }
        ?: emptyList()
    val trustForwardedFor = booleanFlag("VDS_HTTP_TRUST_FORWARDED_FOR", false)

    val jwtSecretRaw = requiredNonBlank("VDS_JWT_SECRET")
    if (jwtSecretRaw.isNotEmpty() && jwtSecretRaw.length < 32) {
        errors += "VDS_JWT_SECRET must be at least 32 characters"
    }
    val jwtTtlHours = positiveLong("VDS_JWT_TTL_HOURS", 24L)

    val fxApiUrlRaw = env["VDS_FX_API_URL"] ?: DEFAULT_FX_API_URL
    if (!isAbsoluteHttpUrl(fxApiUrlRaw)) {
        errors += "VDS_FX_API_URL must be an absolute http or https URL"
    }
    val fxApiKeyRaw = optionalNonBlank("VDS_FX_API_KEY")
    val fxRefreshHours = positiveLong("VDS_FX_REFRESH_HOURS", 12L)

    val bootstrapUsername = optionalNonBlank("VDS_BOOTSTRAP_ADMIN_USERNAME")
    val bootstrapPasswordRaw = optionalNonBlank("VDS_BOOTSTRAP_ADMIN_PASSWORD")
    if (bootstrapPasswordRaw != null) {
        if (bootstrapPasswordRaw.length < 12) {
            errors += "VDS_BOOTSTRAP_ADMIN_PASSWORD must be at least 12 characters"
        }
        if (bootstrapPasswordRaw.toByteArray(Charsets.UTF_8).size > 72) {
            errors += "VDS_BOOTSTRAP_ADMIN_PASSWORD must be at most 72 bytes in UTF-8"
        }
    }
    if (bootstrapUsername != null && bootstrapPasswordRaw == null) {
        errors += "VDS_BOOTSTRAP_ADMIN_PASSWORD is required when VDS_BOOTSTRAP_ADMIN_USERNAME is set"
    }
    if (bootstrapPasswordRaw != null && bootstrapUsername == null) {
        errors += "VDS_BOOTSTRAP_ADMIN_USERNAME is required when VDS_BOOTSTRAP_ADMIN_PASSWORD is set"
    }

    val crawlerEnabled = booleanFlag("VDS_CRAWLER_ENABLED", false)
    val crawlerSchedulerEnabled = booleanFlag("VDS_CRAWLER_SCHEDULER_ENABLED", true)
    val crawlerApiBaseUrl = env["VDS_CRAWLER_API_BASE_URL"] ?: DEFAULT_CRAWLER_API_BASE_URL
    if (!isAbsoluteHttpUrl(crawlerApiBaseUrl)) {
        errors += "VDS_CRAWLER_API_BASE_URL must be an absolute http or https URL"
    }
    val crawlerUsername = optionalNonBlank("VDS_CRAWLER_USERNAME")
    val crawlerPasswordRaw = optionalNonBlank("VDS_CRAWLER_PASSWORD")
    if (crawlerEnabled && crawlerUsername == null) {
        errors += "VDS_CRAWLER_USERNAME is required when VDS_CRAWLER_ENABLED is true"
    }
    if (crawlerEnabled && crawlerPasswordRaw == null) {
        errors += "VDS_CRAWLER_PASSWORD is required when VDS_CRAWLER_ENABLED is true"
    }
    val crawlerIntervalHours = positiveLong("VDS_CRAWLER_INTERVAL_HOURS", 24L)
    val crawlerInitialDelayMinutes = positiveLong("VDS_CRAWLER_INITIAL_DELAY_MINUTES", 10L)
    val crawlerAdapters = env["VDS_CRAWLER_ADAPTERS"]
        ?.split(",")
        ?.map { it.trim() }
        ?.filter { it.isNotEmpty() }
        ?: emptyList()
    val crawlerMaxConcurrentAdapters = positiveInt("VDS_CRAWLER_MAX_CONCURRENT_ADAPTERS", 1)
    val crawlerAdapterTimeoutMinutes = positiveLong("VDS_CRAWLER_ADAPTER_TIMEOUT_MINUTES", 5L)
    val crawlerRunTimeoutMinutes = positiveLong("VDS_CRAWLER_RUN_TIMEOUT_MINUTES", 30L)
    val crawlerHostDelayMs = positiveLong("VDS_CRAWLER_HOST_DELAY_MS", 2000L)
    val crawlerContactUrl = optionalNonBlank("VDS_CRAWLER_CONTACT_URL")
    if (crawlerContactUrl != null && !isAbsoluteHttpUrl(crawlerContactUrl)) {
        errors += "VDS_CRAWLER_CONTACT_URL must be an absolute http or https URL"
    }
    val crawlerUserAgent = optionalNonBlank("VDS_CRAWLER_USER_AGENT")
        ?: defaultCrawlerUserAgent(crawlerContactUrl)
    val crawlerRespectRobots = booleanFlag("VDS_CRAWLER_RESPECT_ROBOTS", true)
    val crawlerMaxBodyBytes = positiveLong("VDS_CRAWLER_MAX_BODY_BYTES", 2_097_152L)
    val crawlerMaxOutOfStockRatio = ratio("VDS_CRAWLER_MAX_OUT_OF_STOCK_RATIO", BigDecimal("0.5"))
    val crawlerDryRunDefault = booleanFlag("VDS_CRAWLER_DRY_RUN_DEFAULT", false)
    // Adapter-specific values are validated by their adapter. The timeout key shares this prefix and is filtered out by adapter-specific lookup.
    val crawlerAdapterEnv = AdapterEnv(env.filterKeys { it.startsWith(ADAPTER_ENV_PREFIX) })

    // Discovery errors become warnings while disabled and startup failures while enabled.
    val discoveryErrors = mutableListOf<String>()
    val discoveryEnabled = booleanFlag("VDS_DISCOVERY_ENABLED", false, discoveryErrors)
    val discoverySourcesDir = optionalNonBlank("VDS_DISCOVERY_SOURCES_DIR")
    val discoverySourcesStrict = booleanFlag("VDS_DISCOVERY_SOURCES_STRICT", false, discoveryErrors)
    val discoveryMaxSources = positiveInt("VDS_DISCOVERY_MAX_SOURCES", 200, discoveryErrors)
    val discoverySearchProviderRaw = optionalNonBlank("VDS_DISCOVERY_SEARCH_PROVIDER")?.lowercase()
    val discoverySearchProvider = when {
        discoverySearchProviderRaw == null -> DiscoverySearchProvider.TAVILY
        else -> DiscoverySearchProvider.fromEnv(discoverySearchProviderRaw) ?: run {
            discoveryErrors += "VDS_DISCOVERY_SEARCH_PROVIDER must be one of " +
                DiscoverySearchProvider.entries.joinToString { it.envValue }
            DiscoverySearchProvider.TAVILY
        }
    }
    val discoveryRetryRateLimit = booleanFlag("VDS_DISCOVERY_RETRY_RATE_LIMIT", true, discoveryErrors)
    val discoverySearchApiKey = optionalNonBlank("VDS_DISCOVERY_SEARCH_API_KEY")
    if (discoverySearchProvider != DiscoverySearchProvider.NONE && discoverySearchApiKey == null) {
        discoveryErrors += "VDS_DISCOVERY_SEARCH_API_KEY is required when VDS_DISCOVERY_ENABLED is true " +
            "and VDS_DISCOVERY_SEARCH_PROVIDER is not none"
    }
    val discoverySearchEndpoint = env["VDS_DISCOVERY_SEARCH_ENDPOINT"] ?: DEFAULT_DISCOVERY_SEARCH_ENDPOINT
    if (!isAbsoluteHttpUrl(discoverySearchEndpoint)) {
        discoveryErrors += "VDS_DISCOVERY_SEARCH_ENDPOINT must be an absolute http or https URL"
    }
    val discoverySearchTimeoutSeconds = positiveLong("VDS_DISCOVERY_SEARCH_TIMEOUT_SECONDS", 10L, discoveryErrors)
    val discoveryMaxResponseBytes = positiveLong("VDS_DISCOVERY_MAX_RESPONSE_BYTES", 524_288L, discoveryErrors)
    val discoveryQueries = (env["VDS_DISCOVERY_QUERIES"] ?: DEFAULT_DISCOVERY_QUERIES)
        .split(DISCOVERY_QUERY_SEPARATOR)
        .map { it.trim() }
        .filter { it.isNotEmpty() }
    if (discoveryQueries.isEmpty()) {
        discoveryErrors += "VDS_DISCOVERY_QUERIES must name at least one query"
    }
    val discoveryQueryDelayMs = positiveLong("VDS_DISCOVERY_QUERY_DELAY_MS", 1000L, discoveryErrors)
    val discoveryMaxQueriesPerPass = positiveInt("VDS_DISCOVERY_MAX_QUERIES_PER_PASS", 10, discoveryErrors)
    val discoveryMaxCreditsPerMonth = positiveInt("VDS_DISCOVERY_MAX_CREDITS_PER_MONTH", 300, discoveryErrors)
    val discoveryMaxResultsPerQuery = boundedInt("VDS_DISCOVERY_MAX_RESULTS_PER_QUERY", 10, 1, 20, discoveryErrors)
    val discoveryMaxNewCandidates = positiveInt("VDS_DISCOVERY_MAX_NEW_CANDIDATES_PER_PASS", 20, discoveryErrors)
    val discoveryMaxKnownProviders = positiveInt("VDS_DISCOVERY_MAX_KNOWN_PROVIDERS", 5000, discoveryErrors)
    // Normalize once for case-insensitive comparisons. An explicitly empty list disables the corresponding denylist.
    val discoveryDomainDenylistInline = env["VDS_DISCOVERY_DOMAIN_DENYLIST"]
        ?.split(DISCOVERY_LIST_SEPARATOR)
        ?.map { it.trim().lowercase() }
        ?.filter { it.isNotEmpty() }
    val discoveryDomainDenylistFile = optionalNonBlank("VDS_DISCOVERY_DOMAIN_DENYLIST_FILE")
    if (discoveryDomainDenylistInline != null && discoveryDomainDenylistFile != null) {
        discoveryErrors += "VDS_DISCOVERY_DOMAIN_DENYLIST and VDS_DISCOVERY_DOMAIN_DENYLIST_FILE are mutually exclusive"
    }
    // The file is opened only while discovery is enabled: with it off, no path is touched.
    val discoveryDomainDenylist = discoveryDomainDenylistInline
        ?: discoveryDomainDenylistFile
            ?.takeIf { discoveryEnabled }
            ?.let { readDomainDenylistFile(it, discoveryErrors) }
        ?: DEFAULT_DISCOVERY_DOMAIN_DENYLIST
    val discoveryRequiredKeywords = env["VDS_DISCOVERY_REQUIRED_KEYWORDS"]
        ?.split(DISCOVERY_LIST_SEPARATOR)
        ?.map { it.trim().lowercase() }
        ?.filter { it.isNotEmpty() }
        ?: DEFAULT_DISCOVERY_REQUIRED_KEYWORDS
    val discoveryIntervalHours = positiveLong("VDS_DISCOVERY_INTERVAL_HOURS", 48L, discoveryErrors)
    val discoveryInitialDelayMinutes = positiveLong("VDS_DISCOVERY_INITIAL_DELAY_MINUTES", 15L, discoveryErrors)
    val discoveryMinPassIntervalHours = positiveLong("VDS_DISCOVERY_MIN_PASS_INTERVAL_HOURS", 24L, discoveryErrors)
    val discoveryPassTimeoutMinutes = positiveLong("VDS_DISCOVERY_PASS_TIMEOUT_MINUTES", 10L, discoveryErrors)
    val discoveryStateFile = optionalNonBlank("VDS_DISCOVERY_STATE_FILE")
    // Skip these filesystem checks while discovery is disabled. The crawler's source loader checks its own directory independently.
    if (discoveryEnabled) {
        if (discoveryStateFile == null) {
            discoveryErrors += "VDS_DISCOVERY_STATE_FILE is required when VDS_DISCOVERY_ENABLED is true"
        } else {
            val parent = pathOrNull(discoveryStateFile)?.toAbsolutePath()?.parent
            if (parent == null || !Files.isDirectory(parent) || !Files.isWritable(parent)) {
                discoveryErrors += "VDS_DISCOVERY_STATE_FILE must sit in an existing writable directory, " +
                    "which '$discoveryStateFile' does not"
            }
        }
        if (discoverySourcesDir != null) {
            val dir = pathOrNull(discoverySourcesDir)
            if (dir == null || !Files.isDirectory(dir) || !Files.isReadable(dir)) {
                discoveryErrors += "VDS_DISCOVERY_SOURCES_DIR must be a readable directory, " +
                    "which '$discoverySourcesDir' is not"
            }
        }
    }
    if (discoveryEnabled) {
        errors += discoveryErrors
    } else {
        discoveryErrors.forEach {
            logger.warn("VDS_DISCOVERY_ENABLED is false, so this does not prevent startup: {}", it)
        }
    }

    if (errors.isNotEmpty()) {
        throw ConfigurationException("Invalid configuration: ${errors.joinToString("; ")}")
    }

    return AppConfig(
        db = DbConfig(
            url = dbUrl,
            user = dbUser,
            password = Secret(dbPassword),
            poolMax = dbPoolMax,
            poolMinIdle = dbPoolMinIdle,
            connectionTimeoutMs = dbConnectionTimeoutMs,
            leakDetectionMs = dbLeakDetectionMs,
        ),
        http = HttpConfig(
            port = httpPort,
            corsAllowedOrigins = corsAllowedOrigins,
            trustForwardedFor = trustForwardedFor,
        ),
        jwt = JwtConfig(
            secret = Secret(jwtSecretRaw),
            ttlHours = jwtTtlHours,
        ),
        fx = FxConfig(
            apiUrl = fxApiUrlRaw,
            apiKey = fxApiKeyRaw?.let { Secret(it) },
            refreshHours = fxRefreshHours,
        ),
        bootstrapAdmin = if (bootstrapUsername != null && bootstrapPasswordRaw != null) {
            BootstrapAdminConfig(username = bootstrapUsername, password = Secret(bootstrapPasswordRaw))
        } else {
            null
        },
        crawler = CrawlerConfig(
            enabled = crawlerEnabled,
            apiBaseUrl = crawlerApiBaseUrl,
            username = crawlerUsername,
            password = crawlerPasswordRaw?.let { Secret(it) },
            intervalHours = crawlerIntervalHours,
            schedulerEnabled = crawlerSchedulerEnabled,
            initialDelayMinutes = crawlerInitialDelayMinutes,
            adapters = crawlerAdapters,
            maxConcurrentAdapters = crawlerMaxConcurrentAdapters,
            adapterTimeoutMinutes = crawlerAdapterTimeoutMinutes,
            runTimeoutMinutes = crawlerRunTimeoutMinutes,
            hostDelayMs = crawlerHostDelayMs,
            userAgent = crawlerUserAgent,
            contactUrl = crawlerContactUrl,
            respectRobots = crawlerRespectRobots,
            maxBodyBytes = crawlerMaxBodyBytes,
            maxOutOfStockRatio = crawlerMaxOutOfStockRatio,
            dryRunDefault = crawlerDryRunDefault,
            adapterEnv = crawlerAdapterEnv,
        ),
        discovery = DiscoveryConfig(
            enabled = discoveryEnabled,
            sourcesDir = discoverySourcesDir,
            sourcesStrict = discoverySourcesStrict,
            maxSources = discoveryMaxSources,
            searchProvider = discoverySearchProvider,
            searchApiKey = discoverySearchApiKey?.let { Secret(it) },
            searchEndpoint = discoverySearchEndpoint,
            searchTimeoutSeconds = discoverySearchTimeoutSeconds,
            maxResponseBytes = discoveryMaxResponseBytes,
            queries = discoveryQueries,
            queryDelayMs = discoveryQueryDelayMs,
            maxQueriesPerPass = discoveryMaxQueriesPerPass,
            maxCreditsPerMonth = discoveryMaxCreditsPerMonth,
            maxResultsPerQuery = discoveryMaxResultsPerQuery,
            maxNewCandidatesPerPass = discoveryMaxNewCandidates,
            maxKnownProviders = discoveryMaxKnownProviders,
            domainDenylist = discoveryDomainDenylist,
            requiredKeywords = discoveryRequiredKeywords,
            intervalHours = discoveryIntervalHours,
            initialDelayMinutes = discoveryInitialDelayMinutes,
            minPassIntervalHours = discoveryMinPassIntervalHours,
            passTimeoutMinutes = discoveryPassTimeoutMinutes,
            stateFile = discoveryStateFile,
            retryRateLimit = discoveryRetryRateLimit,
        ),
    )
}

private fun pathOrNull(value: String): Path? = runCatching { Path.of(value) }.getOrNull()

/**
 * Map VDS_CRAWLER_ADAPTER_<ID>_* to lower-case suffix keys, with hyphens in the ID written as underscores.
 * Nested IDs such as a and a-b can match the same variable, so pick nonoverlapping IDs.
 */
fun crawlerAdapterConfig(adapterId: String, env: Map<String, String>): Map<String, String> {
    val prefix = ADAPTER_ENV_PREFIX + adapterId.uppercase().replace('-', '_') + "_"
    return env.entries
        .filter { it.key.startsWith(prefix) && it.key.length > prefix.length }
        .associate { it.key.removePrefix(prefix).lowercase() to it.value }
}

/**
 * `vds-provider-grabber/<version>`, plus ` (+<contact url>)` when `VDS_CRAWLER_CONTACT_URL` is
 * set, so a site owner can tell who is crawling and reach us. The version comes from the same
 * packaged `version.properties` that `GET /version` serves (read independently here because
 * `api/routes/OpsRoutes.kt` reads it for its own response and is not this task's to refactor);
 * a missing resource degrades to `unknown` rather than failing startup.
 */
internal fun defaultCrawlerUserAgent(contactUrl: String?): String {
    val base = "$CRAWLER_USER_AGENT_PRODUCT/${appVersion()}"
    return if (contactUrl == null) base else "$base (+$contactUrl)"
}

internal fun appVersion(): String {
    val properties = Properties()
    val classLoader = Thread.currentThread().contextClassLoader
        ?: AppConfig::class.java.classLoader
    classLoader.getResourceAsStream("version.properties")?.use { properties.load(it) }
    return properties.getProperty("version", "unknown")
}

// Require a host as well as an HTTP(S) scheme. The URL must also meet the configured path and component rules.
private fun isAbsoluteHttpUrl(value: String): Boolean {
    val uri = runCatching { URI(value) }.getOrNull() ?: return false
    return uri.isAbsolute && (uri.scheme == "http" || uri.scheme == "https") && !uri.host.isNullOrEmpty()
}
