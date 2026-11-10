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
 * ADMIN-only endpoints over the vds-002 crawler (ADR-02/ADR-05): trigger a run, inspect the
 * in-memory run history, and list the registered adapters -- this file wires nothing of the
 * crawler's own behaviour, only exposes what tasks 1-6 built.
 *
 * [registry]/[runner]/[scope] are `null` **together**, exactly when `VDS_CRAWLER_ENABLED` is
 * `false` (see `AppComponents.buildComponents`): every handler below answers `503` through the
 * existing envelope in that case, `GET /runs` and `GET /adapters` deliberately consistent about it
 * (never one `503` and the other an empty `200`).
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

        // The same non-blocking slot CrawlScheduler's own tick uses: 409 is "already running",
        // never a queued second run.
        val runId = activeRunner.tryReserve(req.dryRun ?: crawlerConfig.dryRunDefault)
            ?: throw ConflictException("A crawl run is already in progress")
        val selection = AdapterSelection(adapterIds = req.adapterIds ?: crawlerConfig.adapters, maxOutOfStockRatio = ratio)
        // Launched on the application-owned scope AppComponents builds and Application.kt's
        // shutdownGracefully cancels -- never GlobalScope, so this run cannot outlive shutdown.
        // The catch mirrors CrawlScheduler.tick()'s own defensive wrapper: runReserved already
        // guarantees the slot is released and a terminal summary published no matter what, but an
        // exception escaping before/around that (e.g. a future regression above) must still go
        // through this project's logging, not the coroutine library's default uncaught handler.
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
        // A malformed UUID is "not found", never a 500: nothing here reads the path parameter as
        // anything but an opaque run id.
        val runId = call.parameters["runId"]
            ?.let { raw -> runCatching { UUID.fromString(raw) }.getOrNull() }
            ?: throw NotFoundException("Crawl run not found")
        val summary = activeRunner.run(runId) ?: throw NotFoundException("Crawl run not found")
        call.respond(summary.toDto())
    }

    get("/api/v1/admin/crawl/adapters") {
        call.requireRole(Role.ADMIN)
        val activeRegistry = registry ?: throw ServiceUnavailableException()
        // Already validated at startup (AppComponents.buildComponents fails fast on an unknown
        // configured id), so this cannot throw here -- neither the configuration nor the registry's
        // entry list changes at runtime. Still true under vds-004's config-sourced entries: the
        // registry is built once, from files read once, and restart-to-apply means a file edited
        // after startup changes nothing until the next boot (ADR-01 decision 3). The list validated
        // in buildComponents is literally this list, config-sourced ids included.
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
