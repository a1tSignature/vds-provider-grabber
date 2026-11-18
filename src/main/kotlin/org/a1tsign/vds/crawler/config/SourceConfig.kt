package org.a1tsign.vds.crawler.config

import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.a1tsign.vds.domain.BillingPeriod
import org.a1tsign.vds.domain.DiskType
import org.a1tsign.vds.domain.DomainName
import org.a1tsign.vds.domain.PaymentMethod
import org.a1tsign.vds.domain.ProviderStatus
import org.a1tsign.vds.domain.Virtualization
import org.jsoup.select.Selector
import org.slf4j.LoggerFactory
import java.net.URI

/**
 * A JSON description of one HTML source. Selector regexes and executable expressions are prohibited.
 * The source slug must match its domain unless explicitly acknowledged. Omitted sections remain unobserved, and missing configured roots fail.
 */
@Serializable
data class SourceConfig(
    /** Must equal [SourceConfigFormat.SCHEMA_VERSION]; a file from another format is refused, not guessed at. */
    val schemaVersion: Int,
    /** Adapter ID matching ^[a-z0-9-]+$, unique in the source directory. */
    val id: String,
    /** The `provider.slug` this source owns; checked against [baseUrl]'s domain. */
    val providerSlug: String,
    /** Absolute `http`/`https`, without query or fragment: [pagePath] is appended to it verbatim. */
    val baseUrl: String,
    val enabled: Boolean = false,
    val pagePath: String = "/",
    /** The selector format supports HTML sources only. */
    val sourceKind: String = SourceConfigFormat.SOURCE_KIND_HTML,
    val provider: ProviderSection? = null,
    val legalInfo: LegalInfoSection? = null,
    val paymentMethods: PaymentMethodsSection? = null,
    val locations: LocationsSection? = null,
    val tariffs: TariffsSection? = null,
    /**
     * The deliberate claim that [tariffs] is the provider's **complete** catalog -- the only claim
     * that lets the reconciler flip a tariff out of stock, and the one the mass-change guard acts
     * on. Default `false`, so claiming it is always a conscious act by the config's author.
     */
    val tariffListComplete: Boolean = false,
    /** Accepts a [providerSlug] that is not [baseUrl]'s slug, with a WARN on load. */
    val acknowledgeSlugMismatch: Boolean = false,
) {
    fun pageUrl(): String = baseUrl.trimEnd('/') + pagePath
}

/**
 * Where one value is read from and whether its absence fails the source.
 *
 * Exactly one of [text] (`true`) and [attr] (an attribute name) must be given -- the two ways
 * `ExampleStaticAdapter` reads a value, and the only two.
 *
 * [required] governs **absence** only. A value that is *present* but unreadable -- a non-numeric
 * cell, a price the options cannot parse, an enum value the map does not name and no `default`
 * covers -- always fails the source, optional or not: it means the page changed under the config,
 * and dropping it silently would record "not observed" about something that is plainly there.
 */
interface FieldSpec {
    val selector: String
    val text: Boolean
    val attr: String?
    val required: Boolean

    /**
     * A constant used instead of reading the page, for what a plans page states once or not at
     * all ("every plan is KVM with 1 IPv4"). Mutually exclusive with [selector]/[text]/[attr].
     */
    val value: String?
}

/** A string value, exactly as jsoup's `text()`/`attr()` returns it (whitespace already collapsed). */
@Serializable
data class TextSpec(
    override val selector: String = "",
    override val text: Boolean = false,
    override val attr: String? = null,
    override val required: Boolean = true,
    override val value: String? = null,
) : FieldSpec

/**
 * A whole number: the first number in the value, so `2 vCPU Cores` reads as 2. A value with no
 * number, or with a fractional one, fails the source instead of becoming `0`.
 *
 * On a size field (`ramMb`, `diskGb`, `trafficLimitGb`) the unit written after the number -- `MB`,
 * `MiB`, `GB`, `GiB`, `TB`, `TiB` -- is converted to the field's own unit; [unit] names the unit
 * of a page that writes none, and defaults to the field's own.
 */
@Serializable
data class IntSpec(
    override val selector: String = "",
    override val text: Boolean = false,
    override val attr: String? = null,
    override val required: Boolean = true,
    override val value: String? = null,
    val unit: String? = null,
) : FieldSpec

