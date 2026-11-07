package com.ynk.virtualdisplay

import com.ynk.virtualdisplay.automation.AttemptId
import com.ynk.virtualdisplay.automation.BudgetAttempt
import com.ynk.virtualdisplay.automation.BudgetLedgerState
import com.ynk.virtualdisplay.automation.BudgetLedgerStore
import com.ynk.virtualdisplay.automation.CarePendingReconciliation
import com.ynk.virtualdisplay.automation.InterruptedAttemptRecovery
import com.ynk.virtualdisplay.automation.InterruptedAttemptRecovery.Outcome
import com.ynk.virtualdisplay.automation.PlanBudgetLedger
import com.ynk.virtualdisplay.automation.ReserveStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class InterruptedAttemptRecoveryTest {
    @get:Rule val folder = TemporaryFolder()

    private class MemoryStore(var state: BudgetLedgerState) : BudgetLedgerStore {
        override fun read() = state
        override fun write(state: BudgetLedgerState) { this.state = state }
    }

    private val session = "9013e972-89be-48dd-a5de-846df1dfb986"
    private val observedAt = 1_000_000L
    private val elapsed = observedAt + 11 * 60_000L

    private fun store(id: String = session, status: ReserveStatus = ReserveStatus.UNCONFIRMED_RETAINED,
        minutes: Int = 10, step: String = "work"): MemoryStore {
        val attemptId = AttemptId(id, step, 1).value
        return MemoryStore(BudgetLedgerState("2026-11-01", 0,
            mapOf(attemptId to BudgetAttempt(attemptId, "2026-11-01", minutes, status, "CHECKPOINT_ACTION_UNCONFIRMED"))))
    }

    private fun checkpoint(id: String = session, at: Long = observedAt, named: String = id) {
        File(folder.root, "pet-checkpoints").mkdirs()
        File(folder.root, "pet-checkpoints/$named.json")
            .writeText("""{"sessionId":"$id","observedAt":$at,"actionPending":true}""")
    }

    @Test fun finishedShiftReleasesTheGateWithoutRefundingItsMinutes() {
        checkpoint(); val store = store()
        val outcome = InterruptedAttemptRecovery(folder.root, store).run(elapsed)
        assertTrue(outcome is Outcome.Reconciled)
        val attempt = store.state.attempts.values.single()
        assertEquals(ReserveStatus.RECONCILED_STOPPED, attempt.status)
        assertTrue(attempt.reason!!.contains("OUTCOME_REMAINS_UNKNOWN"))
        assertEquals(10, PlanBudgetLedger(store) { "2026-11-01" }.usedMinutes())
        assertTrue(store.state.attempts.values.none(CarePendingReconciliation::unresolved))
    }

    @Test fun shiftThatMayStillBeRunningKeepsTheGateClosed() {
        checkpoint(); val store = store()
        val outcome = InterruptedAttemptRecovery(folder.root, store).run(observedAt + 5 * 60_000L)
        assertEquals(Outcome.StillRunning(6 * 60_000L), outcome)
        assertEquals(ReserveStatus.UNCONFIRMED_RETAINED, store.state.attempts.values.single().status)
    }

    @Test fun clockBeforeTheCheckpointNeverCountsAsElapsed() {
        checkpoint(); val store = store()
        assertTrue(InterruptedAttemptRecovery(folder.root, store).run(observedAt - 1) is Outcome.StillRunning)
    }

    @Test fun zeroMinuteCareOnlyNeedsTheStartMargin() {
        checkpoint(); val store = store(minutes = 0, step = "care")
        assertTrue(InterruptedAttemptRecovery(folder.root, store).run(observedAt + 60_000L) is Outcome.Reconciled)
    }

    @Test fun missingOrForeignCheckpointIsNotApplicable() {
        val store = store()
        assertEquals(Outcome.NotApplicable, InterruptedAttemptRecovery(folder.root, store).run(elapsed))
        checkpoint(id = "other-session", named = session)
        assertEquals(Outcome.NotApplicable, InterruptedAttemptRecovery(folder.root, store).run(elapsed))
        assertEquals(ReserveStatus.UNCONFIRMED_RETAINED, store.state.attempts.values.single().status)
    }

    @Test fun allowlistedLegacySessionsStayWithTheirOwnRecovery() {
        val legacy = CarePendingReconciliation.legacySessionIds.first()
        checkpoint(id = legacy)
        assertEquals(Outcome.NotApplicable,
            InterruptedAttemptRecovery(folder.root, store(id = legacy, minutes = 0, step = "care")).run(elapsed))
    }

    @Test fun nothingPendingIsNotApplicable() {
        assertEquals(Outcome.NotApplicable, InterruptedAttemptRecovery(folder.root,
            store(status = ReserveStatus.COMPLETED)).run(elapsed))
    }
}
