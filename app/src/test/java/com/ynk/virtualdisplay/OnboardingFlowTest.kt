package com.ynk.virtualdisplay

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
}
