package com.ynk.virtualdisplay

import com.ynk.virtualdisplay.automation.CareStep
import com.ynk.virtualdisplay.automation.CourseDependencyPort
import com.ynk.virtualdisplay.automation.PlanPreflight
import com.ynk.virtualdisplay.automation.PlanRunner
import com.ynk.virtualdisplay.automation.PlanStep
import com.ynk.virtualdisplay.automation.PlanStepType
import com.ynk.virtualdisplay.automation.PlanV2
import com.ynk.virtualdisplay.automation.ResourcePreflight
import com.ynk.virtualdisplay.automation.ResourcePreflightPort
import com.ynk.virtualdisplay.automation.RunOrigin
import com.ynk.virtualdisplay.automation.RunRequest
import com.ynk.virtualdisplay.automation.RunState
import com.ynk.virtualdisplay.automation.StepExecutor
import com.ynk.virtualdisplay.automation.StepOutcome
import com.ynk.virtualdisplay.automation.StepState
import com.ynk.virtualdisplay.automation.StudyPreflight
import com.ynk.virtualdisplay.automation.StudyStep
import com.ynk.virtualdisplay.automation.WorkStep
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * STR-01/STR-02：优先动作走不通时按规则转移，而不是让整轮停下。
 *
 * 失败的事实必须留着：受阻那一步仍然是 BLOCKED，只是后面的动作照常执行了。
 */
class PlanRunnerTransferTest {

    private fun plan(transfer: Boolean, vararg steps: PlanStep) = PlanV2(planId = "p", name = "plan",
        dailyBudgetMinutes = 480, steps = steps.toList(), transferOnBlock = transfer)

    private fun readyPreflight() = PlanPreflight(
        courses = CourseDependencyPort { StudyPreflight.Ok(it, "课程", 30, 66) },
        resources = ResourcePreflightPort { ResourcePreflight.Ready("snap", false, false) })

    private fun request(plan: PlanV2) = RunRequest("req", plan, "dev", 4, RunOrigin.PLAN, 1L)

    @Test fun aBlockedFirstActionTransfersAndTheLaterActionsStillRun() = runBlocking {
        val kinds = mutableListOf<PlanStepType>()
        val executors = mapOf(
            PlanStepType.WORK to StepExecutor { _, _ -> kinds += PlanStepType.WORK; StepOutcome.Blocked("WORK_ENERGY_INSUFFICIENT") },
            PlanStepType.CARE to StepExecutor { _, _ -> kinds += PlanStepType.CARE; StepOutcome.Completed() },
            PlanStepType.STUDY to StepExecutor { _, _ -> kinds += PlanStepType.STUDY; StepOutcome.Completed() })
        val outcome = PlanRunner(FakeRunStore(), readyPreflight(), executors)
            .run(request(plan(true, WorkStep("w", "work:x"), CareStep("c", 90, 90), StudyStep("s", "course", 1))), "run")
        assertEquals(listOf(PlanStepType.WORK, PlanStepType.CARE, PlanStepType.STUDY), kinds)
        assertEquals(StepState.BLOCKED, outcome.steps[0].state)
        assertEquals("WORK_ENERGY_INSUFFICIENT", outcome.steps[0].reason)
        assertEquals(StepState.COMPLETED, outcome.steps[1].state)
        assertEquals(StepState.COMPLETED, outcome.steps[2].state)
        assertEquals(1, outcome.transfers.size)
        assertEquals("w", outcome.transfers[0].stoppedStepId)
        assertEquals("c", outcome.transfers[0].resumedStepId)
        assertEquals("WORK_ENERGY_INSUFFICIENT", outcome.transfers[0].stoppedReason)
        // 发生过转移的轮次不算干净成功：第一个动作确实没有做成。
        assertEquals(RunState.FAILED, outcome.state)
    }

