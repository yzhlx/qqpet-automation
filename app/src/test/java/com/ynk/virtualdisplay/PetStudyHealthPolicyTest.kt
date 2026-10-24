package com.ynk.virtualdisplay

import com.ynk.virtualdisplay.automation.*
import org.junit.Assert.*
import org.junit.Test

class PetStudyHealthPolicyTest {
    private fun known(value:Int,at:Long=1000L)=ObservedValue.Known(value,at,1.0,"frame",ObservationSource.DIGIT_RECOGNITION)
    private fun health(mood:ObservedValue<Int>)=HealthObservations(known(94),known(94),mood,
        known(93),known(93),ObservedValue.Unknown("bar unavailable",1000,"frame"),false,"frame")
    @Test fun lowUnknownStaleMoodDoesNotAffectTheHealthReading() {
        for(mood in listOf(known(20),ObservedValue.Unknown("ambiguous",1000,"frame"),
            ObservedValue.Stale(known(80),90000,"expired"),ObservedValue.Unsupported("no reader")))
            PetStudyHealthPolicy.read(health(mood),1000)
    }
    /**
     * [PetStudyHealthPolicy] is still used by CARE (it is how a reading is bounded) and by the plan
     * threshold validation, but it is NOT a study gate any more. These tests pin its reading
     * semantics only; the "a lesson is not blocked by health" rule is covered by
     * [PlanResourcesTest] and by the controller/flow call sites that no longer call requireReady.
     */
    @Test fun unknownEnergyNeverDefaultsToZero() {
        val h=health(known(94)).copy(energy=ObservedValue.Unknown("ambiguous",1000,"frame"))
        assertTrue(runCatching {PetStudyHealthPolicy.read(h,1000)}.isFailure)
    }

    @Test fun staleCleanlinessNeverAuthorizesAClaimOfFreshData() {
        val h=health(known(94)).copy(cleanliness=known(94,-60000))
        assertTrue(runCatching {PetStudyHealthPolicy.read(h,1000)}.isFailure)
    }

    @Test fun conservativeBarBoundaryStillApplies() {
        val h=health(known(94)).copy(barEnergy=known(89))
        assertFalse(PetStudyHealthPolicy.read(h,1000).ready)
    }
    /**
     * The game digit stays available next to the conservative bound, so care can tell a FULL pet
     * from a pet whose bar estimate lags (measured 2026-10-21, session 1792549392632: the game
     * showed 体力 100 while the bar estimate read 99, and the loop fed a pet that was already full).
     */
    @Test fun theGameDigitIsReadableNextToTheConservativeBound() {
        val now=System.currentTimeMillis()
        val fresh=ObservedValue.Known(100,now,1.0,"frame",ObservationSource.DIGIT_RECOGNITION)
        val bar=ObservedValue.Known(99,now,1.0,"frame",ObservationSource.CONSERVATIVE_BAR_ESTIMATE)
        val h=HealthObservations(fresh,fresh,known(94),bar,bar,
            ObservedValue.Unknown("bar unavailable",now,"frame"),false,"frame")
        val reading=PetCareReading(h)
        assertEquals(99,reading.energy)
        assertEquals(100,reading.digitEnergy)
        assertEquals(100,reading.digitCleanliness)
    }
}
