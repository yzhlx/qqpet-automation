package com.ynk.virtualdisplay

import com.ynk.virtualdisplay.automation.*
import org.junit.Assert.*
import org.junit.Test

class PetStudyHealthPolicyTest {
    private fun known(value:Int,at:Long=1000L)=ObservedValue.Known(value,at,1.0,"frame",ObservationSource.DIGIT_RECOGNITION)
    private fun health(mood:ObservedValue<Int>)=HealthObservations(known(94),known(94),mood,
        known(93),known(93),ObservedValue.Unknown("bar unavailable",1000,"frame"),false,"frame")
    @Test fun lowUnknownStaleMoodDoesNotAuthorizeOrPreventStudy() {
        for(mood in listOf(known(20),ObservedValue.Unknown("ambiguous",1000,"frame"),
            ObservedValue.Stale(known(80),90000,"expired"),ObservedValue.Unsupported("no reader")))
            PetStudyHealthPolicy.requireReady(health(mood),1000)
    }
    @Test fun unknownEnergyNeverDefaultsToZero() {
        val h=health(known(94)).copy(energy=ObservedValue.Unknown("ambiguous",1000,"frame"))
        assertTrue(runCatching {PetStudyHealthPolicy.read(h,1000)}.isFailure)
    }
    @Test fun staleCleanlinessNeverAuthorizes() {
        val h=health(known(94)).copy(cleanliness=known(94,-60000))
        assertTrue(runCatching {PetStudyHealthPolicy.requireReady(h,1000)}.isFailure)
    }
    @Test fun conservativeBarBoundaryStillApplies() {
        val h=health(known(94)).copy(barEnergy=known(89))
        assertFalse(PetStudyHealthPolicy.read(h,1000).ready)
        assertTrue(runCatching {PetStudyHealthPolicy.requireReady(h,1000)}.isFailure)
    }
}
