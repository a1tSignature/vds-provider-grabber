package org.a1tsign.vds.api

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import java.math.BigDecimal
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit

/** Fractional digits every money value is normalised to -- matches `NUMERIC(12, 4)` in the schema. */
const val MONEY_SCALE: Int = 4

/** Integer digits a money value may have -- the other half of `NUMERIC(12, 4)`. */
const val MONEY_MAX_INTEGER_DIGITS: Int = 8

/** Plain decimal notation: optional `-`, digits, optional `.digits`. No exponent, `+`, grouping or whitespace. */
private val PLAIN_DECIMAL_PATTERN = Regex("""^-?\d+(\.\d+)?$""")

/**
 * [BigDecimal] is a JSON string in plain notation in requests and responses, so money never passes
 * through a `Double` or reaches a client as `9.2E-1`.
 *
 * With `isLenient = false` on [ApiJson], [Decoder.decodeString] rejects a JSON number token, so
 * `{"amount": 10}` is a `400`.
 */
object BigDecimalSerializer : KSerializer<BigDecimal> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("org.a1tsign.vds.api.BigDecimal", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: BigDecimal) {
        // toPlainString(), never toString(): the latter emits scientific notation as soon as the
        // scale is negative or far from zero (BigDecimal("1E+3").toString() == "1E+3").
        encoder.encodeString(value.toPlainString())
    }

    override fun deserialize(decoder: Decoder): BigDecimal =
        parsePlainDecimal(decoder.decodeString())
            ?: throw SerializationException("Expected a decimal number in plain notation as a JSON string")
}

/**
 * [Instant] is written as UTC with `Z` and whole seconds: `2026-10-28T10:00:00Z`.
 *
 * Input must be ISO-8601 with an explicit offset. A timestamp without one is rejected: assuming UTC
 * would shift a client's local time by its offset.
 */
object InstantSerializer : KSerializer<Instant> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("org.a1tsign.vds.api.Instant", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: Instant) {
        encoder.encodeString(DateTimeFormatter.ISO_INSTANT.format(value.truncatedTo(ChronoUnit.SECONDS)))
    }

    override fun deserialize(decoder: Decoder): Instant {
        val raw = decoder.decodeString()
        return try {
            OffsetDateTime.parse(raw, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant()
        } catch (e: DateTimeParseException) {
            // Keep the cause: the server-side WARN shows what failed to parse; the client gets a fixed message.
            throw SerializationException(
                "Expected an ISO-8601 timestamp with an explicit offset, e.g. 2026-10-28T12:00:00Z",
                e,
            )
        }
    }
}

/**
 * The only `Json` instance, used by `ContentNegotiation`. A second one would let endpoints disagree
 * on the wire format.
 *
 * - `ignoreUnknownKeys = false`: a typo in a request field is a `400`, not a silently dropped field.
 * - `encodeDefaults = true`: every documented response key is present, even when it equals its default.
 * - `explicitNulls = false`: a null field is omitted, not sent as `null`. In DTOs, optional means absent.
 * - `isLenient = false`: a number where a string is required is a `400`, which keeps `Double` out
 *   of money values ([BigDecimalSerializer]).
 */
val ApiJson: Json = Json {
    ignoreUnknownKeys = false
    encodeDefaults = true
    explicitNulls = false
    isLenient = false
    allowSpecialFloatingPointValues = false
    coerceInputValues = false
    serializersModule = SerializersModule {
        contextual(BigDecimal::class, BigDecimalSerializer)
        contextual(Instant::class, InstantSerializer)
    }
}

/** Parses [raw] only if it is in plain decimal notation; `null` otherwise (never throws). */
fun parsePlainDecimal(raw: String): BigDecimal? =
    if (PLAIN_DECIMAL_PATTERN.matches(raw)) BigDecimal(raw) else null

/** Outcome of reading a money value off the wire -- see [parseAmount]. */
sealed interface AmountParse {
    /** [value] is normalised to exactly [MONEY_SCALE] fractional digits. */
    data class Valid(val value: BigDecimal) : AmountParse

    /** [reason] is a client-safe field message for the error envelope's `details`. */
    data class Invalid(val reason: String) : AmountParse
}

/**
 * Applies the `NUMERIC(12, 4)` money rules to a raw string. [Validation.validateAmount] attaches the
 * field name to the failure.
 */
fun parseAmount(raw: String): AmountParse {
    val value = parsePlainDecimal(raw)
        ?: return AmountParse.Invalid("must be a decimal number in plain notation")
    if (value.signum() < 0) {
        return AmountParse.Invalid("must not be negative")
    }
    if (value.scale() > MONEY_SCALE) {
        return AmountParse.Invalid("must have at most $MONEY_SCALE fractional digits")
    }
    // toBigInteger() drops the fraction and any leading zeros, so "00012.5" counts as 2 integer
    // digits and "0.5" as 1 -- the same count PostgreSQL's NUMERIC(12, 4) applies.
    if (value.toBigInteger().abs().toString().length > MONEY_MAX_INTEGER_DIGITS) {
        return AmountParse.Invalid("must have at most $MONEY_MAX_INTEGER_DIGITS integer digits")
    }
    return AmountParse.Valid(value.setScale(MONEY_SCALE))
}
