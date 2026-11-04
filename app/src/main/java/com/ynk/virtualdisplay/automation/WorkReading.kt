package com.ynk.virtualdisplay.automation

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * STR-11：岗位详情页上一次现场读到的收益与消耗。
 *
 * 这是页面读数（PAGE_READING），不是真实结算，也不是「已验证」；
 * 开课/开工前 PetWorkFlow 仍会重新读页并与目录核对。读不到就是没有记录，不写假值。
 */
data class WorkReading(
    val optionId: String,
    val venue: String,
    val optionLabel: String,
    val durationMinutes: Int,
    val coinReward: Int,
    val workScoreReward: Int,
    val energyCost: Int,
    val cleanlinessCost: Int,
    val readAtMs: Long,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("optionId", optionId)
        .put("venue", venue)
        .put("optionLabel", optionLabel)
        .put("durationMinutes", durationMinutes)
        .put("coinReward", coinReward)
        .put("workScoreReward", workScoreReward)
        .put("energyCost", energyCost)
        .put("cleanlinessCost", cleanlinessCost)
        .put("readAtMs", readAtMs)

    companion object {
        /** 缺项或负数一律丢弃：未知不写成 0。 */
        fun fromJson(json: JSONObject): WorkReading? {
            val optionId = json.optString("optionId").takeIf { it.isNotBlank() } ?: return null
            val venue = json.optString("venue").takeIf { it.isNotBlank() } ?: return null
            val label = json.optString("optionLabel").takeIf { it.isNotBlank() } ?: return null
            val duration = json.optInt("durationMinutes", 0).takeIf { it > 0 } ?: return null
            val readAt = json.optLong("readAtMs", 0L).takeIf { it > 0 } ?: return null
            val coin = json.optInt("coinReward", -1).takeIf { it >= 0 } ?: return null
            val score = json.optInt("workScoreReward", -1).takeIf { it >= 0 } ?: return null
            val energy = json.optInt("energyCost", -1).takeIf { it >= 0 } ?: return null
            val clean = json.optInt("cleanlinessCost", -1).takeIf { it >= 0 } ?: return null
            return WorkReading(optionId, venue, label, duration, coin, score, energy, clean, readAt)
        }
    }
}

/** 原子写在 files/work-readings.json；文件不存在表示还没现场读过岗位收益。 */
class WorkReadingStore(context: Context) {
    private val atomic = AtomicFile(File(context.filesDir, FILE_NAME))

    fun load(): Map<String, WorkReading> {
        if (!atomic.baseFile.exists()) return emptyMap()
        val root = JSONObject(atomic.readFully().toString(Charsets.UTF_8))
        val array = root.optJSONArray("readings") ?: return emptyMap()
        val result = LinkedHashMap<String, WorkReading>()
        for (index in 0 until array.length()) {
            val reading = array.optJSONObject(index)?.let { WorkReading.fromJson(it) } ?: continue
            result[reading.optionId] = reading
        }
        return result
    }

    fun save(readings: Collection<WorkReading>) {
        val array = JSONArray()
        readings.sortedBy { it.optionId }.forEach { array.put(it.toJson()) }
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
        const val FILE_NAME = "work-readings.json"
    }
}
