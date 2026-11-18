package org.a1tsign.vds.crawler.enrich

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.a1tsign.vds.config.EnrichmentConfig
import org.a1tsign.vds.crawler.config.SourceLoadReport
import org.a1tsign.vds.crawler.write.CatalogApiClient
import org.a1tsign.vds.crawler.write.CatalogApiException
import org.a1tsign.vds.crawler.write.CatalogProviderDetail
import org.a1tsign.vds.crawler.write.CatalogProviderSummary
import org.a1tsign.vds.crawler.write.CrawlWriter
import org.a1tsign.vds.crawler.write.PLANNED_OPERATIONS_LIMIT
import org.a1tsign.vds.domain.EnrichmentFacet
import org.a1tsign.vds.domain.EnrichmentOutcome
import org.a1tsign.vds.domain.SystemStatus
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit

/** Providers per batch; the queue is re-read and RSS logged at each batch boundary. */
const val ENRICH_BATCH_SIZE: Int = 10

/** The process exit-code contract shared with `scripts/enrich-launch.sh` and the memory gate. */
object EnrichExit {
    const val DRAINED = 0
    const val FATAL = 1
    const val STOPPED_EARLY = 10
}

/** Bounds the queue walk: past this many pages of 100 the launch works with what it read. */
private const val MAX_QUEUE_PAGES = 100

private val QUEUED_STATUSES = listOf(SystemStatus.CONFIGURED, SystemStatus.CANDIDATE)

/**
 * Pricing page and observed facets per provider slug, from the declarative sources. Compiled
 * reference adapters are not in here, so they contribute nothing.
 */
class SourceIndex(report: SourceLoadReport) {
    private val bySlug = report.loaded.associateBy { it.config.providerSlug }

    fun pricingUrl(slug: String): String? = bySlug[slug]?.config?.pageUrl()

    fun observedFacets(slug: String): Set<EnrichmentFacet> {
        val source = bySlug[slug]?.config?.takeIf { it.enabled } ?: return emptySet()
        return buildSet {
            if (source.legalInfo != null) {
                add(EnrichmentFacet.LEGAL_ENTITY_NAME)
                add(EnrichmentFacet.REGISTRATION_COUNTRY)
            }
            if (source.paymentMethods != null) add(EnrichmentFacet.PAYMENT_METHOD)
            if (source.locations != null) add(EnrichmentFacet.LOCATION)
        }
    }
}

data class EnrichWorker(val step: EnrichmentStep, val writer: CrawlWriter)

/**
 * One launch of the enrichment process: batches of [ENRICH_BATCH_SIZE] from a queue re-read per
 * batch, each provider attempted at most once, results submitted per provider as soon as its crawl
 * ends. Returns an [EnrichExit] code; only [CancellationException] leaves, after the final line.
 */
