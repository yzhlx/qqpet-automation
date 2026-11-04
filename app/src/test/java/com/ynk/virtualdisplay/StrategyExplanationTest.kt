package com.ynk.virtualdisplay

import com.ynk.virtualdisplay.automation.CareStep
import com.ynk.virtualdisplay.automation.PlanStep
import com.ynk.virtualdisplay.automation.PlanStepType
import com.ynk.virtualdisplay.automation.PlanV2
import com.ynk.virtualdisplay.automation.RunCounts
import com.ynk.virtualdisplay.automation.RunOrigin
import com.ynk.virtualdisplay.automation.RunRequest
import com.ynk.virtualdisplay.automation.RunSnapshot
import com.ynk.virtualdisplay.automation.RunState
import com.ynk.virtualdisplay.automation.SkipReason
import com.ynk.virtualdisplay.automation.StepExecution
import com.ynk.virtualdisplay.automation.StepState
import com.ynk.virtualdisplay.automation.StrategyExplanation
import com.ynk.virtualdisplay.automation.StudyStep
import com.ynk.virtualdisplay.automation.WorkStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * STR-16：策略解释只说真话。
 *
 * 勾选过但没跑到的动作不能被写成完成，没勾选的动作也不能冒充完成，
 * 每个原因码都必须原样保留，交给呈现层翻译。
 */
class StrategyExplanationTest {

    private val work = WorkStep("w", "work:bamboo_runner")
    private val care = CareStep("c", 90, 90)
    private val study = StudyStep("s", "course", 1)

    private fun plan(vararg steps: PlanStep) = PlanV2(planId = "p", name = "plan",
        dailyBudgetMinutes = 480, steps = steps.toList())

    private fun request(plan: PlanV2) = RunRequest("req", plan, "dev", 4, RunOrigin.PLAN, 1L)

    private fun execution(index: Int, step: PlanStep, state: StepState, reason: String? = null) =
        StepExecution("run", step.stepId, index, step.type, state, reason, 1L, 2L)

    @Test fun everyStepTypeHasItsOwnLabel() {
        val labels = PlanStepType.values().map { StrategyExplanation.labelOf(it) }
        assertEquals(PlanStepType.values().size, labels.toSet().size)
        labels.forEach { assertTrue("label must not be blank", it.isNotBlank()) }
    }

    @Test fun completedAndSkippedStepsKeepTheirRealOutcomeAndReason() {
        val snapshot = RunSnapshot("run", request(plan(work, care)), RunState.COMPLETED,
            steps = listOf(
                execution(0, work, StepState.SKIPPED, SkipReason.FUNDS_SUFFICIENT.name),
                execution(1, care, StepState.COMPLETED)),
            counts = RunCounts(careCompleted = 1))
        val byLabel = StrategyExplanation.actionsOf(plan(work, care), snapshot).associateBy { it.label }
        assertEquals(StrategyExplanation.Outcome.SKIPPED, byLabel.getValue("打工").outcome)
        assertEquals(SkipReason.FUNDS_SUFFICIENT.name, byLabel.getValue("打工").reason)
        assertEquals(StrategyExplanation.Outcome.COMPLETED, byLabel.getValue("护理").outcome)
        assertNull(byLabel.getValue("护理").reason)
    }

    @Test fun aFailedStepLeavesLaterStepsUnexecutedInsteadOfCompleted() {
        val snapshot = RunSnapshot("run", request(plan(work, care, study)), RunState.FAILED,
            steps = listOf(
                execution(0, work, StepState.FAILED, "WORK_ENERGY_INSUFFICIENT"),
                execution(1, care, StepState.PENDING),
                execution(2, study, StepState.PENDING)),
            stopReason = "WORK_ENERGY_INSUFFICIENT")
        val actions = StrategyExplanation.actionsOf(plan(work, care, study), snapshot)
        assertEquals(StrategyExplanation.Outcome.FAILED, actions[0].outcome)
        assertEquals("WORK_ENERGY_INSUFFICIENT", actions[0].reason)
        assertEquals(StrategyExplanation.Outcome.NOT_EXECUTED, actions[1].outcome)
        assertEquals(StrategyExplanation.STOPPED_BY_PREVIOUS, actions[1].reason)
        assertEquals(StrategyExplanation.Outcome.NOT_EXECUTED, actions[2].outcome)
    }

    @Test fun stepsThePlanDidNotSelectAreNeverReportedAsDone() {
        val snapshot = RunSnapshot("run", request(plan(work)), RunState.COMPLETED,
            steps = listOf(execution(0, work, StepState.COMPLETED)))
        val actions = StrategyExplanation.actionsOf(plan(work), snapshot)
        val notSelected = actions.filter { it.outcome == StrategyExplanation.Outcome.NOT_SELECTED }
        assertEquals(PlanStepType.values().size - 1, notSelected.size)
        assertTrue(notSelected.none { it.label == "打工" })
        notSelected.forEach { assertNull(it.reason) }
    }

    @Test fun withoutAnyRunRecordEverySelectedActionIsReportedAsNotExecuted() {
        val actions = StrategyExplanation.actionsOf(plan(work, care), null)
        assertEquals(StrategyExplanation.Outcome.NOT_EXECUTED, actions[0].outcome)
        assertEquals(StrategyExplanation.NOT_STARTED, actions[0].reason)
        assertEquals(StrategyExplanation.Outcome.NOT_EXECUTED, actions[1].outcome)
        assertEquals(StrategyExplanation.NOT_STARTED, actions[1].reason)
    }

    @Test fun aCancelledStepIsNotCountedAsExecuted() {
        val snapshot = RunSnapshot("run", request(plan(work, care)), RunState.STOPPED,
            steps = listOf(
                execution(0, work, StepState.CANCELLED, "USER_STOP"),
                execution(1, care, StepState.PENDING)),
            stopReason = "USER_STOP")
        val actions = StrategyExplanation.actionsOf(plan(work, care), snapshot)
        assertEquals(StrategyExplanation.Outcome.NOT_EXECUTED, actions[0].outcome)
        assertEquals("USER_STOP", actions[0].reason)
        assertEquals(StrategyExplanation.Outcome.NOT_EXECUTED, actions[1].outcome)
    }

    @Test fun selectedActionsComeFirstInExecutionOrderAndUnselectedOnesLast() {
        val snapshot = RunSnapshot("run", request(plan(work, care)), RunState.COMPLETED,
            steps = listOf(
                execution(0, work, StepState.COMPLETED),
                execution(1, care, StepState.COMPLETED)))
        val actions = StrategyExplanation.actionsOf(plan(work, care), snapshot)
        assertEquals("打工", actions[0].label)
        assertEquals("护理", actions[1].label)
        assertTrue(actions.drop(2).all { it.outcome == StrategyExplanation.Outcome.NOT_SELECTED })
    }
}
