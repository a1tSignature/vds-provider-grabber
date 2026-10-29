package org.a1tsign.vds.support

import org.a1tsign.vds.auth.PasswordHasher
import org.a1tsign.vds.db.UserRepository
import org.a1tsign.vds.db.UserWithRoles
import org.a1tsign.vds.domain.Role
import org.a1tsign.vds.domain.UserStatus
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.time.Instant

class UserFixture(
    private val database: Database,
    private val users: UserRepository,
    private val passwords: PasswordHasher,
) {
    fun create(
        username: String,
        password: String,
        roles: Set<Role>,
        status: UserStatus = UserStatus.ACTIVE,
        at: Instant,
    ): UserWithRoles = transaction(database) {
        val id = users.insertUser(username, null, passwords.hash(password), status, at)
        users.addRoles(id, roles)
        users.findById(id) ?: error("Inserted fixture user $username could not be loaded")
    }
}
