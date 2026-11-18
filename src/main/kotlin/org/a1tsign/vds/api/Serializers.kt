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

private val PLAIN_DECIMAL_PATTERN = Regex("""^-?\d+(\.\d+)?$""")

/** Encode decimals as plain JSON strings to preserve precision. Strict string decoding rejects number tokens. */
object BigDecimalSerializer : KSerializer<BigDecimal> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("org.a1tsign.vds.api.BigDecimal", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: BigDecimal) {
        // Use toPlainString to keep negative scales and very small values out of scientific notation.
        encoder.encodeString(value.toPlainString())
    }

    override fun deserialize(decoder: Decoder): BigDecimal =
        parsePlainDecimal(decoder.decodeString())
            ?: throw SerializationException("Expected a decimal number in plain notation as a JSON string")
}

/** Write UTC instants with whole seconds. Input must carry an explicit ISO-8601 offset. */
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
            throw SerializationException(
                "Expected an ISO-8601 timestamp with an explicit offset, e.g. 2026-10-28T12:00:00Z",
                e,
            )
        }
    }
}

/** Reject unknown request keys. Encode response defaults and omit null fields. Strict decoding preserves string-only decimals. */
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

sealed interface AmountParse {
    /** [value] is normalised to exactly [MONEY_SCALE] fractional digits. */
    data class Valid(val value: BigDecimal) : AmountParse

    /** [reason] is a client-safe field message for the error envelope's `details`. */
    data class Invalid(val reason: String) : AmountParse
}

/** Apply the storage range and scale of NUMERIC(12, 4). */
fun parseAmount(raw: String): AmountParse {
    val value = parsePlainDecimal(raw)
        ?: return AmountParse.Invalid("must be a decimal number in plain notation")
    if (value.signum() < 0) {
        return AmountParse.Invalid("must not be negative")
    }
    if (value.scale() > MONEY_SCALE) {
        return AmountParse.Invalid("must have at most $MONEY_SCALE fractional digits")
    }
    // Count integer digits without the fraction or leading zeros, as PostgreSQL does.
    if (value.toBigInteger().abs().toString().length > MONEY_MAX_INTEGER_DIGITS) {
        return AmountParse.Invalid("must have at most $MONEY_MAX_INTEGER_DIGITS integer digits")
    }
    return AmountParse.Valid(value.setScale(MONEY_SCALE))
}
