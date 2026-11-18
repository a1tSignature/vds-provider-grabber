package org.a1tsign.vds.config

import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

/** Use the injected clock for wall time so expiry and effective dates can be controlled in tests. */
fun Clock.nowUtc(): Instant = instant()

/** Exposed stores timestampWithTimeZone as OffsetDateTime while the domain uses Instant. */
fun Instant.toUtcOffset(): OffsetDateTime = atOffset(ZoneOffset.UTC)
