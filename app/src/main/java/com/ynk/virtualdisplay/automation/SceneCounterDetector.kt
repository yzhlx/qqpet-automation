package com.ynk.virtualdisplay.automation

/**
 * Detects the game's blue consumable counter inside the measured counter box.
 *
 * WHY NOT OCR. The counter is a stylized blue numeral with a white outline (measured 2026-10-22:
 * x=448..471, y=1224..1255 on the bath scene). ML Kit reads neither this nor the food-scene
 * counter, even on a tight crop that visibly frames the glyph, so an OCR-only reader can never
 * observe stock. The colour signature, however, is unambiguous: the counter is the only blue ink
 * inside that box, and a blue counter is drawn only while the pet owns the item.
 *
 * SCOPE. Presence proves AT LEAST ONE owned item (a lower bound), never an exact count. Exact
 * counts stay the job of a digit reader; this only proves that the pet is not empty, which is
 * what the "use the stock first" rule needs.
 */
object SceneCounterDetector {
    /** The counter ink: saturated blue, clearly separated from the beige/white background. */
    const val MIN_BLUE = 170
    const val MIN_BLUE_OVER_RED = 50
    const val MIN_BLUE_OVER_GREEN = 25

    /** Below this pixel count the blue specks are noise, not a drawn counter. */
    const val MIN_INK_PIXELS = 30

    fun isBlue(r: Int, g: Int, b: Int): Boolean =
        b >= MIN_BLUE && b - r >= MIN_BLUE_OVER_RED && b - g >= MIN_BLUE_OVER_GREEN

    /** True when the crop holds a drawn blue counter. */
    fun hasCounter(pixels: IntArray, width: Int, height: Int): Boolean {
        if (width <= 0 || height <= 0 || pixels.size < width * height) return false
        var count = 0
        for (index in pixels.indices) {
            val color = pixels[index]
            if (isBlue((color shr 16) and 0xFF, (color shr 8) and 0xFF, color and 0xFF)) {
                count++
                if (count >= MIN_INK_PIXELS) return true
            }
        }
        return false
    }
}
