package com.ynk.virtualdisplay

import com.ynk.virtualdisplay.automation.*
import org.junit.Assert.*
import org.junit.Test

class PlanV2Test {
    private val workId = "work:verified"
    private fun plan(steps: List<PlanStep> = listOf(StudyStep("study", "study:course", 2))) =
        PlanV2(planId = "plan", name = "独立方案", dailyBudgetMinutes = 480, steps = steps)
    private fun invalid(block: () -> Unit) {
        try { block(); fail("Expected invalid model") } catch (_: IllegalArgumentException) { }
    }

    @Test fun studyRoundsTwoAndFourArePreserved() {
        assertEquals(2, (plan().steps.single() as StudyStep).rounds)
        assertEquals(4, StudyStep("s", "study:course", 4).rounds)
    }
    @Test fun fixedTypesAndOrderAreExplicit() {
        val steps = listOf(WorkStep("w", workId), CareStep("c", 60, 100), StudyStep("s", "course", 10))
        val p = plan(steps)
        PlanValidator.validate(p, setOf(workId))
        assertEquals(listOf(PlanStepType.WORK, PlanStepType.CARE, PlanStepType.STUDY), p.steps.map { it.type })
    }
    @Test fun allSevenNonemptySubsetsAreValid() {
        val all = listOf(WorkStep("w", workId), CareStep("c", 70, 70), StudyStep("s", "course"))
        for (mask in 1..7) PlanValidator.validate(plan(all.filterIndexed { i, _ -> mask and (1 shl i) != 0 }), setOf(workId))
    }
    @Test fun schemaIdentityNameAndBudgetBoundsAreValidated() {
        invalid { PlanV2(1, "p", "name", 480, plan().steps) }
        invalid { PlanV2(planId = " ", name = "name", dailyBudgetMinutes = 480, steps = plan().steps) }
        invalid { PlanV2(planId = "p", name = "", dailyBudgetMinutes = 480, steps = plan().steps) }
        invalid { PlanV2(planId = "p", name = "x".repeat(81), dailyBudgetMinutes = 480, steps = plan().steps) }
        for (budget in listOf(0, 481)) invalid { PlanV2(planId = "p", name = "name", dailyBudgetMinutes = budget, steps = plan().steps) }
        assertEquals(80, PlanV2(planId = "p", name = "x".repeat(80), dailyBudgetMinutes = 1, steps = plan().steps).name.length)
    }
    @Test fun emptyDuplicateReversedOrDuplicateStepIdentitiesAreRejected() {
        invalid { plan(emptyList()) }
        invalid { plan(listOf(CareStep("c1", 70, 70), CareStep("c2", 80, 80))) }
        invalid { plan(listOf(StudyStep("s", "course"), WorkStep("w", workId))) }
        invalid { plan(listOf(WorkStep("same", workId), CareStep("same", 70, 70))) }
    }
    @Test fun workWhitelistIsExplicitAndDoesNotLoadAndroid() {
        val p = plan(listOf(WorkStep("w", workId)))
        invalid { PlanValidator.validate(p, emptySet()) }
        PlanValidator.validate(p, setOf(workId))
    }
    @Test fun conditionalWorkRequiresFollowingStudyAndOnlyOneShift() {
        invalid { plan(listOf(WorkStep("w", workId, WorkTrigger.IF_FUNDS_SHORT))) }
        PlanValidator.validate(plan(listOf(WorkStep("w", workId, WorkTrigger.IF_FUNDS_SHORT), StudyStep("s", "course"))), setOf(workId))
        invalid { WorkStep("w", workId, shifts = 2) }
    }
    @Test fun eachSelectedStepChecksOnlyItsOwnRequiredParameters() {
        PlanValidator.validate(plan(listOf(CareStep("c", 60, 60))), emptySet())
        invalid { WorkStep("", workId) }
        invalid { WorkStep("w", " ") }
        invalid { CareStep("c", 59, 100) }
        invalid { CareStep("c", 100, 101) }
        invalid { StudyStep("s", " ") }
        invalid { StudyStep("s", "course", 0) }
        invalid { StudyStep("s", "course", 11) }
    }
    @Test fun planDetachesAndProtectsTheCallerStepList() {
        val input = mutableListOf<PlanStep>(CareStep("c", 70, 70))
        val p = plan(input)
        input.clear()
        assertEquals(1, p.steps.size)
        try { (p.steps as MutableList).clear(); fail("Mutable snapshot") } catch (_: UnsupportedOperationException) { }
        assertEquals(p, p.copy())
    }
    @Test fun legacyProfilePreservesItsSourceWithoutChangingNewActionTypes() {
        val p = plan().copy(executionProfile = ExecutionProfile.LEGACY_MANAGED, legacySource = "{old}")
        assertEquals("{old}", p.legacySource)
        assertEquals(ExecutionProfile.LEGACY_MANAGED, p.executionProfile)
    }
}

