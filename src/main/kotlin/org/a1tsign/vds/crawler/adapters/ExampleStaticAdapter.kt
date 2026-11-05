package org.a1tsign.vds.crawler.adapters

import org.a1tsign.vds.crawler.CrawlContext
import org.a1tsign.vds.crawler.ProviderAdapter
import org.a1tsign.vds.crawler.SourceKind
import org.a1tsign.vds.crawler.model.LegalInfoSnapshot
import org.a1tsign.vds.crawler.model.LocationKey
import org.a1tsign.vds.crawler.model.LocationSnapshot
import org.a1tsign.vds.crawler.model.PriceSnapshot
import org.a1tsign.vds.crawler.model.ProviderInfo
import org.a1tsign.vds.crawler.model.ProviderSnapshot
import org.a1tsign.vds.crawler.model.TariffSnapshot
import org.a1tsign.vds.domain.BillingPeriod
import org.a1tsign.vds.domain.Confidence
import org.a1tsign.vds.domain.DiskType
import org.a1tsign.vds.domain.PaymentMethod
import org.a1tsign.vds.domain.ProviderStatus
import org.a1tsign.vds.domain.Virtualization
import org.jsoup.nodes.Element
import java.math.BigDecimal
import java.net.URI

/**
 * Everything the two reference adapters report as their own failure: a missing or malformed
 * configuration value, or a page that does not match the selectors. The orchestrator turns it into
 * that adapter's `ADAPTER_ERROR` result and carries [message] into the run summary, so the message
 * names the offending selector or variable and -- at most 40 characters of -- the value that broke,
 * never a token or a whole page body.
 */
class AdapterFailureException(message: String) : Exception(message)

/**
 * The HTML reference adapter (spec req. 42): it demonstrates the whole loop -- one polite fetch,
 * jsoup selectors, a full [ProviderSnapshot], reconciliation into the catalog -- against a **fixture
 * page**, and is the adapter the end-to-end test and `scripts/measure-footprint.sh --crawler` drive.
 * It is **not** an adapter for a real hosting company; each of those is its own spec, ToS check
 * included.
 *
 * ### Why it is registered but not selected by default
 *
 * [org.a1tsign.vds.crawler.AdapterRegistry.production] registers it with `includedByDefault = false`,
 * so an empty `VDS_CRAWLER_ADAPTERS` never runs it on a production box; an operator opts in by
 * naming `example-static` explicitly (the plan's resolution of spec req. 10 vs req. 42).
 *
 * ### No hardcoded host
 *
 * The page it reads is `<VDS_CRAWLER_ADAPTER_EXAMPLE_STATIC_BASE_URL>[PAGE_PATH]`, supplied through
 * [CrawlContext.config] -- a test points it at the local fixture server, the footprint script at a
 * sidecar, and nothing in this file names a host. A missing or malformed value is an
 * [AdapterFailureException], i.e. this adapter's own failure, not a crash of the run.
 *
 * ### What it is allowed to use
 *
 * Only [CrawlContext]: the shared polite [org.a1tsign.vds.crawler.fetch.PageFetcher] (so the
 * timeouts, the per-host delay, robots.txt and the streamed 2 MiB body cap cannot be opted out of),
 * the injected clock and its own config. No `HttpClient`, no `Jsoup.connect`, no `CatalogApiClient`,
 * no write path of any kind (ADR-02/ADR-04).
 *
 * ### Money and honesty
 *
 * Amounts are parsed into [BigDecimal] straight from the page text -- `Double` appears nowhere --
 * and a value that does not parse fails the adapter instead of silently becoming `0`.
 * `tariffListComplete = true` is a deliberate claim that the plans table is the provider's complete
 * catalog (ADR-03): when a selector breaks and the table reads empty, that claim is what makes the
 * reconciler's mass-change guard refuse the run instead of flipping every tariff out of stock.
 */
class ExampleStaticAdapter : ProviderAdapter {

    override val id: String = ID

    /** Immutable by contract: changing it would re-point this adapter at a different catalog row. */
    override val providerSlug: String = PROVIDER_SLUG

    override val sourceKind: SourceKind = SourceKind.HTML

    override suspend fun fetch(ctx: CrawlContext): ProviderSnapshot {
        val pageUrl = pageUrl(ctx.config)
        val page = ctx.fetcher.getHtml(pageUrl)
        val locations = parseLocations(page)
        return ProviderSnapshot(
            provider = ProviderInfo(
                slug = PROVIDER_SLUG,
                name = requiredText(page, "h1.provider-name"),
                websiteUrl = requiredAttribute(page, "a.provider-site", "href"),
                // Not read from the page: a plans page that answers and lists plans is the only
                // "active" signal this fixture carries, and inventing a richer status from markup
                // would be a guess the catalog would then treat as observed fact.
                status = ProviderStatus.ACTIVE,
            ),
            legalInfo = parseLegalInfo(page, pageUrl),
            paymentMethods = parsePaymentMethods(page),
            locations = locations,
            tariffs = parseTariffs(page, locations.map { it.key }),
            tariffListComplete = true,
            observedAt = ctx.clock.instant(),
            sourceUrls = listOf(pageUrl),
        )
    }

