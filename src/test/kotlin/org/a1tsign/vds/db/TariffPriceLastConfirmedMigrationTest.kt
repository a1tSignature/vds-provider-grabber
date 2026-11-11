package org.a1tsign.vds.db

import org.a1tsign.vds.support.PostgresSupport
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.MigrationVersion
import java.sql.Connection
import java.sql.SQLException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The shared harness migrates to latest when the container starts, so this test owns a schema of
 * its own on that container to seed a price row before `V3` exists.
 */
class TariffPriceLastConfirmedMigrationTest {

    @Test
    fun `a price row written before V3 comes out confirmed at its created_at, and the column rejects NULL`() {
        PostgresSupport.dataSource.connection.use { connection ->
            connection.execute("DROP SCHEMA IF EXISTS $SCHEMA CASCADE")
            try {
                flyway(target = "2").migrate()
                connection.execute("SET search_path TO $SCHEMA")
                connection.execute(
                    """
                    INSERT INTO provider (slug, name, website_url, status) VALUES ('old-host', 'Old', 'https://old.example', 'ACTIVE');
                    INSERT INTO tariff (provider_id, name, vcpu_count, ram_mb, disk_gb, disk_type, virtualization)
                        SELECT id, 'Basic', 1, 1024, 20, 'SSD', 'KVM' FROM provider;
                    INSERT INTO tariff_price (tariff_id, billing_period, amount, currency, effective_from, data_source, created_at)
                        SELECT id, 'MONTHLY', 5, 'USD', '2024-01-01T00:00:00Z', 'MANUAL', '2024-03-05T10:15:30Z' FROM tariff;
                    """.trimIndent(),
                )

                flyway(target = null).migrate()

                connection.createStatement().use { statement ->
                    statement.executeQuery("SELECT last_confirmed_at = created_at, created_at = '2024-03-05T10:15:30Z' FROM tariff_price").use { rs ->
                        rs.next()
                        assertEquals(true to true, rs.getBoolean(1) to rs.getBoolean(2))
                    }
                }
                assertFailsWith<SQLException> { connection.execute("UPDATE tariff_price SET last_confirmed_at = NULL") }
            } finally {
                connection.execute("SET search_path TO DEFAULT")
                connection.execute("DROP SCHEMA IF EXISTS $SCHEMA CASCADE")
            }
        }
    }

    private fun flyway(target: String?): Flyway = Flyway.configure()
        .dataSource(PostgresSupport.dataSource)
        .locations("classpath:db/migration")
        .schemas(SCHEMA)
        .defaultSchema(SCHEMA)
        .createSchemas(true)
        .apply { if (target != null) target(MigrationVersion.fromVersion(target)) }
        .load()

    private fun Connection.execute(sql: String) = createStatement().use { it.execute(sql) }

    private companion object {
        const val SCHEMA = "vds_v3_migration_test"
    }
}
