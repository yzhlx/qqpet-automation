package com.ynk.virtualdisplay

import com.ynk.virtualdisplay.automation.CourseCatalog
import com.ynk.virtualdisplay.automation.CourseEntry
import com.ynk.virtualdisplay.automation.CourseTiming
import com.ynk.virtualdisplay.automation.DailyBudgetStore
import com.ynk.virtualdisplay.automation.SessionPlan
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Budget minutes for a scanned course: what a fresh, complete catalog may authorise, and what the
 * detail page must repeat back before 开始上课 may be tapped.
 */
class CourseTimingTest {

    /** The historical default still resolves through the catalog like any other course. */
    @Test fun resolveReturnsTheDefaultCoursesTenMinutes() {
        val plan = SessionPlan()
        val timing = CourseTiming.resolve(plan, catalog(entry(SessionPlan.DEFAULT_COURSE_ID, 10)), NOW)
        assertEquals(SessionPlan.DEFAULT_COURSE_ID, timing.id)
        assertEquals("蹦蹦跳跳体能课", timing.name)
        assertEquals(10, timing.durationMinutes)
    }

    /** A scanned 30-minute course is exactly the case the verified-course table cannot price. */
    @Test fun resolveReturnsANonDefaultThirtyMinuteCourse() {
        val plan = SessionPlan(courseId = "study:高级专注课")
        val timing = CourseTiming.resolve(plan, catalog(entry("study:高级专注课", 30)), NOW)
        assertEquals("高级专注课", timing.name)
        assertEquals(30, timing.durationMinutes)
    }

    @Test fun resolveRejectsAMissingIncompleteOrStaleCatalog() {
        val plan = SessionPlan(courseId = "study:高级专注课")
        val courses = listOf(entry("study:高级专注课", 30))
        assertRejected("COURSE_TIMING_CATALOG_MISSING") { CourseTiming.resolve(plan, null, NOW) }
        // A sweep that hit its screen cap saved what it read but is not the whole list, so it may
        // not price a lesson: the missing courses could include a different, real duration rule.
        assertRejected("COURSE_TIMING_CATALOG_INCOMPLETE") {
            CourseTiming.resolve(plan, CourseCatalog(NOW - 1000, courses, complete = false), NOW)
        }
        assertRejected("COURSE_TIMING_CATALOG_STALE") {
            CourseTiming.resolve(plan, CourseCatalog(NOW - SessionPlan.CATALOG_MAX_AGE_MS - 1, courses, complete = true), NOW)
        }
    }

    @Test fun resolveRejectsAnAbsentLockedOrDurationlessCourse() {
        val plan = SessionPlan(courseId = "study:高级专注课")
        assertRejected("COURSE_NOT_FOUND") {
            CourseTiming.resolve(plan, catalog(entry("study:别的课", 30)), NOW)
        }
        assertRejected("COURSE_LOCKED") {
            CourseTiming.resolve(plan, catalog(entry("study:高级专注课", 30, unlocked = false)), NOW)
        }
        // A row the recogniser read without its minutes line is a course, but not a priced one.
        assertRejected("COURSE_DURATION_MISSING") {
            CourseTiming.resolve(plan, catalog(entry("study:高级专注课", null)), NOW)
        }
    }

    @Test fun aDurationOutsideOneDaysBudgetCannotBeCharged() {
        assertRejected("COURSE_DURATION_OUT_OF_RANGE") { CourseTiming("study:零分钟课", "零分钟课", 0) }
        assertRejected("COURSE_DURATION_OUT_OF_RANGE") {
            CourseTiming("study:超长课", "超长课", DailyBudgetStore.HARD_STOP_MINUTES + 1)
        }
    }

    @Test fun detailMustRepeatTheSameNameAndMinutes() {
        val timing = CourseTiming("study:高级专注课", "高级专注课", 30)
        timing.confirmDetail("高级专注课", 30)
        // The page title is an OCR line like any other, so surrounding whitespace is not a change.
        timing.confirmDetail(" 高级专注课 ", 30)
        assertRejected("COURSE_DETAIL_NAME_MISMATCH") { timing.confirmDetail("别的课", 30) }
        assertRejected("COURSE_DETAIL_MINUTES_MISMATCH") { timing.confirmDetail("高级专注课", 10) }
    }

    private companion object {
        const val NOW = 10_000_000L
    }

    /** A catalog only counts when the sweep was complete, so this helper never fakes it. */
    private fun catalog(vararg courses: CourseEntry) = CourseCatalog(NOW - 1000, courses.toList(), complete = true)

    private fun entry(id: String, minutes: Int?, unlocked: Boolean = true) =
        CourseEntry(id = id, name = id.substringAfter(':'), durationMinutes = minutes, unlocked = unlocked, rowTop = 100, rowBottom = 140)

    private fun assertRejected(code: String, block: () -> Unit) {
        val thrown = runCatching(block).exceptionOrNull()
        assertEquals("expected $code, got $thrown", code, thrown?.message?.substringBefore(':'))
    }
}