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

/**
 * Plain decimal notation: optional sign, digits, optional `.digits`. No exponent (`1e3`), no
 * leading `+`, no grouping separator, no whitespace -- so what the client sent and what the
 * database will store are the same number read the same way by every client.
 */
private val PLAIN_DECIMAL_PATTERN = Regex("""^-?\d+(\.\d+)?$""")

/**
 * [BigDecimal] on the wire is **always** a JSON string in plain decimal notation, in requests and
 * responses alike, so no money value ever passes through a `Double` (`0.1 + 0.2`) or reaches a
 * client as `9.2E-1`.
 *
 * `deserialize` calls [Decoder.decodeString], which on the shared [ApiJson] (`isLenient = false`)
 * refuses a JSON *number* token -- that is what makes `{"amount": 10}` a `400` instead of a
 * silently accepted double.
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
 * [Instant] on the wire is **always** UTC with a `Z` suffix and whole-second precision
 * (`2026-10-28T10:00:00Z`): the output has one shape, so a client never has to cope with a
 * sometimes-present fractional part.
 *
 * Input must be ISO-8601 **with an explicit offset** ([DateTimeFormatter.ISO_OFFSET_DATE_TIME]).
 * A timestamp without one (`2026-10-28T12:00:00`) is rejected rather than guessed at: guessing
 * UTC would silently shift a client's local-time input by its own offset.
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
            // The cause is kept so the WARN that ApiError's BadRequestException branch logs says
            // which part of the timestamp was unparseable; the client still gets a fixed message.
            throw SerializationException(
                "Expected an ISO-8601 timestamp with an explicit offset, e.g. 2026-10-28T12:00:00Z",
                e,
            )
        }
    }
}

/**
 * The one and only `Json` instance in the codebase -- `ContentNegotiation` is configured with it
 * and nothing else may build another, or two endpoints would disagree about the wire format.
 *
 * - `ignoreUnknownKeys = false`: a typo in a request body is a `400`, not a silently dropped field.
 * - `encodeDefaults = true`: a response field is never missing just because it happens to equal
 *   its default, so a client can rely on every documented key being present.
 * - `explicitNulls = false`: a null field is **omitted** rather than sent as `null`. That is what
 *   makes `details` absent (not `"details":null`) on a non-validation error, and it is the rule
 *   every later DTO must be written against: optional means absent.
 * - `isLenient = false`: an unquoted/number token where a string is required is a `400` -- the
 *   guard that keeps `Double` out of the money path (see [BigDecimalSerializer]).
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
 * The money rules of `NUMERIC(12, 4)`, applied to the raw string so the caller can report *which*
 * field was wrong -- `Validation.validateAmount` is the wrapper that records it.
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
