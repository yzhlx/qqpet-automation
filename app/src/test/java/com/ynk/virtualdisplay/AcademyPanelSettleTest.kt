package com.ynk.virtualdisplay

import com.ynk.virtualdisplay.automation.AcademyPanelSettle
import com.ynk.virtualdisplay.automation.AcademyPanelSettle.Page
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AcademyPanelSettleTest {
    private val threshold = 0.97

    @Test
    fun `an open panel counts at once`() {
        assertEquals(Page.PANEL, AcademyPanelSettle(threshold).observe(0, 0.60, 0.996))
    }

    @Test
    fun `the map counts only after it held for the whole hold window`() {
        val settle = AcademyPanelSettle(threshold)
        assertNull(settle.observe(0, 0.987, 0.50))
        assertNull(settle.observe(AcademyPanelSettle.MAP_HOLD_MS - 1, 0.987, 0.50))
        assertEquals(Page.MAP, settle.observe(AcademyPanelSettle.MAP_HOLD_MS, 0.987, 0.50))
    }

    @Test
    fun `a panel sliding up after the map is waited for, not stopped on`() {
        // Session 1793713687771, 21:48: map 0.987, then a mid-slide frame at 0.899, then the panel.
        val settle = AcademyPanelSettle(threshold)
        assertNull(settle.observe(0, 0.987, 0.50))
        assertNull(settle.observe(650, 0.899, 0.80))
        assertEquals(Page.PANEL, settle.observe(1200, 0.70, 0.996))
    }

    @Test
    fun `a mid-slide frame restarts the map hold`() {
        val settle = AcademyPanelSettle(threshold)
        assertNull(settle.observe(0, 0.987, 0.50))
        assertNull(settle.observe(1400, 0.910, 0.80))
        assertNull(settle.observe(1700, 0.987, 0.50))
        assertNull(settle.observe(1700 + AcademyPanelSettle.MAP_HOLD_MS - 1, 0.987, 0.50))
        assertEquals(Page.MAP, settle.observe(1700 + AcademyPanelSettle.MAP_HOLD_MS, 0.987, 0.50))
    }
}
