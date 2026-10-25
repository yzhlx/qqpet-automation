package com.ynk.virtualdisplay.automation

import java.util.Collections

enum class PlanStepType { WORK, CARE, STUDY }
enum class WorkTrigger { ALWAYS, IF_FUNDS_SHORT }
enum class ExecutionProfile { SELECTED_ACTIONS, LEGACY_MANAGED }

sealed interface PlanStep {
    val stepId: String
    val type: PlanStepType
}

data class WorkStep(
    override val stepId: String,
    val workOptionId: String,
    val trigger: WorkTrigger = WorkTrigger.ALWAYS,
    val shifts: Int = 1,
) : PlanStep {
    override val type: PlanStepType get() = PlanStepType.WORK
    init {
        require(stepId.isNotBlank()) { "PLAN_STEP_ID_BLANK" }
        require(workOptionId.isNotBlank()) { "PLAN_WORK_OPTION_BLANK" }
        require(shifts == 1) { "PLAN_WORK_SHIFTS_INVALID:$shifts" }
    }
}

data class CareStep(
    override val stepId: String,
    val energyThreshold: Int,
    val cleanlinessThreshold: Int,
) : PlanStep {
    override val type: PlanStepType get() = PlanStepType.CARE
    init {
        require(stepId.isNotBlank()) { "PLAN_STEP_ID_BLANK" }
        require(energyThreshold in 60..100) { "PLAN_CARE_ENERGY_OUT_OF_RANGE:$energyThreshold" }
        require(cleanlinessThreshold in 60..100) { "PLAN_CARE_CLEANLINESS_OUT_OF_RANGE:$cleanlinessThreshold" }
    }
}

data class StudyStep(
    override val stepId: String,
    val courseId: String,
    val rounds: Int = 1,
) : PlanStep {
    override val type: PlanStepType get() = PlanStepType.STUDY
    init {
        require(stepId.isNotBlank()) { "PLAN_STEP_ID_BLANK" }
        require(courseId.isNotBlank()) { "PLAN_COURSE_BLANK" }
        require(rounds in 1..10) { "PLAN_STUDY_ROUNDS_OUT_OF_RANGE:$rounds" }
    }
}

/** Pure configuration. The list is detached and unmodifiable even if a caller supplied a MutableList. */
class PlanV2(
    val schemaVersion: Int = 2,
    val planId: String,
    val name: String,
    val dailyBudgetMinutes: Int,
    steps: List<PlanStep>,
    val executionProfile: ExecutionProfile = ExecutionProfile.SELECTED_ACTIONS,
    val legacySource: String? = null,
) {
    val steps: List<PlanStep> = Collections.unmodifiableList(steps.toList())

    init {
        require(schemaVersion == 2) { "PLAN_SCHEMA_UNSUPPORTED:$schemaVersion" }
        require(planId.isNotBlank()) { "PLAN_ID_BLANK" }
        require(name.isNotBlank() && name.length in 1..80) { "PLAN_NAME_INVALID" }
        require(dailyBudgetMinutes in 1..480) { "PLAN_BUDGET_OUT_OF_RANGE:$dailyBudgetMinutes" }
        require(this.steps.size in 1..3) { "PLAN_STEPS_COUNT_INVALID:${this.steps.size}" }
        require(this.steps.map { it.stepId }.distinct().size == this.steps.size) { "PLAN_STEP_ID_DUPLICATE" }
        require(this.steps.map { it.type }.distinct().size == this.steps.size) { "PLAN_STEP_TYPE_DUPLICATE" }
        require(this.steps.zipWithNext().all { (left, right) -> left.type.ordinal < right.type.ordinal }) {
            "PLAN_STEP_ORDER_INVALID"
        }
        this.steps.filterIsInstance<WorkStep>().forEach { work ->
            require(work.trigger != WorkTrigger.IF_FUNDS_SHORT || this.steps.any { it is StudyStep }) {
                "PLAN_CONDITIONAL_WORK_REQUIRES_STUDY"
            }
        }
    }

    fun copy(
        schemaVersion: Int = this.schemaVersion,
        planId: String = this.planId,
        name: String = this.name,
        dailyBudgetMinutes: Int = this.dailyBudgetMinutes,
        steps: List<PlanStep> = this.steps,
        executionProfile: ExecutionProfile = this.executionProfile,
        legacySource: String? = this.legacySource,
    ) = PlanV2(schemaVersion, planId, name, dailyBudgetMinutes, steps, executionProfile, legacySource)

    override fun equals(other: Any?): Boolean = other is PlanV2 &&
        schemaVersion == other.schemaVersion && planId == other.planId && name == other.name &&
        dailyBudgetMinutes == other.dailyBudgetMinutes && steps == other.steps &&
        executionProfile == other.executionProfile && legacySource == other.legacySource

    override fun hashCode(): Int {
        var result = schemaVersion
        result = 31 * result + planId.hashCode()
        result = 31 * result + name.hashCode()
        result = 31 * result + dailyBudgetMinutes
        result = 31 * result + steps.hashCode()
        result = 31 * result + executionProfile.hashCode()
        return 31 * result + (legacySource?.hashCode() ?: 0)
    }

    override fun toString(): String = "PlanV2(planId=$planId, name=$name, steps=$steps, executionProfile=$executionProfile)"
}

/** Callers supply the verified zero-coin-entry work whitelist; no Android or catalog initialization. */
object PlanValidator {
    fun validate(plan: PlanV2, verifiedWorkIds: Set<String>) {
        plan.steps.filterIsInstance<WorkStep>().forEach { work ->
            require(work.workOptionId in verifiedWorkIds) { "PLAN_WORK_OPTION_NOT_VERIFIED:${work.workOptionId}" }
        }
    }
}
