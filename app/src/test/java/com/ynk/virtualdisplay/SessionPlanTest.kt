package com.ynk.virtualdisplay

import android.graphics.Rect
import com.ynk.virtualdisplay.automation.CourseCatalog
import com.ynk.virtualdisplay.automation.CourseEntry
import com.ynk.virtualdisplay.automation.CourseListParser
import com.ynk.virtualdisplay.automation.CourseRowLocator
import com.ynk.virtualdisplay.automation.CourseScanAccumulator
import com.ynk.virtualdisplay.automation.DailyBudgetStore
import com.ynk.virtualdisplay.automation.SessionPlan
import com.ynk.virtualdisplay.automation.TextLine
import com.ynk.virtualdisplay.automation.TuitionWorkDecision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The configurable session: plan bounds, course scanning and the work-before-study decision. */
class SessionPlanTest {

    @Test fun defaultsMatchTheHistoricalHardcodedSession() {
        val plan = SessionPlan()
        assertEquals(SessionPlan.DEFAULT_COURSE_ID, plan.courseId)
        assertEquals("蹦蹦跳跳体能课", plan.courseName)
        assertEquals(1, plan.rounds)
        assertEquals(90, plan.energyThreshold)
        assertEquals(90, plan.cleanlinessThreshold)
        assertEquals(DailyBudgetStore.HARD_STOP_MINUTES, plan.dailyBudgetMinutes)
        assertFalse(plan.workBeforeStudy)
        assertEquals(10, plan.courseDurationMinutes())
    }

    @Test fun outOfRangeValuesAreRejected() {
        assertRejected("PLAN_ROUNDS_OUT_OF_RANGE") { SessionPlan(rounds = 0) }
        assertRejected("PLAN_ROUNDS_OUT_OF_RANGE") { SessionPlan(rounds = 11) }
        assertRejected("PLAN_ENERGY_THRESHOLD_OUT_OF_RANGE") { SessionPlan(energyThreshold = 59) }
        assertRejected("PLAN_CLEANLINESS_THRESHOLD_OUT_OF_RANGE") { SessionPlan(cleanlinessThreshold = 101) }
        assertRejected("PLAN_BUDGET_OUT_OF_RANGE") { SessionPlan(dailyBudgetMinutes = DailyBudgetStore.HARD_STOP_MINUTES + 1) }
        assertRejected("PLAN_WORK_OPTION_MISSING") { SessionPlan(workBeforeStudy = true, workOptionId = null) }
    }

    @Test fun unknownCourseDurationFailsClosed() {
        val plan = SessionPlan(courseId = "study:没人测过的课")
        assertRejected("PLAN_COURSE_DURATION_UNKNOWN") { plan.courseDurationMinutes() }
    }

    /**
     * The one rule both the picker and the 托管 button read. Only the measured course may start a
     * session, because only its length can be charged against the daily budget.
     */
    @Test fun onlyAMeasuredCourseMayStartASession() {
        assertTrue(SessionPlan().hasKnownDuration)
        assertTrue(SessionPlan(courseId = SessionPlan.DEFAULT_COURSE_ID).hasKnownDuration)
        assertFalse(SessionPlan(courseId = "study:别的课").hasKnownDuration)
        assertTrue(SessionPlan.hasMeasuredDuration(SessionPlan.DEFAULT_COURSE_ID))
        assertFalse(SessionPlan.hasMeasuredDuration("study:别的课"))
    }

    /**
     * Typing "6" on the way to "65" used to be committed as 6, and the plan's own range check threw
     * out of composition. Partial input must stay uncommitted instead.
     */
    @Test fun halfTypedNumbersAreNotPlanValues() {
        val threshold = SessionPlan.THRESHOLD_RANGE
        assertNull(SessionPlan.parseIntOrNull("6", threshold))
        assertNull(SessionPlan.parseIntOrNull("10", threshold))
        assertEquals(65, SessionPlan.parseIntOrNull("65", threshold))
        assertEquals(100, SessionPlan.parseIntOrNull("100", threshold))
        assertNull(SessionPlan.parseIntOrNull("101", threshold))
        assertNull(SessionPlan.parseIntOrNull("", threshold))
        assertNull(SessionPlan.parseIntOrNull("-9", threshold))
        assertEquals(10, SessionPlan.parseIntOrNull("10", SessionPlan.ROUNDS_RANGE))
        assertNull(SessionPlan.parseIntOrNull("11", SessionPlan.ROUNDS_RANGE))
        assertEquals(480, SessionPlan.parseIntOrNull("480", SessionPlan.BUDGET_RANGE))
        assertNull(SessionPlan.parseIntOrNull("0", SessionPlan.BUDGET_RANGE))
    }

