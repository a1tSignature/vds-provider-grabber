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
 * Record each credit before sending a query and persist the pass start before searching so crashes cannot reset either budget.
 * A missing state file begins a ledger. Invalid or unwritable state blocks spending. Month boundaries use UTC.
 * The shared run slot must serialize access to this ledger.
 */
class CreditLedger(
    private val path: Path,
    private val clock: Clock,
    internal val maxCreditsPerMonth: Int = 300,
    internal val maxQueriesPerPass: Int = 10,
    private val minPassIntervalHours: Long = 24,
) {

    /** Force bypasses only the minimum interval. State errors and credit caps still refuse a pass. */
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

    /** Treat a missing state file as a fresh ledger. Invalid or unreadable state fails closed. */
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

    /** Write the temporary file beside the state file and rename it so readers never see a partial write. */
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

    private fun prune(months: Map<String, Int>, now: Instant): Map<String, Int> {
        val oldest = YearMonth.from(now.atZone(ZoneOffset.UTC)).minusMonths(KEPT_MONTHS - 1L)
        return months.filterKeys { key ->
            // Preserve future month keys so a backward clock jump cannot erase recorded credits.
            !YearMonth.parse(key).isBefore(oldest)
        }
    }

    internal companion object {
        const val SCHEMA_VERSION: Int = 1

        const val KEPT_MONTHS: Int = 3

        private val json = Json {
            ignoreUnknownKeys = false
            isLenient = false
            encodeDefaults = true
        }

        private val logger = LoggerFactory.getLogger(CreditLedger::class.java)
    }
}

/** Persist a credit before each query. A timeout or ambiguous response still consumes the reserved credit. */
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

    /** Flush while unwinding, merging with persisted state so a cleanup write cannot lose an already recorded credit. */
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
