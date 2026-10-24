package com.ynk.virtualdisplay

import com.ynk.virtualdisplay.automation.CareFundsEstimate
import com.ynk.virtualdisplay.automation.SessionPlan
import com.ynk.virtualdisplay.automation.TuitionWorkDecision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pre-care work precheck: a pet too poor to restock must run its work shift BEFORE care,
 * because care's verified buys fail at EXCHANGE_PAY_UNCONFIRMED when the coin balance cannot
 * pay for the consumables the thresholds demand (measured on device 2026-10-20: energy 26,
 * cleanliness 7, coins 1, cookie unit 5 — the session died in care and never reached work).
 */
class CareFundsEstimateTest {
    private val plan = SessionPlan(workBeforeStudy = true)

    @Test fun aHealthyPetNeedsNoCareCoins() {
        assertEquals(0, CareFundsEstimate.careCoins(plan, 95, 95))
    }

    @Test fun gapsRoundUpToWholeConsumables() {
        // 90-26=64 energy → 7 cookies (7×10=70 covers 64); 90-7=83 cleanliness → 9 soaps.
        assertEquals(7 * 5 + 9 * 2, CareFundsEstimate.careCoins(plan, 26, 7))
    }

    @Test fun aPetAlreadyAboveThresholdCostsNothing() {
        assertEquals(0, CareFundsEstimate.careCoins(plan, 100, 100))
    }

    @Test fun theDeviceStateThatDiedInCareDemandsShiftAndBuys() {
        // The measured failing state: coins 1, tuition 30 for the 30-minute rows, care needs 53.
        val care = CareFundsEstimate.careCoins(plan, 26, 7)
        assertTrue(TuitionWorkDecision.needsWork(1, 30 + care))
    }

    @Test fun oneShiftDoesNotAlwaysCoverTuitionPlusCare() {
        // 帮武馆跑腿 pays +68 coins (verified). Tuition 30 + care 53 = 83 > 68, so one shift is
        // not always enough — the estimate deliberately keeps the in-flow ensureTuition as the
        // second gate. This test pins that arithmetic so nobody \"fixes\" it by removing the gate.
        val care = CareFundsEstimate.careCoins(plan, 26, 7)
        assertTrue(68 < 30 + care)
    }
}
