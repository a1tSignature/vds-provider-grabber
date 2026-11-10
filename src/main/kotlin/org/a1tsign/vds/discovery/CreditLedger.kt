package org.a1tsign.vds.discovery

import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.a1tsign.vds.config.nowUtc
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneOffset
import java.time.format.DateTimeParseException

/** The on-disk shape. `months` is `"YYYY-MM"` (UTC) -> credits spent, pruned to the last 3 months. */
@Serializable
internal data class CreditLedgerState(
    val schemaVersion: Int,
    val lastPassAt: String? = null,
    val months: Map<String, Int> = emptyMap(),
)

/** Why the ledger said no. Every one of these **ends a pass cleanly** -- none is an exception. */
enum class BudgetRefusal {
    /** The state file exists but cannot be read or parsed: fail closed, a lost counter is not a reset. */
    STATE_UNREADABLE,

    /** The state file cannot be written: a credit we could not record must not be spent. */
    STATE_UNWRITABLE,

    /** `VDS_DISCOVERY_MAX_CREDITS_PER_MONTH` is reached. Never overridable, not even by force. */
    MONTHLY_CAP_REACHED,

    /** `VDS_DISCOVERY_MAX_QUERIES_PER_PASS` is reached: this pass is done, the month is not. */
    PASS_QUERY_CAP_REACHED,

    /** The previous pass is younger than `VDS_DISCOVERY_MIN_PASS_INTERVAL_HOURS`; `force` overrides. */
    MIN_PASS_INTERVAL,
}

/** What a refusal reports, so a caller can log and serve the numbers instead of guessing. */
data class LedgerCounters(
    val creditsThisMonth: Int,
    val creditsThisPass: Int,
    val queriesThisPass: Int,
    val maxCreditsPerMonth: Int,
    val maxQueriesPerPass: Int,
)

sealed interface PassStart {
    data class Started(val pass: CreditPass) : PassStart

    data class Refused(val reason: BudgetRefusal, val message: String, val counters: LedgerCounters) : PassStart
}

sealed interface CreditDecision {
    data class Granted(val counters: LedgerCounters) : CreditDecision

    data class Refused(val reason: BudgetRefusal, val message: String, val counters: LedgerCounters) : CreditDecision
}

/**
 * The in-process half of "we will not pay for search" (ADR-02 decision 4). The vendor's own free
 * allowance is a **soft** limit -- it documents a pay-as-you-go path that charges once the plan's
 * credit limit is reached -- so the hard half is an account with no payment method (an operational
 * rule, in `README.md`) and this counter, which **fails closed**:
 *
 * - the state file **absent** ⇒ first run, proceed;
 * - the state file **present but unparsable**, or unreadable, or unwritable ⇒ **refuse**, with one
 *   ERROR line. A lost counter must never become an overage;
 * - a credit is recorded **before** it is spent (write-ahead), so a pass killed between two queries
 *   cannot forget what it consumed;
 * - `lastPassAt` is written when a pass **starts**, not when it ends, so a crash-restart loop cannot
 *   fire a pass every `VDS_DISCOVERY_INITIAL_DELAY_MINUTES` and drain the allowance.
 *
 * ### The budget this cap is built on
 *
 * A 48-hour cadence is ≈ 15 passes/month × 10 queries × 1 credit (`search_depth: basic`) ≈ **150
 * credits/month** of the 1 000 free. The default [maxCreditsPerMonth] of **300** therefore leaves
 * room for roughly as many manual passes again and still stops at **30 %** of the allowance.
 *
 * Month boundaries are **UTC**, and every "now" comes from the injected [clock] -- `Instant.now()`
 * and `System.currentTimeMillis()` are banned in `src/main`.
 *
 * Not thread-safe, and not meant to be: there is one pass at a time (task vds-004.6 owns the single
 * pass slot, exactly as `CrawlerRunner` owns the single run slot).
 */
