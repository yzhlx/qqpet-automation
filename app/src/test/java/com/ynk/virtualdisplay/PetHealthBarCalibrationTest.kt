package com.ynk.virtualdisplay

import com.ynk.virtualdisplay.automation.PetHealthCheck
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pixel health bar has to reproduce the game's own digits, including at the top of the
 * scale. Measured on FOA_AL00 2026-10-21, session 1792549392632 (frames
 * round-1-20/26/34-open-health-after.png and the older s20-managed-pass samples): the game's
 * digit and the last fully filled pixel of the same bar row.
 *
 * A FULL bar used to read 99, so a threshold of 100 was unreachable and the care loop fed a pet
 * that was already full (session 1792549392632 stopped with FEED_NO_EXPECTED_GAIN_OR_EMPTY).
 */
class PetHealthBarCalibrationTest {
    @Test fun aFullBarReadsOneHundred() {
        assertEquals(100, PetHealthCheck.barValue(504))
    }

    @Test fun theMeasuredBarsReproduceTheGamesOwnDigits() {
        // 体力 93, 100, 12 / 清洁 61, 6 / 心情 20, 21 / 体力 72 / 清洁 76.
        assertEquals(93, PetHealthCheck.barValue(491))
        assertEquals(100, PetHealthCheck.barValue(504))
        assertEquals(12, PetHealthCheck.barValue(342))
        assertEquals(61, PetHealthCheck.barValue(432))
        assertEquals(6, PetHealthCheck.barValue(331))
        assertEquals(20, PetHealthCheck.barValue(357))
        assertEquals(21, PetHealthCheck.barValue(359))
        assertEquals(72, PetHealthCheck.barValue(452))
        assertEquals(76, PetHealthCheck.barValue(460))
    }

    /**
     * The one sample the fitted line misses is 心情 2, which reads 3. An over-read is the harmless
     * direction: the game's own digit always wins the minimum that authorizes a lesson.
     */
    @Test fun theVeryBottomOfTheScaleOnlyOverReads() {
        assertEquals(3, PetHealthCheck.barValue(325))
    }

    @Test fun anEmptyBarNeverClaimsEnergy() {
        assertEquals(0, PetHealthCheck.barValue(317))
        assertEquals(0, PetHealthCheck.barValue(318))
    }

    @Test fun theEstimateNeverLeavesTheSensorsRange() {
        for(end in -50..600) {
            val value = PetHealthCheck.barValue(end)
            assertTrue("barValue($end)=$value out of range", value in 0..100)
        }
    }
}
