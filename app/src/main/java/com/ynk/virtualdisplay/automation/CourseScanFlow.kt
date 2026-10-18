package com.ynk.virtualdisplay.automation

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import kotlinx.coroutines.delay

/**
 * Reads the academy course list into a [CourseCatalog]. Read only: it scrolls the list but never
 * taps a course, a button or anything else that could spend a coin.
 *
 * A row counts as locked when its text renders grey. The threshold is the measured ink colour of
 * an unlocked row; anything paler is treated as unavailable and recorded as locked, so a course
 * the pet has not unlocked can never be offered for selection.
 */
class CourseScanFlow(
    private val flow: PetFlowContext,
    private val navigation: PetNavigation,
    private val reader: CourseNameReader,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    suspend fun scan(): CourseCatalog = with(flow) {
        navigation.openAcademyCourseList()
        var collected = emptyList<CourseEntry>()
        var stagnant = 0
        repeat(MAX_SCREENS) {
            val screen = readScreen()
            val folded = CourseScanAccumulator.fold(collected, screen)
            collected = folded.courses
            if (folded.unchanged) stagnant++ else stagnant = 0
            // Two screens in a row that add nothing means the list has stopped moving, which is
            // the bottom. One is not enough: a slow scroll can land on the same rows twice.
            if (stagnant >= 2) return@with CourseCatalog(clock(), collected)
            scrollDown()
        }
        // The cap is a safety bound, not a guess about the list length. Hitting it still returns
        // everything read so far; the catalog records no claim about what lies below it.
        CourseCatalog(clock(), collected)
    }

    private suspend fun readScreen(): List<CourseEntry> = with(flow) {
        val frame = frame()
        try {
            val lines = reader.read(frame)
            // Temporary: the first real scan stored an empty catalog, so record exactly what the
            // recogniser returned until the row rules are confirmed against a real frame.
            emit("course-scan-ocr", result = lines.joinToString(" || ") { "${it.bounds.toShortString()}:${it.text}" }.take(1500))
            CourseListParser.parse(lines) { row -> lockedRow(frame, row) }
        } finally {
            frame.recycle()
        }
    }

    /** Grey ink means the row is locked. An empty sample fails closed and reports locked. */
    private fun lockedRow(frame: Bitmap, row: Rect): Boolean {
        var ink = 0
        var total = 0
        var y = row.top.coerceAtLeast(0)
        val bottom = row.bottom.coerceAtMost(frame.height)
        val right = row.right.coerceAtMost(frame.width)
        while (y < bottom) {
            var x = row.left.coerceAtLeast(0)
            while (x < right) {
                val pixel = frame.getPixel(x, y)
                val luminance = ((pixel shr 16) and 0xFF) + ((pixel shr 8) and 0xFF) + (pixel and 0xFF)
                if (luminance < INK_LUMINANCE) ink++
                total++
                x += SAMPLE_STRIDE
            }
            y += SAMPLE_STRIDE
        }
        return total == 0 || ink * 100 / total < LOCKED_INK_PERCENT
    }

    private suspend fun scrollDown() = with(flow) {
        safety()
        // The course list is a horizontal row of cards, so a vertical drag never reveals the card
        // that is cut off at the screen's right edge.
        input.drag(listOf(android.graphics.PointF(SCROLL_FROM_X.toFloat(), SCROLL_Y.toFloat()), android.graphics.PointF(SCROLL_TO_X.toFloat(), SCROLL_Y.toFloat())), SCROLL_MS)
        delay(profile.pageSettleMs)
    }

    companion object {
        /** Sum of RGB channels below which a pixel counts as text ink rather than panel background. */
        const val INK_LUMINANCE = 360
        /** A row with less ink than this, in percent, renders grey and is locked. */
        const val LOCKED_INK_PERCENT = 4
        const val SAMPLE_STRIDE = 3
        const val MAX_SCREENS = 8
        const val LIST_X = 360
        /**
         * Across the course cards, right to left, by roughly one card. The row sits just under the
         * panel title; higher than this lands on the academy map behind the panel and scrolls nothing.
         */
        const val SCROLL_Y = 950
        const val SCROLL_FROM_X = 560
        const val SCROLL_TO_X = 200
        const val SCROLL_MS = 300L
    }
}

/**
 * Finds the row for a named course on the list that is on screen now. Returns the point to tap,
 * or null when the course is not on this screen. Never guesses a nearby row: a miss is a miss.
 */
object CourseRowLocator {
    fun locate(lines: List<TextLine>, courseName: String): android.graphics.PointF? {
        val line = lines.firstOrNull { it.text.trim() == courseName } ?: return null
        return android.graphics.PointF(
            (line.bounds.left + line.bounds.right) / 2f,
            (line.bounds.top + line.bounds.bottom) / 2f,
        )
    }

    /** True when the detail page's title is exactly the course that was requested. */
    fun titleMatches(lines: List<TextLine>, courseName: String): Boolean =
        lines.any { it.text.trim() == courseName }
}

/**
 * Selects [plan]'s course from the academy list by its name, then proves the detail page that
 * opened is the one asked for. Either check failing stops the session; it never taps a different
 * row to recover.
 */
class CourseSelector(
    private val flow: PetFlowContext,
    private val navigation: PetNavigation,
    private val reader: CourseNameReader,
) {
    suspend fun select(plan: SessionPlan) = with(flow) {
        navigation.openAcademyCourseList()
        var point: android.graphics.PointF? = null
        var screen = 0
        while (point == null && screen < CourseScanFlow.MAX_SCREENS) {
            val frame = frame()
            point = try { CourseRowLocator.locate(reader.read(frame), plan.courseName) } finally { frame.recycle() }
            if (point == null) {
                safety()
                input.drag(listOf(android.graphics.PointF(CourseScanFlow.SCROLL_FROM_X.toFloat(), CourseScanFlow.SCROLL_Y.toFloat()), android.graphics.PointF(CourseScanFlow.SCROLL_TO_X.toFloat(), CourseScanFlow.SCROLL_Y.toFloat())), CourseScanFlow.SCROLL_MS)
                delay(profile.pageSettleMs)
            }
            screen++
        }
        val target = point ?: error("COURSE_NOT_FOUND:${plan.courseName}")
        navigation.tap(profile.courseListProbe, target, profile.courseDetailProbe, "select-${plan.courseName}")
        val detail = frame()
        val matches = try { CourseRowLocator.titleMatches(reader.read(detail), plan.courseName) } finally { detail.recycle() }
        check(matches) { "COURSE_MISMATCH:${plan.courseName}" }
    }
}
