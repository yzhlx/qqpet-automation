package com.ynk.virtualdisplay

import com.ynk.virtualdisplay.automation.DiagnosticItem
import com.ynk.virtualdisplay.automation.DiagnosticLevel
import com.ynk.virtualdisplay.automation.DiagnosticReport
import com.ynk.virtualdisplay.ui.feedback.DiagnosticReportText
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticReportTextTest {

    private fun report(
        items: List<DiagnosticItem>,
        reused: Boolean = false,
    ) = DiagnosticReport(
        timestamp = 0L,
        androidVersion = "Android 13 (API 33)",
        manufacturer = "HUAWEI FOA-AL00",
        items = items,
        capabilityChecksReused = reused,
    )

    private val baseOk = DiagnosticItem(
        level = DiagnosticLevel.BASE,
        title = "Shizuku 授权状态",
        passed = true,
        message = "已授权",
    )
    private val failedShizuku = DiagnosticItem(
        level = DiagnosticLevel.BASE,
        title = "Shizuku 运行状态",
        passed = false,
        message = "服务未启动",
        remedy = "打开 Shizuku 点「启动」",
    )
    private val unknownRoom = DiagnosticItem(
        level = DiagnosticLevel.TARGET_APP,
        title = "QQ 后台运行状态",
        passed = true,
        message = "QQ 已在后台虚拟屏中启动",
        remedy = "开始任务前会再识别一次宠物房间",
        unknown = true,
    )

    @Test
    fun reportCarriesDeviceVersionAndFailureRemedy() {
        val text = DiagnosticReportText.build(
            report(listOf(baseOk, failedShizuku)),
            appVersion = "1.0.146",
            gitHash = "3cc5b02c0ffee",
        )

        assertTrue(text.contains("蟹啦运行环境诊断报告"))
        assertTrue(text.contains("App：1.0.146 (3cc5b02)"))
        assertTrue(text.contains("设备：HUAWEI FOA-AL00 · Android 13 (API 33)"))
        assertTrue(text.contains("- [正常] Shizuku 授权状态：已授权"))
        assertTrue(text.contains("- [异常] Shizuku 运行状态：服务未启动"))
        assertTrue(text.contains("下一步：打开 Shizuku 点「启动」"))
        assertTrue(text.contains("还有 1 项需要处理"))
        assertTrue(text.contains("不含账号、聊天、配对码、截图或日志正文"))
        assertFalse(text.contains("昵称"))
    }

    @Test
    fun unknownItemIsPendingNotFailure() {
        val text = DiagnosticReportText.build(
            report(listOf(baseOk, unknownRoom)),
            appVersion = "1.0.146",
            gitHash = "3cc5b02",
        )

        assertTrue(text.contains("- [待核对] QQ 后台运行状态"))
        assertTrue(text.contains("下一步：开始任务前会再识别一次宠物房间"))
        assertTrue(text.contains("没有发现阻塞项，1 项需要开始任务时核对"))
        assertFalse(text.contains("异常"))
    }

    @Test
    fun reusedCapabilityChecksAreExplained() {
        val text = DiagnosticReportText.build(
            report(listOf(baseOk), reused = true),
            appVersion = "1.0.146",
            gitHash = "3cc5b02",
        )

        assertTrue(text.contains("沿用上次已通过的能力检测"))
        assertTrue(text.contains("各层检测均正常，可以开始托管"))
    }
}
