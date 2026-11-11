package org.a1tsign.vds.crawler.adapters

import org.a1tsign.vds.crawler.CrawlContext
import org.a1tsign.vds.crawler.ProviderAdapter
import org.a1tsign.vds.crawler.SourceKind
import org.a1tsign.vds.crawler.config.DecimalSpec
import org.a1tsign.vds.crawler.config.EnumSpec
import org.a1tsign.vds.crawler.config.FieldSpec
import org.a1tsign.vds.crawler.config.FlagSpec
import org.a1tsign.vds.crawler.config.IntSpec
import org.a1tsign.vds.crawler.config.LegalInfoSection
import org.a1tsign.vds.crawler.config.LocationsSection
import org.a1tsign.vds.crawler.config.PaymentMethodsSection
import org.a1tsign.vds.crawler.config.PriceSpec
import org.a1tsign.vds.crawler.config.ProviderSection
import org.a1tsign.vds.crawler.config.SourceConfig
import org.a1tsign.vds.crawler.config.SourceConfigFormat
import org.a1tsign.vds.crawler.config.TariffsSection
import org.a1tsign.vds.crawler.config.TextSpec
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

/**
 * The **one** compiled adapter that reads every declarative source: it interprets a
 * [SourceConfig] (one JSON file per site, authored by a human) instead of being written per site.
 *
 * It is an ordinary [ProviderAdapter] -- the SPI is unchanged -- so everything vds-002 built around
 * adapters applies to it untouched: the polite shared fetcher, the per-adapter time budget, snapshot
 * validation, the reconciler and its mass-change guard. In particular it constructs no `HttpClient`,
 * never calls `Jsoup.connect`, has no write path, and takes its page from the **config**
 * ([SourceConfig.pageUrl]) rather than from [CrawlContext.config].
 *
 * ### The four-layer defence of ADR-03, through a config instead of through Kotlin
 *
 * 1. A **section omitted** from the config leaves its snapshot field `null` = "not observed", so the
 *    reconciler touches that part of the catalog not at all.
 * 2. A **configured** section whose `rootSelector` matches nothing is an [AdapterFailureException]
 *    (`FAILED`/`ADAPTER_ERROR`, zero writes), exactly like `ExampleStaticAdapter.required()`. It
 *    never degrades into an empty observed set, which would *replace* the catalog's set with
 *    nothing.
 * 3. A list section matching fewer rows than its `minRows` (default 1) fails the same way;
 *    `"minRows": 0` is the author's explicit "this site honestly lists none".
 * 4. `tariffListComplete` comes from the file and defaults to `false`, so the claim the mass-change
 *    guard acts on is always deliberate.
 *
 * A **required** field (the default) that is missing fails the source; an optional one that is
 * absent is `null`. A field that is *present* but unreadable -- a non-numeric cell, a price the
 * options cannot parse, an enum value the map does not name -- always fails, optional or not: the
 * page has changed under the config, and a value quietly dropped would be recorded as "never
 * observed". Amounts are [BigDecimal] throughout; `Double`/`Float` appear nowhere.
 */
class DeclarativeAdapter(private val config: SourceConfig) : ProviderAdapter {

    override val id: String = config.id

    override val providerSlug: String = config.providerSlug

    override val sourceKind: SourceKind = SourceKind.HTML

    override suspend fun fetch(ctx: CrawlContext): ProviderSnapshot {
        val pageUrl = config.pageUrl()
        val page = ctx.fetcher.getHtml(pageUrl)
        val locations = config.locations?.let { parseLocations(page, it) }
        return ProviderSnapshot(
            provider = parseProvider(page),
            legalInfo = config.legalInfo?.let { parseLegalInfo(page, it, pageUrl) },
            paymentMethods = config.paymentMethods?.let { parsePaymentMethods(page, it) },
            locations = locations,
            tariffs = config.tariffs?.let { parseTariffs(page, it, locations) },
            tariffListComplete = config.tariffListComplete,
            observedAt = ctx.clock.instant(),
            sourceUrls = listOf(pageUrl),
        )
    }

