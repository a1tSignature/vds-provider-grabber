package org.a1tsign.vds.db

import org.a1tsign.vds.api.SortField
import org.a1tsign.vds.config.toUtcOffset
import org.a1tsign.vds.domain.AppUser
import org.a1tsign.vds.domain.Role
import org.a1tsign.vds.domain.UserStatus
import org.a1tsign.vds.domain.parseDbEnum
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.plus
import org.jetbrains.exposed.v1.core.vendors.ForUpdateOption
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Instant

data class UserWithRoles(val user: AppUser, val roles: Set<Role>)
data class AuthUserState(val userId: Long, val username: String, val status: UserStatus, val tokenVersion: Int, val roles: Set<Role>)

/** Repository calls are made only inside a service-owned Exposed transaction. */
class UserRepository {
    /** Serializes the startup-only bootstrap decision across application instances. */
    fun lockBootstrapAdminRole() {
        RoleTable.selectAll().where { RoleTable.code eq Role.ADMIN.name }
            .forUpdate(ForUpdateOption.ForUpdate).firstOrNull()
            ?: error("Seeded ADMIN role is missing")
    }

    /** Serializes all user PATCH state reads and mutations, including partial updates. */
    fun lockAdminUserMutations() {
        RoleTable.selectAll().where { RoleTable.code eq Role.ADMIN.name }
            .forUpdate(ForUpdateOption.ForUpdate).firstOrNull()
            ?: error("Seeded ADMIN role is missing")
    }

    fun findByUsername(username: String): UserWithRoles? = userJoin()
        .selectAll().where { AppUserTable.username eq username }.toList().toUserWithRoles()

    fun loadForAuth(userId: Long): AuthUserState? {
        val rows = userJoin().selectAll().where { AppUserTable.id eq userId }.toList()
        val first = rows.firstOrNull() ?: return null
        val user = first.toAppUser()
        return AuthUserState(
            user.id, user.username, user.status, user.tokenVersion, userJoinRoles(rows),
        )
    }

    fun touchLastLogin(userId: Long, at: Instant) {
        AppUserTable.update({ AppUserTable.id eq userId }) {
            it[lastLoginAt] = at.toUtcOffset()
            it[updatedAt] = at.toUtcOffset()
        }
    }

    fun insertUser(
        username: String,
        email: String?,
        passwordHash: String,
        status: UserStatus,
        now: Instant,
    ): Long = AppUserTable.insert {
        it[AppUserTable.username] = username
        it[AppUserTable.email] = email
        it[AppUserTable.passwordHash] = passwordHash
        it[AppUserTable.status] = status.name
        it[AppUserTable.tokenVersion] = 0
        it[AppUserTable.createdAt] = now.toUtcOffset()
        it[AppUserTable.updatedAt] = now.toUtcOffset()
        it[AppUserTable.lastLoginAt] = null
    }[AppUserTable.id]

    fun addRoles(userId: Long, roles: Set<Role>) {
        val roleIds = RoleTable.selectAll().where { RoleTable.code inList roles.map { it.name } }
            .associate { parseDbEnum<Role>("role", "code", it[RoleTable.code]) to it[RoleTable.id] }
        roles.forEach { role ->
            val roleId = roleIds[role] ?: error("Missing seeded role ${role.name}")
            UserRoleTable.insert {
                it[UserRoleTable.userId] = userId
                it[UserRoleTable.roleId] = roleId
            }
        }
    }

    fun countActiveAdmins(): Long = AppUserTable.join(UserRoleTable, JoinType.INNER, AppUserTable.id, UserRoleTable.userId)
        .join(RoleTable, JoinType.INNER, UserRoleTable.roleId, RoleTable.id)
        .selectAll().where {
            (AppUserTable.status eq UserStatus.ACTIVE.name) and (RoleTable.code eq Role.ADMIN.name)
        }.count()

    fun countUsers(): Long = AppUserTable.selectAll().count()

    fun findById(userId: Long): UserWithRoles? = userJoin()
        .selectAll().where { AppUserTable.id eq userId }.toList().toUserWithRoles()

    fun findPage(page: Int, size: Int, sort: List<SortField>): List<UserWithRoles> {
        val orders = sort.map { field ->
            val column = when (field.field) {
                "username" -> AppUserTable.username
                "createdAt" -> AppUserTable.createdAt
                "lastLoginAt" -> AppUserTable.lastLoginAt
                "status" -> AppUserTable.status
                else -> AppUserTable.id
            }
            column to if (field.descending) SortOrder.DESC else SortOrder.ASC
        }
        val ids = AppUserTable.selectAll().orderBy(*orders.toTypedArray()).limit(size).offset(page.toLong() * size).map { it[AppUserTable.id] }
        if (ids.isEmpty()) return emptyList()
        val rows = userJoin().selectAll().where { AppUserTable.id inList ids }.toList()
        val byId = rows.groupBy { it[AppUserTable.id] }
        return ids.mapNotNull { id -> byId[id]?.let { UserWithRoles(it.first().toAppUser(), userJoinRoles(it)) } }
    }

    /** Locks and counts active ADMIN users other than [id]; callers use this only when [id] is not staying an active ADMIN. */
    fun lockedCountActiveAdminsExcluding(id: Long): Long {
        val rows = AppUserTable.join(UserRoleTable, JoinType.INNER, AppUserTable.id, UserRoleTable.userId).join(RoleTable, JoinType.INNER, UserRoleTable.roleId, RoleTable.id)
            .selectAll().where { (AppUserTable.status eq UserStatus.ACTIVE.name) and (RoleTable.code eq Role.ADMIN.name) }
            .forUpdate(ForUpdateOption.ForUpdate).toList()
        return rows.map { it[AppUserTable.id] }.distinct().count { it != id }.toLong()
    }

    fun updateUser(id: Long, status: UserStatus, roles: Set<Role>, now: Instant, revoke: Boolean) {
        AppUserTable.update({ AppUserTable.id eq id }) {
            it[AppUserTable.status] = status.name
            it[updatedAt] = now.toUtcOffset()
            if (revoke) it[tokenVersion] = tokenVersion + 1
        }
        UserRoleTable.deleteWhere { userId eq id }
        addRoles(id, roles)
    }

    fun changePassword(id: Long, hash: String, now: Instant) {
        AppUserTable.update({ AppUserTable.id eq id }) {
            it[passwordHash] = hash
            it[updatedAt] = now.toUtcOffset()
            it[tokenVersion] = tokenVersion + 1
        }
    }

    private fun userJoin() = AppUserTable
        .join(UserRoleTable, JoinType.LEFT, AppUserTable.id, UserRoleTable.userId)
        .join(RoleTable, JoinType.LEFT, UserRoleTable.roleId, RoleTable.id)

    private fun List<ResultRow>.toUserWithRoles(): UserWithRoles? {
        val first = firstOrNull() ?: return null
        return UserWithRoles(first.toAppUser(), userJoinRoles(this))
    }

    private fun userJoinRoles(rows: List<ResultRow>): Set<Role> =
        rows.mapNotNull { row -> row.getOrNull(RoleTable.code)?.let { parseDbEnum<Role>("role", "code", it) } }.toSet()
}
