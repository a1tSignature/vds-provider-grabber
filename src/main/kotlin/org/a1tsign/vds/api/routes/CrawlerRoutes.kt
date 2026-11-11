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
import org.a1tsign.vds.api.dto.AdapterInfoDto
import org.a1tsign.vds.api.dto.CrawlRunTriggerRequest
import org.a1tsign.vds.api.dto.CrawlRunTriggerResponse
import org.a1tsign.vds.api.dto.toDto
import org.a1tsign.vds.auth.requireRole
import org.a1tsign.vds.config.CrawlerConfig
import org.a1tsign.vds.crawler.AdapterRegistry
import org.a1tsign.vds.crawler.AdapterSelection
import org.a1tsign.vds.crawler.CrawlerRunner
import org.a1tsign.vds.domain.Role
import org.slf4j.LoggerFactory
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

private val logger = LoggerFactory.getLogger("org.a1tsign.vds.api.routes.CrawlerRoutes")

/**
 * ADMIN-only crawler endpoints: trigger a run, read the in-memory run history, list the adapters.
 *
 * [registry], [runner] and [scope] are all `null` when `VDS_CRAWLER_ENABLED=false`; every endpoint
 * here then answers `503`.
 */
fun Route.crawlerRoutes(
    registry: AdapterRegistry?,
    runner: CrawlerRunner?,
    scope: CoroutineScope?,
    crawlerConfig: CrawlerConfig,
    adminRunJob: AtomicReference<Job?>,
) {
    post("/api/v1/admin/crawl/run") {
        call.requireRole(Role.ADMIN)
        val activeRegistry = registry ?: throw ServiceUnavailableException()
        val activeRunner = runner ?: throw ServiceUnavailableException()
        val activeScope = scope ?: throw ServiceUnavailableException()

        val req = call.receive<CrawlRunTriggerRequest>()
        val v = Validation()
        val knownIds = activeRegistry.entries.map { it.adapter.id }
        req.adapterIds?.forEach { id ->
            if (id !in knownIds) {
                v.reject("adapterIds", "unknown adapter id '$id'; known ids: ${knownIds.joinToString()}")
            }
        }
        val ratio = req.maxOutOfStockRatio?.let { v.validateRatio("maxOutOfStockRatio", it) }
        v.throwIfInvalid()

        // Shares the slot with CrawlScheduler: 409 means a run is in progress; runs are never queued.
        val runId = activeRunner.tryReserve(req.dryRun ?: crawlerConfig.dryRunDefault)
            ?: throw ConflictException("A crawl run is already in progress")
        val selection = AdapterSelection(adapterIds = req.adapterIds ?: crawlerConfig.adapters, maxOutOfStockRatio = ratio)
        // The application scope is cancelled on shutdown, so the run cannot outlive the app.
        // runReserved already releases the slot on failure; the catch only sends unexpected errors
        // to our logger instead of the default uncaught-exception handler.
        val job = activeScope.launch {
            try {
                activeRunner.runReserved(runId, selection)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Throwable) {
                logger.warn("admin-triggered crawl run {} ended with an unhandled failure", runId, failure)
            }
        }
        adminRunJob.set(job)

        call.respond(HttpStatusCode.Accepted, CrawlRunTriggerResponse(runId.toString()))
    }

    get("/api/v1/admin/crawl/runs") {
        call.requireRole(Role.ADMIN)
        val activeRunner = runner ?: throw ServiceUnavailableException()
        call.respond(activeRunner.recentRuns().map { it.toDto() })
    }

    get("/api/v1/admin/crawl/runs/{runId}") {
        call.requireRole(Role.ADMIN)
        val activeRunner = runner ?: throw ServiceUnavailableException()
        // A malformed UUID is a 404, not a 500.
        val runId = call.parameters["runId"]
            ?.let { raw -> runCatching { UUID.fromString(raw) }.getOrNull() }
            ?: throw NotFoundException("Crawl run not found")
        val summary = activeRunner.run(runId) ?: throw NotFoundException("Crawl run not found")
        call.respond(summary.toDto())
    }

    get("/api/v1/admin/crawl/adapters") {
        call.requireRole(Role.ADMIN)
        val activeRegistry = registry ?: throw ServiceUnavailableException()
        // Cannot throw: startup validates the configured ids, and the registry never changes at runtime.
        val selectedIds = activeRegistry.select(crawlerConfig.adapters).map { it.id }.toSet()
        call.respond(
            activeRegistry.entries.map { entry ->
                AdapterInfoDto(
                    id = entry.adapter.id,
                    providerSlug = entry.adapter.providerSlug,
                    sourceKind = entry.adapter.sourceKind.name,
                    enabled = entry.adapter.id in selectedIds,
                    origin = entry.origin.name,
                    file = entry.file,
                )
            },
        )
    }
}