/**
 * An enum constant, through an explicit **value -> constant** map: a real page says `NVMe SSD`, not
 * `NVME`, so name equality (what `ExampleStaticAdapter` can afford on its own fixture) is not
 * enough. [default] is the constant to use for a value the map does not name; without it such a
 * value fails the source.
 */
@Serializable
data class EnumSpec(
    override val selector: String = "",
    val values: Map<String, String> = emptyMap(),
    val default: String? = null,
    override val text: Boolean = false,
    override val attr: String? = null,
    override val required: Boolean = true,
    override val value: String? = null,
) : FieldSpec

/** A boolean, through the same explicit map as [EnumSpec] -- never a guess from a value's shape. */
@Serializable
data class FlagSpec(
    override val selector: String = "",
    val values: Map<String, Boolean> = emptyMap(),
    val default: Boolean? = null,
    override val text: Boolean = false,
    override val attr: String? = null,
    override val required: Boolean = true,
    override val value: String? = null,
) : FieldSpec

/**
 * A plain decimal (a coordinate), with the page's own separator characters. [maxFractionDigits] is
 * bounded by [SourceConfigFormat.COORDINATE_SCALE] -- the `NUMERIC(9, 6)` the catalog stores.
 */
@Serializable
data class DecimalSpec(
    override val selector: String,
    val grouping: String? = null,
    val decimal: String = ".",
    val maxFractionDigits: Int = SourceConfigFormat.COORDINATE_SCALE,
    override val text: Boolean = false,
    override val attr: String? = null,
    override val required: Boolean = true,
    override val value: String? = null,
) : FieldSpec

/**
 * One advertised price: which [billingPeriod] it is, where to read it, which currency its symbol
 * stands for and how the page writes its number. The amount is the number right next to the
 * currency symbol, after it or before it, so `From $8.50 /month` reads 8.50 and `6.00 € −37%` reads
 * 6.00; failing that, the first number after (then before) the symbol. With a `fixed` currency it
 * is the first number in the value.
 *
 * [maxFractionDigits] is bounded by [SourceConfigFormat.MONEY_SCALE] -- the `NUMERIC(12, 4)` the
 * catalog stores. An amount with more fraction digits than that is refused rather than rounded: the
 * price history is append-only and not back-correctable.
 */
@Serializable
data class PriceSpec(
    val billingPeriod: String,
    override val selector: String,
    val currency: CurrencySpec,
    /** Thousands separator to strip, e.g. `","` or `" "`. One character; absent means none. */
    val grouping: String? = null,
    /** Decimal separator, one character -- `"."` or `","`. */
    val decimal: String = ".",
    val maxFractionDigits: Int = SourceConfigFormat.MONEY_SCALE,
    override val text: Boolean = false,
    override val attr: String? = null,
    override val required: Boolean = true,
    override val value: String? = null,
    /**
     * `false` (the default): struck-out amounts (`del`, `s`, `strike`, or a `line-through` /
     * `strike` / `old-price` class or style) inside the selected element are ignored, so the price
     * a customer pays now is read. `true` reads the struck regular price instead, from inside the
     * selected element or next to it.
     */
    val regularPrice: Boolean = false,
) : FieldSpec

/**
 * The currency of a price: either [fixed] (the page carries no symbol at all) or [bySymbol], the
 * symbols this page may use mapped to their ISO-4217 code. Exactly one of the two.
 *
 * A symbol the map does not name fails the source -- guessing `USD` from a `$` on a European page
 * would write a wrong amount into a price history that cannot be corrected afterwards.
 */
@Serializable
data class CurrencySpec(
    val fixed: String? = null,
    val bySymbol: Map<String, String>? = null,
)

/**
 * Where the provider's own identity is read. `status` is a **fixed** constant rather than a
 * selector: a plans page that answers is the only "active" signal such a page carries, and
 * inventing a richer status from markup would be a guess the catalog would then treat as observed
 * fact (the same reasoning `ExampleStaticAdapter` spells out).
 */
@Serializable
data class ProviderSection(
    val name: TextSpec,
    val websiteUrl: TextSpec,
    val rootSelector: String? = null,
    val description: TextSpec? = null,
    val status: String = SourceConfigFormat.DEFAULT_PROVIDER_STATUS,
)

