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
    val workCount: Int = 0,
    val adventureCount: Int = 0,
    val signInCount: Int = 0,
    val mailboxCount: Int = 0,
    val luckyBagCount: Int = 0,
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

    /** The raw code stays in the run's log; 记录 only says what happened in words. */
        fun stopReasonText(reason: String): String = when {
        reason == com.ynk.virtualdisplay.automation.ManualPause.REASON -> "你手动暂停"
        reason.contains("USER_STOP", true) || reason == "用户停止" -> "用户停止"
        reason.contains("PERMISSION_DENIED", true) || reason.contains("SHIZUKU", true) || reason.contains("DISPLAY_PERMISSION", true) -> "系统权限不足，请在排故页检查 Shizuku 与显示授权"
        reason.contains("PLAN_DISPLAY_NOT_READY", true) -> "虚拟显示未就绪，请在主页选择或创建虚拟屏"
        reason.contains("PAGE_MISMATCH", true) -> "页面对不上"
        reason.contains("OCR_TIMEOUT", true) || reason.contains("BUTTON_NOT_FOUND", true) -> "界面未识别目标按钮或入口，请确认 QQ 处于宠物页面"
        reason.contains("WORK_ALL_CANDIDATES_REFUSED", true) -> "候选打工岗位均未达到开工条件"
        reason.startsWith("WORK_NOT_STARTED", true) -> "条件不够，没有开始打工"
        reason.contains("ENERGY_INSUFFICIENT", true) -> "宠物当前体力不足，请先喂食护理"
        reason.contains("COINS_INSUFFICIENT", true) -> "金币余额不足以支付学费或开销"
        reason.contains("COOKIE_OUT_OF_STOCK", true) || reason.contains("SOAP_OUT_OF_STOCK", true) -> "背包护理道具不足"
        reason.contains("ADVENTURE_LIMIT", true) || reason.contains("ADVENTURE_ENTRY_NOT_FOUND", true) -> "冒险入口未找到或已达今日次数上限"
        reason.startsWith("ADVENTURE_", true) -> "冒险未完成或未到结算"
        reason.startsWith("WORK_", true) -> "打工未完成"
        reason.startsWith("TOWN_EXIT", true) -> "没能回到宠物房间"
        reason.startsWith("PLAN_", true) -> "方案条件不满足"
        reason.contains("QQ_NOT_READY", true) || reason.contains("ROOM_UNCONFIRMED", true) -> "QQ 宠物未处于房间状态，请先在后台拉起 QQ"
        reason.contains("BUDGET", true) -> "今天蟹累了，明天再来"
        else -> "中途停止"
    }


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
            return RecordsSummary(null, null, 0, 0, 0, 0, 0)
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
        val workCount = records.sumOf { it.workCompleted ?: 0 }
        val adventureCount = records.sumOf { it.adventureCompleted ?: 0 }
        val signInCount = records.sumOf { it.signInCompleted ?: 0 }
        val mailboxCount = records.sumOf { it.mailboxCompleted ?: 0 }
        val luckyBagCount = records.sumOf { it.luckyBagCompleted ?: 0 }
        return RecordsSummary(
            rangeText = rangeText,
            // Time the runs took, which is not the 今日已用 figure: that one counts game minutes.
            minutesText = "运行 $minutes 分钟",
            courses = records.sumOf { it.completedRounds },
            careCount = records.sumOf { it.care?.total ?: 0 },
            pauseCount = records.sumOf { it.pauseCount },
            workCount = workCount,
            adventureCount = adventureCount,
            signInCount = signInCount,
            mailboxCount = mailboxCount,
            luckyBagCount = luckyBagCount,
        )
    }

    /**
     * Health changes across the rounds of one session, deduced from the before → after readings the
     * controller itself recorded. A round whose readings are missing contributes nothing.
     */
    fun changes(rounds: List<RoundRecord>, record: SessionRunRecord? = null): List<MetricChange> {
        val usable = rounds.filter { it.before != null && it.after != null }
        val baseChanges = if (usable.isEmpty()) {
            listOf(
                MetricChange("体力", "—", "—", null, "—", "本会话没有成对的读数", false),
                MetricChange("清洁", "—", "—", null, "—", "本会话没有成对的读数", false),
                MetricChange("心情", "—", "—", null, "—", "本会话没有成对的读数", false),
            )
        } else {
            val first = usable.first().before!!
            val last = usable.last().after!!
            listOf(
                change("体力", first.stamina, last.stamina),
                change("清洁", first.cleanliness, last.cleanliness),
                change("心情", first.mood, last.mood),
            )
        }
        val coinBefore = record?.roomCoinsBefore
        val coinAfter = record?.roomCoinsAfter
        return if (coinBefore != null && coinAfter != null) {
            baseChanges + change("金币", coinBefore, coinAfter)
        } else {
            baseChanges
        }
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
        val coinBefore = record?.roomCoinsBefore
        val coinAfter = record?.roomCoinsAfter
        if (coinBefore != null && coinAfter != null) {
            val delta = coinAfter - coinBefore
            val deltaStr = if (delta >= 0) "+$delta" else "$delta"
            out += ActionOutcome(
                label = "金币变动",
                meta = "房间顶栏实际读数 $coinBefore → $coinAfter",
                verdict = deltaStr,
                verdictTone = if (delta >= 0) VerdictTone.CONFIRMED else VerdictTone.PENDING,
            )
        } else {
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
        }
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
        val taskParts = mutableListOf<String>()
        if (record.completedRounds > 0 || record.rounds > 0) {
            taskParts += "学习 ${record.completedRounds}/${record.rounds} 节"
        }
        if ((record.workCompleted ?: 0) > 0) {
            taskParts += "打工 ${record.workCompleted} 班"
        }
        if ((record.adventureCompleted ?: 0) > 0) {
            taskParts += "冒险 ${record.adventureCompleted} 轮"
        }
        if ((record.signInCompleted ?: 0) > 0) {
            taskParts += "每日签到"
        }
        if ((record.mailboxCompleted ?: 0) > 0) {
            taskParts += "信箱待领"
        }
        if ((record.luckyBagCompleted ?: 0) > 0) {
            taskParts += "福袋 ${record.luckyBagCompleted} 次"
        }
        val taskDesc = if (taskParts.isEmpty()) "完成 ${record.completedRounds}/${record.rounds} 节" else taskParts.joinToString(" · ")
        SessionHistoryRow(
            id = ev.id,
            whenText = whenText,
            outcomeText = text,
            outcomeTone = tone,
            detail = "$taskDesc · " +
                "${record.runtimeMs / 60_000L} 分钟 · 护理 ${record.care?.total ?: 0} 次" +
                (record.stopReason?.let { " · ${stopReasonText(it)}" } ?: ""),
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
