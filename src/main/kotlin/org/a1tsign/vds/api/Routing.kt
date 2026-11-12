package org.a1tsign.vds.api

import io.ktor.server.application.Application
import io.ktor.server.auth.authenticate
import io.ktor.server.routing.routing
import org.a1tsign.vds.AppComponents
import org.a1tsign.vds.auth.installBearerJwtAuth
import org.a1tsign.vds.api.routes.opsRoutes
import org.a1tsign.vds.api.routes.authRoutes
import org.a1tsign.vds.api.routes.adminUserRoutes
import org.a1tsign.vds.api.routes.crawlerRoutes
import org.a1tsign.vds.api.routes.dictionaryRoutes
import org.a1tsign.vds.api.routes.discoveryRoutes
import org.a1tsign.vds.api.routes.fxRoutes
import org.a1tsign.vds.api.routes.providerRoutes
import org.a1tsign.vds.api.routes.locationRoutes
import org.a1tsign.vds.api.routes.tariffRoutes
import org.a1tsign.vds.api.routes.priceRoutes
import org.a1tsign.vds.api.routes.openApiRoutes
import org.a1tsign.vds.api.routes.staticRoutes

/** Registers every route group, one line per group. [staticBasePackage] lets tests point the SPA fallback at a fixture. */
fun Application.registerRoutes(components: AppComponents, staticBasePackage: String = "static") {
    if (components.isConfigured) installBearerJwtAuth(components.authService)
    routing {
        opsRoutes(components.dataSource)
        if (components.isConfigured) {
            authRoutes(components.authService, components.config.http.trustForwardedFor)
            staticRoutes(staticBasePackage)
            authenticate("bearer-jwt") {
                adminUserRoutes(components.userService)
                fxRoutes(components.fxRefreshService)
                crawlerRoutes(
                    components.crawlerRegistry,
                    components.crawlerRunner,
                    components.crawlerScope,
                    components.config.crawler,
                    components.crawlerAdminRunJob,
                )
                discoveryRoutes(components.discoveryRunner, components.discoveryScope, components.discoverySources, components.discoveryAdminPassJob)
                providerRoutes(components.providerService, components.catalogQueryService)
                locationRoutes(components.locationService)
                tariffRoutes(components.tariffService, components.catalogQueryService)
                priceRoutes(components.priceService)
                dictionaryRoutes()
                openApiRoutes()
            }
        }
    }
}
