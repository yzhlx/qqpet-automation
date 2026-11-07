package com.ynk.virtualdisplay.ui.theme

import android.content.Context

/**
 * MKA-08：外观偏好。深色主题受限于整套 UI 直接引用 PetTokens 浅色调色板（20 个文件），
 * 没有深色设计稿之前不提供切换，避免做一个只会显示错误颜色的假开关。
 * 「减少动效」是真实开关：等待指示从旋转 spinner 换成静态文字，并关闭点击涟漪。
 */
object AppearancePrefs {
    private const val PREFS_NAME = "appearance_prefs"
    private const val KEY_REDUCE_MOTION = "reduce_motion"

    fun reduceMotion(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_REDUCE_MOTION, false)

    fun setReduceMotion(context: Context, value: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_REDUCE_MOTION, value).apply()
    }
}
