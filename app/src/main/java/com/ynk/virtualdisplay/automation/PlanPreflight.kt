package com.ynk.virtualdisplay.automation

/**
 * What must be true BEFORE any consuming input of a plan, checked only for selected dependencies.
 *
 * The rule that keeps standalone 打工 and 护理 honest: a plan without a STUDY step must NOT read the
 * course catalog at all. A missing course stops a plan that DOES study BEFORE it works or buys
 * anything, so a broken course can never cost coins first.
 */
class PlanPreflight(
    private val courses: CourseDependencyPort?,
    private val resources: ResourcePreflightPort,
) {
    sealed interface Result {
        data class Ready(
            val course: StudyPreflight.Ok?,
            val resource: ResourcePreflight.Ready,
        ) : Result
        data class Blocked(val reason: String) : Result
        data class NeedsObservation(val missing: List<String>) : Result
    }

    suspend fun check(plan: PlanV2): Result {
        val study = plan.steps.filterIsInstance<StudyStep>().singleOrNull()
        val course: StudyPreflight.Ok? = if (study != null) {
            val port = courses ?: return Result.Blocked("STUDY_COURSE_PORT_MISSING")
            when (val pre = port.preflight(study.courseId)) {
                is StudyPreflight.Ok -> pre
                is StudyPreflight.Rejected -> return Result.Blocked(pre.reason)
            }
        } else null // no STUDY: the course dependency is never consulted.

        return when (val res = resources.observe(plan)) {
            is ResourcePreflight.Ready -> Result.Ready(course, res)
            is ResourcePreflight.NeedsObservation -> Result.NeedsObservation(res.missing)
            is ResourcePreflight.Insufficient -> Result.Blocked(res.reason)
        }
    }
}
