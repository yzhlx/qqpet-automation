package com.ynk.virtualdisplay.automation

import java.util.Collections

enum class RunOrigin { WORK_SHORTCUT, CARE_SHORTCUT, STUDY_SHORTCUT, PLAN, LEGACY }
enum class RunState { CREATED, PREFLIGHT, RUNNING, COMPLETED, STOPPED, FAILED, UNCONFIRMED }
enum class StepState { PENDING, RUNNING, COMPLETED, SKIPPED, BLOCKED, FAILED, UNCONFIRMED, CANCELLED }
enum class AttemptState { PREPARED, BUDGET_RESERVED, INPUT_PENDING, START_CONFIRMED, COMPLETED, UNCONFIRMED }
enum class SkipReason { FUNDS_SUFFICIENT, ALREADY_SATISFIED }

/** Length-prefixing avoids collisions when IDs themselves contain separators. */
data class AttemptId(val runId: String, val stepId: String, val iteration: Int) {
    init {
        require(runId.isNotBlank() && stepId.isNotBlank()) { "ATTEMPT_ID_BLANK" }
        require(iteration >= 1) { "ATTEMPT_ITERATION_INVALID:$iteration" }
    }
    val value: String get() = "${runId.length}:$runId${stepId.length}:$stepId:$iteration"
}

data class RunRequest(
    val requestId: String,
    val planSnapshot: PlanV2,
    val deviceSerial: String,
    val displayId: Int,
    val origin: RunOrigin,
    val createdAtMs: Long,
) {
    init {
        require(requestId.isNotBlank()) { "RUN_REQUEST_ID_BLANK" }
        require(deviceSerial.isNotBlank()) { "RUN_DEVICE_SERIAL_BLANK" }
        // Only a virtual display may carry a session; display 0 is the physical/default screen.
        require(displayId > 0) { "RUN_DISPLAY_ID_INVALID:$displayId" }
        require(createdAtMs >= 0) { "RUN_CREATED_TIME_INVALID" }
        val expected = when (origin) {
            RunOrigin.WORK_SHORTCUT -> PlanStepType.WORK
            RunOrigin.CARE_SHORTCUT -> PlanStepType.CARE
            RunOrigin.STUDY_SHORTCUT -> PlanStepType.STUDY
            else -> null
        }
        require(expected == null || (planSnapshot.steps.size == 1 && planSnapshot.steps.single().type == expected)) {
            "RUN_SHORTCUT_PLAN_MISMATCH:$origin"
        }
        require(origin != RunOrigin.WORK_SHORTCUT || (planSnapshot.steps.single() as WorkStep).trigger == WorkTrigger.ALWAYS) {
            "RUN_WORK_SHORTCUT_MUST_BE_EXPLICIT"
        }
    }
}

data class RunCounts(
    val workShiftsCompleted: Int = 0,
    val careCompleted: Int = 0,
    val studyRoundsCompleted: Int = 0,
) {
    init {
        require(workShiftsCompleted >= 0 && careCompleted >= 0 && studyRoundsCompleted >= 0) { "RUN_COUNT_NEGATIVE" }
    }
}

class StepExecution(
    val runId: String,
    val stepId: String,
    val index: Int,
    val type: PlanStepType,
    val state: StepState = StepState.PENDING,
    val reason: String? = null,
    val startedAtMs: Long? = null,
    val endedAtMs: Long? = null,
    evidenceIds: List<String> = emptyList(),
) {
    val evidenceIds: List<String> = Collections.unmodifiableList(evidenceIds.toList())
    init {
        require(runId.isNotBlank() && stepId.isNotBlank()) { "STEP_EXECUTION_ID_BLANK" }
        require(index >= 0) { "STEP_EXECUTION_INDEX_INVALID:$index" }
        validateTimes(startedAtMs, endedAtMs, "STEP")
        require(this.evidenceIds.all { it.isNotBlank() }) { "STEP_EVIDENCE_ID_BLANK" }
        if (state == StepState.SKIPPED) {
            require((type == PlanStepType.WORK && reason == SkipReason.FUNDS_SUFFICIENT.name) ||
                (type == PlanStepType.CARE && reason == SkipReason.ALREADY_SATISFIED.name)) { "STEP_SKIP_REASON_INVALID" }
        }
        if (state in setOf(StepState.BLOCKED, StepState.FAILED, StepState.UNCONFIRMED, StepState.CANCELLED)) {
            require(!reason.isNullOrBlank()) { "STEP_STOP_REASON_REQUIRED:$state" }
        }
    }

    fun copy(
        runId: String = this.runId, stepId: String = this.stepId, index: Int = this.index,
        type: PlanStepType = this.type, state: StepState = this.state, reason: String? = this.reason,
        startedAtMs: Long? = this.startedAtMs, endedAtMs: Long? = this.endedAtMs,
        evidenceIds: List<String> = this.evidenceIds,
    ) = StepExecution(runId, stepId, index, type, state, reason, startedAtMs, endedAtMs, evidenceIds)
}

