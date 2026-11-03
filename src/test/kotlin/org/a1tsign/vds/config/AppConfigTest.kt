package org.a1tsign.vds.config

import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AppConfigTest {
    /** Every variable set to a deliberately non-default, valid value. */
    private fun fullValidEnv(): Map<String, String> = mapOf(
        "VDS_DB_URL" to "jdbc:postgresql://localhost:5432/vds",
        "VDS_DB_USER" to "vds",
        "VDS_DB_PASSWORD" to "db-secret",
        "VDS_DB_POOL_MAX" to "10",
        "VDS_DB_POOL_MIN_IDLE" to "2",
        "VDS_DB_CONNECTION_TIMEOUT_MS" to "7000",
        "VDS_DB_LEAK_DETECTION_MS" to "15000",
        "VDS_HTTP_PORT" to "9090",
        "VDS_JWT_SECRET" to "0".repeat(32),
        "VDS_JWT_TTL_HOURS" to "48",
        "VDS_FX_API_URL" to "https://fx.example.com/latest",
        "VDS_FX_API_KEY" to "fx-api-key",
        "VDS_FX_REFRESH_HOURS" to "6",
        "VDS_BOOTSTRAP_ADMIN_USERNAME" to "admin",
        "VDS_BOOTSTRAP_ADMIN_PASSWORD" to "change-me-please",
        "VDS_CORS_ALLOWED_ORIGINS" to "https://a.example, https://b.example",
    )

    /** The minimal set of variables that have no default: required and nothing else. */
    private fun minimalRequiredEnv(): Map<String, String> = mapOf(
        "VDS_DB_URL" to "jdbc:postgresql://localhost:5432/vds",
        "VDS_DB_USER" to "vds",
        "VDS_DB_PASSWORD" to "db-secret",
        "VDS_JWT_SECRET" to "0".repeat(32),
    )

    @Test
    fun `loads every field from a fully specified valid environment`() {
        val config = loadConfig(fullValidEnv())

        assertEquals("jdbc:postgresql://localhost:5432/vds", config.db.url)
        assertEquals("vds", config.db.user)
        assertEquals("db-secret", config.db.password.reveal())
        assertEquals(10, config.db.poolMax)
        assertEquals(2, config.db.poolMinIdle)
        assertEquals(7000L, config.db.connectionTimeoutMs)
        assertEquals(15000L, config.db.leakDetectionMs)

        assertEquals(9090, config.http.port)
        assertEquals(listOf("https://a.example", "https://b.example"), config.http.corsAllowedOrigins)

        assertEquals("0".repeat(32), config.jwt.secret.reveal())
        assertEquals(48L, config.jwt.ttlHours)

        assertEquals("https://fx.example.com/latest", config.fx.apiUrl)
        assertEquals("fx-api-key", config.fx.apiKey?.reveal())
        assertEquals(6L, config.fx.refreshHours)

        assertEquals("admin", config.bootstrapAdmin?.username)
        assertEquals("change-me-please", config.bootstrapAdmin?.password?.reveal())
    }

    @Test
    fun `applies documented defaults when optional variables are absent`() {
        val config = loadConfig(minimalRequiredEnv())

        assertEquals(5, config.db.poolMax)
        assertEquals(1, config.db.poolMinIdle)
        assertEquals(5000L, config.db.connectionTimeoutMs)
        assertEquals(30000L, config.db.leakDetectionMs)

        assertEquals(8080, config.http.port)
        assertEquals(emptyList(), config.http.corsAllowedOrigins)

        assertEquals(24L, config.jwt.ttlHours)

        assertEquals("https://open.er-api.com/v6/latest/USD", config.fx.apiUrl)
        assertNull(config.fx.apiKey)
        assertEquals(12L, config.fx.refreshHours)

        assertNull(config.bootstrapAdmin)
    }

    @Test
    fun `missing VDS_DB_URL fails fast naming the variable`() {
        val env = minimalRequiredEnv() - "VDS_DB_URL"

        val exception = assertFailsWith<ConfigurationException> { loadConfig(env) }

        assertEquals("Invalid configuration: VDS_DB_URL is required", exception.message)
    }

    @Test
    fun `blank VDS_DB_PASSWORD is treated as missing`() {
        val env = minimalRequiredEnv() + ("VDS_DB_PASSWORD" to "")

        val exception = assertFailsWith<ConfigurationException> { loadConfig(env) }

        assertEquals("Invalid configuration: VDS_DB_PASSWORD is required", exception.message)
    }

    @Test
    fun `VDS_JWT_SECRET of 31 characters fails`() {
        val env = minimalRequiredEnv() + ("VDS_JWT_SECRET" to "0".repeat(31))

        val exception = assertFailsWith<ConfigurationException> { loadConfig(env) }

        assertEquals("Invalid configuration: VDS_JWT_SECRET must be at least 32 characters", exception.message)
    }

    @Test
    fun `VDS_JWT_SECRET of exactly 32 characters succeeds`() {
        val env = minimalRequiredEnv() + ("VDS_JWT_SECRET" to "0".repeat(32))

        val config = loadConfig(env)

        assertEquals(32, config.jwt.secret.reveal().length)
    }

    @Test
    fun `several violations at once are all named on one line`() {
        val env = minimalRequiredEnv() - "VDS_DB_URL" + ("VDS_JWT_SECRET" to "short")

        val exception = assertFailsWith<ConfigurationException> { loadConfig(env) }

        assertEquals(
            "Invalid configuration: VDS_DB_URL is required; VDS_JWT_SECRET must be at least 32 characters",
            exception.message,
        )
    }

    @Test
    fun `VDS_BOOTSTRAP_ADMIN_PASSWORD of 11 characters fails`() {
        val env = minimalRequiredEnv() +
            ("VDS_BOOTSTRAP_ADMIN_USERNAME" to "admin") +
            ("VDS_BOOTSTRAP_ADMIN_PASSWORD" to "a".repeat(11))

        val exception = assertFailsWith<ConfigurationException> { loadConfig(env) }

        assertEquals(
            "Invalid configuration: VDS_BOOTSTRAP_ADMIN_PASSWORD must be at least 12 characters",
            exception.message,
        )
    }

    @Test
    fun `VDS_BOOTSTRAP_ADMIN_PASSWORD of 73 UTF-8 bytes fails`() {
        val password = "a".repeat(73)
        assertEquals(73, password.toByteArray(Charsets.UTF_8).size)
        val env = minimalRequiredEnv() +
            ("VDS_BOOTSTRAP_ADMIN_USERNAME" to "admin") +
            ("VDS_BOOTSTRAP_ADMIN_PASSWORD" to password)

        val exception = assertFailsWith<ConfigurationException> { loadConfig(env) }

        assertEquals(
            "Invalid configuration: VDS_BOOTSTRAP_ADMIN_PASSWORD must be at most 72 bytes in UTF-8",
            exception.message,
        )
    }

    @Test
    fun `a valid 12-character bootstrap admin password succeeds`() {
        val env = minimalRequiredEnv() +
            ("VDS_BOOTSTRAP_ADMIN_USERNAME" to "admin") +
            ("VDS_BOOTSTRAP_ADMIN_PASSWORD" to "123456789012")

        val config = loadConfig(env)

        assertEquals("admin", config.bootstrapAdmin?.username)
        assertEquals("123456789012", config.bootstrapAdmin?.password?.reveal())
    }

    @Test
    fun `bootstrap admin username without a password fails`() {
        val env = minimalRequiredEnv() + ("VDS_BOOTSTRAP_ADMIN_USERNAME" to "admin")

        val exception = assertFailsWith<ConfigurationException> { loadConfig(env) }

        assertEquals(
            "Invalid configuration: VDS_BOOTSTRAP_ADMIN_PASSWORD is required when VDS_BOOTSTRAP_ADMIN_USERNAME is set",
            exception.message,
        )
    }

    @Test
    fun `bootstrap admin password without a username fails`() {
        val env = minimalRequiredEnv() + ("VDS_BOOTSTRAP_ADMIN_PASSWORD" to "123456789012")

        val exception = assertFailsWith<ConfigurationException> { loadConfig(env) }

        assertEquals(
            "Invalid configuration: VDS_BOOTSTRAP_ADMIN_USERNAME is required when VDS_BOOTSTRAP_ADMIN_PASSWORD is set",
            exception.message,
        )
    }

    @Test
    fun `non-numeric VDS_HTTP_PORT fails`() {
        val env = minimalRequiredEnv() + ("VDS_HTTP_PORT" to "not-a-number")

        val exception = assertFailsWith<ConfigurationException> { loadConfig(env) }

        assertEquals(
            "Invalid configuration: VDS_HTTP_PORT must be an integer between 1 and 65535",
            exception.message,
        )
    }

    @Test
    fun `out-of-range VDS_HTTP_PORT fails`() {
        val env = minimalRequiredEnv() + ("VDS_HTTP_PORT" to "70000")

        val exception = assertFailsWith<ConfigurationException> { loadConfig(env) }

        assertEquals(
            "Invalid configuration: VDS_HTTP_PORT must be an integer between 1 and 65535",
            exception.message,
        )
    }

    @Test
    fun `VDS_HTTP_PORT of zero fails as out of range`() {
        val env = minimalRequiredEnv() + ("VDS_HTTP_PORT" to "0")

        val exception = assertFailsWith<ConfigurationException> { loadConfig(env) }

        assertEquals(
            "Invalid configuration: VDS_HTTP_PORT must be an integer between 1 and 65535",
            exception.message,
        )
    }

    @Test
    fun `toString of the loaded config never reveals the password or the jwt secret`() {
        val config = loadConfig(fullValidEnv())

        val rendered = config.toString()

        assertFalse(rendered.contains("db-secret"))
        assertFalse(rendered.contains("0".repeat(32)))
        assertFalse(rendered.contains("fx-api-key"))
        assertFalse(rendered.contains("change-me-please"))
        assertTrue(rendered.contains("***"))
    }

    @Test
    fun `non-positive VDS_DB_POOL_MAX fails`() {
        val env = minimalRequiredEnv() + ("VDS_DB_POOL_MAX" to "0")

        val exception = assertFailsWith<ConfigurationException> { loadConfig(env) }

        assertEquals(
            "Invalid configuration: VDS_DB_POOL_MAX must be a positive integer",
            exception.message,
        )
    }

    @Test
    fun `negative VDS_DB_POOL_MIN_IDLE fails`() {
        val env = minimalRequiredEnv() + ("VDS_DB_POOL_MIN_IDLE" to "-1")

        val exception = assertFailsWith<ConfigurationException> { loadConfig(env) }

        assertEquals(
            "Invalid configuration: VDS_DB_POOL_MIN_IDLE must be zero or greater",
            exception.message,
        )
    }

    @Test
    fun `zero VDS_DB_POOL_MIN_IDLE succeeds`() {
        val env = minimalRequiredEnv() + ("VDS_DB_POOL_MIN_IDLE" to "0")

        val config = loadConfig(env)

        assertEquals(0, config.db.poolMinIdle)
    }

    @Test
    fun `non-positive VDS_DB_CONNECTION_TIMEOUT_MS fails`() {
        val env = minimalRequiredEnv() + ("VDS_DB_CONNECTION_TIMEOUT_MS" to "0")

        val exception = assertFailsWith<ConfigurationException> { loadConfig(env) }

        assertEquals(
            "Invalid configuration: VDS_DB_CONNECTION_TIMEOUT_MS must be a positive integer",
            exception.message,
        )
    }

    @Test
    fun `negative VDS_DB_LEAK_DETECTION_MS fails`() {
        val env = minimalRequiredEnv() + ("VDS_DB_LEAK_DETECTION_MS" to "-5")

        val exception = assertFailsWith<ConfigurationException> { loadConfig(env) }

        assertEquals(
            "Invalid configuration: VDS_DB_LEAK_DETECTION_MS must be zero or greater",
            exception.message,
        )
    }

    @Test
    fun `zero VDS_DB_LEAK_DETECTION_MS succeeds`() {
        val env = minimalRequiredEnv() + ("VDS_DB_LEAK_DETECTION_MS" to "0")

        val config = loadConfig(env)

        assertEquals(0L, config.db.leakDetectionMs)
    }

    @Test
    fun `non-positive VDS_JWT_TTL_HOURS fails`() {
        val env = minimalRequiredEnv() + ("VDS_JWT_TTL_HOURS" to "-1")

        val exception = assertFailsWith<ConfigurationException> { loadConfig(env) }

        assertEquals(
            "Invalid configuration: VDS_JWT_TTL_HOURS must be a positive integer",
            exception.message,
        )
    }

    @Test
    fun `non-absolute VDS_FX_API_URL fails`() {
        val env = minimalRequiredEnv() + ("VDS_FX_API_URL" to "not-a-url")

        val exception = assertFailsWith<ConfigurationException> { loadConfig(env) }

        assertEquals(
            "Invalid configuration: VDS_FX_API_URL must be an absolute http or https URL",
            exception.message,
        )
    }

    @Test
    fun `non-http VDS_FX_API_URL fails`() {
        val env = minimalRequiredEnv() + ("VDS_FX_API_URL" to "ftp://example.com/latest")

        val exception = assertFailsWith<ConfigurationException> { loadConfig(env) }

        assertEquals(
            "Invalid configuration: VDS_FX_API_URL must be an absolute http or https URL",
            exception.message,
        )
    }

    @Test
    fun `non-positive VDS_FX_REFRESH_HOURS fails`() {
        val env = minimalRequiredEnv() + ("VDS_FX_REFRESH_HOURS" to "0")

        val exception = assertFailsWith<ConfigurationException> { loadConfig(env) }

        assertEquals(
            "Invalid configuration: VDS_FX_REFRESH_HOURS must be a positive integer",
            exception.message,
        )
    }

    @Test
    fun `VDS_CORS_ALLOWED_ORIGINS defaults to an empty list when absent`() {
        val config = loadConfig(minimalRequiredEnv())

        assertEquals(emptyList(), config.http.corsAllowedOrigins)
    }

    @Test
    fun `VDS_CORS_ALLOWED_ORIGINS parses a trimmed comma-separated list`() {
        val env = minimalRequiredEnv() + ("VDS_CORS_ALLOWED_ORIGINS" to " https://a.example ,https://b.example")

        val config = loadConfig(env)

        assertEquals(listOf("https://a.example", "https://b.example"), config.http.corsAllowedOrigins)
    }

    // --- crawler (vds-002.1) ---

    private fun assertCrawlerFailure(variable: String, value: String, expectedMessage: String) {
        val exception = assertFailsWith<ConfigurationException> {
            loadConfig(minimalRequiredEnv() + (variable to value))
        }

        assertEquals("Invalid configuration: $expectedMessage", exception.message)
    }

    @Test
    fun `crawler is disabled with every documented default when no VDS_CRAWLER variable is set`() {
        val crawler = loadConfig(minimalRequiredEnv()).crawler

        assertFalse(crawler.enabled)
        assertEquals("http://127.0.0.1:8080", crawler.apiBaseUrl)
        assertNull(crawler.username)
        assertNull(crawler.password)
        assertEquals(24L, crawler.intervalHours)
        assertEquals(10L, crawler.initialDelayMinutes)
        assertEquals(emptyList(), crawler.adapters)
        assertEquals(1, crawler.maxConcurrentAdapters)
        assertEquals(5L, crawler.adapterTimeoutMinutes)
        assertEquals(30L, crawler.runTimeoutMinutes)
        assertEquals(2000L, crawler.hostDelayMs)
        assertEquals("$CRAWLER_USER_AGENT_PRODUCT/${appVersion()}", crawler.userAgent)
        // The packaged version.properties must actually reach the User-Agent, or a crawled site
        // sees "vds-provider-grabber/unknown" and cannot tell which build called it.
        assertFalse(crawler.userAgent.endsWith("/unknown"), crawler.userAgent)
        assertNull(crawler.contactUrl)
        assertTrue(crawler.respectRobots)
        assertEquals(2097152L, crawler.maxBodyBytes)
        assertEquals(0, BigDecimal("0.5").compareTo(crawler.maxOutOfStockRatio))
        assertFalse(crawler.dryRunDefault)
    }

    @Test
    fun `an explicitly disabled crawler needs no credentials`() {
        val config = loadConfig(minimalRequiredEnv() + ("VDS_CRAWLER_ENABLED" to "false"))

        assertFalse(config.crawler.enabled)
        assertNull(config.crawler.username)
    }

    @Test
    fun `an enabled crawler without credentials fails naming both variables`() {
        val exception = assertFailsWith<ConfigurationException> {
            loadConfig(minimalRequiredEnv() + ("VDS_CRAWLER_ENABLED" to "true"))
        }

        assertEquals(
            "Invalid configuration: VDS_CRAWLER_USERNAME is required when VDS_CRAWLER_ENABLED is true; " +
                "VDS_CRAWLER_PASSWORD is required when VDS_CRAWLER_ENABLED is true",
            exception.message,
        )
    }

    @Test
    fun `an enabled crawler missing only the username fails without echoing the password`() {
        val env = minimalRequiredEnv() +
            ("VDS_CRAWLER_ENABLED" to "true") +
            ("VDS_CRAWLER_PASSWORD" to "crawler-secret")

        val exception = assertFailsWith<ConfigurationException> { loadConfig(env) }

        assertEquals(
            "Invalid configuration: VDS_CRAWLER_USERNAME is required when VDS_CRAWLER_ENABLED is true",
            exception.message,
        )
        assertFalse(exception.message!!.contains("crawler-secret"))
    }

    @Test
    fun `an enabled crawler with credentials loads and never renders the password`() {
        val env = minimalRequiredEnv() +
            ("VDS_CRAWLER_ENABLED" to "true") +
            ("VDS_CRAWLER_USERNAME" to "crawler") +
            ("VDS_CRAWLER_PASSWORD" to "crawler-secret")

        val config = loadConfig(env)

        assertTrue(config.crawler.enabled)
        assertEquals("crawler", config.crawler.username)
        assertEquals("crawler-secret", config.crawler.password?.reveal())
        assertFalse(config.toString().contains("crawler-secret"))
    }

    @Test
    fun `non-absolute VDS_CRAWLER_API_BASE_URL fails`() {
        assertCrawlerFailure(
            "VDS_CRAWLER_API_BASE_URL",
            "/relative",
            "VDS_CRAWLER_API_BASE_URL must be an absolute http or https URL",
        )
    }

    @Test
    fun `a URL with a scheme but no host fails for every URL setting`() {
        // "http://" parses as an absolute http URI whose host is null: parseable, unusable, and it
        // used to pass startup validation and only break once the first admin call was issued.
        // One test for all three, because they share `isAbsoluteHttpUrl`.
        assertCrawlerFailure("VDS_FX_API_URL", "http://", "VDS_FX_API_URL must be an absolute http or https URL")
        assertCrawlerFailure(
            "VDS_CRAWLER_API_BASE_URL",
            "http://",
            "VDS_CRAWLER_API_BASE_URL must be an absolute http or https URL",
        )
        assertCrawlerFailure(
            "VDS_CRAWLER_CONTACT_URL",
            "https://",
            "VDS_CRAWLER_CONTACT_URL must be an absolute http or https URL",
        )
    }

    @Test
    fun `zero VDS_CRAWLER_INTERVAL_HOURS fails`() {
        assertCrawlerFailure(
            "VDS_CRAWLER_INTERVAL_HOURS",
            "0",
            "VDS_CRAWLER_INTERVAL_HOURS must be a positive integer",
        )
    }

    @Test
    fun `negative VDS_CRAWLER_HOST_DELAY_MS fails`() {
        assertCrawlerFailure(
            "VDS_CRAWLER_HOST_DELAY_MS",
            "-1",
            "VDS_CRAWLER_HOST_DELAY_MS must be a positive integer",
        )
    }

    @Test
    fun `VDS_CRAWLER_MAX_OUT_OF_STOCK_RATIO above one fails`() {
        assertCrawlerFailure(
            "VDS_CRAWLER_MAX_OUT_OF_STOCK_RATIO",
            "1.5",
            "VDS_CRAWLER_MAX_OUT_OF_STOCK_RATIO must be a decimal number in plain notation between 0 and 1",
        )
    }

    @Test
    fun `VDS_CRAWLER_MAX_OUT_OF_STOCK_RATIO in exponent notation fails`() {
        assertCrawlerFailure(
            "VDS_CRAWLER_MAX_OUT_OF_STOCK_RATIO",
            "1e-1",
            "VDS_CRAWLER_MAX_OUT_OF_STOCK_RATIO must be a decimal number in plain notation between 0 and 1",
        )
    }

    @Test
    fun `a non-boolean VDS_CRAWLER_ENABLED fails`() {
        assertCrawlerFailure("VDS_CRAWLER_ENABLED", "yes", "VDS_CRAWLER_ENABLED must be true or false")
    }

    @Test
    fun `the ratio bounds zero and one are both accepted`() {
        val lower = loadConfig(minimalRequiredEnv() + ("VDS_CRAWLER_MAX_OUT_OF_STOCK_RATIO" to "0"))
        val upper = loadConfig(minimalRequiredEnv() + ("VDS_CRAWLER_MAX_OUT_OF_STOCK_RATIO" to "1"))

        assertEquals(0, BigDecimal.ZERO.compareTo(lower.crawler.maxOutOfStockRatio))
        assertEquals(0, BigDecimal.ONE.compareTo(upper.crawler.maxOutOfStockRatio))
    }

    @Test
    fun `crawler violations join the existing accumulated message instead of short-circuiting it`() {
        val env = minimalRequiredEnv() - "VDS_DB_URL" +
            ("VDS_CRAWLER_ENABLED" to "true") +
            ("VDS_CRAWLER_USERNAME" to "crawler") +
            ("VDS_CRAWLER_PASSWORD" to "crawler-secret") +
            ("VDS_CRAWLER_INTERVAL_HOURS" to "0") +
            ("VDS_CRAWLER_CONTACT_URL" to "/relative") +
            ("VDS_CRAWLER_MAX_OUT_OF_STOCK_RATIO" to "2")

        val exception = assertFailsWith<ConfigurationException> { loadConfig(env) }

        assertEquals(
            "Invalid configuration: VDS_DB_URL is required; " +
                "VDS_CRAWLER_INTERVAL_HOURS must be a positive integer; " +
                "VDS_CRAWLER_CONTACT_URL must be an absolute http or https URL; " +
                "VDS_CRAWLER_MAX_OUT_OF_STOCK_RATIO must be a decimal number in plain notation between 0 and 1",
            exception.message,
        )
        assertFalse(exception.message!!.contains("crawler-secret"))
    }

    @Test
    fun `VDS_CRAWLER_ADAPTERS is trimmed and blank entries are dropped`() {
        val config = loadConfig(minimalRequiredEnv() + ("VDS_CRAWLER_ADAPTERS" to " a , , b "))

        assertEquals(listOf("a", "b"), config.crawler.adapters)
    }

    @Test
    fun `the default user agent carries the contact URL when one is configured`() {
        val config = loadConfig(
            minimalRequiredEnv() + ("VDS_CRAWLER_CONTACT_URL" to "https://bot.example/info"),
        )

        assertEquals("https://bot.example/info", config.crawler.contactUrl)
        assertEquals(
            "$CRAWLER_USER_AGENT_PRODUCT/${appVersion()} (+https://bot.example/info)",
            config.crawler.userAgent,
        )
    }

    @Test
    fun `an explicit VDS_CRAWLER_USER_AGENT wins over the default`() {
        val config = loadConfig(minimalRequiredEnv() + ("VDS_CRAWLER_USER_AGENT" to "custom-agent/9"))

        assertEquals("custom-agent/9", config.crawler.userAgent)
    }

    @Test
    fun `an adapter config map takes only its own VDS_CRAWLER_ADAPTER prefix, lower-cased`() {
        val env = mapOf(
            "VDS_CRAWLER_ADAPTER_EXAMPLE_STATIC_FOO" to "bar",
            "VDS_CRAWLER_ADAPTER_EXAMPLE_STATIC_BASE_URL" to "https://example.test",
            "VDS_CRAWLER_ADAPTER_EXAMPLE_STATIC_" to "ignored, empty key",
            "VDS_CRAWLER_ADAPTER_OTHER_FOO" to "not mine",
            "VDS_CRAWLER_HOST_DELAY_MS" to "1000",
        )

        assertEquals(
            mapOf("foo" to "bar", "base_url" to "https://example.test"),
            crawlerAdapterConfig("example-static", env),
        )
        assertEquals(emptyMap(), crawlerAdapterConfig("unconfigured", env))
    }
}
