package com.ynk.virtualdisplay

import com.ynk.virtualdisplay.automation.*
import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.runBlocking

/** In-memory [RunStore] that also remembers the order of persisted attempts and can fail writes. */
class FakeRunStore : RunStore {
    private val snapshots = linkedMapOf<String, RunSnapshot>()
    private val attempts = linkedMapOf<String, ActivityAttempt>()
    var failIntentWrites = false
    val persistedKinds = mutableListOf<String>()

    override fun save(snapshot: RunSnapshot) { snapshots[snapshot.runId] = snapshot }
    override fun load(runId: String) = snapshots[runId]

    override fun persistIntent(attempt: ActivityAttempt) {
        if (failIntentWrites) throw IllegalStateException("DISK_WRITE_FAILED")
        attempts[attempt.attemptId.value] = attempt
        persistedKinds += "INTENT:${attempt.attemptId.value}"
    }
    override fun persistStartConfirmed(attemptId: AttemptId, atMs: Long) {
        val a = attempts.getValue(attemptId.value)
        attempts[attemptId.value] = a.copy(state = AttemptState.START_CONFIRMED,
            inputPending = true, startConfirmedAtMs = atMs)
    }
    override fun persistCompleted(attemptId: AttemptId, atMs: Long) {
        val a = attempts.getValue(attemptId.value)
        // A completed attempt must not remain input-pending, and needs a completion timestamp.
        attempts[attemptId.value] = a.copy(state = AttemptState.COMPLETED, inputPending = false,
            completionConfirmedAtMs = atMs)
    }
    override fun persistUnconfirmed(attemptId: AttemptId, reason: String) {
        val a = attempts.getValue(attemptId.value)
        attempts[attemptId.value] = a.copy(state = AttemptState.UNCONFIRMED, inputPending = true)
    }
    override fun attempt(attemptId: AttemptId) = attempts[attemptId.value]
    override fun attempts(runId: String) = attempts.values.filter { it.attemptId.runId == runId }
}

class PlanRunnerTest {

    private fun plan(vararg steps: PlanStep) = PlanV2(planId = "p", name = "plan", dailyBudgetMinutes = 480,
        steps = steps.toList())

    private fun readyPreflight() = PlanPreflight(
        courses = CourseDependencyPort { StudyPreflight.Ok(it, "课程", 30, 66) },
        resources = ResourcePreflightPort { ResourcePreflight.Ready("snap", skipConditionalWork = false, careAlreadySatisfied = false) })

    private fun noCoursePreflight() = PlanPreflight(
        courses = null,
        resources = ResourcePreflightPort { ResourcePreflight.Ready("snap", skipConditionalWork = false, careAlreadySatisfied = false) })

    private fun request(plan: PlanV2) = RunRequest("req", plan, "dev", 4, RunOrigin.PLAN, 1L)

    @Test fun sevenNonEmptySubsetsRunSelectedExecutorsInFixedOrder() = runBlocking {
        val kinds = mutableListOf<PlanStepType>()
        val executors = mapOf(
            PlanStepType.WORK to StepExecutor { _, _ -> kinds += PlanStepType.WORK; StepOutcome.Completed() },
            PlanStepType.CARE to StepExecutor { _, _ -> kinds += PlanStepType.CARE; StepOutcome.Completed() },
            PlanStepType.STUDY to StepExecutor { _, c -> kinds += PlanStepType.STUDY; c.beforeConsumingInput(1, 30); c.completed(1, 30); StepOutcome.Completed() },
        )
        // All seven non-empty subsets, always stored in WORK->CARE->STUDY order.
        val subsets = listOf(
            listOf(WorkStep("w", "work:x")),
            listOf(CareStep("c", 90, 90)),
            listOf(StudyStep("s", "course", 1)),
            listOf(WorkStep("w", "work:x"), CareStep("c", 90, 90)),
            listOf(WorkStep("w", "work:x"), StudyStep("s", "course", 1)),
            listOf(CareStep("c", 90, 90), StudyStep("s", "course", 1)),
            listOf(WorkStep("w", "work:x"), CareStep("c", 90, 90), StudyStep("s", "course", 1)),
        )
        for (subset in subsets) {
            kinds.clear()
            val runner = PlanRunner(FakeRunStore(), readyPreflight(), executors)
            val outcome = runner.run(request(plan(*subset.toTypedArray())), "run")
            assertEquals(RunState.COMPLETED, outcome.state)
            assertEquals(subset.map { it.type }, kinds) // exactly the selected types, in order
        }
    }

    @Test fun noStudyPlanNeverConsultsTheCoursePort() = runBlocking {
        var courseReads = 0
        val preflight = PlanPreflight(
            courses = CourseDependencyPort { courseReads++; StudyPreflight.Ok(it, "n", 30, 66) },
            resources = ResourcePreflightPort { ResourcePreflight.Ready("snap", false, false) },
        )
        val executors = mapOf(PlanStepType.WORK to StepExecutor { _, _ -> StepOutcome.Completed() })
        val outcome = PlanRunner(FakeRunStore(), preflight, executors)
            .run(request(plan(WorkStep("w", "work:x"))), "run")
        assertEquals(RunState.COMPLETED, outcome.state)
        assertEquals("a WORK-only plan must not read the course catalog", 0, courseReads)
    }

