package org.a1tsign.vds.api

import java.math.BigDecimal
import java.net.URI

const val SLUG_MAX_LENGTH: Int = 64
const val URL_MAX_LENGTH: Int = 2048

private val SLUG_PATTERN = Regex("^[a-z0-9-]+$")

// Keep these patterns aligned with the country and currency CHECK constraints.
private val COUNTRY_CODE_PATTERN = Regex("^[A-Z]{2}$")
private val CURRENCY_PATTERN = Regex("^[A-Z]{3}$")

/** Accumulate field failures and call [throwIfInvalid] before using validated values. */
class Validation {
    private val details = mutableListOf<ApiErrorDetail>()

    fun reject(field: String, message: String) {
        details += ApiErrorDetail(field, message)
    }

    fun requireNonBlank(field: String, value: String): String? =
        if (value.isBlank()) {
            reject(field, "must not be blank")
            null
        } else {
            value
        }

    fun maxLength(field: String, value: String, max: Int): String? =
        if (value.length > max) {
            reject(field, "must be at most $max characters")
            null
        } else {
            value
        }

    fun <T> rejectDuplicates(field: String, values: List<T>): List<T>? =
        if (values.size == values.toSet().size) values else {
            reject(field, "must not contain duplicate values")
            null
        }

    fun <E : Enum<E>> enumValue(field: String, value: String, values: Array<E>): E? =
        values.firstOrNull { it.name == value } ?: run {
            reject(field, "must be one of ${values.joinToString { it.name }}")
            null
        }

    fun validateSlug(field: String, value: String): String? =
        if (value.length > SLUG_MAX_LENGTH || !SLUG_PATTERN.matches(value)) {
            reject(field, "must match ${SLUG_PATTERN.pattern} and be at most $SLUG_MAX_LENGTH characters")
            null
        } else {
            value
        }

    fun validateCountryCode(field: String, value: String): String? =
        if (COUNTRY_CODE_PATTERN.matches(value)) {
            value
        } else {
            reject(field, "must be an uppercase ISO 3166-1 alpha-2 country code")
            null
        }

    fun validateCurrency(field: String, value: String): String? =
        if (CURRENCY_PATTERN.matches(value)) {
            value
        } else {
            reject(field, "must be an uppercase ISO 4217 currency code")
            null
        }

    fun validateUrl(field: String, value: String): String? =
        if (isAbsoluteHttpUrl(value) && value.length <= URL_MAX_LENGTH) {
            value
        } else {
            reject(field, "must be an absolute http or https URL of at most $URL_MAX_LENGTH characters")
            null
        }

    fun validateAmount(field: String, value: String): BigDecimal? =
        when (val parsed = parseAmount(value)) {
            is AmountParse.Valid -> parsed.value
            is AmountParse.Invalid -> {
                reject(field, parsed.reason)
                null
            }
        }

    fun validateLocationCoordinate(field: String, value: String, minimum: BigDecimal, maximum: BigDecimal): BigDecimal? {
        val parsed = value.toBigDecimalOrNull()
        if (parsed == null) {
            reject(field, "must be a decimal string")
            return null
        }
        if (parsed.stripTrailingZeros().scale() > 6) {
            reject(field, "must have at most 6 fractional digits")
            return null
        }
        if (parsed < minimum || parsed > maximum) {
            reject(field, "must be between $minimum and $maximum")
            return null
        }
        return parsed
    }

    fun validateCoordinatePair(latitude: BigDecimal?, longitude: BigDecimal?) {
        if ((latitude == null) != (longitude == null)) {
            reject("latitude", "latitude and longitude must be supplied together")
            reject("longitude", "latitude and longitude must be supplied together")
        }
    }

    /** Check the wire Int before narrowing to Short so overflow returns a field-level 400. */
    fun validateIntRange(field: String, value: Int, minimum: Int, maximum: Int): Int? =
        if (value < minimum || value > maximum) {
            reject(field, "must be between $minimum and $maximum")
            null
        } else {
            value
        }

    /** A plain-decimal string in `[0, 1]`, parsed as `BigDecimal`. */
    fun validateRatio(field: String, value: String): BigDecimal? {
        val parsed = parsePlainDecimal(value)
        if (parsed == null || parsed.signum() < 0 || parsed > BigDecimal.ONE) {
            reject(field, "must be a decimal number in plain notation between 0 and 1")
            return null
        }
        return parsed
    }

    /** Throws [ValidationException] carrying every failure recorded so far, or returns. */
    fun throwIfInvalid() {
        if (details.isNotEmpty()) {
            throw ValidationException(details.toList())
        }
    }
}

// Absolute HTTP syntax can still omit a host. Require one before accepting the URL.
private fun isAbsoluteHttpUrl(value: String): Boolean {
    val uri = runCatching { URI(value) }.getOrNull() ?: return false
    return uri.isAbsolute && (uri.scheme == "http" || uri.scheme == "https") && !uri.host.isNullOrEmpty()
}
