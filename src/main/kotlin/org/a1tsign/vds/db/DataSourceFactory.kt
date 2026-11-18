package org.a1tsign.vds.db

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.a1tsign.vds.config.DbConfig

/** Build the process-owned pool from [DbConfig]. The caller closes it at shutdown. */
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
