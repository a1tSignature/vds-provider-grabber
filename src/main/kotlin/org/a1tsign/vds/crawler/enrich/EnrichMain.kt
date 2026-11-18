package org.a1tsign.vds.crawler.enrich

import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.a1tsign.vds.config.ConfigurationException
import org.a1tsign.vds.crawler.AdapterRegistry
import org.a1tsign.vds.crawler.config.SourceConfigLoader
import org.a1tsign.vds.crawler.config.SourceLoadReport
import org.a1tsign.vds.crawler.fetch.HostRateLimiter
import org.a1tsign.vds.crawler.fetch.KtorPageFetcher
import org.a1tsign.vds.crawler.fetch.crawlerHttpClient
import org.a1tsign.vds.crawler.write.ApiCrawlWriter
import org.a1tsign.vds.crawler.write.CatalogApiClient
import org.a1tsign.vds.crawler.write.DryRunWriter
import org.slf4j.LoggerFactory
import java.time.Clock
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

private val logger = LoggerFactory.getLogger("org.a1tsign.vds.crawler.enrich.EnrichMain")

/** How long a `SIGTERM` waits for the launch to unwind before the JVM halts anyway. */
private const val SHUTDOWN_GRACE_MS = 5_000L

/** Standalone process that reads and updates the catalog over HTTP. Its exit codes are shared with the launcher. */
fun main(args: Array<String>) {
    val (options, config) = try {
        parseEnrichOptions(args) to loadEnrichProcessConfig()
    } catch (e: ConfigurationException) {
        logger.error(e.message)
        exitProcess(EnrichExit.FATAL)
    }
    exitProcess(runLaunch(options, config))
}

private fun runLaunch(options: EnrichOptions, config: EnrichProcessConfig): Int {
    val clock = Clock.systemUTC()
    val sources = SourceIndex(loadSources(config.sourcesDir, config.maxSources, clock))
    val fetchClient = crawlerHttpClient()
    val rateLimiter = HostRateLimiter(config.crawler.hostDelayMs)
    val catalog = CatalogApiClient(config.crawler, clock)
    val workers = List(options.workers) {
        val fetcher = KtorPageFetcher(config.crawler, clock, fetchClient, rateLimiter = rateLimiter)
        EnrichWorker(
            step = EnrichmentStep(config.enrichment, fetcher, pagesFetched = { fetcher.pagesFetched }),
            writer = if (options.dryRun) DryRunWriter() else ApiCrawlWriter(catalog),
        )
    }
    val launcher = EnrichLauncher(
        config = config.enrichment,
        options = options,
        maxRuntimeMs = TimeUnit.MINUTES.toMillis(config.maxRuntimeMinutes),
        catalog = catalog,
        workers = workers,
        sources = sources,
        clock = clock,
    )
    return try {
        runBlocking {
            val launch = async { launcher.run() }
            val hook = cancelOnShutdown(launch)
            try {
                launch.await()
            } finally {
                runCatching { Runtime.getRuntime().removeShutdownHook(hook) }
            }
        }
    } finally {
        catalog.close()
        fetchClient.close()
    }
}

/** SIGTERM cancels the launch before another request starts. The server applies any in-flight PUT atomically. */
private fun cancelOnShutdown(launch: Job): Thread {
    val hook = Thread {
        launch.cancel()
        runBlocking { withTimeoutOrNull(SHUTDOWN_GRACE_MS) { launch.join() } }
    }
    Runtime.getRuntime().addShutdownHook(hook)
    return hook
}

private fun loadSources(dir: String?, maxSources: Int, clock: Clock): SourceLoadReport {
    if (dir == null) {
        logger.warn("VDS_DISCOVERY_SOURCES_DIR is not set; every provider is crawled from its site's / with no observed facets")
        return SourceLoadReport.NOT_LOADED
    }
    val compiled = AdapterRegistry.compiledEntries()
    val report = SourceConfigLoader(clock).load(
        directory = dir,
        maxSources = maxSources,
        reservedIds = compiled.map { it.adapter.id }.toSet(),
        reservedSlugs = compiled.map { it.adapter.providerSlug }.toSet(),
    )
    report.problems.forEach { logger.warn("Declarative sources not used: {}", it) }
    logger.info("Declarative sources loaded: {}", report.loaded.size)
    return report
}
