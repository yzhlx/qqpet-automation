package com.ynk.virtualdisplay

import com.ynk.virtualdisplay.automation.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Composition rules over the seven non-empty subsets (spec 002, T012 / 001 T029).
 *
 * [PlanRunnerTest] already proves the fixed order and stop-on-failure for one shape; this file pins
 * the rules the acceptance matrix reads back from a persisted run:
 *  - an UNSELECTED step type is never executed (zero executor calls), for every subset;
 *  - a Blocked/Failed/Unconfirmed/Cancelled step leaves every LATER step PENDING;
 *  - only COMPLETED or SKIPPED steps let the run reach COMPLETED;
 *  - the only legal skips are FUNDS_SUFFICIENT (WORK) and ALREADY_SATISFIED (CARE); STUDY has none;
 *  - counts come from persisted attempts, never from the step's reported outcome.
 */
class PlanRunnerCompositionTest {

    private fun plan(vararg steps: PlanStep) = PlanV2(planId = "p", name = "plan",
        dailyBudgetMinutes = 480, steps = steps.toList())

    private fun readyPreflight() = PlanPreflight(
        courses = CourseDependencyPort { StudyPreflight.Ok(it, "课程", 30, 66) },
        resources = ResourcePreflightPort { ResourcePreflight.Ready("snap", false, false) })

    private fun request(plan: PlanV2) = RunRequest("req", plan, "dev", 4, RunOrigin.PLAN, 1L)

    private val work = WorkStep("w", "work:x")
    private val care = CareStep("c", 90, 90)
    private val study = StudyStep("s", "course", 1)

    /** Records every executor call, including types the plan did NOT select. */
    private class CallLog {
        val calls = linkedMapOf<PlanStepType, Int>()
        fun hit(type: PlanStepType) { calls[type] = (calls[type] ?: 0) + 1 }
        fun count(type: PlanStepType) = calls[type] ?: 0
    }

    private fun completingExecutors(log: CallLog): Map<PlanStepType, StepExecutor> = mapOf(
        PlanStepType.WORK to StepExecutor { _, _ -> log.hit(PlanStepType.WORK); StepOutcome.Completed() },
        PlanStepType.CARE to StepExecutor { _, _ -> log.hit(PlanStepType.CARE); StepOutcome.Completed() },
        PlanStepType.STUDY to StepExecutor { _, c ->
            log.hit(PlanStepType.STUDY); c.beforeConsumingInput(1, 30); c.startConfirmed(1); c.completed(1, 30)
            StepOutcome.Completed()
        },
    )

    @Test fun unselectedStepTypesAreNeverExecutedForAnySubset() = runBlocking {
        val subsets = listOf(
            listOf(work), listOf(care), listOf(study),
            listOf(work, care), listOf(work, study), listOf(care, study),
            listOf(work, care, study),
        )
        for (subset in subsets) {
            val log = CallLog()
            val outcome = PlanRunner(FakeRunStore(), readyPreflight(), completingExecutors(log))
                .run(request(plan(*subset.toTypedArray())), "run-${subset.size}-${log.calls.size}")
            assertEquals(RunState.COMPLETED, outcome.state)
            for (type in PlanStepType.entries) {
                val selected = subset.any { it.type == type }
                assertEquals("type $type selected=$selected", if (selected) 1 else 0, log.count(type))
            }
        }
    }

    @Test fun eachTerminalOutcomeLeavesLaterStepsPending() = runBlocking {
        val terminals = listOf<StepOutcome>(
            StepOutcome.Blocked("WORK_ENERGY_INSUFFICIENT"),
            StepOutcome.Failed("WORK_PAGE_MISMATCH"),
            StepOutcome.Unconfirmed("WORK_REWARD_UNREAD"),
        )
        for (terminal in terminals) {
            val store = FakeRunStore()
            val executors = mapOf(
                PlanStepType.WORK to StepExecutor { _, _ -> terminal },
                PlanStepType.CARE to StepExecutor { _, _ -> error("CARE MUST NOT RUN") },
                PlanStepType.STUDY to StepExecutor { _, _ -> error("STUDY MUST NOT RUN") },
            )
            val outcome = PlanRunner(store, readyPreflight(), executors)
                .run(request(plan(work, care, study)), "run-${terminal::class.simpleName}")
            assertEquals(StepState.PENDING, outcome.steps[1].state)
            assertEquals(StepState.PENDING, outcome.steps[2].state)
            assertTrue(outcome.state in setOf(RunState.FAILED, RunState.UNCONFIRMED))
        }
    }

    @Test fun aCancelledStepStopsTheRunAndLeavesLaterStepsPending() {
        val store = FakeRunStore()
        val executors = mapOf(
            PlanStepType.WORK to StepExecutor { _, _ -> throw kotlinx.coroutines.CancellationException("USER_STOP") },
            PlanStepType.CARE to StepExecutor { _, _ -> error("CARE MUST NOT RUN") },
            PlanStepType.STUDY to StepExecutor { _, _ -> error("STUDY MUST NOT RUN") },
        )
        var thrown: Throwable? = null
        try {
            runBlocking { PlanRunner(store, readyPreflight(), executors).run(request(plan(work, care, study)), "run-cancel") }
        } catch (t: Throwable) { thrown = t }
        assertTrue("cancellation must propagate", thrown is kotlinx.coroutines.CancellationException)
        val persisted = checkNotNull(store.load("run-cancel"))
        assertEquals(RunState.STOPPED, persisted.state)
        assertEquals(StepState.CANCELLED, persisted.steps[0].state)
        assertEquals(StepState.PENDING, persisted.steps[1].state)
        assertEquals(StepState.PENDING, persisted.steps[2].state)
    }

