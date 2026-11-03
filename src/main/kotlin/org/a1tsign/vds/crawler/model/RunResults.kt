package org.a1tsign.vds.crawler.model

import java.time.Instant
import java.util.UUID

/** Outcome of one adapter inside a run. `PARTIAL` = some writes applied, some step failed. */
enum class AdapterRunStatus { SUCCESS, PARTIAL, FAILED, SKIPPED }

/** Outcome of a whole run; a run that is still in flight reports `RUNNING`. */
enum class RunStatus { RUNNING, COMPLETED, CANCELLED }

/** Why an adapter run did not succeed -- the single machine-readable cause. */
enum class FailureReason { VALIDATION, TIMEOUT, MASS_CHANGE_GUARD, FETCH, WRITE, AUTH, ADAPTER_ERROR }

/**
 * What one adapter did, as reported by `GET /api/v1/admin/crawl/runs` (spec req. 36). Every
 * counter is "what actually happened", so a dry run reports zeros and lists its intentions in
 * [CrawlRunSummary.plannedOperations] instead.
 *
 * Timings come from a monotonic source ([durationMs] is measured with `System.nanoTime()`, not a
 * wall clock); instants in [CrawlRunSummary] come from the injected `Clock`.
 */
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
    /** Prices skipped because no FX rate was available for their currency. */
    val noFxRateCount: Int = 0,
    val failureReason: FailureReason? = null,
    /** One line, operator-readable: no stack trace, no URL credentials, no secret. */
    val message: String? = null,
)

/**
 * One crawl run. [finishedAt] is `null` while [status] is [RunStatus.RUNNING];
 * [plannedOperations] is filled only by a dry run, which performs no writes at all (ADR-05).
 */
data class CrawlRunSummary(
    val runId: UUID,
    val startedAt: Instant,
    val finishedAt: Instant? = null,
    val status: RunStatus,
    val dryRun: Boolean,
    val adapters: List<AdapterRunResult> = emptyList(),
    val plannedOperations: List<String> = emptyList(),
)
