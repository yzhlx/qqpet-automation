package com.ynk.virtualdisplay.automation

import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.Rect
import kotlinx.coroutines.delay

/** Reads from the left boundary to the right boundary; a bounded partial scan is never saved. */
class CourseScanFlow(
    private val flow: PetFlowContext,
    private val navigation: PetNavigation,
    private val reader: CourseNameReader,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    suspend fun scan(): CourseCatalog {
        navigation.openAcademyCourseList()
        val browser = CourseStripBrowser(flow, reader)
        var page = browser.rewind()
        var collected = emptyList<CourseEntry>()
        var still = 0
        repeat(MAX_SCREENS) { index ->
            collected = CourseScanAccumulator.fold(collected, page.courses).courses
            val next = browser.move(forward = true)
            still = if (CourseStripMotion.same(page.pixels, next.pixels)) still + 1 else 0
            flow.emit("course-scan-page", result = "page=$index courses=${page.courses.joinToString { it.name }} endSamples=$still")
            if (still >= CourseStripMotion.BOUNDARY_SAMPLES) {
                check(!next.clippedRight) { "COURSE_SCAN_STALLED:RIGHT_CARD_CLIPPED" }
                collected = CourseScanAccumulator.fold(collected, next.courses).courses
                check(collected.isNotEmpty()) { "COURSE_SCAN_EMPTY" }
                return CourseCatalog(clock(), collected, complete = true)
            }
            page = next
        }
        error("COURSE_SCAN_INCOMPLETE:RIGHT_BOUNDARY_NOT_REACHED")
    }

    companion object {
        const val MAX_SCREENS = 32
        const val SCROLL_Y = 950
        const val SCROLL_FROM_X = 520
        const val SCROLL_TO_X = 240
        const val SCROLL_MS = 400L

        /** MOVE events in one strip fling. Enough steps to read as a drag, not one long jump. */
        const val FLING_STEPS = 6
    }
}

/** Compares the card strip itself, excluding the animated map and balance/countdown text. */
object CourseStripMotion {
    const val BOUNDARY_SAMPLES = 3
    fun same(before: IntArray, after: IntArray): Boolean =
        before.isNotEmpty() && before.size == after.size &&
            before.indices.sumOf { kotlin.math.abs(before[it] - after[it]).toLong() } <= before.size * 2L
}

internal data class CourseStripPage(
    val courses: List<CourseEntry>, val pixels: IntArray,
    val clippedRight: Boolean, val clippedLeft: Boolean,
)

/** Shared scan/select traversal. Both start at the left boundary and see only complete cards. */
internal class CourseStripBrowser(private val flow: PetFlowContext, private val reader: CourseNameReader) {
    private var dragY = CourseScanFlow.SCROLL_Y

    suspend fun rewind(): CourseStripPage {
        var page = read()
        var still = 0
        repeat(CourseScanFlow.MAX_SCREENS) {
            val next = move(forward = false)
            still = if (CourseStripMotion.same(page.pixels, next.pixels)) still + 1 else 0
            if (still >= CourseStripMotion.BOUNDARY_SAMPLES) {
                check(!next.clippedLeft) { "COURSE_SCAN_STALLED:LEFT_CARD_CLIPPED" }
                return next
            }
            page = next
        }
        error("COURSE_SCAN_INCOMPLETE:LEFT_BOUNDARY_NOT_REACHED")
    }

    suspend fun move(forward: Boolean): CourseStripPage = with(flow) {
        verify(listOf(profile.courseListProbe), "course-strip-before", saveEvidence = false)
        safety()
        val from = if (forward) CourseScanFlow.SCROLL_FROM_X else CourseScanFlow.SCROLL_TO_X
        val to = if (forward) CourseScanFlow.SCROLL_TO_X else CourseScanFlow.SCROLL_FROM_X
        // The strip only moves on a real fling. The two-point gesture (one long MOVE) leaves it
        // pinned: measured on device, the card row ignored DOWN + one 280px MOVE + UP, while the
        // same drag as interpolated MOVE events scrolled it. Each intermediate point must stay
        // inside the strip's measured band so the gesture never leaves the cards.
        val points = ArrayList<PointF>()
        for (i in 0..CourseScanFlow.FLING_STEPS) {
            val t = i.toFloat() / CourseScanFlow.FLING_STEPS
            points += PointF(from + (to - from) * t, dragY.toFloat())
        }
        input.drag(points, CourseScanFlow.SCROLL_MS / CourseScanFlow.FLING_STEPS)
        delay(profile.pageSettleMs)
        read()
    }

