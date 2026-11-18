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
 * Opt-in fixture adapter used by end-to-end and footprint checks. Configure VDS_CRAWLER_ADAPTER_EXAMPLE_STATIC_BASE_URL to select the fixture host.
 * Parse prices as exact decimals and fail on unreadable values. The complete-list claim makes the mass-change guard applicable.
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


    private fun parseLegalInfo(page: Element, pageUrl: String) = LegalInfoSnapshot(
        legalEntityName = requiredText(page, "dd.legal-entity"),
        registrationCountry = requiredText(page, "dd.legal-country"),
        registrationNumber = requiredText(page, "dd.legal-registration"),
        legalAddress = requiredText(page, "dd.legal-address"),
        // Sales copy is weak evidence. LOW confidence lets the catalog preserve stronger legal information.
        confidence = Confidence.LOW,
        sourceUrl = pageUrl,
    )

    /** Require the section to distinguish a broken selector from an observed empty set that clears the catalog values. */
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


    /**
     * The advertised amount, as [BigDecimal] and never `Double`, plus the currency its symbol
     * stands for. A cell that does not match -- "ask us", a symbol this adapter does not know, a
     * number with five decimals -- is an [AdapterFailureException]: a price silently read as `0`
     * would be appended to an append-only, not-back-correctable price history.
     */
    private fun parsePrice(row: Element, selector: String, billingPeriod: BillingPeriod): PriceSnapshot {
        // jsoup text() already normalizes non-breaking spaces in prices.
        val raw = requiredText(row, selector)
        val match = PRICE_PATTERN.matchEntire(raw)
            ?: throw AdapterFailureException(
                "'$selector' is not a price this adapter can read: '${raw.take(VALUE_IN_MESSAGE_LIMIT)}'",
            )
        return PriceSnapshot(
            billingPeriod = billingPeriod,
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

        const val PAGE_PATH: String = "/vps"

        /** `VDS_CRAWLER_ADAPTER_EXAMPLE_STATIC_BASE_URL` as `crawlerAdapterConfig` keys it. */
        const val BASE_URL_CONFIG_KEY: String = "base_url"

        const val BASE_URL_VARIABLE: String = "VDS_CRAWLER_ADAPTER_EXAMPLE_STATIC_BASE_URL"

        private const val PLAN_ID_ATTRIBUTE = "data-plan-id"
        private const val DISK_TYPE_ATTRIBUTE = "data-disk-type"
        private const val VIRTUALIZATION_ATTRIBUTE = "data-virtualization"
        private const val PAYMENT_METHOD_ATTRIBUTE = "data-method"

        private const val VALUE_IN_MESSAGE_LIMIT = 40

        private val HTTP_SCHEMES = setOf("http", "https")

        private val CURRENCY_BY_SYMBOL = mapOf("\$" to "USD", "€" to "EUR")

        /** Keep fractional digits within the NUMERIC(12, 4) storage limit. */
        private val PRICE_PATTERN =
            Regex("([\\$€])\\s*((?:[0-9]{1,3}(?:,[0-9]{3})+|[0-9]+)(?:\\.[0-9]{1,4})?)")
    }
}
