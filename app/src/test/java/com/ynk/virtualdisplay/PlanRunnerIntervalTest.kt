package com.ynk.virtualdisplay

import com.ynk.virtualdisplay.automation.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * STR-15 的步骤间隔：第二步起必须等待「上一步结束时间 + 配置间隔」。
 * 等待量通过注入的 waitMs 记录，因此不依赖真实时间。
 */
class PlanRunnerIntervalTest {

    private fun readyPreflight() = PlanPreflight(
        courses = CourseDependencyPort { StudyPreflight.Ok(it, "课程", 30, 66) },
        resources = ResourcePreflightPort { ResourcePreflight.Ready("snap", false, false) })

    private fun request(plan: PlanV2) = RunRequest("req", plan, "dev", 4, RunOrigin.PLAN, 1L)

    private val work = WorkStep("w", "work:x")
    private val care = CareStep("c", 90, 90)

    private class RecordingStore : RunStore {
        val snapshots = linkedMapOf<String, RunSnapshot>()
        override fun save(snapshot: RunSnapshot) { snapshots[snapshot.runId] = snapshot }
        override fun load(runId: String) = snapshots[runId]
        override fun persistIntent(attempt: ActivityAttempt) = Unit
        override fun persistStartConfirmed(attemptId: AttemptId, atMs: Long) = Unit
        override fun persistCompleted(attemptId: AttemptId, atMs: Long) = Unit
        override fun persistUnconfirmed(attemptId: AttemptId, reason: String) = Unit
        override fun attempt(attemptId: AttemptId): ActivityAttempt? = null
        override fun attempts(runId: String): List<ActivityAttempt> = emptyList()
    }

    @Test
    fun noIntervalConfiguredNeverWaits() = runBlocking {
        val waits = mutableListOf<Long>()
        val plan = PlanV2(planId = "p", name = "plan", dailyBudgetMinutes = 480, steps = listOf(work, care))
        val executors = mapOf(
            PlanStepType.WORK to StepExecutor { _, _ -> StepOutcome.Completed() },
            PlanStepType.CARE to StepExecutor { _, _ -> StepOutcome.Completed() },
        )
        val outcome = PlanRunner(RecordingStore(), readyPreflight(), executors,
            nowMs = { 1_000L }, waitMs = { waits += it }).run(request(plan), "run-1")
        assertEquals(RunState.COMPLETED, outcome.state)
        assertTrue("expected no wait, got " + waits, waits.isEmpty())
    }

    @Test
    fun secondStepWaitsForTheConfiguredInterval() = runBlocking {
        val waits = mutableListOf<Long>()
        var clock = 0L
        val plan = PlanV2(planId = "p", name = "plan", dailyBudgetMinutes = 480,
            steps = listOf(work, care), stepIntervalMinutes = 10)
        val executors = mapOf(
            PlanStepType.WORK to StepExecutor { _, _ -> StepOutcome.Completed() },
            PlanStepType.CARE to StepExecutor { _, _ -> StepOutcome.Completed() },
        )
        val outcome = PlanRunner(RecordingStore(), readyPreflight(), executors,
            nowMs = { clock }, waitMs = { waits += it }).run(request(plan), "run-2")
        assertEquals(RunState.COMPLETED, outcome.state)
        assertEquals(1, waits.size)
        assertTrue("expected ~10 minutes of wait, got " + waits[0], waits[0] in 599_000L..601_000L)
    }

    @Test
    fun intervalIsNotAppliedToTheFirstStep() = runBlocking {
        val waits = mutableListOf<Long>()
        val plan = PlanV2(planId = "p", name = "plan", dailyBudgetMinutes = 480,
            steps = listOf(work), stepIntervalMinutes = 30)
        val executors = mapOf(PlanStepType.WORK to StepExecutor { _, _ -> StepOutcome.Completed() })
        val outcome = PlanRunner(RecordingStore(), readyPreflight(), executors,
            nowMs = { 0L }, waitMs = { waits += it }).run(request(plan), "run-3")
        assertEquals(RunState.COMPLETED, outcome.state)
        assertTrue(waits.isEmpty())
    }
}
