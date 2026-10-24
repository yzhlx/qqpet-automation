package com.ynk.virtualdisplay

import com.ynk.virtualdisplay.automation.*
import org.junit.Assert.*
import org.junit.Test

/**
 * A work shift in a plan must fit together with the lessons the plan still has to pay for, and
 * only with THOSE lessons. Measured 2026-10-22: a WORK+CARE plan stopped with
 * PLAN_COURSE_DURATION_UNKNOWN because it asked the catalog for a course the plan did not contain.
 */
class PlanBudgetPolicyTest {
    private fun plan(steps: List<PlanStep>) = PlanV2(
        planId = "p", name = "plan", dailyBudgetMinutes = 480, steps = steps)

    @Test fun aPlanWithoutStudyProtectsNoLesson() {
        val selected = plan(listOf(WorkStep("w", "work:x", WorkTrigger.ALWAYS, 1)))
        assertEquals(0, PlanBudgetPolicy.suffixLessonMinutes(selected) { error("must not be consulted") })
    }

    @Test fun thePlanOwnStudySuffixIsCounted() {
        val selected = plan(listOf(
            WorkStep("w", "work:x", WorkTrigger.ALWAYS, 1),
            StudyStep("s", "study:田径运动课", 2),
        ))
        assertEquals(60, PlanBudgetPolicy.suffixLessonMinutes(selected) { 30 })
    }

    @Test fun anUnknownCourseDurationFailsClosed() {
        val selected = plan(listOf(StudyStep("s", "study:未知课程", 1)))
        var failed = false
        try { PlanBudgetPolicy.suffixLessonMinutes(selected) { null } }
        catch (e: IllegalStateException) { failed = e.message!!.startsWith("PLAN_COURSE_DURATION_UNKNOWN") }
        assertTrue(failed)
    }

    @Test fun theShiftFitsOnlyWhenTheSuffixAlsoFits() {
        assertTrue(PlanBudgetPolicy.shiftFits(100, 10, 30, 480))
        assertFalse(PlanBudgetPolicy.shiftFits(450, 10, 30, 480))
        assertTrue(PlanBudgetPolicy.shiftFits(450, 10, 0, 480))
    }
}
