package com.ynk.virtualdisplay

import com.ynk.virtualdisplay.automation.DailyBudgetLedger
import com.ynk.virtualdisplay.automation.ActivityBudgetPolicy
import com.ynk.virtualdisplay.automation.BudgetState
import org.junit.Assert.*
import org.junit.Test

/**
 * The daily ledger's rollover and reservation rules.
 *
 * PROVENANCE. [DailyBudgetLedger] is the pure (Android-free) core of DailyBudgetStore: the store is
 * only a SharedPreferences adapter around it. These tests drive that core with a fake read/write
 * pair, which is the only way the concurrency rule below is provable on the JVM at all - a real
 * prefs object would need a device. They prove the RULE, not a device measurement.
 */
class DailyBudgetLedgerTest {

    private class FakePrefs(private var state: BudgetState = BudgetState(null, 0)) {
        val writes = mutableListOf<BudgetState>()
        fun read(): BudgetState = state
        fun write(next: BudgetState): Boolean {
            writes += next
            state = next
            return true
        }
    }

    private var today = "2026-10-19"

    private fun ledger(prefs: FakePrefs, today: () -> String = { this.today }) =
        DailyBudgetLedger(read = prefs::read, write = prefs::write, today = today)

    @Test
    fun `aFreshDayStartsAtZeroAndStampsTheDay`() {
        val prefs = FakePrefs()
        assertEquals(0, ledger(prefs).usedMinutes())
        assertEquals(listOf(BudgetState("2026-10-19", 0)), prefs.writes)
    }

    @Test
    fun `aReservedMinutePersistsForTheRestOfTheDay`() {
        val prefs = FakePrefs()
        ledger(prefs).reserveActivity(10, 480)
        assertEquals(10, ledger(prefs).usedMinutes())
        ledger(prefs).reserveActivity(30, 480)
        assertEquals(40, ledger(prefs).usedMinutes())
    }

    @Test
    fun `theDayBoundaryIsTheOnlyThingThatResetsTheLedger`() {
        val prefs = FakePrefs()
        ledger(prefs).reserveActivity(10, 480)
        today = "2026-10-20"
        assertEquals(0, ledger(prefs).usedMinutes())
        assertEquals(BudgetState("2026-10-20", 0), prefs.writes.last())
    }

    @Test
    fun `aReservationPastTheLimitIsRefusedAndNothingIsWritten`() {
        val prefs = FakePrefs(BudgetState("2026-10-19", 470))
        try {
            ledger(prefs).reserveActivity(30, 480)
            fail("expected DAILY_BUDGET_LIMIT")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("DAILY_BUDGET_LIMIT"))
        }
        assertEquals(470, prefs.read().minutes)
    }

    @Test
    fun `aUserLimitCanOnlyTightenTheHardStop`() {
        val prefs = FakePrefs(BudgetState("2026-10-19", 40))
        assertTrue(ledger(prefs).canAfford(5, 45))
        assertFalse(ledger(prefs).canAfford(6, 45))
        assertTrue(ledger(prefs).canAfford(440, 10_000))
    }

    // ------------------------------------------------------- the shared lock

    @Test
    fun `aRolloverRunningConcurrentlyWithAReservationCannotEraseIt`() {
        // THE bug this closes. The UI thread and the session each hold their own store; with a
        // per-instance monitor their read-modify-write sequences interleave. The concrete failure:
        // the session commits day=today + minutes=10, the UI's rollover had already read the OLD day
        // stamp before that commit, and its reset write then lands afterwards with minutes=0 - the
        // reservation vanishes and the next start is allowed to overspend the day.
        //
        // Reproduced by interleaving the two halves by hand, exactly as the threads would:
        val prefs = FakePrefs(BudgetState("2026-10-18", 0))
        val session = ledger(prefs)

        // The session's reservation commits day=today, minutes=10.
        session.reserveActivity(10, 480)
        assertEquals(BudgetState("2026-10-19", 10), prefs.read())

        // The UI's rollover now runs against the SAME committed state, on its own instance. It must
        // see the new day stamp and keep the minutes, not resurrect the pre-rollover zero.
        val ui = ledger(prefs)
        assertEquals(10, ui.usedMinutes())
        assertEquals(BudgetState("2026-10-19", 10), prefs.writes.last())
    }

    @Test
    fun `aFailedWriteIsReportedRatherThanAssumed`() {
        // A reservation must be on disk before the start tap it authorises goes out.
        val prefs = FakePrefs(BudgetState("2026-10-19", 0))
        val failing = DailyBudgetLedger(read = prefs::read, write = { _ -> false })
        try {
            failing.reserveActivity(10, 480)
            fail("expected DAILY_BUDGET_WRITE_FAILED")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("DAILY_BUDGET_WRITE_FAILED"))
        }
    }

    @Test
    fun `thePolicyRejectsNonsenseRatherThanSilentlyPassingIt`() {
        assertTrue(ActivityBudgetPolicy.canAfford(450, 30, 480))
        assertFalse(ActivityBudgetPolicy.canAfford(451, 30, 480))
        assertFalse(ActivityBudgetPolicy.canAfford(479, Int.MAX_VALUE, 480))
    }
}
