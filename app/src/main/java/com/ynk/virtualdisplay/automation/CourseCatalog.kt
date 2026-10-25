package com.ynk.virtualdisplay.automation

import android.content.Context
import android.graphics.Rect
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * One course as read off the academy list. [unlocked] is decided by the scanner from the row's
 * appearance; a locked course is recorded so the UI can show it, but it can never be selected.
 *
 * [durationMinutes] is a page reading. It can authorise a lesson only after a complete scan and a
 * matching reading of the selected card immediately before the start.
 */
data class CourseEntry(
    val id: String,
    val name: String,
    val durationMinutes: Int?,
    val unlocked: Boolean,
    /** Where the row sat on the screen when it was read, in display pixels. */
    val rowTop: Int,
    val rowBottom: Int,
    val nameLeft: Int = 0,
    val nameRight: Int = 0,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("name", name)
        .put("durationMinutes", durationMinutes ?: JSONObject.NULL)
        .put("unlocked", unlocked)
        .put("rowTop", rowTop).put("rowBottom", rowBottom)
        .put("nameLeft", nameLeft).put("nameRight", nameRight)

    companion object {
        fun idFor(name: String) = "study:$name"

        fun fromJson(json: JSONObject) = CourseEntry(
            id = json.getString("id"),
            name = json.getString("name"),
            durationMinutes = if (json.isNull("durationMinutes")) null else json.getInt("durationMinutes"),
            unlocked = json.getBoolean("unlocked"),
            rowTop = json.getInt("rowTop"),
            rowBottom = json.getInt("rowBottom"),
            nameLeft = json.optInt("nameLeft", 0),
            nameRight = json.optInt("nameRight", 0),
        )
    }
}

/** One line of text an OCR engine found, with the box it occupied. */
data class TextLine(val text: String, val bounds: Rect)

/** Reads text out of one captured frame. The only part of course scanning that touches a model. */
fun interface CourseNameReader {
    fun read(frame: android.graphics.Bitmap): List<TextLine>
}

/**
 * The account's course list, scanned once and reused until it goes stale. A catalog is bound to
 * the account it was read from: another account unlocks different courses, so a plan may only
 * name a course that appears here as [CourseEntry.unlocked].
 */
data class CourseCatalog(
    val scannedAtMs: Long,
    val courses: List<CourseEntry>,
    val complete: Boolean = false,
) {
    fun unlocked(): List<CourseEntry> = courses.filter { it.unlocked }

    fun find(courseId: String): CourseEntry? = courses.firstOrNull { it.id == courseId }

    /** A catalog with no scan, or one older than the plan allows, must not authorise a lesson. */
    fun isFresh(nowMs: Long): Boolean =
        scannedAtMs > 0 && nowMs >= scannedAtMs && nowMs - scannedAtMs <= SessionPlan.CATALOG_MAX_AGE_MS

    fun isUsable(nowMs: Long): Boolean = complete && courses.isNotEmpty() && isFresh(nowMs)

    fun toJson(): JSONObject = JSONObject()
        .put("scannedAtMs", scannedAtMs)
        .put("complete", complete)
        .put("courses", JSONArray().also { array -> courses.forEach { array.put(it.toJson()) } })

    companion object {
        fun fromJson(json: JSONObject): CourseCatalog {
            val array = json.getJSONArray("courses")
            return CourseCatalog(
                scannedAtMs = json.getLong("scannedAtMs"),
                courses = (0 until array.length()).map { CourseEntry.fromJson(array.getJSONObject(it)) },
                complete = json.optBoolean("complete", false),
            )
        }
    }
}

/** Atomic on-disk copy of [CourseCatalog]. Absent file means the account was never scanned. */
class CourseCatalogStore(context: Context) {
    private val atomic = AtomicFile(File(context.filesDir, FILE_NAME))

    fun load(): CourseCatalog? {
        if (!atomic.baseFile.exists()) return null
        return CourseCatalog.fromJson(JSONObject(atomic.readFully().toString(Charsets.UTF_8)))
    }

    fun save(catalog: CourseCatalog) {
        val stream = atomic.startWrite()
        try {
            stream.write(catalog.toJson().toString().toByteArray(Charsets.UTF_8))
            atomic.finishWrite(stream)
        } catch (t: Exception) {
            atomic.failWrite(stream)
            throw t
        }
    }

    companion object {
        const val FILE_NAME = "course-catalog.json"
    }
}

/** Pairs each card's name with the duration directly below its own horizontal centre. */
object CourseListParser {
    val durationPattern = Regex("""(\d+)\s*分钟""")
    private val namePattern = Regex("""[\p{IsHan}]{2,20}""")
    const val NAME_MIN_LEFT = 40

