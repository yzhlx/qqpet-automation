package com.ynk.virtualdisplay

import com.ynk.virtualdisplay.automation.*
import org.junit.Assert.*
import org.junit.Test

/**
 * A care iteration can take longer than the freshness window (a purchase plus a 48s scrub), so a
 * DELTA between two readings must use the recorded numbers. Measured 2026-10-22: the run had
 * already reached 100/100 and then died with HEALTH_STALE while comparing the two readings.
 */
class CareReadingAgeTest {
    private fun observations(at: Long) = HealthObservations(
        energy = ObservedValue.Known(83, at, 1.0, "panel", ObservationSource.DIGIT_RECOGNITION),
        cleanliness = ObservedValue.Known(83, at, 1.0, "panel", ObservationSource.DIGIT_RECOGNITION),
        mood = ObservedValue.Known(30, at, 1.0, "panel", ObservationSource.DIGIT_RECOGNITION),
        barEnergy = ObservedValue.Known(83, at, 1.0, "panel", ObservationSource.CONSERVATIVE_BAR_ESTIMATE),
        barCleanliness = ObservedValue.Known(83, at, 1.0, "panel", ObservationSource.CONSERVATIVE_BAR_ESTIMATE),
        barMood = ObservedValue.Known(30, at, 1.0, "panel", ObservationSource.CONSERVATIVE_BAR_ESTIMATE),
        barReady = true,
        evidence = "panel",
    )

    @Test fun anOldReadingIsStaleForDecisions() {
        val old = PetCareReading(observations(System.currentTimeMillis() - 120_000L))
        assertTrue(old.isStale())
        var threw = false
        try { old.cleanliness } catch (_: IllegalStateException) { threw = true }
        assertTrue("a stale reading must not authorize a current-state decision", threw)
    }

    @Test fun theRecordedValueSurvivesTheAgeGateForDeltaArithmetic() {
        val old = PetCareReading(observations(System.currentTimeMillis() - 120_000L))
        assertEquals(83, old.recordedEnergy)
        assertEquals(83, old.recordedCleanliness)
    }

    @Test fun anOldReadingCanStillBeWrittenDownAsMeasured() {
        // plan-care.json records the "before" reading after the whole care iteration: over a minute later.
        val old = PetCareReading(observations(System.currentTimeMillis() - 120_000L))
        assertEquals(83, old.measuredEnergy)
        assertEquals(83, old.measuredCleanliness)
    }

    @Test fun theWrittenDownValueIsTheLowerOfDigitAndBar() {
        val at = System.currentTimeMillis()
        val h = observations(at).copy(
            energy = ObservedValue.Known(91, at, 1.0, "panel", ObservationSource.DIGIT_RECOGNITION),
            barEnergy = ObservedValue.Known(90, at, 1.0, "panel", ObservationSource.CONSERVATIVE_BAR_ESTIMATE),
        )
        assertEquals(90, PetCareReading(h).measuredEnergy)
    }

    @Test fun anUnreadValueIsLeftOutNotInvented() {
        val at = System.currentTimeMillis()
        val h = observations(at).copy(energy = ObservedValue.Unknown("ENERGY_UNREAD", at, "panel"))
        assertNull(PetCareReading(h).measuredEnergy)
        assertEquals(83, PetCareReading(h).measuredCleanliness)
    }

    @Test fun aFreshReadingIsNotStale() {
        val fresh = PetCareReading(observations(System.currentTimeMillis()))
        assertFalse(fresh.isStale())
        assertEquals(83, fresh.cleanliness)
    }
}
