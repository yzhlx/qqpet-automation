package com.ynk.virtualdisplay.automation

/**
 * Decides, one frame at a time, which page the academy map has settled on.
 *
 * QQ slides the 初级学园 course panel up on its own after the map appears (measured 2026-11-03:
 * 0.65 s and 1.4 s after the map first matched). A frame taken mid-slide matches neither the map
 * nor the panel, and a map frame taken just before the slide is not the final page yet. So the
 * panel counts as soon as it matches - it only closes on input - while the map counts only once it
 * has matched on every frame for [MAP_HOLD_MS].
 */
class AcademyPanelSettle(private val threshold: Double) {
    enum class Page { MAP, PANEL }

    private var mapSince: Long? = null

    /** The settled page, or null while the page is still moving. */
    fun observe(atMs: Long, mapScore: Double, panelScore: Double): Page? {
        if (panelScore >= threshold) return Page.PANEL
        if (mapScore < threshold) {
            mapSince = null
            return null
        }
        val since = mapSince ?: atMs.also { mapSince = it }
        return if (atMs - since >= MAP_HOLD_MS) Page.MAP else null
    }

    companion object {
        const val MAP_HOLD_MS = 2500L
    }
}