    fun parse(lines: List<TextLine>, locked: (Rect) -> Boolean): List<CourseEntry> {
        val durations = lines.mapIndexedNotNull { index, line ->
            durationPattern.find(line.text)?.groupValues?.get(1)?.toIntOrNull()
                ?.takeIf { it in 1..DailyBudgetStore.HARD_STOP_MINUTES }
                ?.let { Triple(it, index, line) }
        }
        val consumed = mutableSetOf<Int>()
        return lines.sortedBy { it.bounds.left }.mapNotNull { line ->
            val rawName = line.text.trim().replace(" ", "")
            // Exact observed glyph confusion, verified against the full card in the r7 scan frames.
            // Do not rewrite arbitrary course suffixes or infer names for other accounts.
            val name = if (rawName == "世界地理谋") "世界地理课" else rawName
            if (!namePattern.matches(name) || line.bounds.left < NAME_MIN_LEFT) return@mapNotNull null
            val candidates = durations.filter { (_, index, duration) ->
                index !in consumed && aligned(line.bounds, duration.bounds)
            }.sortedBy { (_, _, duration) ->
                kotlin.math.abs(line.bounds.left + line.bounds.right - duration.bounds.left - duration.bounds.right)
            }
            val duration = candidates.firstOrNull() ?: return@mapNotNull null
            // Two equally plausible readings cannot authorise a course.
            if (candidates.size > 1 && centreGap(line.bounds, candidates[0].third.bounds) ==
                centreGap(line.bounds, candidates[1].third.bounds)) return@mapNotNull null
            consumed += duration.second
            CourseEntry(
                id = CourseEntry.idFor(name), name = name, durationMinutes = duration.first,
                unlocked = !locked(line.bounds), rowTop = line.bounds.top, rowBottom = line.bounds.bottom,
                nameLeft = line.bounds.left, nameRight = line.bounds.right,
            )
        }
    }

    /** Only whole cards in the calibrated panel may enter a scan or become a tap target. */
    fun completeCards(lines: List<TextLine>, width: Int, height: Int, locked: (Rect) -> Boolean): List<CourseEntry> {
        val panelLines = lines.filter { line ->
            val box = line.bounds
            box.top >= height * 0.46 && box.bottom <= height * 0.66 &&
                box.left >= width * 0.065 && box.right <= width * 0.915
        }
        return parse(panelLines, locked).filter { course ->
            val centre = (course.nameLeft + course.nameRight) / 2.0
            // Measured card width 248px, panel viewport [48,672] on 720px. A sliver may
            // contain complete-looking text, so checking only the name's bounds is insufficient.
            centre - width * (124.0 / 720) >= width * (48.0 / 720) &&
                centre + width * (124.0 / 720) <= width * (672.0 / 720)
        }
    }

    private fun centreGap(a: Rect, b: Rect): Int =
        kotlin.math.abs(a.left + a.right - b.left - b.right)

    private fun aligned(a: Rect, b: Rect): Boolean {
        val height = maxOf(a.bottom - a.top, b.bottom - b.top)
        val below = b.top + b.bottom - a.top - a.bottom
        return height > 0 && below in 0..4 * height && centreGap(a, b) <= 3 * height
    }
}

/** Merges overlapping pages without duplicates; conflicting readings fail instead of replacing facts. */
object CourseScanAccumulator {
    data class Fold(val courses: List<CourseEntry>, val added: Int) {
        val unchanged: Boolean get() = added == 0
    }

    fun fold(collected: List<CourseEntry>, screen: List<CourseEntry>): Fold {
        val merged = collected.associateBy { it.id }.toMutableMap()
        var added = 0
        screen.forEach { course ->
            val previous = merged[course.id]
            if (previous == null) {
                merged[course.id] = course
                added++
            } else {
                check(previous.durationMinutes == course.durationMinutes) { "COURSE_SCAN_DURATION_CONFLICT:${course.name}" }
                check(previous.unlocked == course.unlocked) { "COURSE_SCAN_LOCK_CONFLICT:${course.name}" }
            }
        }
        val courses = merged.values.toList()
        refuseAmbiguousReadings(courses)
        return Fold(courses, courses.size - collected.size)
    }

    /**
     * Refuses a scan in which one card was read two different ways.
     *
     * Measured on device (2026-10-19): the same card came back as 看图识世界课 in one frame and
     * 看圈识世界课 in another, and 闪亮自我表达课 as both 闪亮自我表达谍 and 闪亮自我表达谋. Id-based
     * folding keeps every spelling, so one card silently becomes two or three courses and an
     * over-reported catalog is saved as complete.
     *
     * Picking a winner is not available: each misreading occurs once per page while the true reading
     * may occur fewer times (the strip shows each card on two consecutive pages, and a misread can
     * repeat), so neither first-seen nor most-frequent identifies the real name. Two readings of one
     * card are therefore equally plausible, which under this file's standing rule — conflicting
     * readings fail instead of replacing facts — means the scan fails and the previous catalog is
     * kept. A name nobody can spell consistently could not be selected on the course page anyway:
     * [CourseRowLocator] matches a plan's course by exact name.
     *
     * Card geometry is fixed (248px wide, 264px centre pitch), so two readings describe one card
     * exactly when the durations and lock states match, the lengths match, and one or two glyphs
     * differ. Distinct courses of equal length differ in most of their glyphs and are untouched.
     */
    internal fun refuseAmbiguousReadings(courses: List<CourseEntry>) {
        for (i in courses.indices) for (j in i + 1 until courses.size) {
            val a = courses[i]
            val b = courses[j]
            if (sameCardReadDifferently(a, b)) {
                error("COURSE_SCAN_NAME_AMBIGUOUS:${a.name}|${b.name}")
            }
        }
    }

    private fun sameCardReadDifferently(a: CourseEntry, b: CourseEntry): Boolean {
        if (a.durationMinutes != b.durationMinutes) return false
        if (a.unlocked != b.unlocked) return false
        if (a.name.length != b.name.length) return false
        var different = 0
        for (k in a.name.indices) if (a.name[k] != b.name[k]) different++
        return different in 1..OCR_VARIANT_DISTANCE
    }

    /** Glyphs allowed to differ between two readings of one card's name. */
    const val OCR_VARIANT_DISTANCE = 2
}