/**
 * Where the company blurb is read. The snapshot's `confidence` is deliberately **not** a key: a
 * company description on a sales page is weak evidence by nature, so the interpreter always reports
 * `LOW` and a config cannot claim otherwise about a row a human may have verified.
 */
@Serializable
data class LegalInfoSection(
    val rootSelector: String? = null,
    val legalEntityName: TextSpec? = null,
    val registrationCountry: TextSpec? = null,
    val registrationNumber: TextSpec? = null,
    val legalAddress: TextSpec? = null,
)

/**
 * A list section's shape: the section itself ([rootSelector], whose absence is a broken selector,
 * not an observation) and the rows inside it.
 *
 * `minRows` defaults to **1**: a configured list that matches no row is a failure. `0` is the
 * explicit opt-in to "this site honestly lists none", which for [TariffsSection] is what hands an
 * empty complete list to the reconciler's mass-change guard instead of hiding a broken selector
 * behind an adapter error.
 */
@Serializable
data class PaymentMethodsSection(
    val rootSelector: String,
    val itemSelector: String,
    val method: EnumSpec,
    val minRows: Int = 1,
)

@Serializable
data class LocationsSection(
    val rootSelector: String,
    val rowSelector: String,
    val countryCode: TextSpec,
    val city: TextSpec? = null,
    val datacenterName: TextSpec? = null,
    val region: TextSpec? = null,
    val latitude: DecimalSpec? = null,
    val longitude: DecimalSpec? = null,
    val minRows: Int = 1,
)

@Serializable
data class TariffsSection(
    val rootSelector: String,
    val rowSelector: String,
    val name: TextSpec,
    val vcpuCount: IntSpec,
    val ramMb: IntSpec,
    val diskGb: IntSpec,
    val diskType: EnumSpec,
    val ipv4Count: IntSpec,
    val virtualization: EnumSpec,
    val externalId: TextSpec? = null,
    val url: TextSpec? = null,
    val cpuModel: TextSpec? = null,
    val bandwidthMbps: IntSpec? = null,
    val trafficLimitGb: IntSpec? = null,
    val ipv6Supported: FlagSpec? = null,
    val inStock: FlagSpec? = null,
    val prices: List<PriceSpec> = emptyList(),
    /**
     * `NOT_OBSERVED` (the default) leaves each tariff's `locationKeys` `null`, so the catalog's
     * tariff-to-location assignment is left alone. `ALL_LOCATIONS` is the claim
     * `ExampleStaticAdapter`'s fixture makes -- every plan can be ordered in every location of the
     * `locations` section -- and requires that section to be configured.
     */
    val locationKeys: String = SourceConfigFormat.LOCATIONS_NOT_OBSERVED,
    val minRows: Int = 1,
    /**
     * `true`: tariffs whose names (or external ids) collide are prefixed with the first of disk,
     * RAM, vCPU -- or a combination -- that tells them apart, e.g. `150 GB disk · 16 GB RAM`. Off by
     * default, because a colliding name is usually a selector that matches the same element in
     * every row.
     */
    val disambiguateNames: Boolean = false,
)

sealed interface SourceConfigParse {
    data class Valid(val config: SourceConfig, val warnings: List<String> = emptyList()) : SourceConfigParse

    data class Invalid(val problems: List<String>) : SourceConfigParse
}

/** Owns parsing and validation for one bounded source file. Directory-wide rules belong to [SourceConfigLoader]. */
object SourceConfigFormat {

    const val SCHEMA_VERSION: Int = 1

    /** A config file is at most this large; the bound is enforced wherever content is parsed. */
    const val MAX_FILE_BYTES: Int = 64 * 1024

    const val VALUE_IN_MESSAGE_LIMIT: Int = 40

    const val SOURCE_KIND_HTML: String = "HTML"

    const val DEFAULT_PROVIDER_STATUS: String = "ACTIVE"

    const val LOCATIONS_NOT_OBSERVED: String = "NOT_OBSERVED"

    const val LOCATIONS_ALL: String = "ALL_LOCATIONS"

    val SIZE_UNITS: List<String> = listOf("MB", "GB", "TB")

