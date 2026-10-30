package org.a1tsign.vds.api.routes

import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import org.a1tsign.vds.api.BadGatewayException
import org.a1tsign.vds.api.dto.FxRefreshResponse
import org.a1tsign.vds.auth.requireRole
import org.a1tsign.vds.domain.Role
import org.a1tsign.vds.fx.FxRefreshService

fun Route.fxRoutes(service: FxRefreshService) {
    post("/api/v1/admin/fx/refresh") {
        call.requireRole(Role.EDITOR)
        val inserted = service.refresh() ?: throw BadGatewayException("FX rate provider is unavailable")
        call.respond(FxRefreshResponse(inserted))
    }
}
