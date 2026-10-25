package com.ynk.virtualdisplay

import com.ynk.virtualdisplay.automation.WorkPreparation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The work step has NO app-side care threshold (RULE A): the plan's 体力/清洁 thresholds belong to
 * the lesson, never to the shift. What the shift has is the GAME's own requirement - the work
 * option's cleanliness cost, measured as 「消耗 体力 5（当前 16），清洁 2（当前 0）」.
 *
 * With the pet below that cost the game replaces the 去打工 button with 「你的宠物清洁值不足，请回家洗澡」,
 * so a shift that skips it can never start (measured 2026-10-20: the session stopped with
 * WORK_START_BUTTON_NOT_FOUND, and a manual tap on the same spot changed nothing). The app's
 * answer is to ACT on the game's cost, not to refuse the shift.
 */
class WorkPreparationTest {
    @Test fun theTargetIsTheOptionsOwnCost() {
        assertEquals(2, WorkPreparation.requiredCleanliness(2))
    }

    @Test fun aNegativeCostIsFlooredAtZero() {
        assertEquals(0, WorkPreparation.requiredCleanliness(-1))
    }

    @Test fun thePetIsReadyOnlyAtOrAboveTheCost() {
        assertTrue(WorkPreparation.isReady(2, 2))
        assertTrue(WorkPreparation.isReady(70, 2))
        assertFalse(WorkPreparation.isReady(1, 2))
        assertFalse(WorkPreparation.isReady(0, 2))
    }

    @Test fun anOptionThatCostsNoCleanlinessIsAlwaysReady() {
        assertTrue(WorkPreparation.isReady(0, 0))
    }

    @Test fun theSoapPriceIsTheMeasuredTwoCoins() {
        assertEquals(2, WorkPreparation.SOAP_COINS)
        assertTrue(WorkPreparation.canAffordClean(2))
        assertFalse(WorkPreparation.canAffordClean(1))
    }

    @Test fun theShortfallLineNamesBothNumbers() {
        // The measured device state: the pet holds 1 coin and a 香皂片 costs 2.
        assertEquals(
            "WORK_PREP_SOAP_PAYMENT_UNCONFIRMED:balance=1 soap=2 need=2",
            WorkPreparation.shortfallReason(1, 2))
    }

    @Test fun theWorkTargetIsNeverThePlanThreshold() {
        // The plan asked for 70/70; the shift needs the option's own 2. The preparation must target
        // the shift's cost - the lesson's threshold never gates the work path.
        assertEquals(2, WorkPreparation.requiredCleanliness(2))
        assertTrue(WorkPreparation.requiredCleanliness(2) < 70)
    }
}