    // ---------- configuration ----------

    /**
     * The one page this adapter reads. [config] is the adapter's own
     * `VDS_CRAWLER_ADAPTER_EXAMPLE_STATIC_*` slice, keyed by the lower-cased suffix, so the base URL
     * arrives under [BASE_URL_CONFIG_KEY].
     */
    private fun pageUrl(config: Map<String, String>): String {
        val configured = config[BASE_URL_CONFIG_KEY]?.trim()
        if (configured.isNullOrEmpty()) {
            throw AdapterFailureException("$BASE_URL_VARIABLE is not set, so this adapter has no page to read")
        }
        val base = configured.trimEnd('/')
        runCatching { URI(base) }.getOrNull()
            ?.takeIf { it.scheme?.lowercase() in HTTP_SCHEMES && !it.host.isNullOrEmpty() }
            ?: throw AdapterFailureException(
                "$BASE_URL_VARIABLE must be an absolute http or https URL, got '${base.take(VALUE_IN_MESSAGE_LIMIT)}'",
            )
        return base + PAGE_PATH
    }

    // ---------- sections ----------

    private fun parseLegalInfo(page: Element, pageUrl: String) = LegalInfoSnapshot(
        legalEntityName = requiredText(page, "dd.legal-entity"),
        registrationCountry = requiredText(page, "dd.legal-country"),
        registrationNumber = requiredText(page, "dd.legal-registration"),
        legalAddress = requiredText(page, "dd.legal-address"),
        // A company blurb on a sales page is weak evidence by nature, and the crawler never claims
        // otherwise: vds-001 keeps a human's HIGH/MEDIUM record untouched behind this.
        confidence = Confidence.LOW,
        sourceUrl = pageUrl,
    )

    /**
     * An **observed** set, so it really does replace the catalog's set (ADR-03). The section itself
     * is required -- its absence is a broken selector, not an observation -- while a section that
     * lists nothing is the honest "this provider advertises no payment method".
     */
    private fun parsePaymentMethods(page: Element): Set<PaymentMethod> =
        required(page, "section.payments").select("li.payment-method").map { item ->
            requiredEnum(
                PaymentMethod.entries.toTypedArray(),
                requiredAttribute(item, "li.payment-method", PAYMENT_METHOD_ATTRIBUTE),
                "'li.payment-method[$PAYMENT_METHOD_ATTRIBUTE]'",
            )
        }.toSet()

    private fun parseLocations(page: Element): List<LocationSnapshot> =
        required(page, "table.locations-table").select("tr.location")
            .ifEmpty { throw AdapterFailureException("'table.locations-table' has no 'tr.location' row") }
            .map { row ->
                LocationSnapshot(
                    key = LocationKey(
                        countryCode = requiredText(row, "td.country"),
                        city = requiredText(row, "td.city"),
                        datacenterName = requiredText(row, "td.datacenter"),
                    ),
                    region = requiredText(row, "td.region"),
                )
            }

    /**
     * Every plan row, each with its MONTHLY **and** its ANNUAL price. [locationKeys] is every
     * location of the page, which is what this provider's fixture claims: the plans table is global
     * and the locations table is the full list of places each plan can be ordered in.
     */
    private fun parseTariffs(page: Element, locationKeys: List<LocationKey>): List<TariffSnapshot> =
        required(page, "table.plans-table").select("tr.plan").map { row ->
            TariffSnapshot(
                externalId = requiredAttribute(row, "tr.plan", PLAN_ID_ATTRIBUTE),
                name = requiredText(row, "td.plan-name"),
                vcpuCount = requiredInt(row, "td.vcpu"),
                ramMb = requiredInt(row, "td.ram-mb"),
                diskGb = requiredInt(row, "td.disk-gb"),
                diskType = requiredEnum(
                    DiskType.entries.toTypedArray(),
                    requiredAttribute(row, "tr.plan", DISK_TYPE_ATTRIBUTE),
                    "'tr.plan[$DISK_TYPE_ATTRIBUTE]'",
                ),
                ipv4Count = requiredInt(row, "td.ipv4"),
                virtualization = requiredEnum(
                    Virtualization.entries.toTypedArray(),
                    requiredAttribute(row, "tr.plan", VIRTUALIZATION_ATTRIBUTE),
                    "'tr.plan[$VIRTUALIZATION_ATTRIBUTE]'",
                ),
                locationKeys = locationKeys,
                prices = listOf(
                    parsePrice(row, "td.price-monthly", BillingPeriod.MONTHLY),
                    parsePrice(row, "td.price-annual", BillingPeriod.ANNUAL),
                ),
            )
        }

