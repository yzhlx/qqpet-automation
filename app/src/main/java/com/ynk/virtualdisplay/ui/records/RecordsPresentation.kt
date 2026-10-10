package com.ynk.virtualdisplay.ui.records

import com.ynk.virtualdisplay.ui.data.CareStats
import com.ynk.virtualdisplay.ui.data.HealthSnapshot
import com.ynk.virtualdisplay.ui.data.RoundRecord
import com.ynk.virtualdisplay.ui.data.SessionEvidence
import com.ynk.virtualdisplay.ui.data.SessionRunRecord

/**
 * Pure presentation for the 记录 tab. Everything here is derived from persisted evidence:
 * `run-record.json`, `round-<n>.json` and the real PNG files in the same session directory.
 *
 * Honesty rules enforced:
 *  1. A change is only reported when two real readings exist. When only one side was observed the
 *     row is labelled 无法确认 instead of being filled with a guess.
 *  2. Deltas are computed from the readings themselves; a delta that contradicts the direction of
 *     travel (e.g. 清洁 fell while the layer claimed a clean) is reported as-is, never beautified.
 *  3. Sent input is never equated with an effect: encouragement with a null/zero success count stays
 *     待确认.
 *  4. Metrics the game does not expose stay 未知 / 不支持读取. No 收益, 金币 or 库存 number is invented.
 */

/** One row of 已确认的状态变化. */
data class MetricChange(
    val label: String,
    val beforeText: String,
    val afterText: String,
    /** Signed delta, or null when both readings were not available. */
    val delta: Int?,
    val deltaText: String,
    val meta: String,
    val confirmed: Boolean,
)

/** One row of 动作与道具. */
data class ActionOutcome(
    val label: String,
    val meta: String,
    val verdict: String,
    val verdictTone: VerdictTone,
)

enum class VerdictTone { CONFIRMED, PENDING, UNKNOWN }

data class RecordsSummary(
    /** e.g. "今天 20:35 – 21:46"; null when there is no dated session. */
    val rangeText: String?,
    val minutesText: String?,
    val courses: Int,
    val careCount: Int,
    val pauseCount: Int,
)

data class SessionHistoryRow(
    val id: String,
    val whenText: String,
    val outcomeText: String,
    val outcomeTone: VerdictTone,
    val detail: String,
    val screenshots: Int,
)

object RecordsPresentation {

    /**
     * Aggregates today's sessions. Only sessions that actually produced a run record are counted, so
     * the summary can never be larger than what really ran.
     */
    fun summary(
        today: List<SessionEvidence>,
        timeFormatter: (Long) -> String,
        dateFormatter: (Long) -> String,
    ): RecordsSummary {
        val records = today.mapNotNull { it.record }
        if (records.isEmpty()) {
            return RecordsSummary(null, null, 0, 0, 0)
        }
        val starts = records.mapNotNull { it.startedAtMs }
        val rangeText = if (starts.isEmpty()) {
            "今天"
        } else {
            val start = starts.min()
            val end = records.mapNotNull { it.startedAtMs?.plus(it.runtimeMs) }.maxOrNull() ?: start
            "今天 ${timeFormatter(start)} – ${timeFormatter(end)}"
        }
        val minutes = records.sumOf { it.runtimeMs } / 60_000L
        return RecordsSummary(
            rangeText = rangeText,
            minutesText = "$minutes 分钟",
            courses = records.sumOf { it.completedRounds },
            careCount = records.sumOf { it.care?.total ?: 0 },
            pauseCount = records.sumOf { it.pauseCount },
        )
    }