class CreditLedger(
    private val path: Path,
    private val clock: Clock,
    internal val maxCreditsPerMonth: Int = 300,
    internal val maxQueriesPerPass: Int = 10,
    private val minPassIntervalHours: Long = 24,
) {

    /**
     * Opens a pass, or refuses it with the counters. [force] overrides **only**
     * [BudgetRefusal.MIN_PASS_INTERVAL], with a WARN -- the monthly cap and every state-file problem
     * stay refusals, because those are the ones that cost money.
     */
    fun startPass(force: Boolean = false): PassStart {
        val now = clock.nowUtc()
        val state = readState().getOrElse { problem ->
            logger.error(
                "discovery credit ledger '{}' could not be read ({}); the pass is refused rather than risking an overage",
                path,
                problem.message,
            )
            return PassStart.Refused(
                BudgetRefusal.STATE_UNREADABLE,
                "the credit state file could not be read: ${problem.message}",
                LedgerCounters(0, 0, 0, maxCreditsPerMonth, maxQueriesPerPass),
            )
        }
        val pruned = state.copy(months = prune(state.months, now))
        val creditsThisMonth = pruned.months[monthKey(now)] ?: 0
        val counters = LedgerCounters(creditsThisMonth, 0, 0, maxCreditsPerMonth, maxQueriesPerPass)

        val lastPassAt = pruned.lastPassAt?.let(Instant::parse)
        if (lastPassAt != null) {
            val elapsed = Duration.between(lastPassAt, now)
            if (elapsed < Duration.ofHours(minPassIntervalHours)) {
                if (!force) {
                    return PassStart.Refused(
                        BudgetRefusal.MIN_PASS_INTERVAL,
                        "the previous pass ran ${elapsed.toMinutes()} minutes ago, inside the " +
                            "$minPassIntervalHours hour minimum interval",
                        counters,
                    )
                }
                logger.warn(
                    "a discovery pass is being forced {} minutes after the previous one, inside the {} hour minimum",
                    elapsed.toMinutes(),
                    minPassIntervalHours,
                )
            }
        }
        if (creditsThisMonth >= maxCreditsPerMonth) {
            logger.warn(
                "discovery monthly credit cap reached: {} of {} credits spent this month; the pass is refused",
                creditsThisMonth,
                maxCreditsPerMonth,
            )
            return PassStart.Refused(
                BudgetRefusal.MONTHLY_CAP_REACHED,
                "$creditsThisMonth of $maxCreditsPerMonth credits are already spent this month",
                counters,
            )
        }
        val started = pruned.copy(lastPassAt = now.toString())
        if (!write(started)) {
            return PassStart.Refused(
                BudgetRefusal.STATE_UNWRITABLE,
                "the credit state file '$path' could not be written",
                counters,
            )
        }
        return PassStart.Started(CreditPass(this, started))
    }

    internal fun monthKey(at: Instant): String = YearMonth.from(at.atZone(ZoneOffset.UTC)).toString()

    internal fun now(): Instant = clock.nowUtc()

    /**
     * `null` state (file absent) reads as a fresh ledger. Every other problem -- an I/O failure, a
     * foreign schema version, a month key or timestamp that does not parse, a negative count -- is a
     * failure, so the caller refuses instead of silently starting from zero.
     */
    internal fun readState(): Result<CreditLedgerState> = try {
        val bytes = try {
            Files.readAllBytes(path)
        } catch (_: NoSuchFileException) {
            return Result.success(CreditLedgerState(schemaVersion = SCHEMA_VERSION))
        }
        val state = json.decodeFromString(CreditLedgerState.serializer(), bytes.toString(Charsets.UTF_8))
        when {
            state.schemaVersion != SCHEMA_VERSION ->
                Result.failure(IllegalStateException("schemaVersion is ${state.schemaVersion}, not $SCHEMA_VERSION"))
            state.months.values.any { it < 0 } ->
                Result.failure(IllegalStateException("a monthly credit count is negative"))
            state.months.keys.any { runCatching { YearMonth.parse(it) }.isFailure } ->
                Result.failure(IllegalStateException("a month key is not YYYY-MM"))
            state.lastPassAt != null && runCatching { Instant.parse(state.lastPassAt) }.isFailure ->
                Result.failure(IllegalStateException("lastPassAt is not an ISO-8601 instant"))
            else -> Result.success(state)
        }
    } catch (failure: SerializationException) {
        Result.failure(IllegalStateException("the file is not the expected JSON", failure))
    } catch (failure: DateTimeParseException) {
        Result.failure(IllegalStateException("the file carries an unparsable timestamp", failure))
    } catch (failure: IOException) {
        Result.failure(IllegalStateException("the file could not be read: ${failure.javaClass.simpleName}", failure))
    }

    /**
     * Temp file in the **same directory** plus a rename: a half-written state file would be an
     * unparsable one, which fails closed and would block every later pass.
     */
    internal fun write(state: CreditLedgerState): Boolean {
        val directory = path.toAbsolutePath().parent
        if (directory == null) {
            logger.error("discovery credit ledger path '{}' has no parent directory", path)
            return false
        }
        var temp: Path? = null
        return try {
            temp = Files.createTempFile(directory, ".discovery-state", ".tmp")
            Files.write(temp, json.encodeToString(CreditLedgerState.serializer(), state).toByteArray(Charsets.UTF_8))
            try {
                Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING)
            }
            temp = null
            true
        } catch (failure: IOException) {
            logger.error(
                "discovery credit ledger '{}' could not be written ({}); the pass is refused",
                path,
                failure.javaClass.simpleName,
            )
            false
        } finally {
            temp?.let { runCatching { Files.deleteIfExists(it) } }
        }
    }

    /** Keeps the current month and the two before it; anything older is dropped. */
    private fun prune(months: Map<String, Int>, now: Instant): Map<String, Int> {
        val oldest = YearMonth.from(now.atZone(ZoneOffset.UTC)).minusMonths(KEPT_MONTHS - 1L)
        return months.filterKeys { key ->
            // A key is already known to parse (readState refused the file otherwise); a future key
            // is kept rather than dropped, so a clock moved backwards cannot erase a spend.
            !YearMonth.parse(key).isBefore(oldest)
        }
    }

    internal companion object {
        const val SCHEMA_VERSION: Int = 1

        /** Three months of history: enough to see the previous cycle, small enough to stay tiny. */
        const val KEPT_MONTHS: Int = 3

        private val json = Json {
            ignoreUnknownKeys = false
            isLenient = false
            encodeDefaults = true
        }

        private val logger = LoggerFactory.getLogger(CreditLedger::class.java)
    }
}

