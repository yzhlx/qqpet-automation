package com.ynk.virtualdisplay.automation

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * STR-08「今日停止线」：今天最多再上几节学习课（纯函数，JVM 可测）。
 *
 * 只认已确认完成的节数：没读到结果的课不计入（产品约束：未知不当作成功）。
 * 停止线为 null 表示不启用本条限制，方案照旧按自己排的节数执行。
 *
 * 统计口径是「今天所有会话里已确认的学习节数」，跨方案累计：
 * 停止线保护的是今天的学费支出总量，换一个属性重新开课不会把今天的上限清零。
 */
object StudyDailyCapPolicy {

    /** 停止线的取值上限，和冒险的每日次数上限一致。 */
    const val MAX_CAP = 50

    /** 一条会话记录里可用于统计的学习事实。 */
    data class ConfirmedRecord(
        val startedAtMs: Long,
        val roundsCompleted: Int,
        val targetAttribute: String?,
    )

    /**
     * 本次方案还能上几节。
     *
     * @param confirmedToday 今日已确认的学习节数
     * @param cap 今日停止线（节）；null 表示不启用
     * @param plannedRounds 方案自己排的节数
     */
    fun allowedRounds(confirmedToday: Int, cap: Int?, plannedRounds: Int): Int {
        val remaining = cap?.minus(confirmedToday.coerceAtLeast(0)) ?: return plannedRounds.coerceAtLeast(0)
        return maxOf(0, minOf(plannedRounds.coerceAtLeast(0), remaining))
    }

    /** 今日（本地日期）已确认的学习节数；只统计当天、只统计有记录的节数。 */
    fun sumConfirmedToday(records: List<ConfirmedRecord>, dayKey: String): Int {
        val format = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        return records.filter { format.format(Date(it.startedAtMs)) == dayKey }
            .sumOf { it.roundsCompleted.coerceAtLeast(0) }
    }

    /** 本地日期键，和会话记录里的 startedAt 用同一套口径。 */
    fun dayKey(ms: Long): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(ms))
}
