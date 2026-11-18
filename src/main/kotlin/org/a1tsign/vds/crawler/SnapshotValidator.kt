package org.a1tsign.vds.crawler

import org.a1tsign.vds.api.MONEY_MAX_INTEGER_DIGITS
import org.a1tsign.vds.api.MONEY_SCALE
import org.a1tsign.vds.crawler.model.LegalInfoSnapshot
import org.a1tsign.vds.crawler.model.LocationMatchKey
import org.a1tsign.vds.crawler.model.LocationSnapshot
import org.a1tsign.vds.crawler.model.PriceSnapshot
import org.a1tsign.vds.crawler.model.ProviderInfo
import org.a1tsign.vds.crawler.model.ProviderSnapshot
import org.a1tsign.vds.crawler.model.TariffSnapshot
import java.math.BigDecimal
import java.net.URI
import java.time.Clock
import java.time.Duration

/** One rejected value. [path] locates it inside the snapshot, e.g. `tariffs[2].prices[0].amount`. */
data class SnapshotProblem(val path: String, val message: String)

val MAX_OBSERVED_AT_SKEW: Duration = Duration.ofSeconds(60)

// Keep storage limits aligned with API validation while returning snapshot paths rather than HTTP errors.
private const val SLUG_MAX_LENGTH = 64
private const val URL_MAX_LENGTH = 2048
private const val PROVIDER_NAME_MAX_LENGTH = 255
private const val TARIFF_NAME_MAX_LENGTH = 255
private const val TARIFF_EXTERNAL_ID_MAX_LENGTH = 128
private const val COORDINATE_SCALE = 6

private val SLUG_PATTERN = Regex("^[a-z0-9-]+$")
private val COUNTRY_CODE_PATTERN = Regex("^[A-Z]{2}$")
private val CURRENCY_PATTERN = Regex("^[A-Z]{3}$")
private val LATITUDE_MIN = BigDecimal("-90")
private val LATITUDE_MAX = BigDecimal("90")
private val LONGITUDE_MIN = BigDecimal("-180")
private val LONGITUDE_MAX = BigDecimal("180")

/**
 * Validate the entire snapshot before writes, reporting the first failure per invalid value without repairing input.
 * A null section leaves catalog data untouched. An empty collection records an observed empty set.
 */
class SnapshotValidator(private val clock: Clock) {

    /** Every problem found, in a stable order (snapshot order). An empty list means "writable". */
    fun validate(adapter: ProviderAdapter, snapshot: ProviderSnapshot): List<SnapshotProblem> {
        val problems = mutableListOf<SnapshotProblem>()
        validateProvider(problems, adapter, snapshot.provider)
        validateSourceUrls(problems, snapshot.sourceUrls)
        validateObservedAt(problems, snapshot)
        validateTariffListComplete(problems, snapshot)
        snapshot.legalInfo?.let { validateLegalInfo(problems, it) }
        snapshot.locations?.let { validateLocations(problems, it) }
        snapshot.tariffs?.let { validateTariffs(problems, it, snapshot.locations) }
        return problems
    }

    /** A complete tariff list can mark missing tariffs out of stock. Require an observed tariff list for that claim. */
    private fun validateTariffListComplete(problems: MutableList<SnapshotProblem>, snapshot: ProviderSnapshot) {
        if (snapshot.tariffListComplete && snapshot.tariffs == null) {
            problems += SnapshotProblem("tariffListComplete", "must be false while tariffs are not observed")
        }
    }

    private fun validateProvider(
        problems: MutableList<SnapshotProblem>,
        adapter: ProviderAdapter,
        provider: ProviderInfo,
    ) {
        // Require the adapter's exact slug to prevent writes to another provider.
        if (provider.slug != adapter.providerSlug) {
            problems += SnapshotProblem("provider.slug", "must equal the adapter's providerSlug")
        }
        validateSlug(problems, "provider.slug", provider.slug)
        validateNonBlank(problems, "provider.name", provider.name)
        validateMaxLength(problems, "provider.name", provider.name, PROVIDER_NAME_MAX_LENGTH)
        validateUrl(problems, "provider.websiteUrl", provider.websiteUrl)
    }

    private fun validateSourceUrls(problems: MutableList<SnapshotProblem>, sourceUrls: List<String>) {
        if (sourceUrls.isEmpty()) {
            problems += SnapshotProblem("sourceUrls", "must name at least one page the snapshot was built from")
        }
        sourceUrls.forEachIndexed { index, url -> validateUrl(problems, "sourceUrls[$index]", url) }
    }

    private fun validateObservedAt(problems: MutableList<SnapshotProblem>, snapshot: ProviderSnapshot) {
        if (snapshot.observedAt.isAfter(clock.instant().plus(MAX_OBSERVED_AT_SKEW))) {
            problems += SnapshotProblem(
                "observedAt",
                "must not be more than ${MAX_OBSERVED_AT_SKEW.toSeconds()}s in the future",
            )
        }
    }