data class ActivityAttempt(
    val attemptId: AttemptId,
    val durationMinutes: Int,
    val state: AttemptState = AttemptState.PREPARED,
    val inputPending: Boolean = state == AttemptState.INPUT_PENDING || state == AttemptState.UNCONFIRMED,
    val inputDispatchedAtMs: Long? = null,
    val startConfirmedAtMs: Long? = null,
    val completionConfirmedAtMs: Long? = null,
) {
    init {
        // CARE has an input intent but spends no daily game minutes; work/study ports validate >0.
        require(durationMinutes >= 0) { "ATTEMPT_DURATION_INVALID:$durationMinutes" }
        require(state !in setOf(AttemptState.INPUT_PENDING, AttemptState.UNCONFIRMED) || inputPending) {
            "ATTEMPT_UNCONFIRMED_INPUT_MUST_STAY_PENDING"
        }
        require(state != AttemptState.START_CONFIRMED || startConfirmedAtMs != null) { "ATTEMPT_START_PROOF_REQUIRED" }
        require(state != AttemptState.COMPLETED || completionConfirmedAtMs != null) { "ATTEMPT_COMPLETION_PROOF_REQUIRED" }
        require(state != AttemptState.COMPLETED || !inputPending) { "ATTEMPT_COMPLETED_MUST_NOT_BE_PENDING" }
        listOfNotNull(inputDispatchedAtMs, startConfirmedAtMs, completionConfirmedAtMs).forEach {
            require(it >= 0) { "ATTEMPT_TIME_NEGATIVE" }
        }
        validateTimes(inputDispatchedAtMs, startConfirmedAtMs, "ATTEMPT_START")
        validateTimes(startConfirmedAtMs, completionConfirmedAtMs, "ATTEMPT_COMPLETION")
    }
}

/** Full immutable snapshot; no execution record may be fabricated for an unselected action. */
class RunSnapshot(
    val runId: String,
    val request: RunRequest,
    val state: RunState = RunState.CREATED,
    steps: List<StepExecution> = request.planSnapshot.steps.mapIndexed { index, step ->
        StepExecution(runId, step.stepId, index, step.type)
    },
    val counts: RunCounts = RunCounts(),
    val startedAtMs: Long? = null,
    val endedAtMs: Long? = null,
    val stopReason: String? = null,
) {
    val steps: List<StepExecution> = Collections.unmodifiableList(steps.toList())
    init {
        require(runId.isNotBlank()) { "RUN_ID_BLANK" }
        validateTimes(startedAtMs, endedAtMs, "RUN")
        val selected = request.planSnapshot.steps
        require(this.steps.size == selected.size) { "RUN_STEP_COUNT_MISMATCH" }
        this.steps.forEachIndexed { index, step ->
            require(step.runId == runId && step.index == index && step.stepId == selected[index].stepId &&
                step.type == selected[index].type) { "RUN_STEP_IDENTITY_MISMATCH:$index" }
        }
        val failedIndex = this.steps.indexOfFirst { it.state in setOf(StepState.BLOCKED, StepState.FAILED, StepState.UNCONFIRMED, StepState.CANCELLED) }
        require(failedIndex < 0 || this.steps.drop(failedIndex + 1).all { it.state == StepState.PENDING }) {
            "RUN_EXECUTED_AFTER_STOPPED_STEP"
        }
        require(state != RunState.COMPLETED || this.steps.all { it.state == StepState.COMPLETED || it.state == StepState.SKIPPED }) {
            "RUN_COMPLETED_WITH_UNFINISHED_STEPS"
        }
        require(counts.workShiftsCompleted <= selected.filterIsInstance<WorkStep>().sumOf { it.shifts }) { "RUN_WORK_COUNT_EXCEEDED" }
        require(counts.careCompleted <= selected.filterIsInstance<CareStep>().size || request.planSnapshot.executionProfile == ExecutionProfile.LEGACY_MANAGED) { "RUN_CARE_COUNT_EXCEEDED" }
        require(counts.studyRoundsCompleted <= selected.filterIsInstance<StudyStep>().sumOf { it.rounds }) { "RUN_STUDY_COUNT_EXCEEDED" }
    }

    fun copy(
        runId: String = this.runId, request: RunRequest = this.request, state: RunState = this.state,
        steps: List<StepExecution> = this.steps, counts: RunCounts = this.counts,
        startedAtMs: Long? = this.startedAtMs, endedAtMs: Long? = this.endedAtMs, stopReason: String? = this.stopReason,
    ) = RunSnapshot(runId, request, state, steps, counts, startedAtMs, endedAtMs, stopReason)
}

private fun validateTimes(start: Long?, end: Long?, label: String) {
    require(start == null || start >= 0) { "${label}_START_TIME_NEGATIVE" }
    require(end == null || end >= 0) { "${label}_END_TIME_NEGATIVE" }
    require(start == null || end == null || end >= start) { "${label}_TIME_REVERSED" }
}