    /**
     * Health changes across the rounds of one session, deduced from the before → after readings the
     * controller itself recorded. A round whose readings are missing contributes nothing.
     */
    fun changes(rounds: List<RoundRecord>): List<MetricChange> {
        val usable = rounds.filter { it.before != null && it.after != null }
        if (usable.isEmpty()) {
            return listOf(
                MetricChange("体力", "—", "—", null, "—", "本会话没有成对的读数", false),
                MetricChange("清洁", "—", "—", null, "—", "本会话没有成对的读数", false),
                MetricChange("心情", "—", "—", null, "—", "本会话没有成对的读数", false),
            )
        }
        val first = usable.first().before!!
        val last = usable.last().after!!
        return listOf(
            change("体力", first.stamina, last.stamina),
            change("清洁", first.cleanliness, last.cleanliness),
            change("心情", first.mood, last.mood),
        )
    }

    private fun change(label: String, before: Int?, after: Int?): MetricChange {
        val confirmed = before != null && after != null
        val delta = if (confirmed) after!! - before!! else null
        return MetricChange(
            label = label,
            beforeText = before?.toString() ?: "—",
            afterText = after?.toString() ?: "—",
            delta = delta,
            deltaText = when {
                delta == null -> "—"
                delta > 0 -> "+$delta"
                delta < 0 -> "−${-delta}"
                else -> "0"
            },
            meta = if (confirmed) "首节读数 → 末节读数" else "缺一侧读数，无法确认",
            confirmed = confirmed,
        )
    }

    /**
     * 动作与道具. Each row is decided by the layer's own recorded outcome, never by the intent:
     * a feed counts as 生效 only when stamina actually rose; encouragement stays 待确认 unless the
     * layer recorded a positive confirmed count.
     */
    fun actions(rounds: List<RoundRecord>, record: SessionRunRecord?): List<ActionOutcome> {
        val out = mutableListOf<ActionOutcome>()
        val feedCount = record?.care?.feedCount ?: rounds.sumOf { it.care?.feedCount ?: 0 }
        val cleanCount = record?.care?.cleanCount ?: rounds.sumOf { it.care?.cleanCount ?: 0 }

        if (feedCount > 0) {
            val rose = rounds.any { r ->
                val b = r.before?.stamina
                val a = r.after?.stamina
                b != null && a != null && a > b
            }
            out += ActionOutcome(
                label = "喂食小饼干",
                meta = "$feedCount 次 · " + if (rose) "体力已确认上升" else "体力变化未确认",
                verdict = if (rose) "生效" else "待确认",
                verdictTone = if (rose) VerdictTone.CONFIRMED else VerdictTone.PENDING,
            )
        }
        if (cleanCount > 0) {
            val rose = rounds.any { r ->
                val b = r.before?.cleanliness
                val a = r.after?.cleanliness
                b != null && a != null && a > b
            }
            out += ActionOutcome(
                label = "洗澡",
                meta = "$cleanCount 次 · " + if (rose) "清洁已确认上升" else "清洁变化未确认",
                verdict = if (rose) "生效" else "待确认",
                verdictTone = if (rose) VerdictTone.CONFIRMED else VerdictTone.PENDING,
            )
        }
        val encouragement = record?.encouragementSent
            ?: rounds.sumOf { it.encouragementSent ?: 0 }
        if (encouragement > 0) {
            out += ActionOutcome(
                label = "鼓励 ×$encouragement",
                meta = "已发送，游戏是否计入无法确认",
                verdict = "待确认",
                verdictTone = VerdictTone.PENDING,
            )
        }
        out += ActionOutcome(
            label = "剩余库存",
            meta = "游戏未提供可稳定识别的位置",
            verdict = "未知",
            verdictTone = VerdictTone.UNKNOWN,
        )
        out += ActionOutcome(
            label = "金币 / 属性 / 库存",
            meta = "本版本未接入读取器（NO_READER_IN_SLICE1）",
            verdict = "不支持读取",
            verdictTone = VerdictTone.UNKNOWN,
        )
        return out
    }