    // ---------- sections ----------

    private fun parseProvider(page: Element): ProviderInfo {
        val section: ProviderSection = config.provider
            ?: throw AdapterFailureException("the config has no 'provider' section, so no snapshot can be built")
        val root = sectionRoot(page, section.rootSelector, "provider")
        return ProviderInfo(
            // From the config, which the format pins to baseUrl's own domain: an adapter cannot
            // point itself at an unrelated catalog row.
            slug = config.providerSlug,
            name = present(text(root, section.name, "provider.name"), "provider.name"),
            websiteUrl = present(text(root, section.websiteUrl, "provider.websiteUrl"), "provider.websiteUrl"),
            description = section.description?.let { text(root, it, "provider.description") },
            status = constant(ProviderStatus.entries, section.status, "provider.status"),
        )
    }

    /**
     * `confidence` is always [Confidence.LOW] and `sourceUrl` is always the page actually read: a
     * company blurb on a sales page is weak evidence by nature, and vds-001 keeps a human's
     * HIGH/MEDIUM record untouched behind that.
     */
    private fun parseLegalInfo(page: Element, section: LegalInfoSection, pageUrl: String): LegalInfoSnapshot {
        val root = sectionRoot(page, section.rootSelector, "legalInfo")
        return LegalInfoSnapshot(
            legalEntityName = section.legalEntityName?.let { text(root, it, "legalInfo.legalEntityName") },
            registrationCountry = section.registrationCountry?.let { text(root, it, "legalInfo.registrationCountry") },
            registrationNumber = section.registrationNumber?.let { text(root, it, "legalInfo.registrationNumber") },
            legalAddress = section.legalAddress?.let { text(root, it, "legalInfo.legalAddress") },
            confidence = Confidence.LOW,
            sourceUrl = pageUrl,
        )
    }

    /**
     * An **observed** set, so it really does replace the catalog's set (ADR-03) -- which is why the
     * section's absence from the page is a failure and only `minRows: 0` can make it empty.
     */
    private fun parsePaymentMethods(page: Element, section: PaymentMethodsSection): Set<PaymentMethod> =
        rows(page, section.rootSelector, section.itemSelector, section.minRows, "paymentMethods").map { item ->
            present(
                enum(item, section.method, PaymentMethod.entries, "paymentMethods.method"),
                "paymentMethods.method",
            )
        }.toSet()

    private fun parseLocations(page: Element, section: LocationsSection): List<LocationSnapshot> =
        rows(page, section.rootSelector, section.rowSelector, section.minRows, "locations").map { row ->
            LocationSnapshot(
                key = LocationKey(
                    countryCode = present(text(row, section.countryCode, "locations.countryCode"), "locations.countryCode"),
                    city = section.city?.let { text(row, it, "locations.city") },
                    datacenterName = section.datacenterName?.let { text(row, it, "locations.datacenterName") },
                ),
                region = section.region?.let { text(row, it, "locations.region") },
                latitude = section.latitude?.let { decimal(row, it, "locations.latitude") },
                longitude = section.longitude?.let { decimal(row, it, "locations.longitude") },
            )
        }

