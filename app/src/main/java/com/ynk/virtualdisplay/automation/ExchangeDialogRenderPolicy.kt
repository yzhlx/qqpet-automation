package com.ynk.virtualdisplay.automation

/**
 * QQ paints the exchange bottom sheet in two phases: the title and the coin counter show up
 * first on a still blank white body, and only a later frame carries the product tile, the
 * quantity stepper and the pay button. The title template matches in BOTH phases (0.9961
 * measured), so a title match alone cannot prove the dialog is readable -- reading the quantity
 * off the first frame yields DIALOG_DIGITS_ABSENT.
 *
 * The pay button band is used as the readiness gate: it is the last element of the sheet to be
 * painted and the pay total is read from it, so "the band is painted" is exactly "the dialog is
 * usable". Measured 2026-11-04 on DKS9K23420020242 (virtual display 39, 720x1600): the blank
 * phase leaves 0/58500 painted pixels in the band, the complete phase 55096/58500 (94.2%).
 *
 * Pure Kotlin, no Android types: unit-testable on JVM.
 */
object ExchangeDialogRenderPolicy {
    /** Pay button band of the 720x1600 exchange sheet. */
    const val BAND_LEFT = 35
    const val BAND_TOP = 1380
    const val BAND_RIGHT = 685
    const val BAND_BOTTOM = 1470

    /** Share of the band that must be painted before the sheet counts as rendered. */
    const val MIN_INK_RATIO = 0.50

    /** A pixel counts as painted unless every channel is brighter than this. */
    const val WHITE_CUTOFF = 245

    /** True when at least [MIN_INK_RATIO] of [pixels] is painted. */
    fun rendered(pixels: IntArray): Boolean {
        if (pixels.isEmpty()) return false
        var ink = 0
        for (color in pixels) {
            val red = (color shr 16) and 0xFF
            val green = (color shr 8) and 0xFF
            val blue = color and 0xFF
            if (red <= WHITE_CUTOFF || green <= WHITE_CUTOFF || blue <= WHITE_CUTOFF) ink++
        }
        return ink.toDouble() / pixels.size >= MIN_INK_RATIO
    }
}