    /** `NUMERIC(12, 4)` on `tariff_price.amount`. */
    const val MONEY_SCALE: Int = 4

    /** `NUMERIC(9, 6)` on `location.latitude`/`longitude`. */
    const val COORDINATE_SCALE: Int = 6

    private const val MESSAGE_LIMIT: Int = 300

    internal val ID_PATTERN = Regex("^[a-z0-9-]+$")

    internal val CURRENCY_PATTERN = Regex("^[A-Z]{3}$")

    /**
     * A quoted run in a parser message, truncated by [sanitise]: kotlinx's decoding messages quote
     * the offending literal, and that literal comes from a file we do not control.
     */
    private val QUOTED_IN_MESSAGE = Regex("'([^']*)'")

    internal val HTTP_SCHEMES = setOf("http", "https")

    /** jsoup compiles regex patterns in these selector forms, so the format rejects them. */
    internal val REGEX_SELECTOR_MARKERS = listOf(":matches", "~=")

    private val logger = LoggerFactory.getLogger(SourceConfigFormat::class.java)

    /** Reject unknown keys so a typo cannot silently make a section unobserved. */
    private val json = Json {
        ignoreUnknownKeys = false
        isLenient = false
        allowSpecialFloatingPointValues = false
        coerceInputValues = false
        allowStructuredMapKeys = false
    }

    /** [content] as a file would hold it; the 64 KB bound is measured on its UTF-8 bytes. */
    fun parse(fileName: String, content: String): SourceConfigParse =
        parse(fileName, content.toByteArray(Charsets.UTF_8))

    /** Returns a validated config with warnings or all problems found in the file. */
    fun parse(fileName: String, bytes: ByteArray): SourceConfigParse {
        if (bytes.size > MAX_FILE_BYTES) {
            return SourceConfigParse.Invalid(
                listOf("$fileName: the file is ${bytes.size} bytes, above the $MAX_FILE_BYTES byte limit"),
            )
        }
        val config = try {
            json.decodeFromString(SourceConfig.serializer(), bytes.toString(Charsets.UTF_8))
        } catch (failure: SerializationException) {
            return SourceConfigParse.Invalid(listOf("$fileName: ${sanitise(failure.message)}"))
        }
        return validate(fileName, config)
    }

    /** Validate configs from both parsed files and direct callers against the same format rules. */
    fun validate(fileName: String, config: SourceConfig): SourceConfigParse {
        val check = Problems(fileName)

        if (config.schemaVersion != SCHEMA_VERSION) {
            check.fail("schemaVersion", "must be $SCHEMA_VERSION, got ${config.schemaVersion}")
        }
        check.identifier("id", config.id)
        check.identifier("providerSlug", config.providerSlug)
        if (config.sourceKind != SOURCE_KIND_HTML) {
            check.fail("sourceKind", "must be $SOURCE_KIND_HTML; an API source is out of scope", config.sourceKind)
        }
        val domain = check.baseUrl(config.baseUrl)
        if (!config.pagePath.startsWith("/")) {
            check.fail("pagePath", "must start with '/'", config.pagePath)
        }
        check.slugRule(config, domain)

        check.provider(config.provider)
        check.legalInfo(config.legalInfo)
        check.paymentMethods(config.paymentMethods)
        check.locations(config.locations)
        check.tariffs(config.tariffs, hasLocations = config.locations != null)

        if (check.problems.isNotEmpty()) return SourceConfigParse.Invalid(check.problems)
        check.warnings.forEach { logger.warn("{}", it) }
        return SourceConfigParse.Valid(config, check.warnings)
    }

    /**
     * The parser's own message, reduced to its first line with every quoted run truncated: kotlinx
     * appends the offending JSON input to a decoding message, and a config may hold anything.
     */
    private fun sanitise(message: String?): String {
        val firstLine = (message ?: "could not be read as JSON").lineSequence().first()
        return QUOTED_IN_MESSAGE.replace(firstLine) { "'${it.groupValues[1].take(VALUE_IN_MESSAGE_LIMIT)}'" }
            .take(MESSAGE_LIMIT)
    }

    private class Problems(private val fileName: String) {
        val problems = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        fun fail(key: String, problem: String, value: String? = null) {
            val tail = value?.let { ": '${it.take(VALUE_IN_MESSAGE_LIMIT)}'" } ?: ""
            problems += "$fileName: $key $problem$tail"
        }