    private fun parseTariffs(
        page: Element,
        section: TariffsSection,
        locations: List<LocationSnapshot>?,
    ): List<TariffSnapshot> {
        val locationKeys = if (section.locationKeys == SourceConfigFormat.LOCATIONS_ALL) {
            locations?.map { it.key }
                ?: throw AdapterFailureException(
                    "'tariffs.locationKeys' is ${SourceConfigFormat.LOCATIONS_ALL} but no 'locations' section is configured",
                )
        } else {
            // null = "this source did not observe which locations a tariff can be ordered in", so
            // the catalog's tariff-to-location links are left exactly as they are.
            null
        }
        return rows(page, section.rootSelector, section.rowSelector, section.minRows, "tariffs").map { row ->
            TariffSnapshot(
                externalId = section.externalId?.let { text(row, it, "tariffs.externalId") },
                name = present(text(row, section.name, "tariffs.name"), "tariffs.name"),
                url = section.url?.let { text(row, it, "tariffs.url") },
                vcpuCount = present(int(row, section.vcpuCount, "tariffs.vcpuCount"), "tariffs.vcpuCount"),
                cpuModel = section.cpuModel?.let { text(row, it, "tariffs.cpuModel") },
                ramMb = present(size(row, section.ramMb, SizeUnit.MB, "tariffs.ramMb"), "tariffs.ramMb"),
                diskGb = present(size(row, section.diskGb, SizeUnit.GB, "tariffs.diskGb"), "tariffs.diskGb"),
                diskType = present(enum(row, section.diskType, DiskType.entries, "tariffs.diskType"), "tariffs.diskType"),
                bandwidthMbps = section.bandwidthMbps?.let { int(row, it, "tariffs.bandwidthMbps") },
                trafficLimitGb = section.trafficLimitGb?.let { size(row, it, SizeUnit.GB, "tariffs.trafficLimitGb") },
                ipv4Count = present(int(row, section.ipv4Count, "tariffs.ipv4Count"), "tariffs.ipv4Count"),
                ipv6Supported = section.ipv6Supported?.let { flag(row, it, "tariffs.ipv6Supported") },
                virtualization = present(
                    enum(row, section.virtualization, Virtualization.entries, "tariffs.virtualization"),
                    "tariffs.virtualization",
                ),
                // Absent from the config = in stock, the snapshot's own default: a source that does
                // not report stock may never flip a tariff out of it.
                inStock = section.inStock?.let { flag(row, it, "tariffs.inStock") } ?: true,
                locationKeys = locationKeys,
                prices = section.prices.mapIndexedNotNull { index, spec ->
                    price(row, spec, "tariffs.prices[$index]")
                },
            )
        }
    }

    // ---------- the page ----------

    /** A configured section that matches nothing is a broken selector, never an observation. */
    private fun sectionRoot(page: Element, rootSelector: String?, label: String): Element =
        if (rootSelector == null) {
            page
        } else {
            page.selectFirst(rootSelector)
                ?: throw AdapterFailureException("the page has no element matching '$rootSelector' ('$label.rootSelector')")
        }

    private fun rows(
        page: Element,
        rootSelector: String,
        rowSelector: String,
        minRows: Int,
        label: String,
    ): List<Element> {
        val root = sectionRoot(page, rootSelector, label)
        val rows = root.select(rowSelector)
        if (rows.size < minRows) {
            throw AdapterFailureException(
                "'$rowSelector' matched ${rows.size} row(s) inside '$rootSelector', fewer than " +
                    "the $minRows '$label.minRows' requires",
            )
        }
        return rows
    }

    // ---------- values ----------

    /**
     * The raw page value, or `null` for an **absent** optional one. Absence of a required value is
     * this source's failure, naming the selector and at most
     * [SourceConfigFormat.VALUE_IN_MESSAGE_LIMIT] characters of anything it quotes.
     */
    private fun raw(root: Element, spec: FieldSpec, key: String): String? {
        spec.value?.let { return it }
        val element = root.selectFirst(spec.selector)
            ?: return absent(spec, "$key: the page has no element matching '${spec.selector}'")
        val attribute = spec.attr
        val value = if (attribute != null) element.attr(attribute) else element.text()
        if (value.isEmpty()) {
            return absent(
                spec,
                if (attribute != null) {
                    "$key: '${spec.selector}' carries no '$attribute'"
                } else {
                    "$key: '${spec.selector}' is empty"
                },
            )
        }
        return value
    }

    private fun absent(spec: FieldSpec, problem: String): String? =
        if (spec.required) throw AdapterFailureException(problem) else null

    private fun text(root: Element, spec: TextSpec, key: String): String? = raw(root, spec, key)

    private fun int(root: Element, spec: IntSpec, key: String): Int? {
        val value = raw(root, spec, key) ?: return null
        val number = FIRST_NUMBER.find(value)?.groupValues?.get(1)
            ?: throw AdapterFailureException("$key is not a whole number: '${cut(value)}'")
        return whole(BigDecimal(number.replace(",", "")), key, value)
    }

