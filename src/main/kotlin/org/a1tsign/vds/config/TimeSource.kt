package org.a1tsign.vds.config

import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * The only sanctioned way to read "now" in `src/main/kotlin`. Production wiring passes
 * `Clock.systemUTC()`; tests pass a fixed/mutable clock so time-dependent behaviour (token
 * expiry, price effective dates) is controllable.
 */
fun Clock.nowUtc(): Instant = instant()

/**
 * The `Instant` <-> `OffsetDateTime` conversion needed at the Exposed boundary
 * (`timestampWithTimeZone` columns store `OffsetDateTime`, the domain uses `Instant`).
 */
fun Instant.toUtcOffset(): OffsetDateTime = atOffset(ZoneOffset.UTC)