    @Test fun onlyCompletedOrSkippedStepsLetTheRunComplete() = runBlocking {
        // WORK legally skipped, CARE completed, STUDY completed -> COMPLETED with work count 0.
        val store = FakeRunStore()
        val executors = mapOf(
            PlanStepType.WORK to StepExecutor { _, _ -> StepOutcome.Skipped(SkipReason.FUNDS_SUFFICIENT) },
            PlanStepType.CARE to StepExecutor { _, c ->
                c.beforeConsumingInput(1, 0); c.completed(1, 0); StepOutcome.Completed()
            },
            PlanStepType.STUDY to StepExecutor { _, c ->
                c.beforeConsumingInput(1, 30); c.startConfirmed(1); c.completed(1, 30); StepOutcome.Completed()
            },
        )
        val outcome = PlanRunner(store, readyPreflight(), executors).run(request(plan(work, care, study)), "run-skip")
        assertEquals(RunState.COMPLETED, outcome.state)
        assertEquals(StepState.SKIPPED, outcome.steps[0].state)
        assertEquals(SkipReason.FUNDS_SUFFICIENT.name, outcome.steps[0].reason)
        assertEquals(0, outcome.counts.workShiftsCompleted)
        assertEquals(1, outcome.counts.careCompleted)
        assertEquals(1, outcome.counts.studyRoundsCompleted)
    }

    @Test fun anIllegalSkipReasonIsRejectedByTheSnapshotInvariant() {
        // STUDY has no legal skip: reporting Skipped must fail the RunSnapshot construction, not
        // silently produce a COMPLETED run with a skipped lesson.
        val executors = mapOf(PlanStepType.STUDY to StepExecutor { _, _ -> StepOutcome.Skipped(SkipReason.FUNDS_SUFFICIENT) })
        var thrown: Throwable? = null
        try {
            runBlocking { PlanRunner(FakeRunStore(), readyPreflight(), executors).run(request(plan(study)), "run-badskip") }
        } catch (t: Throwable) { thrown = t }
        assertTrue("a skipped STUDY step must be refused, got ${thrown?.javaClass?.simpleName}",
            thrown is IllegalArgumentException)
    }

    @Test fun aWorkSkipWithTheCareReasonIsRejected() {
        val executors = mapOf(PlanStepType.WORK to StepExecutor { _, _ -> StepOutcome.Skipped(SkipReason.ALREADY_SATISFIED) })
        var thrown: Throwable? = null
        try {
            runBlocking { PlanRunner(FakeRunStore(), readyPreflight(), executors).run(request(plan(work)), "run-badskip2") }
        } catch (t: Throwable) { thrown = t }
        assertTrue(thrown is IllegalArgumentException)
    }

    @Test fun countsComeFromPersistedAttemptsNotFromReportedOutcomes() = runBlocking {
        // Both executors CLAIM Completed but never record a completion proof: counts stay zero.
        val store = FakeRunStore()
        val executors = mapOf(
            PlanStepType.WORK to StepExecutor { _, _ -> StepOutcome.Completed() },
            PlanStepType.CARE to StepExecutor { _, _ -> StepOutcome.Completed() },
        )
        val outcome = PlanRunner(store, readyPreflight(), executors).run(request(plan(work, care)), "run-nocount")
        assertEquals(RunState.COMPLETED, outcome.state)
        assertEquals(0, outcome.counts.workShiftsCompleted)
        assertEquals(0, outcome.counts.careCompleted)
        assertTrue(store.attempts("run-nocount").isEmpty())
    }

    @Test fun aCompletedPrefixSurvivesAnUnconfirmedLaterStep() = runBlocking {
        val store = FakeRunStore()
        val executors = mapOf(
            PlanStepType.WORK to StepExecutor { _, c ->
                c.beforeConsumingInput(1, 10); c.startConfirmed(1); c.completed(1, 10); StepOutcome.Completed()
            },
            PlanStepType.CARE to StepExecutor { _, c ->
                c.beforeConsumingInput(1, 0); StepOutcome.Unconfirmed("CARE_COMPLETION_UNCONFIRMED")
            },
            PlanStepType.STUDY to StepExecutor { _, _ -> error("STUDY MUST NOT RUN") },
        )
        val outcome = PlanRunner(store, readyPreflight(), executors).run(request(plan(work, care, study)), "run-mixed")
        assertEquals(RunState.UNCONFIRMED, outcome.state)
        assertEquals(StepState.COMPLETED, outcome.steps[0].state)
        assertEquals(StepState.UNCONFIRMED, outcome.steps[1].state)
        assertEquals(StepState.PENDING, outcome.steps[2].state)
        assertEquals(1, outcome.counts.workShiftsCompleted)
        assertEquals(0, outcome.counts.careCompleted)
        // The confirmed shift stays COMPLETED and non-pending. The unconfirmed care attempt was
        // reserved but never completed, so it stays INPUT_PENDING: that is the never-replay signal
        // (a returned Unconfirmed leaves the pending intent in place; only a throw auto-confirms it).
        val workAttempt = store.attempts("run-mixed").single { it.attemptId.stepId == "w" }
        val careAttempt = store.attempts("run-mixed").single { it.attemptId.stepId == "c" }
        assertEquals(AttemptState.COMPLETED, workAttempt.state)
        assertFalse(workAttempt.inputPending)
        assertEquals(AttemptState.INPUT_PENDING, careAttempt.state)
        assertTrue(careAttempt.inputPending)
        assertTrue(store.hasPendingInput("run-mixed"))
    }
}
