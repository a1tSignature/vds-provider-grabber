package org.a1tsign.vds.db

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.runBlocking
import org.a1tsign.vds.api.SortField
import org.a1tsign.vds.domain.SystemStatus
import org.a1tsign.vds.service.CatalogQueryService
import org.a1tsign.vds.support.PostgresSupport
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.v1.jdbc.Database
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **The headline criterion of vds-004.2**: `V2` must not empty the public catalog. The default
 * listing hides everything that is not `CONFIGURED`, so if the rows that already exist did not come
 * out `CONFIGURED`, every provider in a real database would silently disappear from
 * `GET /api/v1/providers` -- a one-way door for visibility, which is why this is a before/after
 * assertion on the real listing rather than "the column has a default".
 *
 * The shared harness applies **all** migrations when the container starts
 * (`support/PostgresSupport`), so this test owns its own schema on that same container: its own
 * Flyway with `target = 1` and `defaultSchema`, rows seeded through plain JDBC against the V1 shape
 * (no `system_status` column exists yet), then `migrate()` to latest and the real
 * `CatalogQueryService` over its own Exposed connection.
 */
class ProviderSystemStatusMigrationTest {

    @Test
    fun `migrating a V1 schema with existing providers leaves the default listing unchanged`() {
        withSchemaAtV1 { dataSource, database ->
            val seededIds = seedProviders(dataSource)
            // "Before": the V1 schema cannot be listed through the application (the column the
            // listing filters on does not exist yet), so the baseline is the same row set the
            // id-sorted default page returns -- every provider, in id order.
            assertEquals(3, seededIds.size)

            migrateToLatest(dataSource)

            val queryService = CatalogQueryService(database, ProviderRepository(), TariffRepository(), PriceRepository())
            val (total, content) = runBlocking {
                queryService.listProviders(ProviderListFilter(), page = 0, size = 50, sort = listOf(SortField("id", false)))
            }

            assertEquals(seededIds, content.map { it.id }, "the same ids, in the same order")
            assertEquals(seededIds.size.toLong(), total, "totalElements is unchanged by the migration")
            assertTrue(
                content.all { it.systemStatus == SystemStatus.CONFIGURED },
                "every pre-existing row must read CONFIGURED: ${content.map { it.slug to it.systemStatus }}",
            )
        }
    }

    // ---------- the isolated schema ----------

    private fun withSchemaAtV1(block: (HikariDataSource, Database) -> Unit) {
        dropSchema()
        val dataSource = HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = PostgresSupport.jdbcUrl
                username = PostgresSupport.username
                password = PostgresSupport.password
                maximumPoolSize = 2
                poolName = "vds-v2-migration-pool"
                // Every connection of this pool sees only the test's own schema, so the Exposed
                // queries below (unqualified table names) hit it and never the shared one.
                connectionInitSql = "SET search_path TO $SCHEMA"
            },
        )
        try {
            Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .schemas(SCHEMA)
                .defaultSchema(SCHEMA)
                .createSchemas(true)
                .cleanDisabled(true)
                .target(org.flywaydb.core.api.MigrationVersion.fromVersion("1"))
                .load()
                .migrate()
            block(dataSource, Database.connect(dataSource))
        } finally {
            dataSource.close()
            dropSchema()
        }
    }

    private fun migrateToLatest(dataSource: HikariDataSource) {
        val result = Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration")
            .schemas(SCHEMA)
            .defaultSchema(SCHEMA)
            .cleanDisabled(true)
            .load()
            .migrate()
        assertEquals(1, result.migrationsExecuted, "exactly V2 was pending")
    }

    /** Plain JDBC, V1 columns only -- a row written before `system_status` existed. */
    private fun seedProviders(dataSource: HikariDataSource): List<Long> =
        dataSource.connection.use { connection ->
            listOf("alpha-host" to "ACTIVE", "beta-host" to "INACTIVE", "gamma-host" to "UNKNOWN").map { (slug, status) ->
                connection.prepareStatement(
                    "INSERT INTO provider (slug, name, website_url, status) VALUES (?, ?, ?, ?) RETURNING id",
                ).use { statement ->
                    statement.setString(1, slug)
                    statement.setString(2, "Provider $slug")
                    statement.setString(3, "https://$slug.example")
                    statement.setString(4, status)
                    statement.executeQuery().use { rs ->
                        rs.next()
                        rs.getLong(1)
                    }
                }
            }
        }

    private fun dropSchema() = PostgresSupport.dataSource.connection.use { connection ->
        connection.createStatement().use { it.execute("DROP SCHEMA IF EXISTS $SCHEMA CASCADE") }
    }

    private companion object {
        const val SCHEMA = "vds_v2_migration_test"
    }
}
