package org.a1tsign.vds.api.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.a1tsign.vds.api.ConflictException
import org.a1tsign.vds.api.NotFoundException
import org.a1tsign.vds.api.ServiceUnavailableException
import org.a1tsign.vds.api.Validation
import org.a1tsign.vds.api.dto.DiscoveryRunTriggerRequest
import org.a1tsign.vds.api.dto.DiscoveryRunTriggerResponse
import org.a1tsign.vds.api.dto.toDto
import org.a1tsign.vds.auth.requireRole
import org.a1tsign.vds.crawler.config.SourceLoadReport
import org.a1tsign.vds.discovery.DiscoveryRunner
import org.a1tsign.vds.discovery.PassRequest
import org.a1tsign.vds.domain.Role
import org.slf4j.LoggerFactory
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

private val logger = LoggerFactory.getLogger("org.a1tsign.vds.api.routes.DiscoveryRoutes")

/**
 * ADMIN-only endpoints over the vds-004 discovery pass: trigger one, inspect the in-memory pass
 * history, and see which declarative sources the last startup loaded.
 *
 * [runner]/[scope] are `null` together, exactly while discovery is not constructed
 * (`VDS_DISCOVERY_ENABLED=false`): **every** handler below then answers `503` through the existing
 * envelope -- `GET /sources` included, so the group is consistently unavailable rather than half
 * `503` and half an empty `200` (the `CrawlerRoutes` precedent).
 */
fun Route.discoveryRoutes(
    runner: DiscoveryRunner?,
    scope: CoroutineScope?,
    sources: SourceLoadReport,
    adminPassJob: AtomicReference<Job?>,
) {
    post("/api/v1/admin/discovery/run") {
        call.requireRole(Role.ADMIN)
        val activeRunner = runner ?: throw ServiceUnavailableException()
        val activeScope = scope ?: throw ServiceUnavailableException()

        val req = call.receive<DiscoveryRunTriggerRequest>()
        val v = Validation()
        if (req.maxQueries != null && req.maxQueries < 1) v.reject("maxQueries", "must be 1 or greater")
        v.throwIfInvalid()

        // The slot CrawlerRunner shares: 409 both while another pass runs and while a crawl run does.
        val passId = activeRunner.tryReserve(
            PassRequest(dryRun = req.dryRun ?: false, maxQueries = req.maxQueries, force = req.force ?: false),
        ) ?: throw ConflictException("A crawl run or a discovery pass is already in progress")
        // The application-owned scope Application.kt's shutdownGracefully cancels, never GlobalScope;
        // the job is published so shutdown can join it with a bounded grace period.
        val job = activeScope.launch {
            try {
                activeRunner.runReserved(passId)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Throwable) {
                logger.warn("admin-triggered discovery pass {} ended with an unhandled failure", passId, failure)
            }
        }
        adminPassJob.set(job)

        call.respond(HttpStatusCode.Accepted, DiscoveryRunTriggerResponse(passId.toString()))
    }

    get("/api/v1/admin/discovery/passes") {
        call.requireRole(Role.ADMIN)
        val activeRunner = runner ?: throw ServiceUnavailableException()
        call.respond(activeRunner.recentPasses().map { it.toDto() })
    }

    get("/api/v1/admin/discovery/passes/{passId}") {
        call.requireRole(Role.ADMIN)
        val activeRunner = runner ?: throw ServiceUnavailableException()
        // A malformed UUID is "not found", never a 500.
        val passId = call.parameters["passId"]
            ?.let { raw -> runCatching { UUID.fromString(raw) }.getOrNull() }
            ?: throw NotFoundException("Discovery pass not found")
        val summary = activeRunner.pass(passId) ?: throw NotFoundException("Discovery pass not found")
        call.respond(summary.toDto())
    }

    get("/api/v1/admin/discovery/sources") {
        call.requireRole(Role.ADMIN)
        if (runner == null) throw ServiceUnavailableException()
        call.respond(sources.toDto())
    }
}
