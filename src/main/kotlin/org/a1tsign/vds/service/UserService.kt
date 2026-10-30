package org.a1tsign.vds.service

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.a1tsign.vds.api.ApiErrorDetail
import org.a1tsign.vds.api.ConflictException
import org.a1tsign.vds.api.NotFoundException
import org.a1tsign.vds.api.SortField
import org.a1tsign.vds.api.ValidationException
import org.a1tsign.vds.auth.PasswordHasher
import org.a1tsign.vds.config.nowUtc
import org.a1tsign.vds.db.UserRepository
import org.a1tsign.vds.db.UserWithRoles
import org.a1tsign.vds.domain.Role
import org.a1tsign.vds.domain.UserStatus
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.time.Clock

class UserService(private val database: Database, private val users: UserRepository, private val passwords: PasswordHasher, private val clock: Clock) {
    suspend fun create(username: String, password: String, email: String?, roles: Set<Role>): UserWithRoles = withContext(Dispatchers.IO) {
        passwords.passwordPolicyError(password)?.let { throw validation("password", it) }
        try {
            transaction(database) {
                val id = users.insertUser(username, email, passwords.hash(password), UserStatus.ACTIVE, clock.nowUtc())
                users.addRoles(id, roles)
                users.findById(id)!!
            }
        } catch (e: ExposedSQLException) {
            if (e.message.orEmpty().contains("uq_app_user_username")) throw ConflictException("Username already exists") else throw e
        }
    }

    suspend fun find(id: Long): UserWithRoles = withContext(Dispatchers.IO) {
        transaction(database) { users.findById(id) } ?: throw NotFoundException("User not found")
    }

    suspend fun findPage(page: Int, size: Int, sort: List<SortField>): Pair<Long, List<UserWithRoles>> = withContext(Dispatchers.IO) {
        transaction(database) { users.countUsers() to users.findPage(page, size, sort) }
    }

    suspend fun patch(id: Long, status: UserStatus?, roles: Set<Role>?): UserWithRoles = withContext(Dispatchers.IO) {
        transaction(database) {
            users.lockAdminUserMutations()
            val old = users.findById(id) ?: throw NotFoundException("User not found")
            val nextStatus = status ?: old.user.status
            val nextRoles = roles ?: old.roles
            val stillActiveAdmin = nextStatus == UserStatus.ACTIVE && Role.ADMIN in nextRoles
            if (!stillActiveAdmin && users.lockedCountActiveAdminsExcluding(id) == 0L) {
                throw ConflictException("At least one active ADMIN user must remain")
            }
            val revoke = status == UserStatus.DISABLED && old.user.status != UserStatus.DISABLED
            users.updateUser(id, nextStatus, nextRoles, clock.nowUtc(), revoke)
            users.findById(id)!!
        }
    }

    suspend fun changePassword(id: Long, password: String) = withContext(Dispatchers.IO) {
        passwords.passwordPolicyError(password)?.let { throw validation("password", it) }
        transaction(database) {
            if (users.findById(id) == null) throw NotFoundException("User not found")
            users.changePassword(id, passwords.hash(password), clock.nowUtc())
        }
    }

    private fun validation(field: String, message: String) = ValidationException(listOf(ApiErrorDetail(field, message)))
}
