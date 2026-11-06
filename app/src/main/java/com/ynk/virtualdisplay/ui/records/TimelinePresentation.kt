package com.ynk.virtualdisplay.ui.records

import com.ynk.virtualdisplay.ui.data.JournalEvent
import com.ynk.virtualdisplay.ui.data.SessionEvidence

/**
 * OBS-05：今天发生过什么，按时间倒序。
 *
 * 只使用两类已经落盘的事实：会话的 run-record（开始 / 结束）和会话事件日志里
 * COURSE_STARTED / LEARNING_CONFIRMED / ERROR 三种记录。打工、领取的完成次数没有
 * 单独时间戳，就不在这个列表里编一个时间出来。
 */
data class TimelineRow(
    val timeText: String,
    val label: String,
    val detail: String,
    val tone: VerdictTone,
    /** 这一行自己的状态词，避免把「开始托管」也标成进行中。 */
    val chip: String,
)

object TimelinePresentation {

    fun rows(
        sessions: List<SessionEvidence>,
        events: List<JournalEvent>,
        timeFormatter: (Long) -> String,
        limit: Int = 40,
    ): List<TimelineRow> {
        val stamped = mutableListOf<Pair<Long, TimelineRow>>()

        sessions.forEach { evidence ->
            val record = evidence.record ?: return@forEach
            val started = record.startedAtMs ?: return@forEach
            stamped += started to TimelineRow(
                timeText = timeFormatter(started),
                label = "开始托管",
                detail = if (record.rounds > 0) "计划 " + record.rounds + " 节" else "本次方案已启动",
                tone = VerdictTone.UNKNOWN,
                chip = "开始",
            )
            val outcome = when (record.terminalState) {
                "COMPLETED" -> "已完成" to VerdictTone.CONFIRMED
                "STOPPED" -> "已停止" to VerdictTone.UNKNOWN
                "FAILED" -> "失败" to VerdictTone.PENDING
                else -> record.terminalState to VerdictTone.UNKNOWN
            }
            val end = started + record.runtimeMs
            stamped += end to TimelineRow(
                timeText = timeFormatter(end),
                label = "本轮结束",
                detail = buildString {
                    append(outcome.first)
                    record.stopReason?.let { reason ->
                        append(" · ")
                        append(RecordsPresentation.stopReasonText(reason))
                    }
                },
                tone = outcome.second,
                chip = outcome.first,
            )
        }

        events.forEach { event ->
            val row = when (event.kind) {
                "COURSE_STARTED" -> TimelineRow(
                    timeText = timeFormatter(event.wallTimeMs),
                    label = "开始上课",
                    detail = listOfNotNull(
                        event.payload["round"]?.let { "第 " + it + " 节" },
                        event.payload["course"]?.removePrefix("study:"),
                        event.payload["durationMinutes"]?.let { "约 " + it + " 分钟" },
                    ).joinToString(" · "),
                    tone = VerdictTone.UNKNOWN,
                    // A row is a moment, not a live state: "进行中" stayed on screen long after the course ended.
                    chip = "已开始",
                )
                "ADVENTURE_COMPLETED" -> TimelineRow(
                    timeText = timeFormatter(event.wallTimeMs),
                    label = "冒险完成",
                    detail = listOfNotNull(
                        event.payload["round"]?.let { "第 " + it + " 轮" },
                        event.payload["mode"],
                        event.payload["summary"]?.takeIf { it.isNotBlank() && it != "冒险完成" },
                    ).joinToString(" · "),
                    tone = VerdictTone.CONFIRMED,
                    chip = "已完成",
                )
                "ADVENTURE_PENDING" -> TimelineRow(
                    timeText = timeFormatter(event.wallTimeMs),
                    label = "宠物出发冒险",
                    detail = listOfNotNull(
                        event.payload["round"]?.let { "第 " + it + " 轮" },
                        event.payload["mode"],
                        "结果回游戏里看",
                    ).joinToString(" · "),
                    tone = VerdictTone.PENDING,
                    chip = "待确认",
                )
                "LEARNING_CONFIRMED" -> TimelineRow(
                    timeText = timeFormatter(event.wallTimeMs),
                    label = "学习已确认",
                    detail = event.payload["round"]?.let { "第 " + it + " 节" } ?: "课程页已确认",
                    tone = VerdictTone.CONFIRMED,
                    chip = "已确认",
                )
                "ERROR" -> {
                    val reason = event.payload["reason"].orEmpty()
                    val translated = RecordsPresentation.stopReasonText(reason)
                    TimelineRow(
                        timeText = timeFormatter(event.wallTimeMs),
                        label = "这一步没完成",
                        detail = if (translated == "中途停止") "没有可确认的结果，已按安全规则停下"
                        else translated,
                        tone = VerdictTone.PENDING,
                        chip = "待确认",
                    )
                }
                else -> null
            }
            if (row != null) stamped += event.wallTimeMs to row
        }

        return collapseRepeats(stamped.sortedByDescending { it.first }.map { it.second }).take(limit)
    }

    /**
     * The same unfinished step is recorded once per interrupted attempt, so a few of them left over from
     * earlier runs showed up as identical lines. Identical neighbours become one line with a count.
     */
    internal fun collapseRepeats(rows: List<TimelineRow>): List<TimelineRow> {
        val out = mutableListOf<TimelineRow>()
        var run = 0
        for (row in rows) {
            val last = out.lastOrNull()
            if (last != null && last.label == row.label && last.detail.removeCountSuffix() == row.detail &&
                last.chip == row.chip && last.timeText == row.timeText
            ) {
                run++
                out[out.lastIndex] = last.copy(detail = row.detail + "（共 " + (run + 1) + " 次）")
            } else {
                run = 0
                out += row
            }
        }
        return out
    }

    private val COUNT_SUFFIX = Regex("""（共 \d+ 次）$""")

    private fun String.removeCountSuffix(): String = replace(COUNT_SUFFIX, "")
}