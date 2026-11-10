package org.a1tsign.vds.api.dto

import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import org.a1tsign.vds.crawler.config.SourceLoadReport
import org.a1tsign.vds.discovery.model.DiscoveryPassSummary
import org.a1tsign.vds.discovery.model.PlannedCandidate
import java.time.Instant

/**
 * Body of `POST /api/v1/admin/discovery/run`. Every field is optional: [dryRun] defaults to
 * `false` (a dry run still searches, so it still spends real credits -- it only writes nothing),
 * [maxQueries] can only lower `VDS_DISCOVERY_MAX_QUERIES_PER_PASS`, and [force] overrides only the
 * minimum interval between passes, never the monthly credit cap.
 */
@Serializable
data class DiscoveryRunTriggerRequest(
    val dryRun: Boolean? = null,
    val maxQueries: Int? = null,
    val force: Boolean? = null,
)

/** `202` response of `POST /api/v1/admin/discovery/run`; the pass is already visible as `RUNNING`. */
@Serializable
data class DiscoveryRunTriggerResponse(val passId: String)

@Serializable
data class PlannedCandidateDto(
    val slug: String,
    val name: String,
    val websiteUrl: String,
    val query: String,
    val resultUrl: String,
)

/** Mirrors `discovery.model.DiscoveryPassSummary`. `finishedAt` is absent while the pass is `RUNNING`. */
@Serializable
data class DiscoveryPassSummaryDto(
    val passId: String,
    @Contextual val startedAt: Instant,
    @Contextual val finishedAt: Instant? = null,
    val status: String,
    val dryRun: Boolean,
    val queriesIssued: Int,
    val queriesFailed: Int,
    val creditsSpent: Int,
    val creditsRemainingThisMonth: Int? = null,
    val vendorCreditsRemaining: Int? = null,
    val resultsSeen: Int,
    /** Keyed by `DENYLIST`, `KEYWORD`, `MALFORMED_URL`, `SLUG_TOO_LONG`, `ALREADY_KNOWN`; every key present. */
    val resultsRejected: Map<String, Int>,
    val knownProviders: Int,
    val candidatesCreated: Int,
    val candidatesDeferred: Int,
    val candidatesFailed: Int,
    val plannedCandidates: List<PlannedCandidateDto> = emptyList(),
    val failureReason: String? = null,
    val message: String? = null,
)

/** One declarative source that became a registry entry at startup. */
@Serializable
data class DiscoverySourceDto(
    val id: String,
    val providerSlug: String,
    val enabled: Boolean,
    val file: String,
    @Contextual val loadedAt: Instant,
)

/** One source file that was refused at startup, with every problem found in it. */
@Serializable
data class DiscoverySourceFailureDto(
    val file: String,
    val errors: List<String>,
)

/**
 * `GET /api/v1/admin/discovery/sources`: the **startup snapshot** of `VDS_DISCOVERY_SOURCES_DIR`
 * (restart-to-apply, vds-004 ADR-01). [problems] are directory-level refusals; [directory] is
 * absent when no directory was read.
 */
@Serializable
data class DiscoverySourcesDto(
    val directory: String? = null,
    val loaded: List<DiscoverySourceDto>,
    val failed: List<DiscoverySourceFailureDto>,
    val problems: List<String>,
)

fun PlannedCandidate.toDto(): PlannedCandidateDto = PlannedCandidateDto(slug, name, websiteUrl, query, resultUrl)

fun DiscoveryPassSummary.toDto(): DiscoveryPassSummaryDto = DiscoveryPassSummaryDto(
    passId = passId.toString(),
    startedAt = startedAt,
    finishedAt = finishedAt,
    status = status.name,
    dryRun = dryRun,
    queriesIssued = queriesIssued,
    queriesFailed = queriesFailed,
    creditsSpent = creditsSpent,
    creditsRemainingThisMonth = creditsRemainingThisMonth,
    vendorCreditsRemaining = vendorCreditsRemaining,
    resultsSeen = resultsSeen,
    resultsRejected = resultsRejected.entries.associate { (reason, count) -> reason.name to count },
    knownProviders = knownProviders,
    candidatesCreated = candidatesCreated,
    candidatesDeferred = candidatesDeferred,
    candidatesFailed = candidatesFailed,
    plannedCandidates = plannedCandidates.map { it.toDto() },
    failureReason = failureReason?.name,
    message = message,
)

fun SourceLoadReport.toDto(): DiscoverySourcesDto = DiscoverySourcesDto(
    directory = directory,
    loaded = loaded.map { DiscoverySourceDto(it.config.id, it.config.providerSlug, it.config.enabled, it.file, it.loadedAt) },
    failed = failed.map { DiscoverySourceFailureDto(it.file, it.problems) },
    problems = problems,
)
