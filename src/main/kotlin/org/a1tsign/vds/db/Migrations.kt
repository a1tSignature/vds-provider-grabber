package org.a1tsign.vds.db

import org.flywaydb.core.Flyway
import org.slf4j.LoggerFactory
import javax.sql.DataSource

private val logger = LoggerFactory.getLogger("org.a1tsign.vds.db.Migrations")

/**
 * Applies every pending migration under `classpath:db/migration` before the HTTP server binds.
 * Works today against an empty `db/migration` directory (no schema yet — task 4 adds
 * `V1__core_schema.sql`) and against a populated one later, unchanged.
 *
 * `clean` is permanently disabled (never allow a wipe of a real database) and baselining is off
 * (every environment starts from `V1`, there is no pre-Flyway legacy schema to baseline against).
 * A failure throws [org.flywaydb.core.api.FlywayException]; the caller (`Application.kt`'s
 * `main()`) turns that into a one-line ERROR log and a non-zero exit, the same pattern already
 * used for [org.a1tsign.vds.config.ConfigurationException] — no retry/backoff here.
 */
fun runMigrations(dataSource: DataSource) {
    val flyway = Flyway.configure()
        .dataSource(dataSource)
        .locations("classpath:db/migration")
        .cleanDisabled(true)
        .baselineOnMigrate(false)
        .load()
    val result = flyway.migrate()
    logger.info("Applied {} database migration(s)", result.migrationsExecuted)
}
