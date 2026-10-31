package com.ynk.virtualdisplay.automation

import android.graphics.Bitmap
import android.graphics.Rect
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
        val walker = CourseListWalker(flow)
        check(walker.rewind()) { "COURSE_SCAN_INCOMPLETE:HEAD_NOT_REACHED" }
        var collected = emptyList<CourseEntry>()
        repeat(MAX_SCREENS) {
            collected = CourseScanAccumulator.fold(collected, readScreen()).courses
            if (!walker.advance()) {
                check(collected.isNotEmpty()) { "COURSE_SCAN_INCOMPLETE:NO_COURSES_READ" }
                return@with CourseCatalog(clock(), collected)
            }
        }
        // A catalog claims to be the whole list, so a walk that never saw the end is not one.
        error("COURSE_SCAN_INCOMPLETE:END_NOT_REACHED")
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
        /** 30 moves over ~900 ms, the same shape as the `input swipe` measured to move this list. */
        const val SWIPE_STEPS = 30
        const val SWIPE_STEP_MS = 30L
        /** A shorter wait can read the list while it is still gliding and misjudge its position. */
        const val SWIPE_SETTLE_MS = 1500L
    }
}

/**
 * Moves the horizontal course list and reports whether it really moved.
 *
 * The panel opens scrolled to whichever course the game last selected, so a course can sit on
 * either side of the first screen: every walk starts by rewinding to the head.
 *
 * "Did not move" is read from the pixels of the card row, never from OCR. The recogniser returns
 * different text for the same card, and a card it failed to read would look like the list's end.
 * A frame without the course panel fails instead of comparing, because two identical frames of
 * some other page would otherwise pass for the end of the list.
 */
class CourseListWalker(private val flow: PetFlowContext) {
    suspend fun rewind(): Boolean {
        repeat(CourseScanFlow.MAX_SCREENS) { if (!move(forward = false)) return true }
        return false
    }

    /** One screen towards the tail; false once the list has nothing further to show. */
    suspend fun advance(): Boolean = move(forward = true)

    private suspend fun move(forward: Boolean): Boolean = with(flow) {
        val before = rowSample()
        safety()
        val path = CourseListGesture.swipeXs(forward).map { android.graphics.PointF(it, CourseScanFlow.SCROLL_Y.toFloat()) }
        input.drag(path, CourseScanFlow.SWIPE_STEP_MS, holdMs = 0L)
        delay(CourseScanFlow.SWIPE_SETTLE_MS)
        !CourseListGesture.sameRow(before, rowSample())
    }

    private suspend fun rowSample(): IntArray = with(flow) {
        val current = frame(latest = true)
        try {
            check(score(current, listOf(profile.courseListProbe)) >= profile.matchThreshold) { "COURSE_LIST_PANEL_LOST" }
            val sample = IntArray(CourseListGesture.SAMPLE_SIZE)
            var index = 0
            for (y in CourseListGesture.rowYs) for (x in CourseListGesture.rowXs) sample[index++] = current.getPixel(x, y)
            sample
        } finally {
            current.recycle()
        }
    }
}

/** The pure half of [CourseListWalker], so the gesture and the end test run without a device. */
object CourseListGesture {
    val rowXs = 60 until 650 step 4
    val rowYs = 770 until 1020 step 4
    val SAMPLE_SIZE = rowXs.count() * rowYs.count()
    private const val CHANNEL_TOLERANCE = 8
    private const val MAX_CHANGED_PER_MILLE = 5

    /** Evenly spaced finger positions; forward moves the cards left, towards the tail. */
    fun swipeXs(forward: Boolean): List<Float> {
        val from = (if (forward) CourseScanFlow.SCROLL_FROM_X else CourseScanFlow.SCROLL_TO_X).toFloat()
        val to = (if (forward) CourseScanFlow.SCROLL_TO_X else CourseScanFlow.SCROLL_FROM_X).toFloat()
        return (0..CourseScanFlow.SWIPE_STEPS).map { from + (to - from) * it / CourseScanFlow.SWIPE_STEPS }
    }

    /** Tolerates capture noise only; any real scroll changes far more of the row than this. */
    fun sameRow(a: IntArray, b: IntArray): Boolean {
        require(a.size == b.size) { "COURSE_ROW_SAMPLE_SIZE" }
        val changed = a.indices.count { differs(a[it], b[it]) }
        return changed * 1000 <= a.size * MAX_CHANGED_PER_MILLE
    }

    private fun differs(a: Int, b: Int) = listOf(16, 8, 0).any { shift ->
        kotlin.math.abs(((a shr shift) and 0xFF) - ((b shr shift) and 0xFF)) > CHANNEL_TOLERANCE
    }
}

/**
 * Finds the row for a named course on the list that is on screen now. Returns the point to tap,
 * or null when the course is not on this screen. Never guesses a nearby row: a miss is a miss.
 */
object CourseRowLocator {
    fun locate(lines: List<TextLine>, courseName: String): android.graphics.PointF? {
        val line = lines.firstOrNull { CourseNames.canonical(it.text) == courseName } ?: return null
        return android.graphics.PointF(
            (line.bounds.left + line.bounds.right) / 2f,
            (line.bounds.top + line.bounds.bottom) / 2f,
        )
    }

    /** True when the detail page's title is exactly the course that was requested. */
    fun titleMatches(lines: List<TextLine>, courseName: String): Boolean =
        lines.any { CourseNames.canonical(it.text) == courseName }
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
        val walker = CourseListWalker(flow)
        check(walker.rewind()) { "COURSE_LIST_HEAD_NOT_REACHED" }
        var point: android.graphics.PointF? = null
        var screen = 0
        while (point == null && screen < CourseScanFlow.MAX_SCREENS) {
            val frame = frame()
            point = try { CourseRowLocator.locate(reader.read(frame), plan.courseName) } finally { frame.recycle() }
            if (point == null && !walker.advance()) break
            screen++
        }
        val target = point ?: error("COURSE_NOT_FOUND:${plan.courseName}")
        navigation.tap(profile.courseListProbe, target, profile.courseDetailProbe, "select-${plan.courseName}")
        val detail = frame()
        val matches = try { CourseRowLocator.titleMatches(reader.read(detail), plan.courseName) } finally { detail.recycle() }
        check(matches) { "COURSE_MISMATCH:${plan.courseName}" }
    }
}
