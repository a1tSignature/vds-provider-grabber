package org.a1tsign.vds.config

import org.a1tsign.vds.api.parsePlainDecimal
import java.math.BigDecimal
import java.net.URI
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

/**
 * The `VDS_CRAWLER_*` settings of the vds-002 crawler. Nothing in here is wired into the running
 * application yet -- task vds-002.7 owns that -- and with [enabled] `false` (the default) no
 * crawler component is constructed at all, so the service behaves exactly as vds-001 does.
 *
 * [username] and [password] can only be `null` while [enabled] is `false`: [loadConfig] turns a
 * missing credential into a startup failure as soon as the crawler is switched on. Every other
 * value is parsed and validated even when the crawler is disabled, so a typo is caught before
 * somebody enables it.
 */
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
    /** `false` is logged at WARN on startup by the component that reads it (task vds-002.7). */
    val respectRobots: Boolean,
    val maxBodyBytes: Long,
    /** In `[0, 1]`, always a [BigDecimal] -- `Double` must not appear in this value's path. */
    val maxOutOfStockRatio: BigDecimal,
    val dryRunDefault: Boolean,
    /**
     * Every `VDS_CRAWLER_ADAPTER_*` variable, for the composition root to hand each adapter its own
     * slice. Defaults to empty so a test building this object by hand needs no environment;
     * [loadConfig] always fills it, and `AppComponentsWiringTest` pins that production path.
     */
    val adapterEnv: AdapterEnv = AdapterEnv(emptyMap()),
)

data class AppConfig(
    val db: DbConfig,
    val http: HttpConfig,
    val jwt: JwtConfig,
    val fx: FxConfig,
    // null when the bootstrap-admin variables are absent; task 7 does the actual creation.
    val bootstrapAdmin: BootstrapAdminConfig?,
    val crawler: CrawlerConfig,
)

/**
 * Thrown by [loadConfig] when the environment is misconfigured. The message names every
 * offending variable on one line, never a secret's value.
 */
class ConfigurationException(message: String) : Exception(message)

private const val DEFAULT_FX_API_URL = "https://open.er-api.com/v6/latest/USD"

private const val DEFAULT_CRAWLER_API_BASE_URL = "http://127.0.0.1:8080"

/** The prefix of every per-adapter variable; see [crawlerAdapterConfig] for the per-id suffix. */
const val ADAPTER_ENV_PREFIX: String = "VDS_CRAWLER_ADAPTER_"

/** Product token of the crawler's `User-Agent`; the version and contact URL follow it. */
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

    fun positiveInt(name: String, default: Int): Int {
        val raw = env[name] ?: return default
        val parsed = raw.toIntOrNull()
        if (parsed == null || parsed <= 0) {
            errors += "$name must be a positive integer"
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

    fun positiveLong(name: String, default: Long): Long {
        val raw = env[name] ?: return default
        val parsed = raw.toLongOrNull()
        if (parsed == null || parsed <= 0) {
            errors += "$name must be a positive integer"
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

    fun booleanFlag(name: String, default: Boolean): Boolean {
        val raw = env[name] ?: return default
        return when (raw) {
            "true" -> true
            "false" -> false
            else -> {
                errors += "$name must be true or false"
                default
            }
        }
    }

    fun ratio(name: String, default: BigDecimal): BigDecimal {
        val raw = env[name] ?: return default
        // Same plain-decimal rule the money path on the wire uses (api/Serializers.kt's
        // parsePlainDecimal): no exponent, no leading '+', no grouping, no whitespace -- so this
        // value is a BigDecimal end to end and never goes through Double.
        val parsed = parsePlainDecimal(raw)
        if (parsed == null || parsed.signum() < 0 || parsed > BigDecimal.ONE) {
            errors += "$name must be a decimal number in plain notation between 0 and 1"
            return default
        }
        return parsed
    }

    // --- database ---
    val dbUrl = requiredNonBlank("VDS_DB_URL")
    val dbUser = requiredNonBlank("VDS_DB_USER")
    val dbPassword = requiredNonBlank("VDS_DB_PASSWORD")
    val dbPoolMax = positiveInt("VDS_DB_POOL_MAX", 5)
    val dbPoolMinIdle = nonNegativeInt("VDS_DB_POOL_MIN_IDLE", 1)
    val dbConnectionTimeoutMs = positiveLong("VDS_DB_CONNECTION_TIMEOUT_MS", 5000L)
    val dbLeakDetectionMs = nonNegativeLong("VDS_DB_LEAK_DETECTION_MS", 30000L)

    // --- http ---
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

    // --- jwt ---
    val jwtSecretRaw = requiredNonBlank("VDS_JWT_SECRET")
    if (jwtSecretRaw.isNotEmpty() && jwtSecretRaw.length < 32) {
        errors += "VDS_JWT_SECRET must be at least 32 characters"
    }
    val jwtTtlHours = positiveLong("VDS_JWT_TTL_HOURS", 24L)

    // --- fx ---
    val fxApiUrlRaw = env["VDS_FX_API_URL"] ?: DEFAULT_FX_API_URL
    if (!isAbsoluteHttpUrl(fxApiUrlRaw)) {
        errors += "VDS_FX_API_URL must be an absolute http or https URL"
    }
    val fxApiKeyRaw = optionalNonBlank("VDS_FX_API_KEY")
    val fxRefreshHours = positiveLong("VDS_FX_REFRESH_HOURS", 12L)

    // --- bootstrap admin ---
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

    // --- crawler (vds-002) ---
    val crawlerEnabled = booleanFlag("VDS_CRAWLER_ENABLED", false)
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
    // Taken whole, unvalidated: what a key means is the adapter's business, and loadConfig does not
    // know which ids exist. VDS_CRAWLER_ADAPTER_TIMEOUT_MINUTES shares the prefix and is copied in
    // too, which is harmless -- crawlerAdapterConfig filters by the full per-id prefix, so only an
    // adapter literally called `timeout` could ever see it.
    val crawlerAdapterEnv = AdapterEnv(env.filterKeys { it.startsWith(ADAPTER_ENV_PREFIX) })

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
    )
}

/**
 * The `VDS_CRAWLER_ADAPTER_<ID>_*` slice of [env] that belongs to the adapter [adapterId], in the
 * shape handed to an adapter through `CrawlContext.config`: `<ID>` is the adapter id upper-cased
 * with `-` replaced by `_`, and the map key is the remaining suffix, lower-cased —
 * `VDS_CRAWLER_ADAPTER_EXAMPLE_STATIC_FOO=bar` becomes `{"foo": "bar"}` for `example-static`.
 *
 * [env] is always a parameter, never the real process environment, so this mapping stays pure.
 *
 * Caveat an adapter author has to respect: the prefix is ambiguous between two ids whose
 * upper-cased forms nest (`a` and `a-b` — `VDS_CRAWLER_ADAPTER_A_B_FOO` belongs to both), which
 * the registry's id uniqueness does not rule out. Pick ids that do not nest.
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

// The host check is what separates a usable URL from a merely parseable one ("http://" is an
// absolute http URI with no host at all, and used to pass startup validation) -- the same rule as
// `api/Validation.kt` and `crawler/SnapshotValidator.kt`. It applies to every caller here:
// VDS_FX_API_URL, VDS_CRAWLER_API_BASE_URL and VDS_CRAWLER_CONTACT_URL.
private fun isAbsoluteHttpUrl(value: String): Boolean {
    val uri = runCatching { URI(value) }.getOrNull() ?: return false
    return uri.isAbsolute && (uri.scheme == "http" || uri.scheme == "https") && !uri.host.isNullOrEmpty()
}