    suspend fun read(): CourseStripPage = with(flow) {
        val frame = frame()
        try {
            check(score(frame, listOf(profile.courseListProbe)) >= profile.matchThreshold) { "COURSE_LIST_UNCONFIRMED" }
            val lines = reader.read(frame)
            val courses = CourseCardReader.read(frame, lines, reader, { lockedRow(frame, it) }) { linesRead ->
                emit("course-card-ocr", result = "centre lines=$linesRead")
            }
            check(courses.isNotEmpty()) { "COURSE_SCAN_PAGE_UNREADABLE" }
            dragY = courses.map { (it.rowTop + it.rowBottom) / 2 }.sorted().let { it[it.size / 2] }
            save(frame, "course-strip-${serial++}")
            val nameTop = courses.minOf { it.rowTop } - 15
            val nameBottom = courses.maxOf { it.rowBottom } + 15
            val clippedRight = lines.any { line ->
                line.bounds.top in nameTop..nameBottom && line.bounds.left > frame.width * 0.70 &&
                    line.bounds.left < frame.width * 0.915 &&
                    !CourseListParser.durationPattern.containsMatchIn(line.text) &&
                    (line.bounds.left + line.bounds.right) / 2.0 + frame.width * 124.0 / 720 > frame.width * 672.0 / 720
            }
            val clippedLeft = lines.any { line ->
                line.bounds.top in nameTop..nameBottom && line.bounds.right > frame.width * 0.085 &&
                    line.bounds.right < frame.width * 0.30 &&
                    !CourseListParser.durationPattern.containsMatchIn(line.text) &&
                    (line.bounds.left + line.bounds.right) / 2.0 - frame.width * 124.0 / 720 < frame.width * 48.0 / 720
            }
            CourseStripPage(courses, signature(frame), clippedRight, clippedLeft)
        } finally { frame.recycle() }
    }

    private fun signature(frame: Bitmap): IntArray {
        val values = ArrayList<Int>()
        for (y in frame.height * 47 / 100 until frame.height * 64 / 100 step 8) {
            for (x in frame.width * 7 / 100 until frame.width * 93 / 100 step 8) {
                val pixel = frame.getPixel(x, y)
                values += (pixel shr 16) and 255
                values += (pixel shr 8) and 255
                values += pixel and 255
            }
        }
        return values.toIntArray()
    }

    /** Grey text is unavailable; unknown or empty ink samples never authorise selection. */
    private fun lockedRow(frame: Bitmap, row: Rect): Boolean {
        var ink = 0
        var total = 0
        for (y in row.top.coerceAtLeast(0) until row.bottom.coerceAtMost(frame.height) step 2) {
            for (x in row.left.coerceAtLeast(0) until row.right.coerceAtMost(frame.width) step 2) {
                val pixel = frame.getPixel(x, y)
                val luminance = ((pixel shr 16) and 255) + ((pixel shr 8) and 255) + (pixel and 255)
                if (luminance < 360) ink++
                total++
            }
        }
        return total == 0 || ink * 100 / total < 4
    }
}

/** Calibrated card geometry; the slot inventory is independent of OCR duration count. */
internal object CourseCardSlots {
    fun isWhole(centre: Int, width: Int): Boolean =
        centre - width * 124 / 720 >= width * 48 / 720 &&
            centre + width * 124 / 720 <= width * 672 / 720

    fun centres(anchor: Int, width: Int): List<Int> =
        (-2..2).map { anchor + it * width * 264 / 720 }.filter { isWhole(it, width) }
}

