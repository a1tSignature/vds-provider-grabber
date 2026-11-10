package org.a1tsign.vds.api.dto

import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import org.a1tsign.vds.crawler.model.AdapterRunResult
import org.a1tsign.vds.crawler.model.CrawlRunSummary
import java.time.Instant

/**
 * Body of `POST /api/v1/admin/crawl/run`. Every field is optional: an absent [adapterIds] means
 * "the configured default selection" (the registry's `includedByDefault` entries, or exactly the
 * ids of `VDS_CRAWLER_ADAPTERS`), an absent [dryRun] means the configured
 * `VDS_CRAWLER_DRY_RUN_DEFAULT`, and an absent [maxOutOfStockRatio] means the configured
 * `VDS_CRAWLER_MAX_OUT_OF_STOCK_RATIO`. [maxOutOfStockRatio] is a plain-decimal **string**, like
 * every decimal on this API's wire -- `Validation.validateRatio` parses it into a `BigDecimal` in
 * `[0, 1]`; a JSON number here is a 400 before this class is even reached (the field is typed
 * `String`, and the shared `ApiJson` is not lenient).
 */
@Serializable
data class CrawlRunTriggerRequest(
    val adapterIds: List<String>? = null,
    val dryRun: Boolean? = null,
    val maxOutOfStockRatio: String? = null,
)

/** `202` response of `POST /api/v1/admin/crawl/run` -- the run is already visible via [CrawlRunSummaryDto]. */
@Serializable
data class CrawlRunTriggerResponse(val runId: String)

/** Mirrors `crawler.model.AdapterRunResult`, kept as a separate wire type by ADR-03 convention. */
@Serializable
data class AdapterRunResultDto(
    val adapterId: String,
    val providerSlug: String,
    val status: String,
    val durationMs: Long,
    val pagesFetched: Int = 0,
    val providersCreated: Int = 0,
    val locationsCreated: Int = 0,
    val tariffsCreated: Int = 0,
    val tariffsUpdated: Int = 0,
    val tariffsMarkedOutOfStock: Int = 0,
    val pricesAppended: Int = 0,
    val pricesUnchanged: Int = 0,
    val noFxRateCount: Int = 0,
    val failureReason: String? = null,
    val message: String? = null,
)

/** Mirrors `crawler.model.CrawlRunSummary`. `finishedAt` is absent while the run is `RUNNING`. */
@Serializable
data class CrawlRunSummaryDto(
    val runId: String,
    @Contextual val startedAt: Instant,
    @Contextual val finishedAt: Instant? = null,
    val status: String,
    val dryRun: Boolean,
    val adapters: List<AdapterRunResultDto> = emptyList(),
    val plannedOperations: List<String> = emptyList(),
)

/**
 * One row of `GET /api/v1/admin/crawl/adapters`. [enabled] means "selected by the current
 * configuration"; [origin] tells a compiled adapter from one described by a JSON file (vds-004
 * ADR-01) and [file] names that file, present exactly when [origin] is `CONFIG`.
 */
@Serializable
data class AdapterInfoDto(
    val id: String,
    val providerSlug: String,
    val sourceKind: String,
    val enabled: Boolean,
    val origin: String,
    val file: String? = null,
)

fun AdapterRunResult.toDto(): AdapterRunResultDto = AdapterRunResultDto(
    adapterId = adapterId,
    providerSlug = providerSlug,
    status = status.name,
    durationMs = durationMs,
    pagesFetched = pagesFetched,
    providersCreated = providersCreated,
    locationsCreated = locationsCreated,
    tariffsCreated = tariffsCreated,
    tariffsUpdated = tariffsUpdated,
    tariffsMarkedOutOfStock = tariffsMarkedOutOfStock,
    pricesAppended = pricesAppended,
    pricesUnchanged = pricesUnchanged,
    noFxRateCount = noFxRateCount,
    failureReason = failureReason?.name,
    message = message,
)

fun CrawlRunSummary.toDto(): CrawlRunSummaryDto = CrawlRunSummaryDto(
    runId = runId.toString(),
    startedAt = startedAt,
    finishedAt = finishedAt,
    status = status.name,
    dryRun = dryRun,
    adapters = adapters.map { it.toDto() },
    plannedOperations = plannedOperations,
)