    @Test fun missingCourseStopsBeforeAnyWorkOrPurchase() = runBlocking {
        var workRan = false
        val preflight = PlanPreflight(
            courses = CourseDependencyPort { StudyPreflight.Rejected("COURSE_NOT_FOUND:$it") },
            resources = ResourcePreflightPort { ResourcePreflight.Ready("snap", false, false) },
        )
        val executors = mapOf(
            PlanStepType.WORK to StepExecutor { _, _ -> workRan = true; StepOutcome.Completed() },
            PlanStepType.STUDY to StepExecutor { _, _ -> StepOutcome.Completed() },
        )
        val outcome = PlanRunner(FakeRunStore(), preflight, executors)
            .run(request(plan(WorkStep("w", "work:x"), StudyStep("s", "course", 1))), "run")
        assertEquals(RunState.FAILED, outcome.state)
        assertFalse("a plan that needs a missing course must not work first", workRan)
        assertTrue(outcome.stopReason!!.startsWith("COURSE_NOT_FOUND"))
    }

    @Test fun aFailedStepLeavesLaterStepsPendingAndStops() = runBlocking {
        val store = FakeRunStore()
        val executors = mapOf(
            PlanStepType.WORK to StepExecutor { _, _ -> StepOutcome.Blocked("WORK_ENERGY_INSUFFICIENT") },
            PlanStepType.CARE to StepExecutor { _, _ -> error("CARE MUST NOT RUN") },
            PlanStepType.STUDY to StepExecutor { _, _ -> error("STUDY MUST NOT RUN") },
        )
        val outcome = PlanRunner(store, readyPreflight(), executors)
            .run(request(plan(WorkStep("w", "work:x"), CareStep("c", 90, 90), StudyStep("s", "course", 1))), "run")
        assertEquals(RunState.FAILED, outcome.state)
        assertEquals(StepState.BLOCKED, outcome.steps[0].state)
        assertEquals(StepState.PENDING, outcome.steps[1].state)
        assertEquals(StepState.PENDING, outcome.steps[2].state)
    }

    @Test fun confirmedCompletionSurvivesUnconfirmedCleanup() = runBlocking {
        // The shift's award is confirmed, then leaving the page fails: step/run UNCONFIRMED but the
        // work count must remain 1 because it is derived from the persisted COMPLETED attempt.
        val store = FakeRunStore()
        val executors = mapOf(PlanStepType.WORK to StepExecutor { _, c ->
            c.beforeConsumingInput(1, 10); c.startConfirmed(1); c.completed(1, 10)
            StepOutcome.Unconfirmed("WORK_COMPLETION_DISMISS_UNCONFIRMED")
        })
        val outcome = PlanRunner(store, readyPreflight(), executors)
            .run(request(plan(WorkStep("w", "work:x"))), "run")
        assertEquals(RunState.UNCONFIRMED, outcome.state)
        assertEquals(1, outcome.counts.workShiftsCompleted)
    }

    @Test fun intentWriteFailureSendsNoInputAndStops() = runBlocking {
        val store = FakeRunStore().apply { failIntentWrites = true }
        var reachedConsume = false
        val executors = mapOf(PlanStepType.STUDY to StepExecutor { _, c ->
            reachedConsume = true
            c.beforeConsumingInput(1, 30) // throws before any tap
            StepOutcome.Completed()
        })
        // A failed intent write must NOT escape the runner: the run ends in a persisted terminal
        // state so the attempt is durable and cannot be replayed. Nothing was sent.
        val outcome = PlanRunner(store, readyPreflight(), executors).run(request(plan(StudyStep("s", "course", 1))), "run")
        assertTrue(reachedConsume)
        assertTrue(store.attempts("run").isEmpty())
        assertEquals(RunState.UNCONFIRMED, outcome.state)
        assertEquals("DISK_WRITE_FAILED", outcome.stopReason)
        assertEquals(StepState.UNCONFIRMED, outcome.steps[0].state)
    }

    @Test fun studyRoundsAndWorkShiftCountsComeFromPersistedAttempts() = runBlocking {
        val store = FakeRunStore()
        val executors = mapOf(
            PlanStepType.STUDY to StepExecutor { step, c ->
                val rounds = (step as StudyStep).rounds
                for (r in 1..rounds) { c.beforeConsumingInput(r, 30); c.startConfirmed(r); c.completed(r, 30) }
                StepOutcome.Completed()
            },
        )
        val outcome = PlanRunner(store, readyPreflight(), executors)
            .run(request(plan(StudyStep("s", "course", 3))), "run")
        assertEquals(3, outcome.counts.studyRoundsCompleted)
    }

    @Test fun aPendingAttemptIsNotReplayedByASecondBeforeConsumingInput() {
        val store = FakeRunStore()
        val ctx = ExecutingContext(store, "run", plan(StudyStep("s", "course", 1)), StudyStep("s", "course", 1), 0) { 1L }
        ctx.beforeConsumingInput(1, 30)
        assertTrue(store.hasPendingInput("run"))
        var blocked = false
        try { ctx.beforeConsumingInput(1, 30) } catch (_: IllegalStateException) { blocked = true }
        assertTrue("a re-entered consuming input for a pending attempt must be refused", blocked)
    }
}
