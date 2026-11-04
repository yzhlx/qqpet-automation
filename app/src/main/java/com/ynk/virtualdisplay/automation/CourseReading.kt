package com.ynk.virtualdisplay.automation

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * STR-10：课程详情页上一次读到的学费、时长和页面上写明的属性收益。
 *
 * 这些数据只用于方案页的候选课程比较；真正开课前仍会重新打开课程页核对学费，
 * 所以旧读数不会让一次扣费蒙混过关。[attributeGain] 读不到就是 null（未知）。
 */
data class CourseReading(
    val courseId: String,
    val courseName: String,
    val durationMinutes: Int,
    val tuitionCoins: Int,
    val attributeGain: CourseAttributeGain?,
    val readAtMs: Long,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("courseId", courseId)
        .put("courseName", courseName)
        .put("durationMinutes", durationMinutes)
        .put("tuitionCoins", tuitionCoins)
        .put("attributeGain", attributeGain?.let {
            JSONObject().put("attribute", it.attribute).put("amount", it.amount)
        } ?: JSONObject.NULL)
        .put("readAtMs", readAtMs)

    companion object {
        /** 任何一项不完整的条目都丢弃，宁可显示未知也不显示半条事实。 */
        fun fromJson(json: JSONObject): CourseReading? {
            val courseId = json.optString("courseId").takeIf { it.isNotBlank() } ?: return null
            val courseName = json.optString("courseName").takeIf { it.isNotBlank() } ?: return null
            val duration = json.optInt("durationMinutes", 0).takeIf { it > 0 } ?: return null
            val tuition = json.optInt("tuitionCoins", 0).takeIf { it > 0 } ?: return null
            val readAt = json.optLong("readAtMs", 0L).takeIf { it > 0 } ?: return null
            val gain = json.optJSONObject("attributeGain")?.let { value ->
                val attribute = value.optString("attribute").takeIf { it.isNotBlank() } ?: return@let null
                val amount = value.optInt("amount", 0).takeIf { it > 0 } ?: return@let null
                CourseAttributeGain(attribute, amount)
            }
            return CourseReading(courseId, courseName, duration, tuition, gain, readAt)
        }
    }
}

/** 原子写在 files/course-readings.json；文件不存在表示还没读过任何课程收益。 */
class CourseReadingStore(context: Context) {
    private val atomic = AtomicFile(File(context.filesDir, FILE_NAME))

    fun load(): Map<String, CourseReading> {
        if (!atomic.baseFile.exists()) return emptyMap()
        val root = JSONObject(atomic.readFully().toString(Charsets.UTF_8))
        val array = root.optJSONArray("readings") ?: return emptyMap()
        val result = LinkedHashMap<String, CourseReading>()
        for (index in 0 until array.length()) {
            val reading = array.optJSONObject(index)?.let { CourseReading.fromJson(it) } ?: continue
            result[reading.courseId] = reading
        }
        return result
    }

    fun save(readings: Collection<CourseReading>) {
        val array = JSONArray()
        readings.sortedBy { it.courseId }.forEach { array.put(it.toJson()) }
        val text = JSONObject().put("readings", array).toString()
        val stream = atomic.startWrite()
        try {
            stream.write(text.toByteArray(Charsets.UTF_8))
            atomic.finishWrite(stream)
        } catch (t: Exception) {
            atomic.failWrite(stream)
            throw t
        }
    }

    companion object {
        const val FILE_NAME = "course-readings.json"
    }
}

/**
 * 课程详情页的一次读取：学费与余额，以及页面上写明的属性收益。
 *
 * 学费和属性收益写在同一页上，所以一次读页就够。ML Kit 在这一页不稳定，
 * 读不到就换新帧重试；全部失败时抛 COURSE_COST_UNREADABLE，绝不拿旧值顶替。
 */
suspend fun readCoursePageReading(flow: PetFlowContext, reader: CourseNameReader): CoursePageReading {
    var lastLines: List<String> = emptyList()
    var attempts = 0
    while (attempts < COURSE_PAGE_READ_ATTEMPTS) {
        if (attempts > 0) kotlinx.coroutines.delay(COURSE_PAGE_RETRY_DELAY_MS)
        val frame = flow.frame(latest = attempts > 0)
        try {
            check(flow.score(frame, listOf(flow.profile.courseListProbe)) >= flow.profile.matchThreshold) {
                "COURSE_COST_PAGE_UNCONFIRMED"
            }
            val lines = reader.read(frame).sortedBy { it.bounds.top }
            lastLines = lines.map { it.text }
            val text = lines.joinToString(" ") { it.text }
            val cost = CourseCostReading.parse(text)
            if (cost != null) return CoursePageReading(cost, CourseAttributeGain.parse(text))
        } finally {
            frame.recycle()
        }
        attempts++
    }
    flow.emit("course-cost-read-unresolved", result = "FAIL",
        reason = "COURSE_COST_UNREADABLE|lines=" + lastLines.joinToString(";"))
    error("COURSE_COST_UNREADABLE")
}

private const val COURSE_PAGE_READ_ATTEMPTS = 4
private const val COURSE_PAGE_RETRY_DELAY_MS = 800L
