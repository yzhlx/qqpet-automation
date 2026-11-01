package com.ynk.virtualdisplay

import com.ynk.virtualdisplay.automation.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class InterruptedRunClosureTest {
    @get:Rule val folder = TemporaryFolder()

    private class MemoryStore(var state: BudgetLedgerState) : BudgetLedgerStore {
        override fun read() = state
        override fun write(state: BudgetLedgerState) { this.state = state }
    }

    private val runId = "f41c51e3-ca62-47dd-8eb6-c54821ee7a06"
    private val observedAt = 1_000_000L
    private val elapsed = observedAt + 11 * 60_000L
    private val runRoot get() = File(folder.root, "plan-runs")

    private fun saveRun(steps: List<PlanStep>, stopReason: String = "UNKNOWN_WAIT_RESULT_PAGE",
        state: RunState = RunState.UNCONFIRMED) {
        val plan = PlanV2(planId = "p", name = "n", dailyBudgetMinutes = 480, steps = steps)
        FileRunStore(runRoot).save(RunSnapshot(runId, RunRequest("r", plan, "pet-local-device", 6, RunOrigin.PLAN, 1),
            state = state, startedAtMs = 1, stopReason = stopReason))
    }

    private fun studyRun() = saveRun(listOf(WorkStep("w", "work:verified"), StudyStep("study", "course:verified")))

    private fun budget(step: String = "study", minutes: Int = 10): MemoryStore {
        val id = AttemptId(runId, step, 1).value
        return MemoryStore(BudgetLedgerState("2026-11-01", 0, mapOf(id to BudgetAttempt(id, "2026-11-01", minutes,
            ReserveStatus.UNCONFIRMED_RETAINED, "CHECKPOINT_ACTION_UNCONFIRMED"))))
    }

    private fun checkpoint() {
        File(folder.root, "pet-checkpoints").mkdirs()
        File(folder.root, "pet-checkpoints/$runId.json")
            .writeText("""{"sessionId":"$runId","observedAt":$observedAt,"actionPending":false}""")
    }

    private fun recovery(store: BudgetLedgerStore) = InterruptedAttemptRecovery(folder.root, store)

    @Test fun studyRunIsReleasedAfterTheCourseTimeWithoutInventingAResult() {
        studyRun(); checkpoint(); val ledger = budget()
        val outcome = recovery(ledger).runAndCloseRuns(runRoot, elapsed)
        assertTrue(outcome is InterruptedAttemptRecovery.Outcome.Reconciled)
        assertNull(PlanRunPendingGate.blockingRunId(runRoot))
        val run = checkNotNull(FileRunStore(runRoot).load(runId))
        assertEquals(RunState.FAILED, run.state)
        assertEquals(InterruptedRunClosure.PREFIX + "UNKNOWN_WAIT_RESULT_PAGE", run.stopReason)
        assertEquals(RunCounts(), run.counts)
        assertEquals(10, PlanBudgetLedger(ledger) { "2026-11-01" }.usedMinutes())
    }

    @Test fun courseThatMayStillBeRunningKeepsTheRunBlocking() {
        studyRun(); checkpoint(); val ledger = budget()
        val outcome = recovery(ledger).runAndCloseRuns(runRoot, observedAt + 5 * 60_000L)
        assertTrue(outcome is InterruptedAttemptRecovery.Outcome.StillRunning)
        assertEquals(runId, PlanRunPendingGate.blockingRunId(runRoot))
    }

    @Test fun unreadableCheckpointNeverClosesARunWithBudgetPending() {
        studyRun()
        assertEquals(InterruptedAttemptRecovery.Outcome.NotApplicable, recovery(budget()).runAndCloseRuns(runRoot, elapsed))
        assertEquals(runId, PlanRunPendingGate.blockingRunId(runRoot))
    }

    @Test fun runLeftBehindWithNothingChargedIsClosed() {
        saveRun(listOf(WorkStep("w", "work:verified")), "", RunState.RUNNING)
        val outcome = recovery(MemoryStore(BudgetLedgerState("2026-11-01", 0, emptyMap()))).runAndCloseRuns(runRoot, elapsed)
        assertTrue(outcome is InterruptedAttemptRecovery.Outcome.Reconciled)
        assertNull(PlanRunPendingGate.blockingRunId(runRoot))
    }

    @Test fun healthStaleNonStudyRunStaysWithFinalization() {
        saveRun(listOf(WorkStep("w", "work:verified")), "HEALTH_STALE:OBSERVATION_EXPIRED")
        recovery(MemoryStore(BudgetLedgerState("2026-11-01", 0, emptyMap()))).runAndCloseRuns(runRoot, elapsed)
        assertEquals(runId, PlanRunPendingGate.blockingRunId(runRoot))
    }

    @Test fun closedRunWithAnUnresolvedInputStopsBlockingButKeepsItsAudit() {
        studyRun(); checkpoint()
        val store = FileRunStore(runRoot); val id = AttemptId(runId, "study", 1)
        store.persistIntent(ActivityAttempt(id, 10, AttemptState.INPUT_PENDING, inputPending = true))
        recovery(budget()).runAndCloseRuns(runRoot, elapsed)
        assertNull(PlanRunPendingGate.blockingRunId(runRoot))
        assertTrue(store.hasPendingInput(runId))
    }
}