    private fun validateLegalInfo(problems: MutableList<SnapshotProblem>, legalInfo: LegalInfoSnapshot) {
        legalInfo.registrationCountry?.let {
            validateCountryCode(problems, "legalInfo.registrationCountry", it)
        }
        legalInfo.sourceUrl?.let { validateUrl(problems, "legalInfo.sourceUrl", it) }
    }

    private fun validateLocations(problems: MutableList<SnapshotProblem>, locations: List<LocationSnapshot>) {
        val seen = mutableSetOf<LocationMatchKey>()
        locations.forEachIndexed { index, location ->
            val path = "locations[$index]"
            validateCountryCode(problems, "$path.key.countryCode", location.key.countryCode)
            validateCoordinates(problems, path, location)
            // matchKey() from the model, not a re-derived normalisation: the uniqueness rule here
            // and the catalog's `uq_location_provider_country_city_dc` must stay the same rule.
            if (!seen.add(location.key.matchKey())) {
                problems += SnapshotProblem("$path.key", "duplicates an earlier location of this snapshot")
            }
        }
    }

    private fun validateCoordinates(
        problems: MutableList<SnapshotProblem>,
        path: String,
        location: LocationSnapshot,
    ) {
        val latitude = location.latitude
        val longitude = location.longitude
        latitude?.let { validateCoordinate(problems, "$path.latitude", it, LATITUDE_MIN, LATITUDE_MAX) }
        longitude?.let { validateCoordinate(problems, "$path.longitude", it, LONGITUDE_MIN, LONGITUDE_MAX) }
        // The database requires both coordinates or neither. Report the missing half.
        if (latitude == null && longitude != null) {
            problems += SnapshotProblem("$path.latitude", "must be set together with longitude")
        }
        if (longitude == null && latitude != null) {
            problems += SnapshotProblem("$path.longitude", "must be set together with latitude")
        }
    }

    private fun validateTariffs(
        problems: MutableList<SnapshotProblem>,
        tariffs: List<TariffSnapshot>,
        locations: List<LocationSnapshot>?,
    ) {
        val knownLocations = locations?.map { it.key.matchKey() }?.toSet()
        val seenNames = mutableSetOf<String>()
        val seenExternalIds = mutableSetOf<String>()
        tariffs.forEachIndexed { index, tariff ->
            val path = "tariffs[$index]"
            validateNonBlank(problems, "$path.name", tariff.name)
            validateMaxLength(problems, "$path.name", tariff.name, TARIFF_NAME_MAX_LENGTH)
            tariff.externalId?.let {
                validateNonBlank(problems, "$path.externalId", it)
                validateMaxLength(problems, "$path.externalId", it, TARIFF_EXTERNAL_ID_MAX_LENGTH)
            }
            tariff.url?.let { validateUrl(problems, "$path.url", it) }
            validateTariffSizes(problems, path, tariff)
            validatePrices(problems, path, tariff.prices)
            validateLocationLinks(problems, path, tariff, knownLocations)
            // uq_tariff_provider_name is on lower(name); uq_tariff_provider_external_id ignores
            // nulls, so two tariffs without an external id are not duplicates of each other.
            if (!seenNames.add(tariff.name.lowercase())) {
                problems += SnapshotProblem("$path.name", "duplicates the name of an earlier tariff, ignoring case")
            }
            tariff.externalId?.let {
                if (!seenExternalIds.add(it)) {
                    problems += SnapshotProblem("$path.externalId", "duplicates the externalId of an earlier tariff")
                }
            }
        }
    }

    private fun validateTariffSizes(
        problems: MutableList<SnapshotProblem>,
        path: String,
        tariff: TariffSnapshot,
    ) {
        validateIntRange(problems, "$path.vcpuCount", tariff.vcpuCount, 1, Short.MAX_VALUE.toInt())
        validateIntRange(problems, "$path.ipv4Count", tariff.ipv4Count, 0, Short.MAX_VALUE.toInt())
        validateIntRange(problems, "$path.ramMb", tariff.ramMb, 1, Int.MAX_VALUE)
        validateIntRange(problems, "$path.diskGb", tariff.diskGb, 1, Int.MAX_VALUE)
        tariff.bandwidthMbps?.let { validateIntRange(problems, "$path.bandwidthMbps", it, 1, Int.MAX_VALUE) }
        tariff.trafficLimitGb?.let { validateIntRange(problems, "$path.trafficLimitGb", it, 1, Int.MAX_VALUE) }
    }

    private fun validatePrices(
        problems: MutableList<SnapshotProblem>,
        tariffPath: String,
        prices: List<PriceSnapshot>,
    ) {
        val seenPeriods = mutableSetOf<String>()
        prices.forEachIndexed { index, price ->
            val path = "$tariffPath.prices[$index]"
            validateCurrency(problems, "$path.currency", price.currency)
            validateAmount(problems, "$path.amount", price.amount)
            if (!seenPeriods.add(price.billingPeriod.name)) {
                problems += SnapshotProblem(
                    "$path.billingPeriod",
                    "duplicates an earlier price of the same tariff for this billing period",
                )
            }
        }
    }

