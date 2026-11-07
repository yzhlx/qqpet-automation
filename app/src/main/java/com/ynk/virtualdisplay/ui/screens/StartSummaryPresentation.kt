package com.ynk.virtualdisplay.ui.screens

import com.ynk.virtualdisplay.automation.AdventureStep
import com.ynk.virtualdisplay.automation.CareStep
import com.ynk.virtualdisplay.automation.CourseCatalog
import com.ynk.virtualdisplay.automation.LuckyBagStep
import com.ynk.virtualdisplay.automation.MailboxStep
import com.ynk.virtualdisplay.automation.PlanV2
import com.ynk.virtualdisplay.automation.PlanStep
import com.ynk.virtualdisplay.automation.RewardConfidence
import com.ynk.virtualdisplay.automation.SignInStep
import com.ynk.virtualdisplay.automation.StudyStep
import com.ynk.virtualdisplay.automation.WorkCatalog
import com.ynk.virtualdisplay.automation.WorkStep
import com.ynk.virtualdisplay.automation.WorkTrigger

/** 开始前摘要的一行：左侧是分类，右侧是能从本机资料确定的内容。 */
data class StartSummaryLine(val label: String, val value: String)

/**
 * 开始托管前的摘要（引导清单第八节）：动作顺序、能算出的预计占用、已知消耗。
 *
 * 只使用方案、已扫描课程目录和岗位目录里真实存在的值：
 *  - 课程时长要扫过才写；没扫到就说「未读取」，不按固定 10 分钟猜；
 *  - 学费只在课程页读到，这里不写数字，避免把估算当成余额；
 *  - 冒险与每日领取的等待时间无法预读，明确说「未计入」。
 */
object StartSummaryPresentation {

    fun lines(
        plan: PlanV2,
        catalog: CourseCatalog?,
        budgetMinutes: Int,
        workCatalog: WorkCatalog = WorkCatalog(),
    ): List<StartSummaryLine> {
        if (plan.steps.isEmpty()) return emptyList()
        return listOf(
            StartSummaryLine("执行顺序", plan.steps.joinToString(" → ") { describe(it, catalog, workCatalog) }),
            StartSummaryLine("预计占用", durationText(plan, catalog, workCatalog)),
            StartSummaryLine("已知消耗", resourceText(plan, workCatalog, budgetMinutes)),
        )
    }

    private fun workEntry(step: WorkStep, work: WorkCatalog) =
        work.all().firstOrNull { it.id == step.workOptionId }

    private fun courseName(id: String, catalog: CourseCatalog?): String =
        catalog?.find(id)?.name ?: id.substringAfter(':')

    private fun describe(step: PlanStep, catalog: CourseCatalog?, work: WorkCatalog): String = when (step) {
        is SignInStep -> "签到"
        is MailboxStep -> "信箱"
        is LuckyBagStep -> "福袋（最多 " + step.maxDailyCount + " 次）"
        is WorkStep -> workEntry(step, work)
            ?.let { "打工：" + it.optionLabel + "（" + it.durationMinutes + " 分钟）" }
            ?: "打工"
        is CareStep -> "护理（体力≥" + step.energyThreshold + " / 清洁≥" + step.cleanlinessThreshold + "）"
        is StudyStep -> "学习 " + step.rounds + " 节（" +
            step.courseIds.joinToString("、") { courseName(it, catalog) } + "）"
        is AdventureStep -> "冒险 " + step.rounds + " 轮"
    }

    /** 一轮一节的真实课表：均衡时按轮换顺序取课，和 STR-09 的执行顺序一致。 */
    private fun studyMinutes(step: StudyStep, catalog: CourseCatalog?): Int? {
        val ids = if (step.rotation.isEmpty()) List(step.rounds) { step.courseId }
        else List(step.rounds) { index -> step.rotation[index % step.rotation.size].courseId }
        var total = 0
        for (id in ids) {
            total += catalog?.find(id)?.durationMinutes ?: return null
        }
        return total
    }

    private fun durationText(plan: PlanV2, catalog: CourseCatalog?, work: WorkCatalog): String {
        var minutes = 0
        var studyUnknown = false
        var otherUnknown = false
        var conditionalWork = false
        plan.steps.forEach { step ->
            when (step) {
                is StudyStep -> studyMinutes(step, catalog)?.let { minutes += it } ?: run { studyUnknown = true }
                is WorkStep -> if (step.trigger == WorkTrigger.ALWAYS) {
                    workEntry(step, work)?.let { minutes += it.durationMinutes } ?: run { otherUnknown = true }
                } else conditionalWork = true
                // 护理是几次点击，没有可预读的固定时长。
                is CareStep -> Unit
                is AdventureStep, is SignInStep, is MailboxStep, is LuckyBagStep -> otherUnknown = true
            }
        }
        val known = when {
            studyUnknown -> "学习时长未读取，暂不给出总计"
            minutes > 0 -> "约 " + minutes + " 分钟"
            else -> "预计时长未知"
        }
        val caveats = buildList {
            if (studyUnknown) add("课程时长以课程页为准")
            if (otherUnknown) add("领取与冒险的等待时间未计入")
            if (conditionalWork) add("缺钱时的打工未计入")
        }
        return if (caveats.isEmpty()) known else known + "（" + caveats.joinToString("；") + "）"
    }

    private fun resourceText(plan: PlanV2, work: WorkCatalog, budgetMinutes: Int): String {
        val parts = mutableListOf<String>()
        plan.steps.filterIsInstance<CareStep>().firstOrNull()?.let {
            parts += "护理阈值 体力≥" + it.energyThreshold + " / 清洁≥" + it.cleanlinessThreshold
        }
        plan.steps.filterIsInstance<WorkStep>().forEach { step ->
            workEntry(step, work)?.let { entry ->
                val reward = if (entry.confidence == RewardConfidence.VERIFIED)
                    "，回报 +" + entry.coinReward + " 金币（已确认）" else "，回报未确认"
                parts += "打工耗体力 " + entry.energyCost + " / 清洁 " + entry.cleanlinessCost + reward
            }
        }
        parts += "共用每日预算 ≤ " + budgetMinutes + " 分钟"
        parts += "学费按课程页显示，未读取时不显示数字"
        return parts.joinToString(" · ")
    }
}