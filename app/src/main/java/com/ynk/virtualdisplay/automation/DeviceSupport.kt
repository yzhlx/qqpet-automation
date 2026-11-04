package com.ynk.virtualdisplay.automation

import android.os.Build
import java.util.Locale

/**
 * 坐标和页面模板只在一台真机上量过：华为 FOA-AL00（QQ 9.3.70 / 16410）。
 *
 * 其他机型不阻止运行，但必须如实说明「没有实测」，并由页面识别兜底：
 * 认不出页面就停下，绝不盲点。判断不碰系统、不弹窗，便于单测。
 */
object DeviceSupport {
    const val CALIBRATED_MODEL = "FOA-AL00"

    fun isCalibrated(model: String? = Build.MODEL): Boolean =
        normalize(model) == CALIBRATED_MODEL

    fun modelLabel(model: String? = Build.MODEL): String {
        val trimmed = model?.trim().orEmpty()
        return if (trimmed.isEmpty()) "未知机型" else trimmed
    }

    /** 诊断页的一行：没实测过不等于不能用，所以只标「待核对」，不算失败。 */
    fun diagnosticMessage(model: String? = Build.MODEL): String =
        if (isCalibrated(model)) {
            "已实测机型（" + CALIBRATED_MODEL + "）"
        } else {
            "未实测机型（" + modelLabel(model) + "）：按钮位置按 " + CALIBRATED_MODEL +
                " 校准，认不出页面会自动停止"
        }

    fun diagnosticRemedy(model: String? = Build.MODEL): String? =
        if (isCalibrated(model)) {
            null
        } else {
            "先跑一次「健康检查」（不花金币）确认能认出宠物房间；反复识别失败时，" +
                "可在设置 → 高级 → 虚拟屏控制台里自己操作"
        }

    /** 首页提示卡：只在未实测机型出现，实测机型不留噪音。 */
    fun homeWarningTitle(model: String? = Build.MODEL): String? =
        if (isCalibrated(model)) null else "这台手机没有实测过"

    fun homeWarningBody(model: String? = Build.MODEL): String? =
        if (isCalibrated(model)) {
            null
        } else {
            "按钮位置是在 " + CALIBRATED_MODEL + " 上量的。在 " + modelLabel(model) +
                " 上会先确认画面，认不出就停下、不会盲点，也可能因此跑不完。" +
                "建议先只跑一次「健康检查」（不花金币），确认能认出宠物房间再安排学习或打工。"
        }

    private fun normalize(model: String?): String =
        model?.trim()?.uppercase(Locale.ROOT).orEmpty()
}