    private fun validateLocationLinks(
        problems: MutableList<SnapshotProblem>,
        path: String,
        tariff: TariffSnapshot,
        knownLocations: Set<LocationMatchKey>?,
    ) {
        val locationKeys = tariff.locationKeys ?: return
        if (knownLocations == null) {
            // locations == null means "locations not observed", so there is nothing to link to and
            // the reconciler could not act on these keys at all.
            problems += SnapshotProblem("$path.locationKeys", "must be null while the snapshot's locations are null")
            return
        }
        locationKeys.forEachIndexed { index, key ->
            if (key.matchKey() !in knownLocations) {
                problems += SnapshotProblem(
                    "$path.locationKeys[$index]",
                    "must match a location of this snapshot",
                )
            }
        }
    }

    private fun validateSlug(problems: MutableList<SnapshotProblem>, path: String, value: String) {
        if (value.length > SLUG_MAX_LENGTH || !SLUG_PATTERN.matches(value)) {
            problems += SnapshotProblem(
                path,
                "must match ${SLUG_PATTERN.pattern} and be at most $SLUG_MAX_LENGTH characters",
            )
        }
    }

    private fun validateNonBlank(problems: MutableList<SnapshotProblem>, path: String, value: String) {
        if (value.isBlank()) {
            problems += SnapshotProblem(path, "must not be blank")
        }
    }

    private fun validateMaxLength(problems: MutableList<SnapshotProblem>, path: String, value: String, max: Int) {
        if (value.length > max) {
            problems += SnapshotProblem(path, "must be at most $max characters")
        }
    }

    private fun validateCountryCode(problems: MutableList<SnapshotProblem>, path: String, value: String) {
        if (!COUNTRY_CODE_PATTERN.matches(value)) {
            problems += SnapshotProblem(path, "must be an uppercase ISO 3166-1 alpha-2 country code")
        }
    }

    private fun validateCurrency(problems: MutableList<SnapshotProblem>, path: String, value: String) {
        if (!CURRENCY_PATTERN.matches(value)) {
            problems += SnapshotProblem(path, "must be an uppercase ISO 4217 currency code")
        }
    }

    private fun validateUrl(problems: MutableList<SnapshotProblem>, path: String, value: String) {
        if (!isAbsoluteHttpUrl(value) || value.length > URL_MAX_LENGTH) {
            problems += SnapshotProblem(
                path,
                "must be an absolute http or https URL of at most $URL_MAX_LENGTH characters",
            )
        }
    }

    private fun validateIntRange(
        problems: MutableList<SnapshotProblem>,
        path: String,
        value: Int,
        minimum: Int,
        maximum: Int,
    ) {
        if (value < minimum || value > maximum) {
            problems += SnapshotProblem(path, "must be between $minimum and $maximum")
        }
    }

    /** The `NUMERIC(12, 4)` money rule, mirroring `api/Serializers.kt`'s `parseAmount`. */
    private fun validateAmount(problems: MutableList<SnapshotProblem>, path: String, amount: BigDecimal) {
        when {
            amount.signum() < 0 ->
                problems += SnapshotProblem(path, "must not be negative")
            amount.scale() > MONEY_SCALE ->
                problems += SnapshotProblem(path, "must have at most $MONEY_SCALE fractional digits")
            amount.toBigInteger().abs().toString().length > MONEY_MAX_INTEGER_DIGITS ->
                problems += SnapshotProblem(path, "must have at most $MONEY_MAX_INTEGER_DIGITS integer digits")
        }
    }

    /** `NUMERIC(9, 6)` plus the range CHECKs, mirroring `Validation.validateLocationCoordinate`. */
    private fun validateCoordinate(
        problems: MutableList<SnapshotProblem>,
        path: String,
        value: BigDecimal,
        minimum: BigDecimal,
        maximum: BigDecimal,
    ) {
        if (value.stripTrailingZeros().scale() > COORDINATE_SCALE) {
            problems += SnapshotProblem(path, "must have at most $COORDINATE_SCALE fractional digits")
        } else if (value < minimum || value > maximum) {
            problems += SnapshotProblem(path, "must be between $minimum and $maximum")
        }
    }
}

const val PROBLEM_LOG_LIMIT: Int = 10

/** Log a bounded problem list with the remaining count. Paths and messages cannot contain raw page values. */
fun List<SnapshotProblem>.toLogLine(): String {
    if (isEmpty()) {
        return "no problems"
    }
    val shown = take(PROBLEM_LOG_LIMIT).joinToString("; ") { "${it.path}: ${it.message}" }
    val omitted = size - minOf(size, PROBLEM_LOG_LIMIT)
    return if (omitted > 0) "$size problems: $shown; ... and $omitted more" else "$size problems: $shown"
}

// Require a host even when the input parses as an absolute HTTP URI.
private fun isAbsoluteHttpUrl(value: String): Boolean {
    val uri = runCatching { URI(value) }.getOrNull() ?: return false
    return uri.isAbsolute && (uri.scheme == "http" || uri.scheme == "https") && !uri.host.isNullOrEmpty()
}
