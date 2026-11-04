package com.ynk.virtualdisplay.automation

import android.content.Context
import java.time.LocalTime

/**
 * STR-17 夜间暂停窗口：用户自己划定的一段「别动宠物」的时间。
 *
 * 只做两件事，两件事都能被验证：
 *  - 落在窗口里的时段不接受新的托管（宁可明确拒绝，也不偷偷在夜里扣学费）；
 *  - 运行中的任务走到窗口里，会在两步之间停住，到点重新确认画面再继续。
 *
 * 窗口可以跨零点（23:00 → 07:00）。起止相同视为没有窗口，不是全天暂停。
 */
data class QuietWindow(
    val enabled: Boolean = false,
    /** 当天 00:00 起的分钟数，0..1439。 */
    val startMinute: Int = DEFAULT_START_MINUTE,
    /** 当天 00:00 起的分钟数，0..1439；小于起点表示跨到第二天。 */
    val endMinute: Int = DEFAULT_END_MINUTE,
) {
    init {
        require(startMinute in MINUTE_RANGE) { "QUIET_WINDOW_START_OUT_OF_RANGE:$startMinute" }
        require(endMinute in MINUTE_RANGE) { "QUIET_WINDOW_END_OUT_OF_RANGE:$endMinute" }
    }

    fun startText(): String = formatMinute(startMinute)
    fun endText(): String = formatMinute(endMinute)

    /** 跨零点的窗口要写清楚「到第二天」。 */
    fun spansMidnight(): Boolean = enabled && startMinute != endMinute && startMinute > endMinute

    companion object {
        const val DEFAULT_START_MINUTE = 23 * 60
        const val DEFAULT_END_MINUTE = 7 * 60
        val MINUTE_RANGE = 0..1439
        fun formatMinute(minute: Int): String = "%02d:%02d".format(minute / 60, minute % 60)
    }
}

/** 判定是纯函数：只有时间本身能回答，不依赖设备和界面。 */
object QuietWindowPolicy {
    /** 现在是否落在窗口内。 */
    fun isQuiet(nowMinute: Int, window: QuietWindow): Boolean {
        if (!window.enabled) return false
        if (window.startMinute == window.endMinute) return false
        val start = window.startMinute
        val end = window.endMinute
        return if (start < end) nowMinute in start until end
        else nowMinute >= start || nowMinute < end
    }

    /** 距离窗口结束还有几分钟；不在窗口内返回 0。 */
    fun minutesUntilEnd(nowMinute: Int, window: QuietWindow): Int {
        if (!isQuiet(nowMinute, window)) return 0
        val delta = window.endMinute - nowMinute
        return if (delta > 0) delta else delta + 1440
    }

    fun nowMinute(time: LocalTime): Int = time.hour * 60 + time.minute
}

/**
 * 落盘用 SharedPreferences：只有一个开关和两个整数，没有需要原子提交的理由。
 * 读到的分钟数一律夹到合法范围，坏值不能让 App 起不来。
 */
class QuietWindowStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): QuietWindow = QuietWindow(
        enabled = prefs.getBoolean(KEY_ENABLED, false),
        startMinute = prefs.getInt(KEY_START, QuietWindow.DEFAULT_START_MINUTE)
            .coerceIn(QuietWindow.MINUTE_RANGE),
        endMinute = prefs.getInt(KEY_END, QuietWindow.DEFAULT_END_MINUTE)
            .coerceIn(QuietWindow.MINUTE_RANGE),
    )

    fun save(window: QuietWindow) {
        prefs.edit()
            .putBoolean(KEY_ENABLED, window.enabled)
            .putInt(KEY_START, window.startMinute)
            .putInt(KEY_END, window.endMinute)
            .apply()
    }

    companion object {
        private const val PREFS_NAME = "pet_quiet_window"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_START = "startMinute"
        private const val KEY_END = "endMinute"
    }
}

/**
 * 会话开始前的判定：夜间暂停窗口里到底放不放行。
 *
 * 单独抽出来是为了能测：这台设备没有 Shizuku，托管根本起不来，端到端演示不了「夜里被拦下」
 * 这条路，纯函数至少能证明拦下时给用户看的是哪句话。
 */
object SessionStartGate {
    sealed interface Decision {
        /** 放行。 */
        data object Proceed : Decision
        /** 拦下，[message] 直接给用户看。 */
        data class Refused(val message: String) : Decision
    }

    fun evaluate(window: QuietWindow, nowMinute: Int, mode: SessionMode): Decision {
        // 健康检查只读不动作，用户明确点了就照做。
        if (mode == SessionMode.HEALTH_CHECK) return Decision.Proceed
        if (!QuietWindowPolicy.isQuiet(nowMinute, window)) return Decision.Proceed
        val until = if (window.spansMidnight()) "到第二天 " + window.endText() else "到 " + window.endText()
        return Decision.Refused("现在是夜间暂停时段（" + window.startText() + "–" + window.endText() + "），" + until + "再开始托管。")
    }
}