    @Test fun parserPairsANameWithItsDurationAndDropsTheRest() {
        val lines = listOf(
            line("学院", 0, 40),
            line("蹦蹦跳跳体能课", 100, 140),
            line("10分钟", 100, 140),
            line("开始上课", 900, 960),
        )
        val parsed = CourseListParser.parse(lines) { false }
        assertEquals(listOf("蹦蹦跳跳体能课"), parsed.map { it.name })
        assertEquals(10, parsed.single().durationMinutes)
        assertEquals("study:蹦蹦跳跳体能课", parsed.single().id)
        assertTrue(parsed.single().unlocked)
    }

    /**
     * Measured on a real course list: the duration line renders shorter than the name and the two
     * share only a couple of pixels, so a rule that demands a tall overlap pairs nothing.
     */
    @Test fun parserPairsADurationThatSitsBesideButBarelyOverlapsItsName() {
        val lines = listOf(line("蹦蹦跳跳体能课", 937, 963), line("用时: 10分钟", 979, 998))
        val parsed = CourseListParser.parse(lines) { false }
        assertEquals(listOf("蹦蹦跳跳体能课"), parsed.map { it.name })
        assertEquals(10, parsed.single().durationMinutes)
    }

    /** A card cut off at the screen edge reads as a fragment and must not become a catalog entry. */
    @Test fun parserDropsANameClippedAtTheScreenEdge() {
        val clipped = line("闪", 938, 963).also { it.bounds.left = 0; it.bounds.right = 30 }
        val lines = listOf(clipped, line("用时: 10分钟", 976, 999))
        assertTrue(CourseListParser.parse(lines) { false }.isEmpty())
    }

    @Test fun parserDoesNotPairADurationFromAnotherRow() {
        val lines = listOf(line("蹦蹦跳跳体能课", 100, 140), line("用时: 10分钟", 400, 440))
        assertTrue(CourseListParser.parse(lines) { false }.isEmpty())
    }

    @Test fun lockedRowsAreKeptButMarked() {
        val lines = listOf(line("高级课", 200, 240), line("15分钟", 200, 240))
        val parsed = CourseListParser.parse(lines) { it.top == 200 }
        assertEquals(1, parsed.size)
        assertFalse(parsed.single().unlocked)
    }

    @Test fun foldDedupesAndReportsWhenAScreenAddsNothing() {
        val first = entry("体能课")
        val again = CourseScanAccumulator.fold(listOf(first), listOf(first, entry("新课")))
        assertEquals(listOf("体能课", "新课"), again.courses.map { it.name })
        assertFalse(again.unchanged)
        val stagnant = CourseScanAccumulator.fold(again.courses, listOf(entry("新课")))
        assertTrue(stagnant.unchanged)
        assertEquals(again.courses, stagnant.courses)
    }

    @Test fun locateReturnsTheMatchingRowAndNothingElse() {
        val lines = listOf(line("蹦蹦跳跳体能课", 100, 140), line("别的课", 300, 340))
        assertNotNull(CourseRowLocator.locate(lines, "蹦蹦跳跳体能课"))
        assertNull(CourseRowLocator.locate(lines, "不存在的课"))
        assertFalse(CourseRowLocator.titleMatches(lines, "不存在的课"))
        assertTrue(CourseRowLocator.titleMatches(lines, "蹦蹦跳跳体能课"))
    }

    @Test fun staleCatalogCannotAuthoriseALesson() {
        val now = 10_000_000L
        val fresh = CourseCatalog(now - 1000, emptyList())
        val expired = CourseCatalog(now - SessionPlan.CATALOG_MAX_AGE_MS - 1, emptyList())
        val neverScanned = CourseCatalog(0, emptyList())
        assertTrue(fresh.isFresh(now))
        assertFalse(expired.isFresh(now))
        assertFalse(neverScanned.isFresh(now))
    }

    @Test fun workRunsOnlyWhenCoinsAreShortOrUnreadable() {
        assertFalse(TuitionWorkDecision.needsWork(22, 22))
        assertFalse(TuitionWorkDecision.needsWork(100, 22))
        assertTrue(TuitionWorkDecision.needsWork(21, 22))
        assertTrue(TuitionWorkDecision.needsWork(null, 22))
    }

    /**
     * The android.jar used for JVM tests keeps Rect's fields but its constructor ignores the
     * arguments, so a row's position has to be written onto the fields directly.
     */
    private fun line(text: String, top: Int, bottom: Int): TextLine {
        val box = Rect()
        box.top = top
        box.bottom = bottom
        box.left = 100
        box.right = 300
        return TextLine(text, box)
    }

    private fun entry(name: String) = CourseEntry("study:$name", name, 10, true, 0, 40)

    private fun assertRejected(code: String, block: () -> Unit) {
        val thrown = runCatching(block).exceptionOrNull()
        assertTrue("expected $code, got $thrown", thrown?.message?.startsWith(code) == true)
    }
}
