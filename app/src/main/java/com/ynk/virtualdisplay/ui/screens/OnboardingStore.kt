package com.ynk.virtualdisplay.ui.screens

import android.content.Context
import android.content.SharedPreferences

data class OnboardingProgress(
    val welcomed: Boolean = false,
    val setAsideSteps: Set<OnboardingStep> = emptySet(),
    val completed: Boolean = false,
    val previewMode: Boolean = false,
    val userConfirmedKeys: Set<String> = emptySet(),
)

/**
 * 新手引导与授权设置的本地持久化。
 * 确保退出、重开和进程被系统回收后保留进度与用户跳过的可选权限，不要求重新跑首次教程。
 */
class OnboardingStore(private val prefs: SharedPreferences) {
    constructor(context: Context) : this(context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE))

    fun load(): OnboardingProgress {
        val completed = prefs.getBoolean(KEY_COMPLETED, false)
        val welcomed = prefs.getBoolean(KEY_WELCOMED, completed)
        val preview = prefs.getBoolean(KEY_PREVIEW, false)
        val setAsideRaw = prefs.getStringSet(KEY_SET_ASIDE, emptySet()) ?: emptySet()
        val setAside = setAsideRaw.mapNotNull { name ->
            runCatching { OnboardingStep.valueOf(name) }.getOrNull()
        }.toSet()
        val confirmed = prefs.getStringSet(KEY_CONFIRMED, emptySet()) ?: emptySet()
        return OnboardingProgress(
            welcomed = welcomed,
            setAsideSteps = setAside,
            completed = completed,
            previewMode = preview,
            userConfirmedKeys = confirmed,
        )
    }

    fun saveWelcomed(welcomed: Boolean) {
        prefs.edit().putBoolean(KEY_WELCOMED, welcomed).apply()
    }

    fun saveSetAside(steps: Set<OnboardingStep>) {
        val raw = steps.map { it.name }.toSet()
        prefs.edit().putStringSet(KEY_SET_ASIDE, raw).apply()
    }

    fun setAside(step: OnboardingStep) {
        val current = load().setAsideSteps + step
        saveSetAside(current)
    }

    fun saveCompleted(completed: Boolean) {
        prefs.edit()
            .putBoolean(KEY_COMPLETED, completed)
            .putBoolean(KEY_WELCOMED, true)
            .putBoolean(KEY_PREVIEW, false)
            .apply()
    }

    fun savePreviewMode(preview: Boolean) {
        prefs.edit().putBoolean(KEY_PREVIEW, preview).apply()
    }

    fun confirmKey(key: String) {
        val current = load().userConfirmedKeys + key
        prefs.edit().putStringSet(KEY_CONFIRMED, current).apply()
    }

    fun reset() {
        prefs.edit()
            .remove(KEY_COMPLETED)
            .remove(KEY_WELCOMED)
            .remove(KEY_PREVIEW)
            .remove(KEY_SET_ASIDE)
            .remove(KEY_CONFIRMED)
            .apply()
    }

    companion object {
        const val PREFS_NAME = "virtual_display_settings"
        private const val KEY_COMPLETED = "onboarding_completed"
        private const val KEY_WELCOMED = "onboarding_welcomed"
        private const val KEY_PREVIEW = "onboarding_preview_mode"
        private const val KEY_SET_ASIDE = "onboarding_set_aside"
        private const val KEY_CONFIRMED = "onboarding_confirmed_keys"
    }
}
