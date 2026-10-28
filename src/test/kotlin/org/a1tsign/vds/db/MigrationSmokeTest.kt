package org.a1tsign.vds.db

import org.a1tsign.vds.support.AbstractDbTest
import org.a1tsign.vds.support.PostgresSupport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Proves the test harness itself works: Flyway applied cleanly against an empty
 * `db/migration` directory (today's state — task 4 adds the first real migration) and the shared
 * pool can actually run a query.
 */
class MigrationSmokeTest : AbstractDbTest() {
    @Test
    fun `flyway applied cleanly with no failed migrations`() {
        PostgresSupport.dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                val historyTableExists = statement.executeQuery(
                    """
                    SELECT EXISTS (
                        SELECT 1 FROM information_schema.tables
                        WHERE table_schema = 'public' AND table_name = 'flyway_schema_history'
                    )
                    """.trimIndent(),
                ).use { resultSet ->
                    resultSet.next()
                    resultSet.getBoolean(1)
                }
                assertTrue(historyTableExists, "flyway_schema_history must exist after runMigrations")

                val failedMigrationCount = statement.executeQuery(
                    "SELECT count(*) FROM flyway_schema_history WHERE success = false",
                ).use { resultSet ->
                    resultSet.next()
                    resultSet.getInt(1)
                }
                assertEquals(0, failedMigrationCount, "no migration row may be marked as failed")
            }
        }
    }

    @Test
    fun `select 1 works through the pool`() {
        PostgresSupport.dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                val result = statement.executeQuery("SELECT 1").use { resultSet ->
                    resultSet.next()
                    resultSet.getInt(1)
                }
                assertEquals(1, result)
            }
        }
    }
}
