package com.ynk.virtualdisplay

import com.ynk.virtualdisplay.automation.CareAllowance
import com.ynk.virtualdisplay.automation.CareConsumableBudget
import org.junit.Assert.*
import org.junit.Test

class CareAllowanceTest {
    @Test fun wholeItemsCoverTheGap() {
        assertEquals(0,CareAllowance.stepsFor(-40))
        assertEquals(0,CareAllowance.stepsFor(0))
        assertEquals(1,CareAllowance.stepsFor(1))
        assertEquals(1,CareAllowance.stepsFor(10))
        assertEquals(2,CareAllowance.stepsFor(11))
    }

    @Test fun measuredLowStateCanReachSeventy() {
        assertEquals(7,CareAllowance.feedLimit(16,70))
        assertEquals(8,CareAllowance.cleanLimit(0,70))
        assertTrue(CareAllowance.cleanRestockLimit(0,70)>=7)
    }

    @Test fun fullPetNeedsNoCareButAllowanceRemainsBounded() {
        assertEquals(1,CareAllowance.feedLimit(100,90))
        assertEquals(1,CareAllowance.cleanLimit(100,90))
    }

    @Test fun initialRecoveryAndLaterLessonsHaveAllowances() {
        for(threshold in 60..100 step 10) for(rounds in 1..10) {
            val initial=CareAllowance.stepsFor(threshold)
            assertTrue(CareAllowance.cookieBudget(threshold,rounds)>=initial+rounds-1)
            assertTrue(CareAllowance.soapBudget(threshold,rounds)>=2*(initial+rounds))
        }
    }

    @Test fun emptySoapStockCanPrepareForWorkThenRecoverAfterTheShift() {
        val budget=CareConsumableBudget(soaps=CareAllowance.soapBudget(70,1))
        fun scrubWithEmptyStock() {
            // A delivered but unconfirmed stock-out scrub must stay charged.
            budget.reserveSoap().settleAfterFailure(1)
            budget.reserveSoap().confirmConsumed()
        }
        scrubWithEmptyStock() // work preparation: 0 -> 10
        val afterWork=8
        repeat(CareAllowance.stepsFor(70-afterWork)) {scrubWithEmptyStock()}
        assertEquals(16,budget.soapsUsed)
        assertTrue(budget.soapsUsed<=budget.soaps)
    }

    @Test fun maximumPlanStillHasFiniteAllowance() {
        assertTrue(CareAllowance.cookieBudget(100,10)<=40)
        assertTrue(CareAllowance.soapBudget(100,10)<=50)
    }
}
