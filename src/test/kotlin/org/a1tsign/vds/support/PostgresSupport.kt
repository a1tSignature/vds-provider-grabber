package org.a1tsign.vds.support

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.a1tsign.vds.db.runMigrations
import org.jetbrains.exposed.v1.jdbc.Database
import org.junit.jupiter.api.BeforeEach
import org.testcontainers.containers.PostgreSQLContainer
import java.sql.Connection
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicReference

/** Share one PostgreSQL container across the test JVM. Per-class Testcontainers lifecycle would stop it between classes. */
object PostgresSupport {
    // SELF = Nothing: this container never needs the fluent with*() builder methods (which
    // return SELF), only the plain start()/getJdbcUrl()/getUsername()/getPassword() inherited
    // from JdbcDatabaseContainer/GenericContainer, so the usual Kotlin self-type workaround for
    // Testcontainers' `PostgreSQLContainer<SELF extends PostgreSQLContainer<SELF>>` applies.
    private val container: PostgreSQLContainer<Nothing> by lazy {
        PostgreSQLContainer<Nothing>("postgres:17-alpine").apply { start() }
    }

    /** The single Hikari pool the whole test suite shares. Migrations have run by the time this
     *  is first observed (see initializer below). */
    val dataSource: HikariDataSource by lazy {
        val hikariConfig = HikariConfig().apply {
            jdbcUrl = container.jdbcUrl
            username = container.username
            password = container.password
            maximumPoolSize = 5
            minimumIdle = 1
            poolName = "vds-test-pool"
        }
        HikariDataSource(hikariConfig).also { runMigrations(it) }
    }

    val database: Database by lazy { Database.connect(dataSource) }

    val jdbcUrl: String get() = container.jdbcUrl
    val username: String get() = container.username
    val password: String get() = container.password

    /** Discover catalog tables dynamically so additions are cleared between tests. Preserve migration metadata and seeded reference data. */
    fun resetDatabase() {
        // Touch both lazies to guarantee the container/migrations/Exposed connection exist
        // before a test ever calls this (also exercised directly by MigrationSmokeTest).
        database
        dataSource.connection.use { connection ->
            val tables = discoverTruncatableTables(connection)
            if (tables.isNotEmpty()) {
                val tableList = tables.joinToString(", ") { "\"$it\"" }
                connection.createStatement().use { statement ->
                    statement.execute("TRUNCATE $tableList RESTART IDENTITY CASCADE")
                }
            }
        }
    }

    private fun discoverTruncatableTables(connection: Connection): List<String> {
        val tables = mutableListOf<String>()
        connection.createStatement().use { statement ->
            statement.executeQuery(
                """
                SELECT table_name FROM information_schema.tables
                WHERE table_schema = 'public' AND table_type = 'BASE TABLE'
                  AND table_name NOT IN ('flyway_schema_history', 'role')
                """.trimIndent(),
            ).use { resultSet ->
                while (resultSet.next()) {
                    tables += resultSet.getString("table_name")
                }
            }
        }
        return tables
    }
}

class MutableTestClock(
    initial: Instant = Instant.parse("2024-01-01T00:00:00Z"),
    private val zone: ZoneId = ZoneOffset.UTC,
) : Clock() {
    private val current = AtomicReference(initial)

    override fun getZone(): ZoneId = zone

    override fun withZone(zone: ZoneId): Clock = MutableTestClock(current.get(), zone)

    override fun instant(): Instant = current.get()

    fun set(instant: Instant) {
        current.set(instant)
    }

    fun advance(amount: Duration): Instant = current.updateAndGet { it.plus(amount) }
}

/** Reset the shared schema before each DB test so earlier writes cannot affect fixture expectations. */
abstract class AbstractDbTest {
    val clock: MutableTestClock = MutableTestClock()

    @BeforeEach
    fun resetDatabaseBeforeEachTest() {
        PostgresSupport.resetDatabase()
    }
}
