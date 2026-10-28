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

/**
 * Owns the **one** PostgreSQL container every test in the suite shares for the whole JVM run.
 *
 * Deliberately not `@Container`/`@Testcontainers` per test class — that starts (and runs Flyway
 * against) one container per class, which multiplies both wall-clock time and the chance of port
 * exhaustion across dozens of test classes. Instead this `object` starts exactly one container,
 * lazily, on first use; it is never stopped explicitly — Testcontainers' Ryuk sidecar reaps it
 * when the JVM exits. Flyway and Exposed are wired exactly once, below.
 */
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

    /** Exposed connected once, on top of [dataSource]. */
    val database: Database by lazy { Database.connect(dataSource) }

    val jdbcUrl: String get() = container.jdbcUrl
    val username: String get() = container.username
    val password: String get() = container.password

    /**
     * Truncates every table the schema currently has, then re-applies any seed rows a migration
     * is relying on.
     *
     * Table list is **discovered** from `information_schema.tables` (schema `public`, excluding
     * `flyway_schema_history`) instead of hardcoded, because there is no schema yet — task 4
     * writes the first migration — and every later task that adds a table would otherwise have
     * to remember to edit this harness function too. Discovery makes that an automatic, "it just
     * works" property of the harness instead of a manual checklist item for 15 remaining tasks.
     * The trade-off: a table a test deliberately wants to survive resets (there is none planned
     * in this schema) would need an explicit exclusion added here later — acceptable, since the
     * one case this project does have (the `role`/seed rows the migration will insert) is handled
     * by re-seeding below rather than by skipping truncation.
     */
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
            reseedAfterTruncate(connection)
        }
    }

    private fun discoverTruncatableTables(connection: Connection): List<String> {
        val tables = mutableListOf<String>()
        connection.createStatement().use { statement ->
            statement.executeQuery(
                """
                SELECT table_name FROM information_schema.tables
                WHERE table_schema = 'public' AND table_type = 'BASE TABLE'
                  AND table_name <> 'flyway_schema_history'
                """.trimIndent(),
            ).use { resultSet ->
                while (resultSet.next()) {
                    tables += resultSet.getString("table_name")
                }
            }
        }
        return tables
    }

    /**
     * Restores rows a migration seeds that the truncate above just removed. `V1__core_schema.sql`
     * seeds the three `role` lookup rows (`ADMIN`/`EDITOR`/`VIEWER`) directly — not via a
     * repeatable migration, since the task that added them explicitly rules those out — so
     * `TRUNCATE ... CASCADE` wipes them on the very first [resetDatabase] call and Flyway never
     * re-applies an already-migrated version to bring them back. Re-insert exactly what `V1` seeds.
     */
    private fun reseedAfterTruncate(connection: Connection) {
        connection.createStatement().use { statement ->
            statement.execute(
                """
                INSERT INTO role (code, description) VALUES
                    ('ADMIN', 'Full access: manage providers, tariffs, prices and other users'),
                    ('EDITOR', 'Can create and edit providers, tariffs and prices'),
                    ('VIEWER', 'Read-only access to the catalog')
                """.trimIndent(),
            )
        }
    }
}

/**
 * A [Clock] whose instant can be set or advanced from a test, so time-dependent production code
 * (token expiry, price effective dates) is deterministic under test instead of racing the wall
 * clock. Always UTC, matching the "all timestamps UTC" rule the rest of the codebase follows.
 */
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

/**
 * Base class for every test that touches the database. Resets the shared schema before each test
 * method (so no test observes another test's rows) and hands out a controllable [clock]. Tests in
 * later tasks extend this instead of wiring [PostgresSupport] by hand.
 */
abstract class AbstractDbTest {
    val clock: MutableTestClock = MutableTestClock()

    @BeforeEach
    fun resetDatabaseBeforeEachTest() {
        PostgresSupport.resetDatabase()
    }
}