    /**
     * A size in [target] units. The unit written after the number wins; a page that writes none
     * is read in the config's `unit`, or in [target] itself.
     */
    private fun size(root: Element, spec: IntSpec, target: SizeUnit, key: String): Int? {
        val value = raw(root, spec, key) ?: return null
        val match = FIRST_NUMBER.find(value)
            ?: throw AdapterFailureException("$key is not a size this source can read: '${cut(value)}'")
        val unit = SizeUnit.written(match.groupValues[2])
            ?: spec.unit?.let { name -> SizeUnit.entries.firstOrNull { it.name == name } }
            ?: target
        val amount = BigDecimal(match.groupValues[1].replace(",", ""))
        return whole(amount.multiply(unit.megabytes(target)).divide(target.megabytes(target)), key, value)
    }

    private fun whole(number: BigDecimal, key: String, value: String): Int =
        try {
            number.intValueExact()
        } catch (notWhole: ArithmeticException) {
            throw AdapterFailureException("$key is not a whole number: '${cut(value)}'")
        }

    private fun <T : Enum<T>> enum(root: Element, spec: EnumSpec, constants: List<T>, key: String): T? {
        val value = raw(root, spec, key) ?: return null
        val name = spec.value
            ?: spec.values[value]
            ?: spec.default
            ?: throw AdapterFailureException(
                "$key is not a value this source maps (${spec.values.keys.joinToString { cut(it) }}): '${cut(value)}'",
            )
        return constants.firstOrNull { it.name == name }
            ?: throw AdapterFailureException(
                "$key maps to '${cut(name)}', which is not one of ${constants.joinToString { it.name }}",
            )
    }

    private fun flag(root: Element, spec: FlagSpec, key: String): Boolean? {
        val value = raw(root, spec, key) ?: return null
        return spec.value?.toBooleanStrictOrNull()
            ?: spec.values[value]
            ?: spec.default
            ?: throw AdapterFailureException(
                "$key is not a value this source maps (${spec.values.keys.joinToString { cut(it) }}): '${cut(value)}'",
            )
    }

    private fun decimal(root: Element, spec: DecimalSpec, key: String): BigDecimal? {
        val value = raw(root, spec, key) ?: return null
        return number(value, spec.grouping, spec.decimal, spec.maxFractionDigits, signed = true, key = key)
    }

    /**
     * The advertised amount as [BigDecimal] -- never `Double` -- plus the currency its symbol stands
     * for. A cell that does not parse ("ask us", an unknown symbol, more decimals than
     * `NUMERIC(12, 4)` stores) fails the source: an amount silently read as `0` would be appended to
     * a price history that is not back-correctable.
     */
    private fun price(row: Element, spec: PriceSpec, key: String): PriceSnapshot? {
        val value = raw(row, spec, key) ?: return null
        val fixed = spec.currency.fixed
        val symbols = spec.currency.bySymbol.orEmpty()
        // The longest matching symbol wins, so "US$" is not read as "$" when both are mapped.
        val symbol = symbols.keys.filter { value.contains(it) }.maxByOrNull { it.length }
        val currency = fixed
            ?: symbol?.let { symbols.getValue(it) }
            ?: throw AdapterFailureException(
                "$key carries no currency symbol this source knows " +
                    "(${symbols.keys.joinToString { cut(it) }}): '${cut(value)}'",
            )
        // After the symbol first, so "Save 20% - $5.00/mo" reads 5.00 and not 20; a page that
        // writes the symbol last ("1.234,56 EUR") has its number before it.
        val candidates = if (fixed == null && symbol != null) {
            listOf(value.substringAfter(symbol), value.substringBefore(symbol))
        } else {
            listOf(value)
        }
        val digits = candidates.firstNotNullOfOrNull { firstAmount(it, spec) }
            ?: throw AdapterFailureException("$key is not a number this source can read: '${cut(value)}'")
        return PriceSnapshot(
            billingPeriod = constant(BillingPeriod.entries, spec.billingPeriod, "$key.billingPeriod"),
            amount = number(digits, null, ".", spec.maxFractionDigits, signed = false, key = key),
            currency = currency,
        )
    }

