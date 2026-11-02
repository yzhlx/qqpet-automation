package com.ynk.virtualdisplay.ui.screens

enum class OnboardingStep { WELCOME, INSTALL_SHIZUKU, START_SHIZUKU, GRANT_SHIZUKU, OVERLAY, NOTIFICATION, BACKGROUND, FINISH }

/** What the phone reports right now; every step ends by checking this, never by the user's word. */
data class OnboardingFacts(
    val shizukuInstalled: Boolean = false,
    val shizukuRunning: Boolean = false,
    val shizukuGranted: Boolean = false,
    val overlayGranted: Boolean = false,
    val notificationGranted: Boolean = false,
    val batteryExempt: Boolean = false,
    /** 开发者选项是否已开启（系统设置项，读取不需要额外权限）。 */
    val developerEnabled: Boolean = false,
    /** 无线调试是否已开启；配对前必须先打开它。 */
    val wirelessDebugEnabled: Boolean = false,
)

object OnboardingFlow {
    /** The only steps that can be set aside; the app still runs without them. */
    val OPTIONAL = setOf(OnboardingStep.OVERLAY, OnboardingStep.NOTIFICATION, OnboardingStep.BACKGROUND)

    /** Steps counted in "第 N 步"; the welcome and finish pages are not tasks. */
    val COUNTED = OnboardingStep.entries.filter { it != OnboardingStep.WELCOME && it != OnboardingStep.FINISH }

    /** Sui and a Shizuku started elsewhere both run without the manager app being visible here. */
    private fun satisfied(step: OnboardingStep, facts: OnboardingFacts) = when (step) {
        OnboardingStep.WELCOME, OnboardingStep.FINISH -> false
        OnboardingStep.INSTALL_SHIZUKU -> facts.shizukuInstalled || facts.shizukuRunning
        OnboardingStep.START_SHIZUKU -> facts.shizukuRunning
        OnboardingStep.GRANT_SHIZUKU -> facts.shizukuGranted
        OnboardingStep.OVERLAY -> facts.overlayGranted
        OnboardingStep.NOTIFICATION -> facts.notificationGranted
        OnboardingStep.BACKGROUND -> facts.batteryExempt
    }

    fun current(facts: OnboardingFacts, welcomed: Boolean, setAside: Set<OnboardingStep>): OnboardingStep {
        if (!welcomed) return OnboardingStep.WELCOME
        return COUNTED.firstOrNull { !satisfied(it, facts) && it !in setAside } ?: OnboardingStep.FINISH
    }

    /** The step before [step] on the guide, or null where there is nothing to go back to. */
    fun previous(step: OnboardingStep): OnboardingStep? = when (step) {
        OnboardingStep.WELCOME -> null
        OnboardingStep.FINISH -> COUNTED.last()
        else -> COUNTED.indexOf(step).let { i -> if (i <= 0) OnboardingStep.WELCOME else COUNTED[i - 1] }
    }

    /** After the first run only a stopped Shizuku comes back here, typically after a reboot. */
    fun shown(completedBefore: Boolean, shizukuReady: Boolean): Boolean = !completedBefore || !shizukuReady

    /** Returning users skip the welcome and every optional step they already passed once. */
    fun setAsideFor(completedBefore: Boolean): Set<OnboardingStep> = if (completedBefore) OPTIONAL else emptySet()
}
