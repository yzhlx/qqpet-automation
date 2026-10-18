package com.ynk.virtualdisplay.automation

import android.content.Context
import java.time.LocalDate

/**
 * MVP tracks only courses completed by this app. Manual learning/work is NOT visible here.
 * Therefore the 8h guard is exact only when the pet has no outside learning/work that day.
 */
class DailyBudgetStore(context: Context) {
    private val prefs = context.getSharedPreferences("pet_mvp_budget", Context.MODE_PRIVATE)

    fun usedMinutes(): Int {
        rolloverIfNeeded()
        return prefs.getInt(KEY_MINUTES, 0)
    }

    fun addCourse(minutes: Int) {
        require(minutes > 0)
        rolloverIfNeeded()
        prefs.edit().putInt(KEY_MINUTES, usedMinutes() + minutes).apply()
    }

    /**
     * True when another activity of [minutes] would stay inside the allowance. The game's own
     * [HARD_STOP_MINUTES] always applies; [limitMinutes] can only tighten it, which is how a
     * [SessionPlan] asks the app to stop earlier than the game would.
     */
    fun canAfford(minutes: Int, limitMinutes: Int = HARD_STOP_MINUTES): Boolean {
        require(minutes > 0)
        return usedMinutes() + minutes <= minOf(limitMinutes, HARD_STOP_MINUTES)
    }

    fun resetToday() {
        prefs.edit().putString(KEY_DAY, LocalDate.now().toString()).putInt(KEY_MINUTES, 0).apply()
    }

    private fun rolloverIfNeeded() {
        val today = LocalDate.now().toString()
        if (prefs.getString(KEY_DAY, null) != today) {
            prefs.edit().putString(KEY_DAY, today).putInt(KEY_MINUTES, 0).apply()
        }
    }

    companion object {
        private const val KEY_DAY = "day"
        private const val KEY_MINUTES = "minutes"
        const val HARD_STOP_MINUTES = 8 * 60
    }
}