    /** The first unsigned number in [text] under the config's separators; a minus sign before it is refused. */
    private fun firstAmount(text: String, spec: PriceSpec): String? {
        var normalized = text
        spec.grouping?.let { normalized = normalized.replace(it, "") }
        if (spec.decimal != ".") normalized = normalized.replace(spec.decimal, ".")
        val match = FIRST_NUMBER_PLAIN.find(normalized) ?: return null
        val negative = normalized.substring(0, match.range.first).trimEnd().endsWith("-")
        return match.value.takeUnless { negative }
    }

    /**
     * The page's number under the config's declarative options -- strip the grouping character,
     * read the decimal one -- and nothing else: no pattern, no locale guess, no expression. The
     * result must be a plain decimal within [maxFractionDigits], or this source fails.
     */
    private fun number(
        value: String,
        grouping: String?,
        decimal: String,
        maxFractionDigits: Int,
        signed: Boolean,
        key: String,
    ): BigDecimal {
        var digits = value.trim()
        if (grouping != null) digits = digits.replace(grouping, "")
        if (decimal != ".") digits = digits.replace(decimal, ".")
        digits = digits.trim()
        val pattern = if (signed) SIGNED_NUMBER else UNSIGNED_NUMBER
        if (!pattern.matches(digits)) {
            throw AdapterFailureException("$key is not a number this source can read: '${cut(value)}'")
        }
        val amount = BigDecimal(digits)
        if (amount.scale() > maxFractionDigits) {
            throw AdapterFailureException(
                "$key has more than $maxFractionDigits fraction digits, which the catalog cannot store: '${cut(value)}'",
            )
        }
        return amount
    }

    /**
     * A constant the config names. Unreachable for a config the format validated -- which checks
     * every constant against the target enum -- and still a failure rather than a crash here,
     * because a [DeclarativeAdapter] can be built from a config nobody validated.
     */
    private fun <T : Enum<T>> constant(constants: List<T>, name: String, key: String): T =
        constants.firstOrNull { it.name == name }
            ?: throw AdapterFailureException(
                "$key is not one of ${constants.joinToString { it.name }}: '${cut(name)}'",
            )

    /**
     * A value the snapshot has no place to be missing from. The format refuses `"required": false`
     * on such a field, so this is the same defensive backstop as [constant].
     */
    private fun <T : Any> present(value: T?, key: String): T =
        value ?: throw AdapterFailureException("$key has no value, and the snapshot has no place for a missing one")

    private fun cut(value: String): String = value.take(SourceConfigFormat.VALUE_IN_MESSAGE_LIMIT)

    private companion object {
        /** A plain decimal; the separators have already been applied. Code-owned, never from a config. */
        val UNSIGNED_NUMBER = Regex("^[0-9]+(\\.[0-9]+)?$")

        val SIGNED_NUMBER = Regex("^-?[0-9]+(\\.[0-9]+)?$")

        /** The first number in a value (`,` groups thousands) and the word right after it. Code-owned. */
        val FIRST_NUMBER = Regex("([0-9][0-9,]*(?:\\.[0-9]+)?)\\s*([A-Za-z]*)")
        val FIRST_NUMBER_PLAIN = Regex("[0-9]+(\\.[0-9]+)?")
    }

    /**
     * Memory is binary (1 GB = 1024 MB); disk and traffic are sold in decimal units (1 TB = 1000 GB).
     */
    private enum class SizeUnit {
        MB, GB, TB;

        fun megabytes(target: SizeUnit): BigDecimal {
            val step = if (target == MB) BINARY_STEP else DECIMAL_STEP
            return step.pow(ordinal)
        }

        companion object {
            private val BINARY_STEP = BigDecimal(1024)
            private val DECIMAL_STEP = BigDecimal(1000)

            fun written(word: String): SizeUnit? = when (word.lowercase()) {
                "mb", "mib" -> MB
                "gb", "gib" -> GB
                "tb", "tib" -> TB
                else -> null
            }
        }
    }
}
