package com.ynk.virtualdisplay.ui.screens

enum class OnboardingStep {
    WELCOME,
    INSTALL_SHIZUKU,
    START_SHIZUKU,
    GRANT_SHIZUKU,
    OVERLAY,
    NOTIFICATION,
    BACKGROUND,
    FINISH
}

/** 检测状态：满足、不满足或系统限制导致的无法确认。读取失败绝不能直接断定为未开启。 */
enum class CheckState {
    SATISFIED,
    UNSATISFIED,
    UNKNOWN;

    val isSatisfied: Boolean get() = this == SATISFIED
    val isUnknown: Boolean get() = this == UNKNOWN
}

/**
 * 手机当前上报的环境事实。
 * 包含系统版本、三态开关检查结果以及用户手动确认标记。
 */
data class OnboardingFacts(
    val shizukuInstalled: Boolean = false,
    val shizukuRunning: Boolean = false,
    val shizukuGranted: Boolean = false,
    val overlayGranted: Boolean = false,
    val notificationGranted: Boolean = false,
    val batteryExempt: Boolean = false,
    val developerState: CheckState = CheckState.UNSATISFIED,
    val wirelessDebugState: CheckState = CheckState.UNSATISFIED,
    /** 系统 SDK 版本，Android 10 为 29，Android 11 为 30。 */
    val androidSdkInt: Int = 30,
    val qqInstalled: Boolean = false,
    /** 用户手动确认已开启的键（针对 UNKNOWN 无法自动读取的情况）。 */
    val userConfirmedKeys: Set<String> = emptySet(),
) {
    /** Android 11+ (API 30+) 才内置无线调试功能。Android 10 及更早系统需走电脑 USB (ADB) 或 Root 路线。 */
    val supportsWirelessDebugging: Boolean get() = androidSdkInt >= 30

    val developerEnabled: Boolean
        get() = developerState.isSatisfied || "developer_options" in userConfirmedKeys

    val wirelessDebugEnabled: Boolean
        get() = wirelessDebugState.isSatisfied || "wireless_debug" in userConfirmedKeys

    // 兼容旧构造签名
    constructor(
        shizukuInstalled: Boolean = false,
        shizukuRunning: Boolean = false,
        shizukuGranted: Boolean = false,
        overlayGranted: Boolean = false,
        notificationGranted: Boolean = false,
        batteryExempt: Boolean = false,
        developerEnabled: Boolean = false,
        wirelessDebugEnabled: Boolean = false,
    ) : this(
        shizukuInstalled = shizukuInstalled,
        shizukuRunning = shizukuRunning,
        shizukuGranted = shizukuGranted,
        overlayGranted = overlayGranted,
        notificationGranted = notificationGranted,
        batteryExempt = batteryExempt,
        developerState = if (developerEnabled) CheckState.SATISFIED else CheckState.UNSATISFIED,
        wirelessDebugState = if (wirelessDebugEnabled) CheckState.SATISFIED else CheckState.UNSATISFIED,
        androidSdkInt = 30,
        qqInstalled = false,
        userConfirmedKeys = emptySet(),
    )
}

object OnboardingFlow {
    /** The only steps that can be set aside; the app still runs without them. */
    val OPTIONAL = setOf(OnboardingStep.OVERLAY, OnboardingStep.NOTIFICATION, OnboardingStep.BACKGROUND)

    /** Steps counted in "第 N 步"; the welcome and finish pages are not tasks. */
    val COUNTED = OnboardingStep.entries.filter { it != OnboardingStep.WELCOME && it != OnboardingStep.FINISH }

    /** Sui and a Shizuku started elsewhere both run without the manager app being visible here. */
    fun satisfied(step: OnboardingStep, facts: OnboardingFacts): Boolean = when (step) {
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

    /**
     * 判断是否展示全屏引导。
     * 若用户主动选择「先看产品」（previewMode = true），则不强制阻塞，允许先进入主界面查看功能。
     */
    fun shown(completedBefore: Boolean, shizukuReady: Boolean, previewMode: Boolean = false): Boolean {
        if (previewMode) return false
        return !completedBefore || !shizukuReady
    }

    /** Returning users skip the welcome and every optional step they already passed once. */
    fun setAsideFor(completedBefore: Boolean): Set<OnboardingStep> = if (completedBefore) OPTIONAL else emptySet()
}
