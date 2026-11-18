package org.a1tsign.vds.crawler.model

import java.time.Instant
import java.util.UUID

/** Outcome of one adapter inside a run. `PARTIAL` = some writes applied, some step failed. */
enum class AdapterRunStatus { SUCCESS, PARTIAL, FAILED, SKIPPED }

enum class RunStatus { RUNNING, COMPLETED, CANCELLED }

enum class FailureReason { VALIDATION, TIMEOUT, MASS_CHANGE_GUARD, FETCH, WRITE, AUTH, ADAPTER_ERROR }

/** Dry-run counters report intended operations. Write-run counters report applied operations. */
data class AdapterRunResult(
    val adapterId: String,
    val providerSlug: String,
    val status: AdapterRunStatus,
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
    val failureReason: FailureReason? = null,
    /** One line, operator-readable: no stack trace, no URL credentials, no secret. */
    val message: String? = null,
)

/** finishedAt is absent while running. Only dry runs include planned operations. */
data class CrawlRunSummary(
    val runId: UUID,
    val startedAt: Instant,
    val finishedAt: Instant? = null,
    val status: RunStatus,
    val dryRun: Boolean,
    val adapters: List<AdapterRunResult> = emptyList(),
    val plannedOperations: List<String> = emptyList(),
)
