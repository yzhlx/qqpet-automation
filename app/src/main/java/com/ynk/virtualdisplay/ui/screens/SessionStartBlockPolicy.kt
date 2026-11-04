package com.ynk.virtualdisplay.ui.screens

import com.ynk.virtualdisplay.automation.DailyBudgetStore
import com.ynk.virtualdisplay.automation.PendingActionDecision
import com.ynk.virtualdisplay.automation.PendingActionGuard
import com.ynk.virtualdisplay.automation.PlanStepType
import com.ynk.virtualdisplay.automation.PlanV2
import com.ynk.virtualdisplay.automation.QuietWindow
import com.ynk.virtualdisplay.automation.QuietWindowPolicy
import com.ynk.virtualdisplay.automation.SessionStartGate
import java.time.LocalTime

/**
 * 后台按下「开始托管」之前的一次判定：这一次到底能不能开始。
 *
 * 两个闸门都复用会话自己那套说法，后台和服务里给用户看的是同一句：
 *  - 上一次被中断、结果未知的动作还在悬着：先把「做没做」确认掉；
 *  - 现在落在夜间暂停窗口里：按窗口自己的提示拒绝开始。
 *
 * 判定的输入都来自外面传进来的函数，这个文件本身不碰 Android，整条规则才能直接单测。
 */
object SessionStartBlockPolicy {
    sealed interface Decision {
        /** 放行。 */
        data object Proceed : Decision
        /** 拦下，[reason] 记日志，[message] 直接给用户看。 */
        data class Refused(val reason: String, val message: String) : Decision
    }

    /** [pendingActionLabel] 只是给动作一个中文名：拒绝提示里不能说英文代号。 */
    fun evaluate(
        budget: () -> DailyBudgetStore,
        quietWindow: () -> QuietWindow,
        now: () -> LocalTime,
        plan: PlanV2?,
    ): Decision {
        val pending = PendingActionGuard.pendingActions(budget().attempts())
        val types = plan?.steps?.map { it.type }?.toSet() ?: emptySet()
        val blocked = types.flatMap { type ->
            when (val gate = PendingActionGuard.decide(pending, type)) {
                is PendingActionDecision.NeedsConfirmation -> gate.actions
                PendingActionDecision.Proceed -> emptyList()
            }
        }
        if (blocked.isNotEmpty()) {
            val names = blocked.map { pendingActionLabel(it.stepType) }.distinct().joinToString("、")
            return Decision.Refused("PENDING_ACTION_UNCONFIRMED",
                "上一次" + names + "被中断，是否已扣费或已领取无法确认。为避免重复执行，请先在「运行方案」页确认。")
        }
        val window = quietWindow()
        return when (val gate = SessionStartGate.evaluate(
            window, QuietWindowPolicy.nowMinute(now()),
            if (plan == null) com.ynk.virtualdisplay.automation.SessionMode.FULL
            else com.ynk.virtualdisplay.automation.SessionMode.PLAN)) {
            is SessionStartGate.Decision.Refused -> Decision.Refused("QUIET_WINDOW_ACTIVE", gate.message)
            SessionStartGate.Decision.Proceed -> Decision.Proceed
        }
    }

    private fun pendingActionLabel(type: PlanStepType): String = when (type) {
        PlanStepType.STUDY -> "学习"
        PlanStepType.SIGN_IN -> "签到"
        PlanStepType.MAILBOX -> "信箱"
        PlanStepType.LUCKY_BAG -> "福袋"
        else -> type.name
    }
}
