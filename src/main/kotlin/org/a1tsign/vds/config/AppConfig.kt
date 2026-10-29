package org.a1tsign.vds.config

import java.net.URI

/**
 * Wraps a secret value so it never leaks through a `toString()` call (logs, exception messages,
 * accidental `println`). Use [reveal] only where the real value is actually needed (JDBC driver,
 * JWT signer, bcrypt).
 */
data class Secret(private val value: String) {
    override fun toString(): String = "***"

    fun reveal(): String = value
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

data class AppConfig(
    val db: DbConfig,
    val http: HttpConfig,
    val jwt: JwtConfig,
    val fx: FxConfig,
    // null when the bootstrap-admin variables are absent; task 7 does the actual creation.
    val bootstrapAdmin: BootstrapAdminConfig?,
)

/**
 * Thrown by [loadConfig] when the environment is misconfigured. The message names every
 * offending variable on one line, never a secret's value.
 */
class ConfigurationException(message: String) : Exception(message)

private const val DEFAULT_FX_API_URL = "https://open.er-api.com/v6/latest/USD"

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
    )
}

private fun isAbsoluteHttpUrl(value: String): Boolean {
    val uri = runCatching { URI(value) }.getOrNull() ?: return false
    return uri.isAbsolute && (uri.scheme == "http" || uri.scheme == "https")
}
