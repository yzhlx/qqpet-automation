package com.ynk.virtualdisplay

import com.ynk.virtualdisplay.automation.PetHealthCheck
import com.ynk.virtualdisplay.automation.TemplateMatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The health panel sat 21 px higher on 2026-10-31 and scored 0.9935 there, while the coarse search
 * only saw 0.9256 at -24. Scores below follow that frame's replay: 0.9844 one pixel either side.
 */
class HealthPanelDriftTest {
    private val measured = mapOf(-22 to 0.9847, -21 to 0.9935, -20 to 0.9844, -24 to 0.9256)
    private fun score(dy: Int) = measured[dy] ?: 0.85

    @Test fun theExactSearchReachesTheMeasuredDrift() {
        assertTrue(-21 in TemplateMatcher.drifts(true))
        assertFalse(-21 in TemplateMatcher.drifts(false))
    }

    @Test fun anExactProbeDoesNotStopAtTheFirstPassingOffset() {
        val (drift, best) = TemplateMatcher.bestDrift(true, 0.97, 0.8489, TemplateMatcher.drifts(true), ::score)
        assertEquals(-21, drift)
        assertEquals(0.9935, best, 0.0)
    }

    @Test fun anExactProbeChecksOffsetsEvenWhenThePanelPassesAtRest() {
        val (drift, _) = TemplateMatcher.bestDrift(true, 0.97, 0.98, TemplateMatcher.drifts(true)) {
            if (it == 1) 0.99 else 0.85
        }
        assertEquals(1, drift)
    }

    @Test fun coarseProbesKeepTheirSearch() {
        assertEquals(listOf(-64,-56,-48,-40,-32,-24,-16,-8,8,16,24,32,40,48,56,64), TemplateMatcher.drifts(false))
        val calls = mutableListOf<Int>()
        val passing = TemplateMatcher.bestDrift(false, 0.95, 0.96, TemplateMatcher.drifts(false)) { calls += it; 1.0 }
        assertEquals(0 to 0.96, passing)
        assertTrue(calls.isEmpty())
        val (_, missed) = TemplateMatcher.bestDrift(false, 0.95, 0.8489, TemplateMatcher.drifts(false), ::score)
        assertEquals(0.9256, missed, 0.0)
    }

    @Test fun rowsAndTheCloseTapFollowTheDrift() {
        assertEquals(listOf(228, 304, 380), PetHealthCheck.digitRows(0))
        assertEquals(listOf(207, 283, 359), PetHealthCheck.digitRows(-21))
        // 体力 86 / 清洁 82 / 心情 21 read at these rows on the drifted frame.
        assertEquals(listOf(223, 299, 375), PetHealthCheck.barRows(-21))
        assertEquals(454f, PetHealthCheck.closeY(0))
        assertEquals(433f, PetHealthCheck.closeY(-21))
    }
}
