package org.a1tsign.vds.api.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import org.a1tsign.vds.api.ApiErrorDetail
import org.a1tsign.vds.api.NotFoundException
import org.a1tsign.vds.api.Validation
import org.a1tsign.vds.api.ValidationException
import org.a1tsign.vds.api.dto.*
import org.a1tsign.vds.api.pageRequest
import org.a1tsign.vds.api.pageResponse
import org.a1tsign.vds.auth.requireRole
import org.a1tsign.vds.db.UserWithRoles
import org.a1tsign.vds.domain.Role
import org.a1tsign.vds.domain.UserStatus
import org.a1tsign.vds.service.UserService

private const val USERNAME_MAX_LENGTH = 64
private const val EMAIL_MAX_LENGTH = 255

fun Route.adminUserRoutes(service: UserService) {
    post("/api/v1/admin/users") {
        call.requireRole(Role.ADMIN)
        val req = call.receive<CreateUserRequest>()
        val v = Validation()
        v.requireNonBlank("username", req.username)
        v.maxLength("username", req.username, USERNAME_MAX_LENGTH)
        req.email?.let { v.maxLength("email", it, EMAIL_MAX_LENGTH) }
        val roles = req.roles.mapNotNull { v.enumValue("roles", it, Role.entries.toTypedArray()) }.toSet()
        v.throwIfInvalid()
        val created = service.create(req.username, req.password, req.email, roles)
        call.response.header("Location", "/api/v1/admin/users/${created.user.id}")
        call.respond(HttpStatusCode.Created, created.toDto())
    }

    get("/api/v1/admin/users") {
        call.requireRole(Role.ADMIN)
        val page = call.pageRequest(setOf("id", "username", "createdAt", "lastLoginAt", "status"))
        val (total, users) = service.findPage(page.page, page.size, page.sort)
        call.respond(pageResponse(total, page.page, page.size, users.map { it.toDto() }))
    }

    patch("/api/v1/admin/users/{id}") {
        call.requireRole(Role.ADMIN)
        val req = call.receive<PatchUserRequest>()
        if (req.status == null && req.roles == null) {
            throw ValidationException(listOf(ApiErrorDetail("body", "must include status or roles")))
        }
        val v = Validation()
        val status = req.status?.let { v.enumValue("status", it, UserStatus.entries.toTypedArray()) }
        val roles = req.roles?.mapNotNull { v.enumValue("roles", it, Role.entries.toTypedArray()) }?.toSet()
        v.throwIfInvalid()
        val id = call.parameters["id"]?.toLongOrNull() ?: throw NotFoundException("User not found")
        call.respond(service.patch(id, status, roles).toDto())
    }

    put("/api/v1/admin/users/{id}/password") {
        call.requireRole(Role.ADMIN)
        val id = call.parameters["id"]?.toLongOrNull() ?: throw NotFoundException("User not found")
        service.changePassword(id, call.receive<ChangePasswordRequest>().password)
        call.respond(HttpStatusCode.NoContent)
    }
}

private fun UserWithRoles.toDto() = UserDto(user.id, user.username, user.email, user.status, roles.sortedBy { it.name }, user.createdAt, user.updatedAt, user.lastLoginAt)
