package com.ynk.virtualdisplay

import com.ynk.virtualdisplay.automation.*
import org.junit.Assert.*
import org.junit.Test

class PetManagedRuntimeTest {
    @Test fun runtimeAgesHealthWithoutInventingNumbers() {
        val known=ObservedValue.Known(94,1000L,1.0,"frame",ObservationSource.DIGIT_RECOGNITION)
        val s=ManagedRuntimeState(startedElapsedMs=100,energy=known,cleanliness=known)
        val old=s.at(62000,900)
        assertTrue(old.energy is ObservedValue.Stale)
        assertTrue(old.mood is ObservedValue.Unknown)
        assertEquals(800L,old.runtimeMs)
    }
    @Test fun duplicateStartIsRejectedAndCompletionIsCountedOnce() {
        val ledger=PetRoundLedger();ledger.startAttempt(1);ledger.started(1)
        assertTrue(runCatching {ledger.startAttempt(1)}.isFailure)
        assertTrue(ledger.canReconcileResult(1));assertTrue(ledger.complete(1))
        assertFalse(ledger.complete(1));assertEquals(1,ledger.completedCount)
        assertFalse(ledger.canReconcileResult(1))
    }
    @Test fun encouragementNeverExceedsTen() {
        val ledger=PetRoundLedger();ledger.startAttempt(1);ledger.started(1)
        repeat(10) {ledger.encouraged(1)}
        assertTrue(runCatching {ledger.encouraged(1)}.isFailure)
        assertEquals(10,ledger.encouragementCount(1))
    }
}