class PlanExecutionModelsTest {
    private fun request(steps: List<PlanStep> = listOf(StudyStep("s", "course", 4))) = RunRequest(
        requestId = "request", planSnapshot = PlanV2(planId = "plan", name = "run", dailyBudgetMinutes = 480, steps = steps),
        deviceSerial = "device", displayId = 4, origin = RunOrigin.PLAN, createdAtMs = 10L)
    private fun invalid(block: () -> Unit) {
        try { block(); fail("Expected invalid execution model") } catch (_: IllegalArgumentException) { }
    }
    @Test fun attemptIdsAreStableAndDelimiterSafe() {
        assertEquals(AttemptId("run", "step", 2).value, AttemptId("run", "step", 2).value)
        assertNotEquals(AttemptId("a:b", "c", 1).value, AttemptId("a", "b:c", 1).value)
        invalid { AttemptId("", "s", 1) }
        invalid { AttemptId("r", "s", 0) }
    }
    @Test fun snapshotsContainOnlySelectedStepsAndKeepIndependentCounters() {
        val req = request(listOf(WorkStep("w", "work:verified")))
        val snapshot = RunSnapshot("run", req)
        assertEquals(listOf(PlanStepType.WORK), snapshot.steps.map { it.type })
        assertEquals(0, snapshot.counts.studyRoundsCompleted)
        assertEquals(StepState.PENDING, snapshot.steps.single().state)
        invalid { RunSnapshot("run", req, steps = listOf(StepExecution("run", "s", 0, PlanStepType.STUDY))) }
    }
    @Test fun completedRunCannotContainPendingOrUnconfirmedSteps() {
        val req = request()
        invalid { RunSnapshot("run", req, state = RunState.COMPLETED) }
        val done = StepExecution("run", "s", 0, PlanStepType.STUDY, state = StepState.COMPLETED, endedAtMs = 50L)
        val snapshot = RunSnapshot("run", req, state = RunState.COMPLETED, steps = listOf(done), counts = RunCounts(studyRoundsCompleted = 4), endedAtMs = 50L)
        assertEquals(4, snapshot.counts.studyRoundsCompleted)
    }
    @Test fun skipRequiresAnAllowedReasonAndCorrectActionType() {
        invalid { StepExecution("run", "s", 0, PlanStepType.STUDY, StepState.SKIPPED, "FUNDS_SUFFICIENT") }
        invalid { StepExecution("run", "c", 0, PlanStepType.CARE, StepState.SKIPPED, "arbitrary") }
        val skipped = StepExecution("run", "c", 0, PlanStepType.CARE, StepState.SKIPPED, "ALREADY_SATISFIED")
        assertEquals(StepState.SKIPPED, skipped.state)
    }
    @Test fun careCanPersistAZeroMinuteInputIntent() {
        val attempt = ActivityAttempt(AttemptId("run", "care", 1), 0, AttemptState.INPUT_PENDING)
        assertTrue(attempt.inputPending)
        invalid { ActivityAttempt(AttemptId("run", "care", 1), -1) }
    }
    @Test fun inputPendingIsRetainedForUnconfirmedAttempts() {
        val attempt = ActivityAttempt(AttemptId("run", "s", 1), 30, AttemptState.UNCONFIRMED)
        assertTrue(attempt.inputPending)
        invalid { ActivityAttempt(AttemptId("run", "s", 1), 30, AttemptState.UNCONFIRMED, inputPending = false) }
        invalid { ActivityAttempt(AttemptId("run", "s", 1), 30, AttemptState.COMPLETED) }
        // A completed attempt must NOT still be flagged pending, even if a caller forgets to clear it.
        invalid { ActivityAttempt(AttemptId("run", "s", 1), 30, AttemptState.COMPLETED,
            inputPending = true, completionConfirmedAtMs = 40L) }
        val completed = ActivityAttempt(AttemptId("run", "s", 1), 30, AttemptState.COMPLETED,
            inputPending = false, completionConfirmedAtMs = 40L)
        assertEquals(AttemptState.COMPLETED, completed.state)
    }

    @Test fun aConfirmedCompletionSurvivesAnUnconfirmedOverallRun() {
        // Device case: the shift's award was confirmed, then leaving the payroll page failed. The
        // step/run is UNCONFIRMED (cleanup unproven) but the work count must NOT be erased.
        val req = request(listOf(WorkStep("w", "work:verified")))
        val doneStep = StepExecution("run", "w", 0, PlanStepType.WORK, state = StepState.UNCONFIRMED, reason = "DISMISS_UNCONFIRMED")
        val snapshot = RunSnapshot("run", req, state = RunState.UNCONFIRMED, steps = listOf(doneStep),
            counts = RunCounts(workShiftsCompleted = 1), stopReason = "DISMISS_UNCONFIRMED")
        assertEquals(1, snapshot.counts.workShiftsCompleted)
        assertEquals(RunState.UNCONFIRMED, snapshot.state)
    }
    @Test fun collectionSnapshotsAreDefensiveAndTimesAreOrdered() {
        val evidence = mutableListOf("frame")
        val step = StepExecution("run", "s", 0, PlanStepType.STUDY, evidenceIds = evidence)
        evidence.clear()
        assertEquals(listOf("frame"), step.evidenceIds)
        val items = mutableListOf(step)
        val snapshot = RunSnapshot("run", request(), steps = items)
        items.clear()
        assertEquals(1, snapshot.steps.size)
        invalid { StepExecution("run", "s", 0, PlanStepType.STUDY, startedAtMs = 20L, endedAtMs = 10L) }
        invalid { RunCounts(workShiftsCompleted = -1) }
        invalid { request().copy(displayId = -1) }
        // display 0 is the physical screen; a session must name a virtual display.
        invalid { request().copy(displayId = 0) }
    }
}
