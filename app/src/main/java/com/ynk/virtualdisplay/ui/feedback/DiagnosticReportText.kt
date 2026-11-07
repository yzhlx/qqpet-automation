package com.ynk.virtualdisplay.ui.feedback

import com.ynk.virtualdisplay.automation.DiagnosticItem
import com.ynk.virtualdisplay.automation.DiagnosticLevel
import com.ynk.virtualdisplay.automation.DiagnosticReport
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 把分层诊断结果整理成可查看、可复制的纯文本。
 *
 * 只写设备型号、系统版本、App 版本和各层检测状态；不读取也不写入 QQ 昵称、账号、
 * 聊天内容、配对码、截图或日志正文。分享前用户能先看到这段文本。
 */
object DiagnosticReportText {

    private val GROUPS = listOf(
        DiagnosticLevel.BASE to "基础状态",
        DiagnosticLevel.CAPABILITY to "实际能力",
        DiagnosticLevel.TARGET_APP to "QQ 状态（房间在开始任务时核对）",
    )

    fun build(
        report: DiagnosticReport,
        appVersion: String,
        gitHash: String,
        createdAt: Long = System.currentTimeMillis(),
    ): String = buildString {
        appendLine("蟹啦运行环境诊断报告")
        appendLine("时间：" + SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(createdAt)))
        appendLine("App：" + appVersion + " (" + gitHash.take(7) + ")")
        appendLine("设备：" + report.manufacturer + " · " + report.androidVersion)
        appendLine("结论：" + verdict(report))
        if (report.capabilityChecksReused) {
            appendLine("说明：本次沿用上次已通过的能力检测，未重复创建测试屏幕。")
        }
        for ((level, label) in GROUPS) {
            val items = report.items.filter { it.level == level }
            if (items.isEmpty()) continue
            appendLine()
            appendLine(label)
            items.forEach { append(describe(it)) }
        }
        appendLine()
        appendLine("说明：本报告只含设备与检测状态，不含账号、聊天、配对码、截图或日志正文。")
    }

    private fun verdict(report: DiagnosticReport): String {
        val failures = report.items.count { !it.passed && !it.unknown }
        val unknown = report.items.count { it.unknown }
        return when {
            failures > 0 -> "还有 " + failures + " 项需要处理，按每项的「下一步」操作。"
            unknown > 0 -> "没有发现阻塞项，" + unknown + " 项需要开始任务时核对。"
            else -> "各层检测均正常，可以开始托管。"
        }
    }

    private fun describe(item: DiagnosticItem): String = buildString {
        val status = when {
            item.unknown -> "待核对"
            item.passed -> "正常"
            else -> "异常"
        }
        appendLine("- [" + status + "] " + item.title + "：" + item.message)
        if ((!item.passed || item.unknown) && item.remedy != null) {
            appendLine("  下一步：" + item.remedy)
        }
    }
}
