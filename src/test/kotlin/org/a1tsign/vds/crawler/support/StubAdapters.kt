package org.a1tsign.vds.crawler.support

import kotlinx.coroutines.delay
import org.a1tsign.vds.config.CrawlerConfig
import org.a1tsign.vds.config.Secret
import org.a1tsign.vds.crawler.CrawlContext
import org.a1tsign.vds.crawler.ProviderAdapter
import org.a1tsign.vds.crawler.SourceKind
import org.a1tsign.vds.crawler.fetch.FetchException
import org.a1tsign.vds.crawler.fetch.FetchedPage
import org.a1tsign.vds.crawler.fetch.PageFetcher
import org.a1tsign.vds.crawler.model.ProviderSnapshot
import org.a1tsign.vds.crawler.model.TariffSnapshot
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong

/**
 * The adapters the runner tests need: one that returns a given snapshot, one that throws, one that
 * sleeps past a budget, one whose snapshot the validator rejects. Test-only -- the two shipped
 * reference adapters are task vds-002.8.
 */
class StubProviderAdapter(
    override val id: String,
    override val providerSlug: String = id,
    override val sourceKind: SourceKind = SourceKind.HTML,
    private val behaviour: suspend (CrawlContext) -> ProviderSnapshot,
) : ProviderAdapter {
    override suspend fun fetch(ctx: CrawlContext): ProviderSnapshot = behaviour(ctx)
}

/**
 * Returns [snapshot], having fetched one page through the context's fetcher first -- so the runner's
 * `pagesFetched` really comes from the fetcher instead of being assumed.
 */
fun snapshotAdapter(id: String, snapshot: ProviderSnapshot): ProviderAdapter =
    StubProviderAdapter(id, snapshot.provider.slug) { ctx ->
        ctx.fetcher.get("https://$id.example/vps")
        snapshot
    }

fun throwingAdapter(id: String, failure: Throwable = IllegalStateException("selector matched nothing")): ProviderAdapter =
    StubProviderAdapter(id) { throw failure }

/** Sleeps [duration] in a cancellable way, so a time budget can actually cut it short. */
fun sleepingAdapter(id: String, duration: Duration, snapshot: ProviderSnapshot): ProviderAdapter =
    StubProviderAdapter(id, snapshot.provider.slug) {
        delay(duration.toMillis())
        snapshot
    }

/** A snapshot with no `sourceUrls` -- one problem, from an otherwise perfectly writable snapshot. */
fun invalidSnapshotAdapter(id: String, observedAt: Instant): ProviderAdapter =
    StubProviderAdapter(id) { stubSnapshot(id, observedAt).copy(sourceUrls = emptyList()) }

fun robotsDisallowedAdapter(id: String): ProviderAdapter =
    StubProviderAdapter(id) { throw FetchException.RobotsDisallowed("https://$id.example/vps") }

/**
 * A valid snapshot for the provider [slug], observed at [observedAt] -- which **must** come from the
 * test's clock: `SnapshotValidator` rejects an `observedAt` more than 60 s ahead of it, and the
 * shared fixture's own instant is years away from `AbstractDbTest`'s clock.
 */
fun stubSnapshot(
    slug: String,
    observedAt: Instant,
    tariffs: List<TariffSnapshot>? = listOf(stubTariff("VPS 1", "vps-1")),
): ProviderSnapshot = validSnapshot().copy(
    provider = validSnapshot().provider.copy(slug = slug, name = "Provider $slug"),
    legalInfo = null,
    paymentMethods = null,
    locations = null,
    tariffs = tariffs?.map { it.copy(locationKeys = null) },
    tariffListComplete = tariffs != null,
    observedAt = observedAt,
    sourceUrls = listOf("https://$slug.example/vps"),
)

/** USD so no FX rate is needed; see `ReconcilerTest`. */
fun stubTariff(name: String, externalId: String? = null): TariffSnapshot =
    validTariff(name, externalId, BigDecimal("9.9900")).let { tariff ->
        tariff.copy(prices = tariff.prices.map { it.copy(currency = "USD") })
    }

/** Counts what the adapters asked for, and never leaves the process. */
class StubPageFetcher(private val body: String = "<html><body>vps</body></html>") : PageFetcher {
    private val fetched = AtomicLong()

    val pagesFetched: Long get() = fetched.get()

    override suspend fun get(url: String): FetchedPage {
        fetched.incrementAndGet()
        return FetchedPage(url = url, finalUrl = url, status = 200, contentType = "text/html", body = body)
    }

    override suspend fun getHtml(url: String): Document = Jsoup.parse(get(url).body, url)
}

/**
 * A [CrawlerConfig] built directly, so a test needs neither the environment nor a database URL.
 * Defaults are the production ones except [enabled].
 */
fun testCrawlerConfig(
    enabled: Boolean = true,
    adapters: List<String> = emptyList(),
    maxConcurrentAdapters: Int = 1,
    maxOutOfStockRatio: BigDecimal = BigDecimal("0.5"),
    dryRunDefault: Boolean = false,
    intervalHours: Long = 24,
    initialDelayMinutes: Long = 10,
    adapterTimeoutMinutes: Long = 5,
    runTimeoutMinutes: Long = 30,
): CrawlerConfig = CrawlerConfig(
    enabled = enabled,
    apiBaseUrl = "http://127.0.0.1:8080",
    username = "crawler-service",
    password = Secret("crawler-password-1"),
    intervalHours = intervalHours,
    initialDelayMinutes = initialDelayMinutes,
    adapters = adapters,
    maxConcurrentAdapters = maxConcurrentAdapters,
    adapterTimeoutMinutes = adapterTimeoutMinutes,
    runTimeoutMinutes = runTimeoutMinutes,
    hostDelayMs = 2_000,
    userAgent = "vds-provider-grabber/test",
    contactUrl = null,
    respectRobots = true,
    maxBodyBytes = 2_097_152,
    maxOutOfStockRatio = maxOutOfStockRatio,
    dryRunDefault = dryRunDefault,
)
