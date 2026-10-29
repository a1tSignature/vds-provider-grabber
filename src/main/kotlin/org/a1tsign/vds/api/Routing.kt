package org.a1tsign.vds.api

import io.ktor.server.application.Application
import io.ktor.server.routing.routing
import org.a1tsign.vds.AppComponents
import org.a1tsign.vds.api.routes.opsRoutes

/**
 * The single place every route group is registered. Keep it one line per group -- a later task
 * adds its own `xxxRoutes(components.yyy)` line here and nothing else.
 */
fun Application.registerRoutes(components: AppComponents) {
    routing {
        opsRoutes(components.dataSource)
    }
}
