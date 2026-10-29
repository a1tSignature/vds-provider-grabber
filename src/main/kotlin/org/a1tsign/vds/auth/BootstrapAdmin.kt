package org.a1tsign.vds.auth

import org.a1tsign.vds.config.BootstrapAdminConfig
import org.a1tsign.vds.db.UserRepository
import org.a1tsign.vds.domain.Role
import org.a1tsign.vds.domain.UserStatus
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.slf4j.LoggerFactory
import java.time.Instant

class BootstrapAdmin(
    private val database: Database,
    private val users: UserRepository,
    private val passwords: PasswordHasher,
) {
    fun ensure(config: BootstrapAdminConfig?, now: Instant) {
        if (config == null) {
            val isEmpty = transaction(database) { users.countUsers() == 0L }
            if (isEmpty) {
                logger.warn("No users exist; configure VDS_BOOTSTRAP_ADMIN_USERNAME and VDS_BOOTSTRAP_ADMIN_PASSWORD before using the API")
            }
            return
        }

        val created = transaction(database) {
            // This seeded row is a shared mutex across application instances. The count query is
            // deliberately a separate statement after the lock, so READ COMMITTED gives it a
            // fresh snapshot after any competing bootstrap transaction commits.
            users.lockBootstrapAdminRole()
            if (users.countUsers() != 0L) return@transaction false
            val passwordHash = passwords.hash(config.password.reveal())
            val userId = users.insertUser(config.username, null, passwordHash, UserStatus.ACTIVE, now)
            users.addRoles(userId, setOf(Role.ADMIN))
            true
        }
        if (created) logger.warn("Bootstrap admin '${config.username}' created; change its password before use")
    }

    private companion object {
        val logger = LoggerFactory.getLogger(BootstrapAdmin::class.java)
    }
}