        fun identifier(key: String, value: String) {
            if (!ID_PATTERN.matches(value)) {
                fail(key, "must match ${ID_PATTERN.pattern}", value)
            }
            if (value.length > DomainName.SLUG_MAX_LENGTH) {
                fail(key, "must be at most ${DomainName.SLUG_MAX_LENGTH} characters, got ${value.length}", value)
            }
        }

        /** The normalized domain of [baseUrl], or `null` when it is not a usable absolute URL. */
        fun baseUrl(baseUrl: String): String? {
            val uri = runCatching { URI(baseUrl.trim()) }.getOrNull()
            val usable = uri != null &&
                uri.isAbsolute &&
                uri.scheme?.lowercase() in HTTP_SCHEMES &&
                !uri.authority.isNullOrEmpty()
            if (!usable) {
                fail("baseUrl", "must be an absolute http or https URL", baseUrl)
                return null
            }
            if (uri.query != null || uri.fragment != null) {
                fail("baseUrl", "must carry no query and no fragment; pagePath is appended to it", baseUrl)
                return null
            }
            return DomainName.normalize(baseUrl)
        }

        /** Bind the target slug to the source domain to prevent accidental writes to unrelated provider rows. An explicit acknowledgement allows an exception. */
        fun slugRule(config: SourceConfig, domain: String?) {
            val expected = domain?.let { DomainName.slug(it) }
            if (expected == config.providerSlug) return
            val problem = "providerSlug '${config.providerSlug.take(VALUE_IN_MESSAGE_LIMIT)}' is not the slug of " +
                "baseUrl's domain (${expected ?: "which has no usable slug"})"
            if (config.acknowledgeSlugMismatch) {
                warnings += "$fileName: $problem; accepted because acknowledgeSlugMismatch is true"
            } else {
                problems += "$fileName: $problem; set acknowledgeSlugMismatch to accept it deliberately"
            }
        }


        fun provider(section: ProviderSection?) {
            if (section == null) {
                fail("provider", "section is required: a snapshot always carries the provider's identity")
                return
            }
            selector("provider.rootSelector", section.rootSelector)
            field("provider.name", section.name, mandatory = true)
            field("provider.websiteUrl", section.websiteUrl, mandatory = true)
            section.description?.let { field("provider.description", it) }
            constant("provider.status", section.status, ProviderStatus.entries.map { it.name })
        }

        fun legalInfo(section: LegalInfoSection?) {
            if (section == null) return
            selector("legalInfo.rootSelector", section.rootSelector)
            section.legalEntityName?.let { field("legalInfo.legalEntityName", it) }
            section.registrationCountry?.let { field("legalInfo.registrationCountry", it) }
            section.registrationNumber?.let { field("legalInfo.registrationNumber", it) }
            section.legalAddress?.let { field("legalInfo.legalAddress", it) }
        }

        fun paymentMethods(section: PaymentMethodsSection?) {
            if (section == null) return
            selector("paymentMethods.rootSelector", section.rootSelector)
            selector("paymentMethods.itemSelector", section.itemSelector)
            minRows("paymentMethods.minRows", section.minRows)
            enumField("paymentMethods.method", section.method, PaymentMethod.entries.map { it.name }, mandatory = true)
        }

        fun locations(section: LocationsSection?) {
            if (section == null) return
            selector("locations.rootSelector", section.rootSelector)
            selector("locations.rowSelector", section.rowSelector)
            minRows("locations.minRows", section.minRows)
            field("locations.countryCode", section.countryCode, mandatory = true)
            section.city?.let { field("locations.city", it) }
            section.datacenterName?.let { field("locations.datacenterName", it) }
            section.region?.let { field("locations.region", it) }
            section.latitude?.let { field("locations.latitude", it) }
            section.longitude?.let { field("locations.longitude", it) }
        }