    // ---------- values ----------

    /**
     * The advertised amount, as [BigDecimal] and never `Double`, plus the currency its symbol
     * stands for. A cell that does not match -- "ask us", a symbol this adapter does not know, a
     * number with five decimals -- is an [AdapterFailureException]: a price silently read as `0`
     * would be appended to an append-only, not-back-correctable price history.
     */
    private fun parsePrice(row: Element, selector: String, billingPeriod: BillingPeriod): PriceSnapshot {
        // jsoup's text() normalises a non-breaking space (U+00A0) to a plain space and trims it, so
        // a price written with one (ordinary on a real page) needs no extra handling here.
        val raw = requiredText(row, selector)
        val match = PRICE_PATTERN.matchEntire(raw)
            ?: throw AdapterFailureException(
                "'$selector' is not a price this adapter can read: '${raw.take(VALUE_IN_MESSAGE_LIMIT)}'",
            )
        return PriceSnapshot(
            billingPeriod = billingPeriod,
            // getValue, not get: the pattern admits exactly the symbols of the map.
            currency = CURRENCY_BY_SYMBOL.getValue(match.groupValues[1]),
            amount = BigDecimal(match.groupValues[2].replace(",", "")),
        )
    }

    private fun required(root: Element, selector: String): Element =
        root.selectFirst(selector)
            ?: throw AdapterFailureException("the page has no element matching '$selector'")

    /** jsoup's `text()` already collapses and trims whitespace, so a padded cell reads clean. */
    private fun requiredText(root: Element, selector: String): String =
        required(root, selector).text().ifEmpty {
            throw AdapterFailureException("'$selector' of the page is empty")
        }

    private fun requiredAttribute(root: Element, selector: String, attribute: String): String =
        required(root, selector).attr(attribute).ifEmpty {
            throw AdapterFailureException("'$selector' of the page carries no '$attribute'")
        }

    private fun requiredInt(root: Element, selector: String): Int {
        val value = requiredText(root, selector)
        return value.toIntOrNull()
            ?: throw AdapterFailureException(
                "'$selector' is not a whole number: '${value.take(VALUE_IN_MESSAGE_LIMIT)}'",
            )
    }

    private fun <T : Enum<T>> requiredEnum(values: Array<T>, raw: String, label: String): T =
        values.firstOrNull { it.name == raw }
            ?: throw AdapterFailureException(
                "$label is not one of ${values.joinToString { it.name }}: '${raw.take(VALUE_IN_MESSAGE_LIMIT)}'",
            )

    companion object {
        const val ID: String = "example-static"
        const val PROVIDER_SLUG: String = "example-static"

        /** The page is this path under the configured base URL. */
        const val PAGE_PATH: String = "/vps"

        /** `VDS_CRAWLER_ADAPTER_EXAMPLE_STATIC_BASE_URL` as `crawlerAdapterConfig` keys it. */
        const val BASE_URL_CONFIG_KEY: String = "base_url"

        /** Spelled out for the failure message an operator reads in the run summary. */
        const val BASE_URL_VARIABLE: String = "VDS_CRAWLER_ADAPTER_EXAMPLE_STATIC_BASE_URL"

        private const val PLAN_ID_ATTRIBUTE = "data-plan-id"
        private const val DISK_TYPE_ATTRIBUTE = "data-disk-type"
        private const val VIRTUALIZATION_ATTRIBUTE = "data-virtualization"
        private const val PAYMENT_METHOD_ATTRIBUTE = "data-method"

        /** How much of an offending page value a failure message repeats. */
        private const val VALUE_IN_MESSAGE_LIMIT = 40

        private val HTTP_SCHEMES = setOf("http", "https")

        private val CURRENCY_BY_SYMBOL = mapOf("\$" to "USD", "€" to "EUR")

        /**
         * A symbol this adapter knows, optional spacing, then either plain digits or digits grouped
         * in thousands, with at most the four decimals vds-001's `NUMERIC(12, 4)` stores.
         */
        private val PRICE_PATTERN =
            Regex("([\\$€])\\s*((?:[0-9]{1,3}(?:,[0-9]{3})+|[0-9]+)(?:\\.[0-9]{1,4})?)")
    }
}
