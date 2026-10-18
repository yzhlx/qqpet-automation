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
 * [durationMinutes] is a page reading, not a closed-loop measurement. The budget guard does not
 * trust it until the course is in [SessionPlan.KNOWN_COURSE_MINUTES].
 */
data class CourseEntry(
    val id: String,
    val name: String,
    val durationMinutes: Int?,
    val unlocked: Boolean,
    /** Where the row sat on the screen when it was read, in display pixels. */
    val rowTop: Int,
    val rowBottom: Int,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("name", name)
        .put("durationMinutes", durationMinutes ?: JSONObject.NULL)
        .put("unlocked", unlocked)
        .put("rowTop", rowTop).put("rowBottom", rowBottom)

    companion object {
        fun idFor(name: String) = "study:$name"

        fun fromJson(json: JSONObject) = CourseEntry(
            id = json.getString("id"),
            name = json.getString("name"),
            durationMinutes = if (json.isNull("durationMinutes")) null else json.getInt("durationMinutes"),
            unlocked = json.getBoolean("unlocked"),
            rowTop = json.getInt("rowTop"),
            rowBottom = json.getInt("rowBottom"),
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
) {
    fun unlocked(): List<CourseEntry> = courses.filter { it.unlocked }

    fun find(courseId: String): CourseEntry? = courses.firstOrNull { it.id == courseId }

    /** A catalog with no scan, or one older than the plan allows, must not authorise a lesson. */
    fun isFresh(nowMs: Long): Boolean =
        scannedAtMs > 0 && nowMs - scannedAtMs <= SessionPlan.CATALOG_MAX_AGE_MS

    fun toJson(): JSONObject = JSONObject()
        .put("scannedAtMs", scannedAtMs)
        .put("courses", JSONArray().also { array -> courses.forEach { array.put(it.toJson()) } })

    companion object {
        fun fromJson(json: JSONObject): CourseCatalog {
            val array = json.getJSONArray("courses")
            return CourseCatalog(
                scannedAtMs = json.getLong("scannedAtMs"),
                courses = (0 until array.length()).map { CourseEntry.fromJson(array.getJSONObject(it)) },
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

/**
 * Turns one screen of OCR lines into course rows. Pure, so the row rules are testable without a
 * device or a model.
 *
 * A course row is a name line with a duration line ("10分钟") sitting beside it at the same
 * height. A name line with no duration beside it is not a course and is dropped, which is what
 * keeps panel titles and buttons out of the catalog. [locked] reports whether a row renders as
 * locked; the scanner passes that from the row's pixels, never from the text.
 */
object CourseListParser {
    private val durationPattern = Regex("""(\d+)\s*分钟""")

    /** A name starting further left than this is the clipped edge of a card, not a whole card. */
    const val NAME_MIN_LEFT = 40

    fun parse(lines: List<TextLine>, locked: (Rect) -> Boolean): List<CourseEntry> {
        val durations = lines.mapIndexedNotNull { index, line ->
            durationPattern.find(line.text)?.groupValues?.get(1)?.toIntOrNull()?.let { Triple(it, index, line) }
        }
        // Indices, not the lines themselves: TextLine's equality runs Rect.hashCode, which the JVM
        // unit-test android.jar leaves unimplemented.
        val consumed = mutableSetOf<Int>()
        return lines.mapIndexedNotNull { index, line ->
            if (index in consumed) return@mapIndexedNotNull null
            if (durationPattern.containsMatchIn(line.text)) return@mapIndexedNotNull null
            val name = line.text.trim()
            if (name.length < 2) return@mapIndexedNotNull null
            // A card sliding in from the screen edge is only partly visible, and the recogniser
            // reads that sliver as a course of its own. Such a line is cut off at the edge, so it
            // never starts at the left margin the way a fully shown card does.
            if (line.bounds.left < NAME_MIN_LEFT) return@mapIndexedNotNull null
            val duration = durations.firstOrNull { (_, durationIndex, durationLine) ->
                durationIndex !in consumed && aligned(line.bounds, durationLine.bounds)
            } ?: return@mapIndexedNotNull null
            val (_, durationIndex, durationLine) = duration
            consumed += durationIndex
            CourseEntry(
                id = CourseEntry.idFor(name),
                name = name,
                durationMinutes = duration.first,
                // The name line's own box, not a freshly built Rect: the android.jar used for JVM
                // tests keeps Rect's fields but its constructor never writes them, so a new Rect
                // would hand the lock check an empty box and fail every row closed.
                unlocked = !locked(line.bounds),
                rowTop = line.bounds.top,
                rowBottom = line.bounds.bottom,
            )
        }
    }

    /**
     * Same row: the two boxes' vertical centres are at most the taller one's height apart. Overlap
     * is the wrong test here. On a real course list the duration line ("用时: 10分钟") renders
     * shorter than the name and sits just below it, so the two barely overlap and their centres are
     * roughly one line apart. A full line of separation still cannot reach the next course.
     */
    private fun aligned(a: Rect, b: Rect): Boolean {
        val aHeight = a.bottom - a.top
        val bHeight = b.bottom - b.top
        // Both sides are doubled, so this compares the real centre distance against the taller
        // height without a division that would truncate.
        val centreGap = kotlin.math.abs((a.top + a.bottom) - (b.top + b.bottom))
        return centreGap <= 4 * maxOf(aHeight, bHeight)
    }
}

/**
 * Folds several scrolled screens into one catalog. A course whose name was already seen is kept
 * once, at the position of its first sighting, so scrolling back over the same rows cannot
 * duplicate them. [unchanged] is how the scanner knows it has reached the end of the list.
 */
object CourseScanAccumulator {
    data class Fold(val courses: List<CourseEntry>, val added: Int) {
        val unchanged: Boolean get() = added == 0
    }

    fun fold(collected: List<CourseEntry>, screen: List<CourseEntry>): Fold {
        val known = collected.map { it.name }.toMutableSet()
        val fresh = screen.filter { known.add(it.name) }
        return Fold(collected + fresh, fresh.size)
    }
}
