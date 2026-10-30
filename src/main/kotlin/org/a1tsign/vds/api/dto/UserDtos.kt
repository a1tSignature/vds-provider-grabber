package org.a1tsign.vds.api.dto

import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import org.a1tsign.vds.domain.Role
import org.a1tsign.vds.domain.UserStatus
import java.time.Instant

@Serializable data class CreateUserRequest(val username: String, val password: String, val email: String? = null, val roles: List<String>)
@Serializable data class PatchUserRequest(val status: String? = null, val roles: List<String>? = null)
@Serializable data class ChangePasswordRequest(val password: String)
@Serializable data class UserDto(val id: Long, val username: String, val email: String?, val status: UserStatus, val roles: List<Role>, @Contextual val createdAt: Instant, @Contextual val updatedAt: Instant, @Contextual val lastLoginAt: Instant?)
