package com.ynk.virtualdisplay.ui.data

import android.content.Intent
import com.ynk.virtualdisplay.automation.PlanV2
import com.ynk.virtualdisplay.automation.PlanV2JsonCodec
import com.ynk.virtualdisplay.automation.PetRuntime
import com.ynk.virtualdisplay.automation.StudyStep
import com.ynk.virtualdisplay.automation.PlanStepType
import com.ynk.virtualdisplay.automation.SessionMode

/**
 * The one place that decides "how many 节 does this particular start request ask for".
 *
 * The user picks a batch size once (1 / 3 / 10). Only 托管 ([SessionMode.FULL]) runs lessons, so only
 * 托管 may carry that choice. 健康检查 reads the pet a single time and 护理 repairs to the 90/90
 * threshold: neither is a batch of lessons, so both must stay on exactly one pass no matter what the
 * selector shows. Routing every entry through this object keeps the three buttons from each
 * re-inventing that rule — and, because it is plain Kotlin, it is the only part of the start path a
 * JVM unit test can observe.
 *
 * Keep [ROUND_OPTIONS] in sync with `PetSessionController`'s accepted range: `require(totalRounds in
 * 1..10)` and `PetAutomationService.runSession`'s `rounds !in 1..10` guard both accept every value
 * offered here, which is why choosing 10 needs no service-side change.
 */
object SessionStartRequest {
    const val ACTION_START = "com.ynk.virtualdisplay.pet.START"
    const val EXTRA_PLAN_JSON = "plan_json"
    const val EXTRA_REQUEST_ID = "request_id"
    const val EXTRA_DISPLAY_ID = "display_id"

    /** A plan always carries an explicit virtual-display target, never default display 0. */
    fun forPlan(plan: PlanV2, displayId: Int, requestId: String): Intent {
        require(displayId > 0) { "PLAN_DISPLAY_NOT_READY" }
        require(requestId.isNotBlank()) { "PLAN_REQUEST_ID_BLANK" }
        val study = plan.steps.filterIsInstance<StudyStep>().singleOrNull()
        val mode = if (plan.steps.size == 1) when (plan.steps.single().type) {
            PlanStepType.WORK -> SessionMode.WORK
            PlanStepType.CARE -> SessionMode.CARE
            PlanStepType.STUDY -> SessionMode.STUDY
        } else SessionMode.FULL
        return Intent(ACTION_START)
            .setClassName("com.ynk.virtualdisplay", "com.ynk.virtualdisplay.automation.PetAutomationService")
            .putExtra(EXTRA_DISPLAY_ID, displayId)
            .putExtra(EXTRA_PLAN_JSON, PlanV2JsonCodec.encode(plan))
            .putExtra(EXTRA_REQUEST_ID, requestId)
            .putExtra("rounds", study?.rounds ?: DEFAULT_ROUNDS)
            .putExtra("session_mode", mode.wire)
    }

    fun forPlan(plan: PlanV2, requestId: String): Intent {
        val displayId = PetRuntime.state.value.displayId
        check(displayId != null && displayId > 0) { "PLAN_DISPLAY_NOT_READY" }
        return forPlan(plan, displayId, requestId)
    }

    /**
     * Batch sizes offered in the UI. 1 is the default so a careless tap never multiplies the cost.
     *
     * Was listOf(1, 3, 10): the plan sheet already allowed 1..10, so choosing 2, 4, 5... silently
     * became 1 through [sanitize]. Every value the plan can hold is offered here now, and the
     * service/controller both accept 1..10, so nothing is quietly rewritten.
     */
    val ROUND_OPTIONS: List<Int> = (1..10).toList()

    /** 一节: what a session asks for unless the user explicitly chose a longer batch. */
    const val DEFAULT_ROUNDS: Int = 1

    /**
     * The rounds to send for [mode] when the selector currently shows [selected].
     *
     * Returns [DEFAULT_ROUNDS] for every mode that does not run lessons, so selecting 10 and then
     * tapping 健康检查 or 护理 can never turn a read/repair pass into a ten-lesson batch.
     */
    fun plannedRounds(mode: SessionMode, selected: Int): Int =
        if (mode.runsStudy) selected else DEFAULT_ROUNDS

    /**
     * Clamps arbitrary UI state onto the accepted range 1..10.
     *
     * The accepted range is the plan's own range, so 2 and 4 are valid requests rather than being
     * replaced by 1. Anything outside it (a stale UI value, a corrupted store) still falls back to
     * one lesson instead of being sent blindly.
     */
    fun sanitize(selected: Int): Int = if (selected in ROUND_OPTIONS) selected else DEFAULT_ROUNDS
}