/** OCR each card on its own so the next clipped card cannot be merged into the course name. */
internal object CourseCardReader {
    fun read(frame: Bitmap, lines: List<TextLine>, reader: CourseNameReader, locked: (Rect) -> Boolean,
        diagnose: (String) -> Unit = {}): List<CourseEntry> {
        val durations = lines.filter { line ->
            CourseListParser.durationPattern.containsMatchIn(line.text) &&
                line.bounds.top >= frame.height * 0.49 && line.bounds.bottom <= frame.height * 0.66
        }
        val duration = durations.firstOrNull { line ->
            CourseCardSlots.isWhole((line.bounds.left + line.bounds.right) / 2, frame.width)
        } ?: error("COURSE_SCAN_PAGE_UNREADABLE")
        // Re-read every whole slot, including neighbours whose duration full-frame OCR missed.
        val anchor = (duration.bounds.left + duration.bounds.right) / 2
        return CourseCardSlots.centres(anchor, frame.width).map { centre ->
            val half = frame.width * 124 / 720
            val left = centre - half + 8
            // Tight to the two text rows: the card icon above the name leaks colour into a wider
            // crop and ML Kit misread 看图识世界课 as 春图识世界课/看圈识世界课 with it present.
            val top = (duration.bounds.top - frame.height * 45 / 1600).coerceAtLeast(0)
            val right = centre + half - 8
            val bottom = (duration.bounds.bottom + 8).coerceAtMost(frame.height)
            val crop = Bitmap.createBitmap(frame, left, top, right - left, bottom - top)
            // Binarize at native size, THEN 4x upscale. Measured on device 2026-10-20 across three
            // preprocessings of every card: this order reads 6 of 7 course names exactly; upscaling
            // before binarizing, or not binarizing at all, misread more cards. The measured remaining
            // 世界地理谋 spelling is normalised in CourseListParser for both scan and selection.
            val clean = binarize(crop)
            val enlarged = Bitmap.createScaledBitmap(clean, clean.width * 4, clean.height * 4, true)
            val isolated = try {
                reader.read(enlarged).map { line -> TextLine(line.text, Rect(
                    line.bounds.left / 4 + left, line.bounds.top / 4 + top,
                    line.bounds.right / 4 + left, line.bounds.bottom / 4 + top)) }
            } finally { enlarged.recycle(); clean.recycle() }
            val parsed = CourseListParser.completeCards(isolated, frame.width, frame.height, locked)
            if (parsed.isEmpty()) diagnose(isolated.joinToString(";") { "${it.text}@${it.bounds}" })
            checkNotNull(parsed.singleOrNull()) {
                "COURSE_SCAN_CARD_UNREADABLE:$centre"
            }
        }
    }

    /**
     * Otsu binarisation per text row-band: pure black glyphs on pure white. ML Kit's Chinese
     * recogniser was built for documents, and the card's anti-aliased grey edges plus coloured
     * icon pixels measurably turned 课 into 谋/谍/課 and 图 into 圈/春 on this device.
     *
     * Per band, because the two text rows sit at different greys (measured on device
     * 2026-10-20: name glyphs ≈22, duration glyphs ≈137-160, card background ≈206-236). One
     * threshold for the whole crop lands at ≈139 — between the two text colours — which erased
     * the duration row entirely (r6 device log, course-card-ocr: card 497's crop returned the
     * name line only, and the scan failed COURSE_SCAN_CARD_UNREADABLE). Each detected band of
     * text rows gets its own Otsu split, so black name text and light-grey duration text both
     * survive as full black-on-white glyphs.
     */
    private fun binarize(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)
        val grey = IntArray(w * h)
        for (i in pixels.indices) {
            val p = pixels[i]
            grey[i] = ((p shr 16 and 255) + (p shr 8 and 255) + (p and 255)) / 3
        }
        val rowMin = IntArray(h)
        for (y in 0 until h) {
            var m = 255
            for (x in 0 until w) m = minOf(m, grey[y * w + x])
            rowMin[y] = m
        }
        val background = rowMin.max()
        val out = IntArray(w * h) { -0x1 }
        var bandStart = -1
        for (y in 0..h) {
            val textRow = y < h && rowMin[y] < background - 40
            if (textRow && bandStart < 0) bandStart = y
            if (!textRow && bandStart >= 0) {
                binarizeBand(grey, out, w, bandStart, y)
                bandStart = -1
            }
        }
        val clean = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        clean.setPixels(out, 0, w, 0, 0, w, h)
        src.recycle()
        return clean
    }

    private fun binarizeBand(grey: IntArray, out: IntArray, w: Int, top: Int, bottom: Int) {
        if (bottom - top < 3) return
        val hist = IntArray(256)
        var sum = 0L
        var count = 0
        for (y in top until bottom) for (x in 0 until w) {
            val g = grey[y * w + x]
            hist[g]++
            sum += g
            count++
        }
        var weightB = 0
        var sumB = 0L
        var threshold = 127
        var maxVariance = 0.0
        for (t in 0..255) {
            weightB += hist[t]
            if (weightB == 0) continue
            val weightF = count - weightB
            if (weightF == 0) break
            sumB += t.toLong() * hist[t]
            val meanB = sumB.toDouble() / weightB
            val meanF = (sum - sumB).toDouble() / weightF
            val variance = weightB.toDouble() * weightF * (meanB - meanF) * (meanB - meanF)
            if (variance > maxVariance) {
                maxVariance = variance
                threshold = t
            }
        }
        for (y in top until bottom) for (x in 0 until w) {
            out[y * w + x] = if (grey[y * w + x] >= threshold) -0x1 else 0xFF000000.toInt()
        }
    }
}

