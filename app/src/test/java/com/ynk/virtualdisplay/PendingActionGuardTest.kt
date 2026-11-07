package com.ynk.virtualdisplay

import com.ynk.virtualdisplay.automation.*
import org.junit.Assert.*
import org.junit.Test

/**
 * 中断之后先确认：结果未知的动作不能被自动重复执行。
 */
class PendingActionGuardTest {
    private val studyId = AttemptId("run-1", "study", 1).value
    private val mailboxId = AttemptId("run-1", "plan-1:mailbox", 1).value
    private val workId = AttemptId("run-1", "work", 1).value

    private fun attempt(id: String, status: ReserveStatus, minutes: Int = 10,
        day: String = "2026-11-03", reason: String? = null) =
        BudgetAttempt(id, day, minutes, status, reason)

    @Test fun stepTypeComesFromTheAttemptId() {
        assertEquals(PlanStepType.STUDY, PendingActionGuard.stepTypeOf(studyId))
        assertEquals(PlanStepType.MAILBOX, PendingActionGuard.stepTypeOf(mailboxId))
        assertEquals(PlanStepType.WORK, PendingActionGuard.stepTypeOf(workId))
        assertNull(PendingActionGuard.stepTypeOf("not-an-attempt"))
    }

    @Test fun retainedAndReconciledUnknownBothNeedConfirmation() {
        val retained = attempt(studyId, ReserveStatus.UNCONFIRMED_RETAINED, reason = "CAPTURE_LOST")
        val reconciled = attempt(mailboxId, ReserveStatus.RECONCILED_STOPPED,
            reason = "RECONCILED_INTERRUPTED;OUTCOME_REMAINS_UNKNOWN;at=1;no-replay")
        val pending = PendingActionGuard.pendingActions(listOf(retained, reconciled))
        assertEquals(2, pending.size)
        assertEquals(setOf(PlanStepType.STUDY, PlanStepType.MAILBOX), pending.map { it.stepType }.toSet())
    }

    @Test fun finishedOrUnrelatedActionsAreNotPending() {
        val completed = attempt(studyId, ReserveStatus.COMPLETED)
        val confirmedNotDone = attempt(studyId, ReserveStatus.RECONCILED_STOPPED,
            reason = "RECONCILED_INTERRUPTED;OUTCOME_REMAINS_UNKNOWN;USER_CONFIRMED_NOT_DONE;at=1")
        val work = attempt(workId, ReserveStatus.UNCONFIRMED_RETAINED, reason = "CAPTURE_LOST")
        assertTrue(PendingActionGuard.pendingActions(listOf(completed, confirmedNotDone, work)).isEmpty())
    }

    @Test fun decideOnlyBlocksTheActionThatIsUnknown() {
        val pending = PendingActionGuard.pendingActions(
            listOf(attempt(studyId, ReserveStatus.UNCONFIRMED_RETAINED, reason = "CAPTURE_LOST")))
        assertTrue(PendingActionGuard.decide(pending, PlanStepType.MAILBOX) is PendingActionDecision.Proceed)
        val decision = PendingActionGuard.decide(pending, PlanStepType.STUDY)
        assertTrue(decision is PendingActionDecision.NeedsConfirmation)
        assertEquals(1, (decision as PendingActionDecision.NeedsConfirmation).actions.size)
    }

    @Test fun confirmedDoneCountOnlyCountsTodaysOwnAction() {
        val done = attempt(studyId, ReserveStatus.COMPLETED,
            reason = "USER_CONFIRMED_DONE;at=1;note=用户确认已完成")
        val yesterday = attempt(mailboxId, ReserveStatus.COMPLETED, day = "2026-11-02",
            reason = "USER_CONFIRMED_DONE;at=1")
        val other = attempt(workId, ReserveStatus.COMPLETED,
            reason = "USER_CONFIRMED_DONE;at=1")
        val attempts = listOf(done, yesterday, other)
        assertEquals(1, PendingActionGuard.confirmedDoneCount(attempts, "2026-11-03", PlanStepType.STUDY))
        assertEquals(0, PendingActionGuard.confirmedDoneCount(attempts, "2026-11-03", PlanStepType.MAILBOX))
    }

    @Test fun userConfirmationKeepsMinutesAndDayAndEndsThePendingBlock() {
        val store = InMemoryBudgetStore()
        val ledger = PlanBudgetLedger(store) { "2026-11-03" }
        ledger.reserve(studyId, 10)
        ledger.unconfirmed(studyId, "CAPTURE_LOST")
        val updated = ledger.confirmOutcome(studyId, true, "用户确认已完成", 1_700_000L)
        assertEquals(ReserveStatus.COMPLETED, updated.status)
        assertEquals(10, updated.minutes)
        assertEquals("2026-11-03", updated.day)
        assertTrue(updated.reason!!.contains(PendingActionGuard.USER_DONE_MARK))
        assertEquals(10, ledger.usedMinutes("2026-11-03"))
        assertTrue(PendingActionGuard.pendingActions(ledger.attempts()).isEmpty())
        assertEquals(1, PendingActionGuard.confirmedDoneCount(ledger.attempts(), "2026-11-03", PlanStepType.STUDY))
    }

    @Test fun userSaysItDidNotHappenSoItMayRunAgain() {
        val store = InMemoryBudgetStore()
        val ledger = PlanBudgetLedger(store) { "2026-11-03" }
        ledger.reserve(studyId, 10)
        ledger.unconfirmed(studyId, "CAPTURE_LOST")
        val updated = ledger.confirmOutcome(studyId, false, "用户确认没有发生", 1_700_000L)
        assertEquals(ReserveStatus.RECONCILED_STOPPED, updated.status)
        assertTrue(updated.reason!!.contains(PendingActionGuard.USER_NOT_DONE_MARK))
        assertTrue(PendingActionGuard.pendingActions(ledger.attempts()).isEmpty())
        assertEquals(0, PendingActionGuard.confirmedDoneCount(ledger.attempts(), "2026-11-03", PlanStepType.STUDY))
    }

    @Test fun settledAttemptCannotBeConfirmedTwice() {
        val store = InMemoryBudgetStore()
        val ledger = PlanBudgetLedger(store) { "2026-11-03" }
        ledger.reserve(studyId, 10)
        ledger.completed(studyId)
        val failure = runCatching { ledger.confirmOutcome(studyId, true, "用户确认已完成", 1_700_000L) }
        assertTrue(failure.isFailure)
    }
}