        fun tariffs(section: TariffsSection?, hasLocations: Boolean) {
            if (section == null) return
            selector("tariffs.rootSelector", section.rootSelector)
            selector("tariffs.rowSelector", section.rowSelector)
            minRows("tariffs.minRows", section.minRows)
            field("tariffs.name", section.name, mandatory = true)
            field("tariffs.vcpuCount", section.vcpuCount, mandatory = true)
            field("tariffs.ramMb", section.ramMb, mandatory = true, sized = true)
            field("tariffs.diskGb", section.diskGb, mandatory = true, sized = true)
            field("tariffs.ipv4Count", section.ipv4Count, mandatory = true)
            enumField("tariffs.diskType", section.diskType, DiskType.entries.map { it.name }, mandatory = true)
            enumField("tariffs.virtualization", section.virtualization, Virtualization.entries.map { it.name }, mandatory = true)
            section.externalId?.let { field("tariffs.externalId", it) }
            section.url?.let { field("tariffs.url", it) }
            section.cpuModel?.let { field("tariffs.cpuModel", it) }
            section.bandwidthMbps?.let { field("tariffs.bandwidthMbps", it) }
            section.trafficLimitGb?.let { field("tariffs.trafficLimitGb", it, sized = true) }
            section.ipv6Supported?.let { field("tariffs.ipv6Supported", it) }
            section.inStock?.let { field("tariffs.inStock", it) }
            constant("tariffs.locationKeys", section.locationKeys, listOf(LOCATIONS_NOT_OBSERVED, LOCATIONS_ALL))
            if (section.locationKeys == LOCATIONS_ALL && !hasLocations) {
                fail("tariffs.locationKeys", "is $LOCATIONS_ALL but no locations section is configured")
            }
            section.prices.forEachIndexed { index, price -> field("tariffs.prices[$index]", price) }
            section.prices.groupBy { it.billingPeriod }.filterValues { it.size > 1 }.keys.forEach { period ->
                fail("tariffs.prices", "names the same billing period twice", period)
            }
        }


        /**
         * Everything a field's own type decides: where it is read from, plus the options that type
         * carries. The one thing a type cannot decide is **which** enum an [EnumSpec] targets --
         * that is the call site's knowledge, so [enumField] adds it.
         */
        fun field(key: String, spec: FieldSpec, mandatory: Boolean = false, sized: Boolean = false) {
            val constant = spec.value
            if (constant != null) {
                constantValue(key, spec, constant)
            } else {
                selector(key, spec.selector)
                val sources = listOfNotNull(spec.text.takeIf { it }, spec.attr?.takeIf { it.isNotBlank() })
                if (sources.size != 1) {
                    fail(key, "must set exactly one of \"text\": true and \"attr\": \"<name>\"")
                }
            }
            if (spec is IntSpec && spec.unit != null) {
                if (!sized) {
                    fail("$key.unit", "is only allowed on ramMb, diskGb and trafficLimitGb", spec.unit)
                } else if (spec.unit !in SIZE_UNITS) {
                    fail("$key.unit", "must be one of ${SIZE_UNITS.joinToString()}", spec.unit)
                }
            }
            // Reject optional selectors for mandatory snapshot fields before fetching any page.
            if (mandatory && !spec.required) {
                fail(key, "must not set \"required\": false: this field has no optional form")
            }
            when (spec) {
                is FlagSpec -> if (constant == null && spec.values.isEmpty()) fail(key, "needs a non-empty \"values\" map")
                is DecimalSpec -> {
                    separators(key, spec.grouping, spec.decimal)
                    fractionDigits(key, spec.maxFractionDigits, COORDINATE_SCALE)
                }
                is PriceSpec -> {
                    constant("$key.billingPeriod", spec.billingPeriod, BillingPeriod.entries.map { it.name })
                    separators(key, spec.grouping, spec.decimal)
                    fractionDigits(key, spec.maxFractionDigits, MONEY_SCALE)
                    currency("$key.currency", spec.currency)
                }
                else -> Unit
            }
        }

        /** A `"value"` replaces the page read entirely, so it may not be combined with one. */
        fun constantValue(key: String, spec: FieldSpec, constant: String) {
            if (spec.selector.isNotEmpty() || spec.text || spec.attr != null) {
                fail(key, "must set either \"value\" or a selector with \"text\"/\"attr\", not both")
            }
            when (spec) {
                is PriceSpec, is DecimalSpec -> fail(key, "must be read from the page: \"value\" is not allowed here")
                is IntSpec -> if (constant.toIntOrNull() == null) fail("$key.value", "must be a whole number", constant)
                is FlagSpec -> if (constant.toBooleanStrictOrNull() == null) fail("$key.value", "must be true or false", constant)
                else -> if (constant.isBlank()) fail("$key.value", "must not be blank")
            }
        }

