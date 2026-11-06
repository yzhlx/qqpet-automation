package com.ynk.virtualdisplay.ui

import com.ynk.virtualdisplay.automation.SkipReason
import com.ynk.virtualdisplay.automation.StrategyExplanation

/** 一条策略说明的语气：做成的、跳过的、没做的。 */
enum class StrategyLineTone { DONE, SKIPPED, NOT_DONE }

/** 给用户看的一行策略说明。 */
data class StrategyDisplayLine(val text: String, val tone: StrategyLineTone)

/**
 * STR-16 呈现层：把 [StrategyExplanation.Action]（事实）翻译成给用户看的短句。
 *
 * 纯 JVM，无 Android 依赖，可单测。翻译不出来的原因码原样显示，不编造解释。
 */
object StrategyPresentation {

    /** 每个动作一行：做了什么、为什么没做。 */
    fun lines(actions: List<StrategyExplanation.Action>): List<StrategyDisplayLine> = actions.map { action ->
        val tone = when (action.outcome) {
            StrategyExplanation.Outcome.COMPLETED -> StrategyLineTone.DONE
            StrategyExplanation.Outcome.SKIPPED -> StrategyLineTone.SKIPPED
            else -> StrategyLineTone.NOT_DONE
        }
        StrategyDisplayLine(sentence(action), tone)
    }

    /**
     * 一行摘要：做成了什么、跳过了什么、哪些没跑到。
     * 没勾选的动作不进摘要，它们只在逐条说明里出现。
     */
    fun summary(actions: List<StrategyExplanation.Action>): String {
        val done = actions.filter { it.outcome == StrategyExplanation.Outcome.COMPLETED }.map { it.label }
        val skipped = actions.filter { it.outcome == StrategyExplanation.Outcome.SKIPPED }.map { it.label }
        val blocked = actions.filter {
            it.outcome == StrategyExplanation.Outcome.BLOCKED ||
                it.outcome == StrategyExplanation.Outcome.FAILED ||
                it.outcome == StrategyExplanation.Outcome.UNCONFIRMED
        }.map { it.label }
        val notRun = actions.filter { it.outcome == StrategyExplanation.Outcome.NOT_EXECUTED }.map { it.label }
        val parts = mutableListOf<String>()
        if (done.isNotEmpty()) parts.add("执行：" + done.joinToString("、"))
        if (skipped.isNotEmpty()) parts.add("跳过：" + skipped.joinToString("、"))
        if (blocked.isNotEmpty()) parts.add("受阻：" + blocked.joinToString("、"))
        if (notRun.isNotEmpty()) parts.add("未执行：" + notRun.joinToString("、"))
        return if (parts.isEmpty()) "本轮没有可展示的策略说明" else parts.joinToString(" · ")
    }

    private fun sentence(action: StrategyExplanation.Action): String = when (action.outcome) {
        StrategyExplanation.Outcome.COMPLETED -> action.label + "：本轮已执行完成"
        StrategyExplanation.Outcome.SKIPPED -> action.label + "：跳过（" + skipText(action.reason) + "）"
        StrategyExplanation.Outcome.BLOCKED -> action.label + "：被安全阻断（" + reasonText(action.reason) + "）" + transferText(action)
        StrategyExplanation.Outcome.FAILED -> action.label + "：执行失败（" + reasonText(action.reason) + "）" + transferText(action)
        StrategyExplanation.Outcome.UNCONFIRMED -> action.label + "：结果无法确认（" + reasonText(action.reason) + "）" + transferText(action)
        StrategyExplanation.Outcome.NOT_SELECTED -> action.label + "：本方案未勾选"
        StrategyExplanation.Outcome.NOT_EXECUTED -> action.label + "：本轮没有执行（" + reasonText(action.reason) + "）"
    }

    private fun skipText(reason: String?): String = when (reason) {
        SkipReason.FUNDS_SUFFICIENT.name -> "金币已经够，不必先打工"
        SkipReason.ALREADY_SATISFIED.name -> "已经满足条件，不用重复做"
        SkipReason.DAILY_LIMIT_REACHED.name -> "已达今日次数上限"
        SkipReason.UNAVAILABLE.name -> "当前不可用或没认出来，已安全跳过"
        null -> "没有记录原因"
        else -> reason
    }

    /** STR-01/STR-02：本轮因为这个动作确定失败而改做了别的动作。 */
    private fun transferText(action: StrategyExplanation.Action): String =
        if (action.transferredTo == null) "" else "，已转到" + action.transferredTo

    /** 认不出来的原因码原样显示，不编造解释。 */
    private fun reasonText(reason: String?): String {
        if (reason == null) return "没有记录原因"
        return when {
            reason == StrategyExplanation.NOT_STARTED -> reason
            reason == StrategyExplanation.STOPPED_BY_PREVIOUS -> reason
            reason.contains("ENERGY_INSUFFICIENT", true) -> "体力不足"
            reason.contains("CLEANLINESS_INSUFFICIENT", true) -> "清洁不足"
            reason.contains("COINS_INSUFFICIENT", true) -> "金币不够"
            reason.contains("COIN_FLOOR_BREACH", true) -> "会跌破金币底线"
            reason.contains("COIN_SPEND_DAILY_CAP", true) -> "会超过每日学习花费上限"
            reason.contains("DAILY_BUDGET_LIMIT", true) -> "今日时长预算已用完"
            reason.contains("COURSE_SUFFIX", true) -> "课程结算没有确认"
            reason.contains("PAGE_MISMATCH", true) -> "页面对不上"
            reason.startsWith("ENTER_PET_NO_HISTORY_WORD", true) -> "QQ 最近搜索里没有「宠物」"
            reason.startsWith("ENTER_PET_", true) || reason.contains("INITIAL_PAGE_UNKNOWN", true) -> "没能进入宠物房间"
            reason.contains("OCR_TIMEOUT", true) || reason.contains("BUTTON_NOT_FOUND", true) -> "没找到界面上的目标"
            reason.contains("PERMISSION_DENIED", true) || reason.contains("SHIZUKU", true) -> "系统授权不足"
            reason.contains("USER_STOP", true) || reason.contains("MANUAL_PAUSE", true) -> "你停掉了这一轮"
            reason.contains("NO_EXECUTOR_FOR", true) -> "这个动作还没有可用的实现"
            reason.contains("WORK_", true) -> "打工没有完成"
            reason.contains("ADVENTURE_", true) -> "冒险没有完成"
            else -> reason
        }
    }
}