    @Test fun withoutTheOptionTheSameBlockedActionStopsTheRound() = runBlocking {
        val kinds = mutableListOf<PlanStepType>()
        val executors = mapOf(
            PlanStepType.WORK to StepExecutor { _, _ -> kinds += PlanStepType.WORK; StepOutcome.Blocked("WORK_ENERGY_INSUFFICIENT") },
            PlanStepType.CARE to StepExecutor { _, _ -> kinds += PlanStepType.CARE; StepOutcome.Completed() })
        val outcome = PlanRunner(FakeRunStore(), readyPreflight(), executors)
            .run(request(plan(false, WorkStep("w", "work:x"), CareStep("c", 90, 90))), "run")
        assertEquals(listOf(PlanStepType.WORK), kinds)
        assertEquals(StepState.PENDING, outcome.steps[1].state)
        assertTrue(outcome.transfers.isEmpty())
        assertEquals(RunState.FAILED, outcome.state)
    }

    @Test fun anUnconfirmedOutcomeNeverTransfers() = runBlocking {
        val kinds = mutableListOf<PlanStepType>()
        val executors = mapOf(
            PlanStepType.WORK to StepExecutor { _, _ -> kinds += PlanStepType.WORK; StepOutcome.Unconfirmed("WORK_COMPLETION_UNCONFIRMED") },
            PlanStepType.CARE to StepExecutor { _, _ -> kinds += PlanStepType.CARE; StepOutcome.Completed() })
        val outcome = PlanRunner(FakeRunStore(), readyPreflight(), executors)
            .run(request(plan(true, WorkStep("w", "work:x"), CareStep("c", 90, 90))), "run")
        assertEquals(listOf(PlanStepType.WORK), kinds)
        assertEquals(StepState.PENDING, outcome.steps[1].state)
        assertTrue(outcome.transfers.isEmpty())
        assertEquals(RunState.UNCONFIRMED, outcome.state)
    }

    @Test fun aSecondBlockedActionStopsTheRound() = runBlocking {
        val kinds = mutableListOf<PlanStepType>()
        val executors = mapOf(
            PlanStepType.WORK to StepExecutor { _, _ -> kinds += PlanStepType.WORK; StepOutcome.Blocked("WORK_ENERGY_INSUFFICIENT") },
            PlanStepType.CARE to StepExecutor { _, _ -> kinds += PlanStepType.CARE; StepOutcome.Blocked("CARE_ITEMS_MISSING") },
            PlanStepType.STUDY to StepExecutor { _, _ -> kinds += PlanStepType.STUDY; StepOutcome.Completed() })
        val outcome = PlanRunner(FakeRunStore(), readyPreflight(), executors)
            .run(request(plan(true, WorkStep("w", "work:x"), CareStep("c", 90, 90), StudyStep("s", "course", 1))), "run")
        assertEquals(listOf(PlanStepType.WORK, PlanStepType.CARE), kinds)
        assertEquals(StepState.PENDING, outcome.steps[2].state)
        assertEquals(1, outcome.transfers.size)
        assertEquals(RunState.FAILED, outcome.state)
    }

    @Test fun aBlockedLastActionHasNothingToMoveTo() = runBlocking {
        val executors = mapOf(
            PlanStepType.WORK to StepExecutor { _, _ -> StepOutcome.Blocked("WORK_ENERGY_INSUFFICIENT") })
        val outcome = PlanRunner(FakeRunStore(), readyPreflight(), executors)
            .run(request(plan(true, WorkStep("w", "work:x"))), "run")
        assertTrue(outcome.transfers.isEmpty())
        assertEquals(RunState.FAILED, outcome.state)
    }

    @Test fun aCleanRoundWithoutAnyFailureIsStillASuccess() = runBlocking {
        val executors = mapOf(
            PlanStepType.CARE to StepExecutor { _, _ -> StepOutcome.Completed() },
            PlanStepType.STUDY to StepExecutor { _, _ -> StepOutcome.Completed() })
        val outcome = PlanRunner(FakeRunStore(), readyPreflight(), executors)
            .run(request(plan(true, CareStep("c", 90, 90), StudyStep("s", "course", 1))), "run")
        assertEquals(RunState.COMPLETED, outcome.state)
        assertTrue(outcome.transfers.isEmpty())
    }
}