        fun enumField(key: String, spec: EnumSpec, constants: List<String>, mandatory: Boolean = false) {
            field(key, spec, mandatory)
            val constant = spec.value
            if (constant != null) {
                constant("$key.value", constant, constants)
                return
            }
            if (spec.values.isEmpty()) {
                fail(key, "needs a non-empty \"values\" map of page value -> constant")
            }
            spec.values.forEach { (pageValue, target) ->
                if (target !in constants) {
                    fail(
                        "$key.values['${pageValue.take(VALUE_IN_MESSAGE_LIMIT)}']",
                        "is not one of ${constants.joinToString()}",
                        target,
                    )
                }
            }
            spec.default?.let { constant("$key.default", it, constants) }
        }

        fun currency(key: String, spec: CurrencySpec) {
            val fixed = spec.fixed
            val bySymbol = spec.bySymbol
            if ((fixed == null) == (bySymbol == null)) {
                fail(key, "must set exactly one of \"fixed\" and \"bySymbol\"")
                return
            }
            if (fixed != null && !CURRENCY_PATTERN.matches(fixed)) {
                fail("$key.fixed", "must be an ISO-4217 code (${CURRENCY_PATTERN.pattern})", fixed)
            }
            if (bySymbol != null) {
                if (bySymbol.isEmpty()) fail("$key.bySymbol", "needs at least one symbol")
                bySymbol.forEach { (symbol, code) ->
                    if (symbol.isBlank()) fail("$key.bySymbol", "has a blank symbol")
                    if (!CURRENCY_PATTERN.matches(code)) {
                        fail("$key.bySymbol['${symbol.take(VALUE_IN_MESSAGE_LIMIT)}']", "must be an ISO-4217 code", code)
                    }
                }
            }
        }


        /** Reject CSS escapes because jsoup can decode them into regex selector markers. Check the canonical parsed selector too. */
        fun selector(key: String, selector: String?) {
            if (selector == null) return
            if (selector.isBlank()) {
                fail(key, "must not be blank")
                return
            }
            if (selector.contains('\\')) {
                fail(key, "must not use CSS escapes ('\\\\'): they can smuggle jsoup's regex syntax past this check", selector)
                return
            }
            val rawMarker = REGEX_SELECTOR_MARKERS.firstOrNull { selector.contains(it, ignoreCase = true) }
            if (rawMarker != null) {
                fail(key, "must not use jsoup's regex syntax ('$rawMarker'): a config carries no expressions", selector)
                return
            }
            val evaluator = runCatching { Selector.evaluatorOf(selector) }.getOrElse {
                fail(key, "is not a selector jsoup can parse", selector)
                return
            }
            val canonical = evaluator.toString()
            val canonicalMarker = REGEX_SELECTOR_MARKERS.firstOrNull { canonical.contains(it, ignoreCase = true) }
            if (canonicalMarker != null) {
                fail(key, "must not use jsoup's regex syntax ('$canonicalMarker'): a config carries no expressions", selector)
            }
        }

        fun constant(key: String, value: String, constants: List<String>) {
            if (value !in constants) fail(key, "must be one of ${constants.joinToString()}", value)
        }

        fun minRows(key: String, minRows: Int) {
            if (minRows < 0) fail(key, "must not be negative, got $minRows")
        }

        fun fractionDigits(key: String, digits: Int, maxScale: Int) {
            if (digits !in 0..maxScale) {
                fail("$key.maxFractionDigits", "must be between 0 and $maxScale, got $digits")
            }
        }

        fun separators(key: String, grouping: String?, decimal: String) {
            if (decimal.length != 1 || decimal.first().isDigit()) {
                fail("$key.decimal", "must be a single non-digit character", decimal)
            }
            if (grouping != null && (grouping.length != 1 || grouping.first().isDigit())) {
                fail("$key.grouping", "must be a single non-digit character", grouping)
            }
            if (grouping != null && grouping == decimal) {
                fail("$key.grouping", "must differ from the decimal separator", grouping)
            }
        }
    }
}
