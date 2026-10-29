package org.a1tsign.vds.db

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.a1tsign.vds.config.DbConfig

/**
 * Builds the single HikariCP pool the process uses for its entire lifetime. Every tunable comes
 * from [DbConfig] (itself sourced from [org.a1tsign.vds.config.loadConfig]) — nothing here is
 * hardcoded beyond the pool's name. The pool is closed on shutdown, see `Application.kt`.
 */
fun buildDataSource(cfg: DbConfig): HikariDataSource {
    val hikariConfig = HikariConfig().apply {
        jdbcUrl = cfg.url
        username = cfg.user
        password = cfg.password.reveal()
        maximumPoolSize = cfg.poolMax
        minimumIdle = cfg.poolMinIdle
        connectionTimeout = cfg.connectionTimeoutMs
        leakDetectionThreshold = cfg.leakDetectionMs
        poolName = "vds-pool"
    }
    return HikariDataSource(hikariConfig)
}