/**
 * One pass's spending. [requestCredit] is the question a caller asks **before** every query; it
 * records the credit before the query is made, so nothing that happens afterwards -- a timeout, a
 * cancellation, a hard kill -- can lose it.
 */
class CreditPass internal constructor(
    private val ledger: CreditLedger,
    private var state: CreditLedgerState,
) {

    private var creditsThisPass = 0

    private var queriesThisPass = 0

    val counters: LedgerCounters
        get() = LedgerCounters(
            creditsThisMonth = creditsThisMonth(),
            creditsThisPass = creditsThisPass,
            queriesThisPass = queriesThisPass,
            maxCreditsPerMonth = ledger.maxCreditsPerMonth,
            maxQueriesPerPass = ledger.maxQueriesPerPass,
        )

    /**
     * Grants one credit, or refuses with the reason and the counters -- never an exception. The
     * month key is read from the clock on every call, so a pass that straddles a UTC month boundary
     * charges each query to the month it actually ran in.
     */
    fun requestCredit(): CreditDecision {
        if (queriesThisPass >= ledger.maxQueriesPerPass) {
            return CreditDecision.Refused(
                BudgetRefusal.PASS_QUERY_CAP_REACHED,
                "this pass has issued its $queriesThisPass queries, the per-pass maximum",
                counters,
            )
        }
        val month = ledger.monthKey(ledger.now())
        val spentThisMonth = state.months[month] ?: 0
        if (spentThisMonth >= ledger.maxCreditsPerMonth) {
            return CreditDecision.Refused(
                BudgetRefusal.MONTHLY_CAP_REACHED,
                "$spentThisMonth of ${ledger.maxCreditsPerMonth} credits are already spent in $month",
                counters,
            )
        }
        // Write-ahead: the credit is on disk before it is spent, never after.
        val next = state.copy(months = state.months + (month to spentThisMonth + 1))
        if (!ledger.write(next)) {
            return CreditDecision.Refused(
                BudgetRefusal.STATE_UNWRITABLE,
                "the credit could not be recorded, so it is not spent",
                counters,
            )
        }
        state = next
        creditsThisPass++
        queriesThisPass++
        return CreditDecision.Granted(counters)
    }

    /**
     * Flushes the counters now. Redundant while [requestCredit] writes ahead, and kept as the
     * operation a caller invokes before it unwinds -- a pass that is cancelled or times out calls
     * this and is guaranteed not to have forgotten a credit.
     *
     * The flush **merges with what is on disk** instead of overwriting it with this pass's copy: a
     * late flush (a timed-out pass unwinding after a forced pass has already started and spent)
     * must never erase the newer pass's credits or its `lastPassAt`. Per month the larger count
     * wins and the later `lastPassAt` wins, so a flush can only ever raise the counter. A state
     * file that has become unreadable is left alone and reported as a failed flush.
     */
    fun persistSpent(): Boolean {
        val onDisk = ledger.readState().getOrElse { problem ->
            logger.error("discovery credit ledger could not be re-read for a flush ({})", problem.message)
            return false
        }
        val merged = state.copy(
            lastPassAt = listOfNotNull(state.lastPassAt, onDisk.lastPassAt).maxByOrNull(Instant::parse),
            months = (state.months.keys + onDisk.months.keys).associateWith { month ->
                maxOf(state.months[month] ?: 0, onDisk.months[month] ?: 0)
            },
        )
        if (!ledger.write(merged)) return false
        state = merged
        return true
    }

    /** Ends the pass: one flush and one INFO line with what it cost. */
    fun finish(): Boolean {
        val persisted = persistSpent()
        logger.info(
            "discovery pass spent {} credit(s) over {} quer(y/ies); {} of {} credits used this month",
            creditsThisPass,
            queriesThisPass,
            creditsThisMonth(),
            ledger.maxCreditsPerMonth,
        )
        return persisted
    }

    private fun creditsThisMonth(): Int = state.months[ledger.monthKey(ledger.now())] ?: 0

    private companion object {
        private val logger = LoggerFactory.getLogger(CreditPass::class.java)
    }
}
