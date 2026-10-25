package com.ynk.virtualdisplay.automation

import java.time.LocalDateTime

/** Pure OCR-token parser; no OCR engine or live input. Bounds measured on two 720x1600 reports:
 * first-resume-study-stop.png and s20-managed-pass/1792503898355/round-1-result.png.
 * Date line is below 宠物成绩单; course line is beside 课程. Never scan the page clock/body.
 * ROIs are deliberately resolution-specific; a different layout needs independent measurement.
 */
object StudyResultPageParser {
    data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        fun valid() = left >= 0 && top >= 0 && right > left && bottom > top
        fun contains(other: Box) = other.valid() && other.left >= left && other.top >= top && other.right <= right && other.bottom <= bottom
    }
    data class Token(val text: String, val bounds: Box)
    enum class Status { PARSED, NOT_RESULT, UNCONFIRMED }
    data class Result(val status: Status, val courseId: String? = null,
        val printedDateTime: LocalDateTime? = null, val reason: String)
    val TITLE_ROI = Box(200, 220, 520, 290)
    val DATE_ROI = Box(240, 295, 485, 335)
    val COURSE_ROI = Box(340, 625, 680, 685)
    private val DATE = Regex("""(\d{4})\s*/\s*(\d{1,2})\s*/\s*(\d{1,2})\s+(\d{2}):(\d{2})""")

    fun parse(width: Int, height: Int, tokens: List<Token>, resultSceneConfirmed: Boolean): Result {
        fun unclear(reason: String) = Result(Status.UNCONFIRMED, reason = reason)
        if (!resultSceneConfirmed) return Result(Status.NOT_RESULT, reason = "RESULT_PAGE_NOT_CONFIRMED")
        if (width != 720 || height != 1600) return unclear("RESULT_LAYOUT_UNSUPPORTED")
        if (tokens.any { !it.bounds.valid() || it.bounds.right > width || it.bounds.bottom > height }) return unclear("RESULT_TOKEN_BOUNDS_INVALID")
        fun local(roi: Box) = tokens.filter { roi.contains(it.bounds) }.sortedWith(compareBy<Token> { it.bounds.top }.thenBy { it.bounds.left })
        val title = local(TITLE_ROI).joinToString("") { it.text }.replace(Regex("\\s+"), "")
        if (title != "宠物成绩单") return unclear("RESULT_TITLE_UNREAD_OR_AMBIGUOUS")
        val dateText = local(DATE_ROI).joinToString(" ") { it.text }.trim()
        val match = DATE.matchEntire(dateText) ?: return unclear("RESULT_FULL_DATE_TIME_UNREAD_OR_AMBIGUOUS")
        val time = try {
            val values = match.groupValues.drop(1).map { it.toInt() }
            LocalDateTime.of(values[0], values[1], values[2], values[3], values[4])
        } catch (_: RuntimeException) { return unclear("RESULT_DATE_TIME_INVALID") }
        val courseText = local(COURSE_ROI).joinToString("") { it.text }.replace(Regex("\\s+"), "")
        if (!courseText.startsWith("课程")) return unclear("RESULT_COURSE_UNREAD")
        val course = courseText.removePrefix("课程")
        // Accept a single complete course label, never a substring of a multi-candidate OCR line.
        if (!Regex("[\\p{IsHan}A-Za-z0-9]{1,24}课").matches(course) || course.count { it == '课' } != 1) return unclear("RESULT_COURSE_UNREAD_OR_AMBIGUOUS")
        return Result(Status.PARSED, "study:$course", time, "RESULT_FIELDS_PARSED_TIME_SEMANTICS_UNKNOWN")
    }
}
