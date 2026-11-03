package com.ynk.virtualdisplay

import com.ynk.virtualdisplay.ui.screens.CheckState
import com.ynk.virtualdisplay.ui.screens.OnboardingFacts
import com.ynk.virtualdisplay.ui.screens.OnboardingFlow
import com.ynk.virtualdisplay.ui.screens.OnboardingStep
import org.junit.Assert.*
import org.junit.Test

class OnboardingFlowTest {
    private val ready = OnboardingFacts(true, true, true, true, true, true)

    @Test fun walksTheStepsInOrderAsThePhoneReportsThem() {
        var facts = OnboardingFacts()
        fun step() = OnboardingFlow.current(facts, true, emptySet())
        assertEquals(OnboardingStep.INSTALL_SHIZUKU, step())
        facts = facts.copy(shizukuInstalled = true); assertEquals(OnboardingStep.START_SHIZUKU, step())
        facts = facts.copy(shizukuRunning = true); assertEquals(OnboardingStep.GRANT_SHIZUKU, step())
        facts = facts.copy(shizukuGranted = true); assertEquals(OnboardingStep.OVERLAY, step())
        facts = facts.copy(overlayGranted = true); assertEquals(OnboardingStep.NOTIFICATION, step())
        facts = facts.copy(notificationGranted = true); assertEquals(OnboardingStep.BACKGROUND, step())
        facts = facts.copy(batteryExempt = true); assertEquals(OnboardingStep.FINISH, step())
    }

    @Test fun welcomeComesFirstEvenWhenEverythingIsAlreadyDone() {
        assertEquals(OnboardingStep.WELCOME, OnboardingFlow.current(ready, false, emptySet()))
    }

    @Test fun shizukuRunningWithoutTheManagerAppNeedsNoInstallStep() {
        val sui = OnboardingFacts(shizukuRunning = true)
        assertEquals(OnboardingStep.GRANT_SHIZUKU, OnboardingFlow.current(sui, true, emptySet()))
    }

    @Test fun optionalStepsCanBeSetAsideButShizukuCannot() {
        val aside = OnboardingFlow.OPTIONAL
        assertEquals(OnboardingStep.FINISH, OnboardingFlow.current(ready.copy(overlayGranted = false, batteryExempt = false), true, aside))
        assertEquals(OnboardingStep.START_SHIZUKU, OnboardingFlow.current(ready.copy(shizukuRunning = false), true, aside))
    }

    @Test fun afterARebootOnlyTheStoppedShizukuIsAskedAgain() {
        assertFalse(OnboardingFlow.shown(completedBefore = true, shizukuReady = true))
        assertTrue(OnboardingFlow.shown(completedBefore = true, shizukuReady = false))
        val rebooted = OnboardingFacts(shizukuInstalled = true)
        assertEquals(OnboardingStep.START_SHIZUKU, OnboardingFlow.current(rebooted, true, OnboardingFlow.setAsideFor(true)))
    }

    @Test fun previewModeAllowsViewingProductBeforeCompletingOnboarding() {
        // 当用户选择「先看看功能」（previewMode = true），不强制死锁在全屏引导，允许进入主界面查看
        assertFalse(OnboardingFlow.shown(completedBefore = false, shizukuReady = false, previewMode = true))
        // 未开启 previewMode 且未完成设置时正常显示全屏引导
        assertTrue(OnboardingFlow.shown(completedBefore = false, shizukuReady = false, previewMode = false))
    }

    @Test fun android10DoesNotSupportWirelessDebugging() {
        val android10Facts = OnboardingFacts(androidSdkInt = 29)
        assertFalse(android10Facts.supportsWirelessDebugging)

        val android11Facts = OnboardingFacts(androidSdkInt = 30)
        assertTrue(android11Facts.supportsWirelessDebugging)

        val android14Facts = OnboardingFacts(androidSdkInt = 34)
        assertTrue(android14Facts.supportsWirelessDebugging)
    }

    @Test fun unknownCheckStateCanBeConfirmedByUser() {
        val unknownDev = OnboardingFacts(developerState = CheckState.UNKNOWN)
        assertFalse(unknownDev.developerEnabled)

        // 用户在界面上点击「我已开启」后，userConfirmedKeys 生效，流程可继续推进
        val confirmedDev = unknownDev.copy(userConfirmedKeys = setOf("developer_options"))
        assertTrue(confirmedDev.developerEnabled)

        val unknownWifi = OnboardingFacts(wirelessDebugState = CheckState.UNKNOWN)
        assertFalse(unknownWifi.wirelessDebugEnabled)
        val confirmedWifi = unknownWifi.copy(userConfirmedKeys = setOf("wireless_debug"))
        assertTrue(confirmedWifi.wirelessDebugEnabled)
    }

    @Test fun satisfiedCheckMatchesRequiredSteps() {
        assertTrue(OnboardingFlow.satisfied(OnboardingStep.INSTALL_SHIZUKU, OnboardingFacts(shizukuInstalled = true)))
        assertTrue(OnboardingFlow.satisfied(OnboardingStep.START_SHIZUKU, OnboardingFacts(shizukuRunning = true)))
        assertTrue(OnboardingFlow.satisfied(OnboardingStep.GRANT_SHIZUKU, OnboardingFacts(shizukuGranted = true)))
        assertTrue(OnboardingFlow.satisfied(OnboardingStep.OVERLAY, OnboardingFacts(overlayGranted = true)))
        assertTrue(OnboardingFlow.satisfied(OnboardingStep.NOTIFICATION, OnboardingFacts(notificationGranted = true)))
        assertTrue(OnboardingFlow.satisfied(OnboardingStep.BACKGROUND, OnboardingFacts(batteryExempt = true)))
        assertFalse(OnboardingFlow.satisfied(OnboardingStep.WELCOME, ready))
        assertFalse(OnboardingFlow.satisfied(OnboardingStep.FINISH, ready))
    }
}
