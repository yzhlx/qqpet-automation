package com.ynk.virtualdisplay.automation

/** Build a flow configuration exclusively from the selected immutable plan, without legacy I/O. */
object SelectedPlanConfiguration {
    fun toSessionPlan(selected:PlanV2):SessionPlan {
        require(selected.executionProfile==ExecutionProfile.SELECTED_ACTIONS) { "LEGACY_PROFILE_REQUIRES_LEGACY_ADAPTER" }
        val work=selected.steps.filterIsInstance<WorkStep>().singleOrNull()
        val care=selected.steps.filterIsInstance<CareStep>().singleOrNull()
        val study=selected.steps.filterIsInstance<StudyStep>().singleOrNull()
        return SessionPlan(
            courseId=study?.courseId ?: SessionPlan.DEFAULT_COURSE_ID,
            rounds=study?.rounds ?: 1,
            energyThreshold=care?.energyThreshold ?: SessionPlan.DEFAULT_THRESHOLD,
            cleanlinessThreshold=care?.cleanlinessThreshold ?: SessionPlan.DEFAULT_THRESHOLD,
            dailyBudgetMinutes=selected.dailyBudgetMinutes,
            workBeforeStudy=false,
            workOptionId=work?.workOptionId,
        )
    }

    /** The terminal lesson count is independent of how many other steps were selected. */
    fun studyRounds(selected:PlanV2):Int = selected.steps.filterIsInstance<StudyStep>().singleOrNull()?.rounds ?: 0
}