object CourseRowLocator {
    fun locate(lines: List<TextLine>, courseName: String): PointF? {
        val line = lines.firstOrNull { it.text.trim() == courseName } ?: return null
        return PointF((line.bounds.left + line.bounds.right) / 2f, (line.bounds.top + line.bounds.bottom) / 2f)
    }

    fun titleMatches(lines: List<TextLine>, courseName: String): Boolean =
        lines.any { it.text.trim() == courseName }
}

/** Selects a complete card, then requires its selected border and re-reads its own duration. */
class CourseSelector(
    private val flow: PetFlowContext,
    private val navigation: PetNavigation,
    private val reader: CourseNameReader,
) {
    suspend fun select(plan: SessionPlan): CourseEntry = with(flow) {
        navigation.openAcademyCourseList()
        val browser = CourseStripBrowser(flow, reader)
        var page = browser.read()
        if (page.courses.none { it.id == plan.courseId }) page = browser.rewind()
        var still = 0
        repeat(CourseScanFlow.MAX_SCREENS) {
            val found = page.courses.firstOrNull { it.id == plan.courseId }
            if (found != null) {
                val fresh = frame()
                val target = try {
                    check(score(fresh, listOf(profile.courseListProbe)) >= profile.matchThreshold) { "COURSE_LIST_UNCONFIRMED" }
                    CourseCardReader.read(fresh, reader.read(fresh), reader, { false }) { linesRead ->
                        emit("course-card-ocr", result = "target lines=$linesRead")
                    }.singleOrNull { it.id == plan.courseId } ?: error("COURSE_TARGET_MOVED:${plan.courseName}")
                } finally { fresh.recycle() }
                check(found.unlocked) { "COURSE_LOCKED:${plan.courseName}" }
                safety()
                input.tap((target.nameLeft + target.nameRight) / 2f, (target.rowTop + target.rowBottom) / 2f)
                delay(profile.pageSettleMs)
                return@with confirm(plan)
            }
            val next = browser.move(forward = true)
            still = if (CourseStripMotion.same(page.pixels, next.pixels)) still + 1 else 0
            if (still >= CourseStripMotion.BOUNDARY_SAMPLES) {
                check(!next.clippedRight) { "COURSE_SEARCH_INCOMPLETE:RIGHT_CARD_CLIPPED" }
                error("COURSE_NOT_FOUND:${plan.courseName}")
            }
            page = next
        }
        error("COURSE_SEARCH_INCOMPLETE:${plan.courseName}")
    }

    suspend fun confirm(plan: SessionPlan): CourseEntry = with(flow) {
        val detail = frame()
        try {
            check(score(detail, listOf(profile.courseListProbe)) >= profile.matchThreshold) { "COURSE_DETAIL_UNCONFIRMED" }
            val selected = CourseCardReader.read(detail, reader.read(detail), reader, { false }) { linesRead ->
                emit("course-card-ocr", result = "detail lines=$linesRead")
            }.singleOrNull { it.id == plan.courseId } ?: error("COURSE_MISMATCH:${plan.courseName}")
            check(selectedBorder(detail, selected)) { "COURSE_SELECTION_UNCONFIRMED:${plan.courseName}" }
            save(detail, "course-selected")
            emit("course-selected", result = "${selected.id} minutes=${selected.durationMinutes}")
            selected
        } finally { detail.recycle() }
    }

    private fun selectedBorder(frame: Bitmap, course: CourseEntry): Boolean {
        val centre = (course.nameLeft + course.nameRight) / 2
        val radius = frame.width * 124 / 720
        val top = course.rowTop - frame.height * 180 / 1600
        val bottom = course.rowBottom + frame.height * 75 / 1600
        var blue = 0
        for (x in listOf(centre - radius, centre + radius)) {
            for (dx in -5..5) for (y in top.coerceAtLeast(0) until bottom.coerceAtMost(frame.height) step 3) {
                val at = x + dx
                if (at !in 0 until frame.width) continue
                val pixel = frame.getPixel(at, y)
                val r = (pixel shr 16) and 255
                val g = (pixel shr 8) and 255
                val b = pixel and 255
                if (r < 80 && g in 110..220 && b > 190) blue++
            }
        }
        return blue >= 30
    }
}