class EnrichLauncher(
    private val config: EnrichmentConfig,
    private val options: EnrichOptions,
    private val maxRuntimeMs: Long,
    private val catalog: CatalogApiClient,
    private val workers: List<EnrichWorker>,
    private val sources: SourceIndex,
    private val clock: Clock,
    private val launchId: UUID = UUID.randomUUID(),
) {
    private val startedNanos = System.nanoTime()

    /** Recorded before the crawl starts, so a provider that kills the JVM is not retried this launch. */
    private val attempted = ConcurrentHashMap.newKeySet<Long>()

    /** Skipped by the fresh check: not an attempt, but not looked at again this launch either. */
    private val skipped = ConcurrentHashMap.newKeySet<Long>()

    private var batches = 0

    suspend fun run(): Int {
        val (code, reason) = try {
            loadAttemptedFile()
            logger.info(
                "{} enrich launch starting: workers={} newCandidates={} dryRun={} force={} maxBatches={} intervalHours={} alreadyAttempted={}",
                launchId, workers.size, options.newCandidates, options.dryRun, options.force, options.maxBatches ?: "-",
                config.intervalHours, attempted.size,
            )
            loop()
        } catch (cancellation: CancellationException) {
            logFinished("cancelled", null)
            throw cancellation
        } catch (unauthorized: CatalogApiException.Unauthorized) {
            EnrichExit.FATAL to "service account rejected: ${unauthorized.message}"
        } catch (forbidden: ForbiddenSubmit) {
            EnrichExit.FATAL to "service account may not write enrichment (needs EDITOR): ${forbidden.message}"
        } catch (failure: CatalogApiException) {
            EnrichExit.FATAL to "queue read failed: ${failure.message}"
        } catch (failure: IOException) {
            EnrichExit.FATAL to "attempted-file ${options.attemptedFile} unusable: ${failure.javaClass.simpleName}: ${failure.message}"
        }
        logFinished(reason, code)
        return code
    }

    private fun logFinished(reason: String, code: Int?) {
        workers.asSequence().flatMap { it.writer.plannedOperations() }.take(PLANNED_OPERATIONS_LIMIT)
            .forEach { logger.info("{} {}", launchId, it) }
        logger.info(
            "{} enrich launch finished: batches={} attempted={} skipped={} exitReason={} exitCode={} durationMs={}",
            launchId, batches, attempted.size, skipped.size, reason, code ?: "-", elapsedMs(),
        )
    }

    private suspend fun loop(): Pair<Int, String> {
        while (true) {
            val queue = readQueue()
            if (queue.isEmpty()) {
                return EnrichExit.DRAINED to if (batches == 0) "nothing due" else "queue drained"
            }
            if (budgetSpent()) return EnrichExit.STOPPED_EARLY to "runtime budget spent"
            if (options.maxBatches != null && batches >= options.maxBatches) {
                return EnrichExit.STOPPED_EARLY to "--max-batches ${options.maxBatches} reached"
            }
            runBatch(queue.take(ENRICH_BATCH_SIZE))
        }
    }

    private suspend fun runBatch(batch: List<CatalogProviderSummary>) {
        batches++
        val batchStarted = System.nanoTime()
        val queue = ConcurrentLinkedQueue(batch)
        val results = coroutineScope {
            workers.take(batch.size).map { worker ->
                async {
                    buildList {
                        while (!budgetSpent()) {
                            val summary = queue.poll() ?: break
                            add(enrichOne(summary, worker))
                        }
                    }
                }
            }.awaitAll().flatten()
        }
        val outcomes = results.groupingBy { it }.eachCount().toSortedMap()
        val rss = rssMb()
        logger.info(
            "{} enrich batch {} providers={} outcomes={} durationMs={}{}",
            launchId, batches, results.size, outcomes, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - batchStarted),
            if (rss == null) "" else " rssMb=$rss",
        )
    }

    /** Returns the label counted in the batch line: an outcome name or `SKIPPED_*`. */
    private suspend fun enrichOne(summary: CatalogProviderSummary, worker: EnrichWorker): String {
        val detail: CatalogProviderDetail = try {
            catalog.getProvider(summary.id)
        } catch (unauthorized: CatalogApiException.Unauthorized) {
            throw unauthorized
        } catch (notFound: CatalogApiException.ClientError) {
            if (notFound.status != NOT_FOUND) return readFailed(summary, notFound)
            return skip(summary, "SKIPPED_DELETED", "deleted since the queue was read")
        } catch (failure: CatalogApiException) {
            return readFailed(summary, failure)
        }
        if (detail.provider.systemStatus == SystemStatus.REJECTED.name) {
            return skip(summary, "SKIPPED_REJECTED", "rejected since the queue was read")
        }
        if (options.newCandidates && detail.provider.systemStatus != SystemStatus.CANDIDATE.name) {
            return skip(summary, "SKIPPED_NOT_CANDIDATE", "status changed since the queue was read")
        }
        if (!isDue(detail.enrichment?.lastAttemptAt)) {
            return skip(summary, "SKIPPED_NOT_DUE", "no longer due: last attempt ${detail.enrichment?.lastAttemptAt}")
        }

        recordAttempt(summary.id)
        val target = EnrichmentTarget(
            id = summary.id,
            slug = detail.provider.slug,
            systemStatus = detail.provider.systemStatus,
            websiteUrl = detail.provider.websiteUrl,
            pricingUrl = sources.pricingUrl(detail.provider.slug),
            observedFacets = sources.observedFacets(detail.provider.slug),
        )
        val report = worker.step.enrich(target, detail, worker.writer)
        logProvider(target, report)
        return report.outcome.name
    }

    private fun readFailed(summary: CatalogProviderSummary, failure: CatalogApiException): String {
        recordAttempt(summary.id)
        logger.warn(
            "{} enrich provider {} id={} systemStatus={} outcome=FAILED durationMs=0 pages=0 valuesApplied=0 message=Catalog read failed: {}",
            launchId, summary.slug, summary.id, summary.systemStatus, failure.message,
        )
        return EnrichmentOutcome.FAILED.name
    }

    private fun skip(summary: CatalogProviderSummary, label: String, why: String): String {
        skipped += summary.id
        logger.info("{} enrich provider {} id={} systemStatus={} skipped: {}", launchId, summary.slug, summary.id, summary.systemStatus, why)
        return label
    }

    private fun logProvider(target: EnrichmentTarget, report: EnrichmentReport) {
        val line = "{} enrich provider {} id={} systemStatus={} outcome={} durationMs={} pages={} valuesApplied={} message={}"
        val arguments = arrayOf<Any?>(
            launchId, target.slug, target.id, target.systemStatus, report.outcome,
            report.durationMs, report.pagesFetched, report.valuesApplied, report.message,
        )
        when (report.outcome) {
            EnrichmentOutcome.SUCCESS, EnrichmentOutcome.NOTHING_FOUND -> logger.info(line, *arguments)
            else -> logger.warn(line, *arguments)
        }
    }

    /**
     * Every `CONFIGURED`/`CANDIDATE` provider that is due and not yet handled this launch:
     * never-attempted first by `id`, then the oldest attempt first.
     */
    private suspend fun readQueue(): List<CatalogProviderSummary> {
        val members = mutableListOf<CatalogProviderSummary>()
        val statuses = if (options.newCandidates) listOf(SystemStatus.CANDIDATE) else QUEUED_STATUSES
        var page = 0
        while (true) {
            if (page >= MAX_QUEUE_PAGES) {
                logger.warn("{} enrich queue walk stopped after {} pages; using the {} providers read", launchId, page, members.size)
                break
            }
            val result = catalog.listProvidersPage(page, statuses)
            members += result.content
            page++
            if (page >= result.totalPages) break
        }
        return members
            .distinctBy { it.id }
            .filter { it.id !in attempted && it.id !in skipped }
            .filter { isDue(it.enrichmentLastAttemptAt) }
            .sortedWith(compareBy<CatalogProviderSummary>({ it.enrichmentLastAttemptAt != null }, { it.enrichmentLastAttemptAt }, { it.id }))
    }

    private fun isDue(lastAttemptAt: Instant?): Boolean =
        lastAttemptAt == null || options.force ||
            (!options.newCandidates && !lastAttemptAt.plus(Duration.ofHours(config.intervalHours)).isAfter(clock.instant()))

    @Synchronized
    private fun recordAttempt(providerId: Long) {
        attempted += providerId
        val file = options.attemptedFile ?: return
        Files.writeString(Path.of(file), "$providerId\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.SYNC)
    }

    private fun loadAttemptedFile() {
        val path = options.attemptedFile?.let { Path.of(it) } ?: return
        if (!Files.exists(path)) return
        Files.readAllLines(path).mapNotNullTo(attempted) { it.trim().toLongOrNull() }
    }

    private fun budgetSpent(): Boolean = elapsedMs() >= maxRuntimeMs

    private fun elapsedMs(): Long = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos)

    private companion object {
        const val NOT_FOUND = 404
        val logger = LoggerFactory.getLogger(EnrichLauncher::class.java)

        /** `VmRSS` of this process in MB, or `null` where `/proc` does not exist (macOS). */
        fun rssMb(): Long? = try {
            Files.readAllLines(Path.of("/proc/self/status"))
                .firstOrNull { it.startsWith("VmRSS:") }
                ?.split(Regex("\\s+"))?.getOrNull(1)?.toLongOrNull()
                ?.let { it / 1024 }
        } catch (_: Exception) {
            null
        }
    }
}
