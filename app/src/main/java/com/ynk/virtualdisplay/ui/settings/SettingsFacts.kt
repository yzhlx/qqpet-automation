package com.ynk.virtualdisplay.ui.settings

import com.ynk.virtualdisplay.automation.DailyBudgetStore
import com.ynk.virtualdisplay.automation.PetProfile
import com.ynk.virtualdisplay.automation.SessionPlan

/**
 * Pure mapping of the settings screen's real values.
 *
 * Every row on 设置 must be traceable to something in the app. This object collects those values
 * from the automation layer itself — [PetStudyHealthPolicy] for the readiness thresholds,
 * [DailyBudgetStore] for the daily hard stop, [PetProfile] for the calibrated display — so the screen
 * cannot drift from behaviour when one of them changes. Rows with no backing feature are reported as
 * unavailable instead of rendering a switch that does nothing.
 */

/** The readiness thresholds, read from the policy that actually enforces them. */
data class ThresholdFacts(val energy: Int, val cleanliness: Int) {
    val text get() = "$energy / $cleanliness"
}

/** What the batch limits really are. There is no single-session minutes setting in v0.1. */
data class BatchFacts(
    val minRounds: Int,
    val maxRounds: Int,
    val dailyHardStopMinutes: Int,
) {
    val roundsText get() = "$minRounds–$maxRounds 节"
    val dailyText get() = "$dailyHardStopMinutes 分钟"
}

object SettingsFacts {

    /**
     * The gate the saved plan applies before every course. Read from the plan rather than searched
     * for, because the threshold is now a setting and the two axes can differ.
     */
    fun thresholds(plan: SessionPlan): ThresholdFacts =
        ThresholdFacts(plan.energyThreshold, plan.cleanlinessThreshold)

    fun batch(plan: SessionPlan): BatchFacts = BatchFacts(
        minRounds = plan.rounds,
        maxRounds = plan.rounds,
        dailyHardStopMinutes = plan.dailyBudgetMinutes,
    )

    fun displayFacts(profile: PetProfile): String =
        "${profile.displayWidth}×${profile.displayHeight} · ${profile.displayDpi}dpi"

    /**
     * Screenshot retention: the app never deletes evidence, so the honest answer is "no limit —
     * never auto-deleted". Reporting a retention count would describe a feature that does not exist.
     */
    const val SCREENSHOT_POLICY = "不自动清理"

    /** Features the reference drew that simply do not exist yet. */
    val UNAVAILABLE_ROWS = listOf(
        "界面与外观" to "主题与字号未接入",
        "数据与备份" to "导出与备份未接入",
        "运行控制" to "后台与省电策略由系统管理",
        "使用帮助" to "新手引导未编写",
        "问题反馈" to "未接入反馈通道",
    )
}
