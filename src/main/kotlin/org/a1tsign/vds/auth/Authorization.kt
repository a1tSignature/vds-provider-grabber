package org.a1tsign.vds.auth

import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.principal
import org.a1tsign.vds.api.ForbiddenException
import org.a1tsign.vds.api.UnauthorizedException
import org.a1tsign.vds.domain.Role
import org.a1tsign.vds.domain.includes

fun ApplicationCall.requireRole(required: Role): VdsPrincipal {
    val principal = principal<VdsPrincipal>() ?: throw ForbiddenException()
    if (principal.roles.none { it.includes(required) }) throw ForbiddenException()
    return principal
}

fun ApplicationCall.requireAuthenticatedRole(required: Role): VdsPrincipal {
    val principal = principal<VdsPrincipal>() ?: throw UnauthorizedException()
    if (principal.roles.none { it.includes(required) }) throw ForbiddenException()
    return principal
}

fun ApplicationCall.requireCrawlerIdentity(configuredUsername: String?): VdsPrincipal {
    val principal = requireAuthenticatedRole(Role.EDITOR)
    if (configuredUsername.isNullOrBlank() || principal.username != configuredUsername) throw ForbiddenException()
    return principal
}
