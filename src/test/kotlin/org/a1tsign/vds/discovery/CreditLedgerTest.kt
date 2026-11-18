package org.a1tsign.vds.discovery

import org.a1tsign.vds.support.MutableTestClock
import org.a1tsign.vds.support.capturingLogs
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.Comparator
import kotlin.io.path.createDirectory
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CreditLedgerTest {

    private fun clockAt(instant: String) = MutableTestClock(Instant.parse(instant))

    private fun ledger(
        dir: Path,
        clock: MutableTestClock,
        maxCreditsPerMonth: Int = 300,
        maxQueriesPerPass: Int = 10,
        minPassIntervalHours: Long = 24,
        file: String = "discovery-state.json",
    ) = CreditLedger(dir.resolve(file), clock, maxCreditsPerMonth, maxQueriesPerPass, minPassIntervalHours)

    @Test
    fun `a missing state file is a first run, and the credits survive a restart`(@TempDir dir: Path) {
        val clock = clockAt("2026-11-10T08:00:00Z")
        val first = assertIs<PassStart.Started>(ledger(dir, clock).startPass()).pass

        repeat(3) { assertIs<CreditDecision.Granted>(first.requestCredit()) }
        assertTrue(first.finish(), "the final flush really wrote")
        assertEquals(3, first.counters.creditsThisPass)

        // A new process over the same file: the counter is not reset.
        clock.advance(Duration.ofHours(48))
        val second = assertIs<PassStart.Started>(ledger(dir, clock).startPass())

        assertEquals(3, second.pass.counters.creditsThisMonth)
        assertTrue(dir.resolve("discovery-state.json").readText().contains("2026-11"))
    }

    @Test
    fun `the monthly cap and the per-pass query cap end the pass cleanly with the counters reported`(
        @TempDir dir: Path,
    ) {
        val clock = clockAt("2026-11-10T08:00:00Z")
        val monthly = ledger(dir, clock, maxCreditsPerMonth = 2, file = "monthly.json")
        val pass = assertIs<PassStart.Started>(monthly.startPass()).pass

        assertIs<CreditDecision.Granted>(pass.requestCredit())
        assertIs<CreditDecision.Granted>(pass.requestCredit())
        val refused = assertIs<CreditDecision.Refused>(pass.requestCredit())
        assertEquals(BudgetRefusal.MONTHLY_CAP_REACHED, refused.reason)
        assertEquals(2, refused.counters.creditsThisMonth)
        assertEquals(2, refused.counters.creditsThisPass)
        pass.finish()

        clock.advance(Duration.ofHours(48))
        val blocked = assertIs<PassStart.Refused>(monthly.startPass())
        assertEquals(BudgetRefusal.MONTHLY_CAP_REACHED, blocked.reason)
        assertEquals(2, blocked.counters.creditsThisMonth)
        assertIs<PassStart.Refused>(monthly.startPass(force = true))

        val perPass = ledger(dir, clock, maxQueriesPerPass = 1, file = "per-pass.json")
        val short = assertIs<PassStart.Started>(perPass.startPass()).pass
        assertIs<CreditDecision.Granted>(short.requestCredit())
        assertEquals(BudgetRefusal.PASS_QUERY_CAP_REACHED, assertIs<CreditDecision.Refused>(short.requestCredit()).reason)
    }

    @Test
    fun `an unparsable, foreign or unwritable state file refuses the pass`(@TempDir dir: Path) {
        val clock = clockAt("2026-11-10T08:00:00Z")
        val cases = mapOf(
            "truncated.json" to "{\"schemaVersion\": 1, \"months\": {",
            "foreign.json" to """{"schemaVersion": 2, "months": {}}""",
            "bad-month.json" to """{"schemaVersion": 1, "months": {"November": 3}}""",
            "bad-instant.json" to """{"schemaVersion": 1, "lastPassAt": "yesterday", "months": {}}""",
            "negative.json" to """{"schemaVersion": 1, "months": {"2026-11": -5}}""",
        )

        val logs = capturingLogs {
            cases.forEach { (file, content) ->
                dir.resolve(file).writeText(content)

                val refused = assertIs<PassStart.Refused>(ledger(dir, clock, file = file).startPass(), file)

                assertEquals(BudgetRefusal.STATE_UNREADABLE, refused.reason, file)
                assertEquals(0, refused.counters.creditsThisMonth, file)
            }
        }
        // A *zero* count is a legitimate file, not a corrupt one: refusing it would block passes.
        dir.resolve("zero.json").writeText("""{"schemaVersion": 1, "months": {"2026-11": 0}}""")
        assertIs<PassStart.Started>(ledger(dir, clock, file = "zero.json").startPass())
        assertTrue(logs.messages().any { it.contains("refused") }, "a lost counter is an ERROR, not a shrug")

        // Unwritable: the file would have to be created in a directory that does not exist.
        val unwritable = CreditLedger(dir.resolve("absent").resolve("state.json"), clock)
        assertEquals(BudgetRefusal.STATE_UNWRITABLE, assertIs<PassStart.Refused>(unwritable.startPass()).reason)

        val vanishing = dir.resolve("vanishing").createDirectory()
        val pass = assertIs<PassStart.Started>(CreditLedger(vanishing.resolve("state.json"), clock).startPass()).pass
        assertIs<CreditDecision.Granted>(pass.requestCredit())
        Files.walk(vanishing).sorted(Comparator.reverseOrder()).forEach(Files::delete)
        assertFalse(pass.persistSpent(), "a failed flush is reported, not swallowed")
        assertFalse(pass.finish())
    }

    @Test
    fun `the counter rolls over at a UTC month boundary and entries older than three months are pruned`(
        @TempDir dir: Path,
    ) {
        dir.resolve("state.json").writeText(
            """{"schemaVersion": 1, "months": {"2026-07": 9, "2026-08": 5, "2026-09": 4, "2026-10": 3}}""",
        )
        val clock = clockAt("2026-10-31T23:59:00Z")
        val pass = assertIs<PassStart.Started>(ledger(dir, clock, file = "state.json").startPass()).pass

        assertEquals(3, pass.counters.creditsThisMonth, "October's own spend, not the whole file's")
        assertIs<CreditDecision.Granted>(pass.requestCredit())
        assertEquals(4, pass.counters.creditsThisMonth)

        val afterPrune = dir.resolve("state.json").readText()
        assertFalse(afterPrune.contains("2026-07"), "only the current month and the two before it are kept")
        assertTrue(afterPrune.contains("2026-08"))

        clock.advance(Duration.ofMinutes(2))
        assertIs<CreditDecision.Granted>(pass.requestCredit())

        assertEquals(1, pass.counters.creditsThisMonth, "November starts at zero")
        assertTrue(dir.resolve("state.json").readText().contains("\"2026-11\":1"))
    }

    @Test
    fun `a pass inside the minimum interval is refused unless it is forced, and the spend is already on disk`(
        @TempDir dir: Path,
    ) {
        val clock = clockAt("2026-11-10T08:00:00Z")
        val ledger = ledger(dir, clock, minPassIntervalHours = 24)
        val first = assertIs<PassStart.Started>(ledger.startPass()).pass
        assertIs<CreditDecision.Granted>(first.requestCredit())
        // No finish() at all: the pass is abandoned, as a timeout or a cancellation would.

        clock.advance(Duration.ofHours(1))
        val refused = assertIs<PassStart.Refused>(ledger.startPass())
        assertEquals(BudgetRefusal.MIN_PASS_INTERVAL, refused.reason)
        assertTrue(refused.message.contains("24 hour"), refused.message)
        assertEquals(1, refused.counters.creditsThisMonth)

        val logs = capturingLogs {
            val forced = assertIs<PassStart.Started>(ledger.startPass(force = true))
            assertEquals(1, forced.pass.counters.creditsThisMonth)
        }
        // The interval is "less than", so exactly MIN_PASS_INTERVAL_HOURS later needs no force. The
        // forced pass above moved lastPassAt, so the 24 h run from *there*.
        clock.advance(Duration.ofHours(24))
        assertIs<PassStart.Started>(ledger.startPass())
        assertTrue(
            logs.messagesAt(org.slf4j.event.Level.WARN).any { it.contains("forced") },
            "an override of the crash-loop guard is announced: ${logs.messages()}",
        )
    }

    @Test
    fun `a late flush from an older pass never erases what a newer pass recorded`(@TempDir dir: Path) {
        val clock = clockAt("2026-11-10T08:00:00Z")
        val ledger = ledger(dir, clock)
        val stale = assertIs<PassStart.Started>(ledger.startPass()).pass
        assertIs<CreditDecision.Granted>(stale.requestCredit())

        // The first pass has timed out but not unwound yet; a forced pass starts and spends twice.
        clock.advance(Duration.ofHours(1))
        val newer = assertIs<PassStart.Started>(ledger.startPass(force = true)).pass
        assertIs<CreditDecision.Granted>(newer.requestCredit())
        assertIs<CreditDecision.Granted>(newer.requestCredit())
        val newerPassAt = clock.instant()

        // Now the stale pass flushes its own, older copy.
        assertTrue(stale.persistSpent())
        assertTrue(stale.finish())

        val onDisk = ledger.readState().getOrThrow()
        assertEquals(3, onDisk.months["2026-11"], "the newer pass's credits survive the stale flush")
        assertEquals(newerPassAt.toString(), onDisk.lastPassAt, "and so does its lastPassAt")
    }
}