    /** One history row per session that has a run record, newest first. */
    fun history(
        sessions: List<SessionEvidence>,
        timeFormatter: (Long) -> String,
        dateFormatter: (Long) -> String,
    ): List<SessionHistoryRow> = sessions.mapNotNull { ev ->
        val record = ev.record ?: return@mapNotNull null
        val (text, tone) = when (record.terminalState) {
            "COMPLETED" -> "已完成" to VerdictTone.CONFIRMED
            "STOPPED" -> "已停止" to VerdictTone.UNKNOWN
            "FAILED" -> "失败" to VerdictTone.PENDING
            else -> record.terminalState to VerdictTone.UNKNOWN
        }
        val whenText = record.startedAtMs?.let { ms ->
            "${dateFormatter(ms)} ${timeFormatter(ms)}"
        } ?: "时间未记录"
        SessionHistoryRow(
            id = ev.id,
            whenText = whenText,
            outcomeText = text,
            outcomeTone = tone,
            detail = "完成 ${record.completedRounds}/${record.rounds} 节 · " +
                "${record.runtimeMs / 60_000L} 分钟 · 护理 ${record.care?.total ?: 0} 次" +
                (record.stopReason?.let { " · ${it.take(40)}" } ?: ""),
            screenshots = ev.screenshots.size,
        )
    }

    /** The screenshots worth showing for a round: the before frame and the after frame. */
    fun pairedFrames(session: SessionEvidence?, rounds: List<RoundRecord>): List<java.io.File> {
        if (session == null) return emptyList()
        val files = session.screenshots
        val wanted = mutableListOf<java.io.File>()
        val round = rounds.firstOrNull()?.round
        if (round != null) {
            files.firstOrNull { it.name == "round-$round-before.png" }?.let { wanted += it }
            files.firstOrNull { it.name == "round-$round-ready.png" }?.let { wanted += it }
        }
        val lastRound = rounds.lastOrNull()?.round
        if (lastRound != null && lastRound != round) {
            files.firstOrNull { it.name == "round-$lastRound-after.png" }?.let { wanted += it }
        } else if (round != null) {
            files.firstOrNull { it.name == "round-$round-after.png" }?.let { wanted += it }
        }
        return wanted
    }

    /** Human caption for an evidence frame name, never a fabricated time. */
    fun frameCaption(fileName: String): String {
        val stem = fileName.removeSuffix(".png").removePrefix("round-")
        val parts = stem.split("-")
        // `five 7 after`-style raw stems leaked the capture step verbatim; translate the ones the
        // engine actually emits instead of printing the file name at the user.
        val body = parts.drop(2).joinToString("-")
        val phase = when {
            body.endsWith("-before") -> "前往"
            body.endsWith("-after") -> "确认"
            else -> ""
        }
        val name = body.removeSuffix("-before").removeSuffix("-after")
        val known = when {
            name.startsWith("high-five") || name.startsWith("five") -> "互动鼓励"
            name.contains("open-health") -> "健康页"
            name.contains("close-health") -> "关闭健康页"
            name.contains("leave-food") -> "喂食页"
            name.contains("enter-bath") -> "浴缸"
            name.contains("leave-bath") -> "离开浴缸"
            name.contains("outdoor") -> "户外"
            name.contains("select-fixed-course") -> "选择课程"
            name.contains("result-close-course") -> "课程结束"
            name.contains("enter-course") -> "进入课程"
            name.contains("start-course") -> "开始上课"
            name.contains("initial-home") -> "主页"
            else -> null
        }
        if (known != null) return if (phase.isEmpty()) known else phase + known
        return when {
            stem.endsWith("before") -> "首节 · 喂食前"
            stem.endsWith("ready") -> "首节 · 开课前"
            stem.endsWith("after") -> "末节 · 课后"
            else -> stem
        }
    }

    /** Flattens a snapshot pair into the strings the UI prints, keeping nulls visible. */
    fun snapshotText(snapshot: HealthSnapshot?): String =
        if (snapshot == null) "—"
        else "体力 ${snapshot.stamina ?: "—"} / 清洁 ${snapshot.cleanliness ?: "—"} / 心情 ${snapshot.mood ?: "—"}"
}
