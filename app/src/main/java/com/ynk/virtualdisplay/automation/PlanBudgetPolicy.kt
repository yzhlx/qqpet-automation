package com.ynk.virtualdisplay.automation

/**
 * The minutes a work shift must leave free in the daily budget.
 *
 * The shift has to fit together with the lessons that still have to be paid for: a plan that
 * works and then studies needs the shift's minutes AND the lesson's minutes to fit. A plan
 * without a study step has no lesson to protect, so only the shift's own minutes count.
 *
 * Measured 2026-10-22: a WORK+CARE plan asked the course catalog for the duration of a course
 * that was not in the plan and stopped with PLAN_COURSE_DURATION_UNKNOWN before the shift even
 * started. The rule is therefore derived from the PLAN's own steps, never from the stored
 * legacy managed settings.
 */
object PlanBudgetPolicy {
    /** Minutes the shift must reserve for the plan's own study suffix. */
    fun suffixLessonMinutes(plan: PlanV2, courseMinutes: (String) -> Int?): Int {
        val study = plan.steps.filterIsInstance<StudyStep>().singleOrNull() ?: return 0
        val minutes = courseMinutes(study.courseId)
            ?: throw IllegalStateException("PLAN_COURSE_DURATION_UNKNOWN:" + study.courseId)
        require(minutes > 0) { "PLAN_COURSE_DURATION_INVALID:" + study.courseId }
        return Math.multiplyExact(minutes, study.rounds)
    }

    /** True when the shift's own minutes plus the suffix still fit the daily allowance. */
    fun shiftFits(usedMinutes: Int, shiftMinutes: Int, suffixMinutes: Int, limitMinutes: Int): Boolean {
        require(usedMinutes >= 0 && shiftMinutes > 0 && suffixMinutes >= 0) { "PLAN_BUDGET_INPUT_INVALID" }
        require(limitMinutes in 1..MAX_DAILY_MINUTES) { "PLAN_BUDGET_LIMIT_INVALID" }
        return usedMinutes.toLong() + shiftMinutes + suffixMinutes <= limitMinutes
    }

    /** The game's own hard daily stop, in minutes. */
    const val MAX_DAILY_MINUTES = 8 * 60
}
