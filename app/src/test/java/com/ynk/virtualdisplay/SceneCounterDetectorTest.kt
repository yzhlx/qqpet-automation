package com.ynk.virtualdisplay

import com.ynk.virtualdisplay.automation.SceneCounterDetector
import org.junit.Assert.*
import org.junit.Test

/**
 * The blue counter ink measured 2026-10-22 is the only reliable stock signal on the scene:
 * ML Kit reads neither the bath nor the food counter glyph.
 */
class SceneCounterDetectorTest {
    private val beige = (0xFF shl 24) or (0xE6 shl 16) or (0xD2 shl 8) or 0xA8
    private val blue = (0xFF shl 24) or (0x4F shl 16) or (0xB3 shl 8) or 0xF6

    private fun frame(width: Int, height: Int, paint: (IntArray) -> Unit): IntArray {
        val pixels = IntArray(width * height) { beige }
        paint(pixels)
        return pixels
    }

    @Test fun aDrawnBlueCounterIsDetected() {
        val pixels = frame(40, 40) { px ->
            for (y in 8 until 32) for (x in 16 until 24) px[y * 40 + x] = blue
        }
        assertTrue(SceneCounterDetector.hasCounter(pixels, 40, 40))
    }

    @Test fun plainBackgroundIsNotACounter() {
        val pixels = frame(40, 40) {}
        assertFalse(SceneCounterDetector.hasCounter(pixels, 40, 40))
    }

    @Test fun aFewBlueSpecksAreNoiseNotACounter() {
        val pixels = frame(40, 40) { px ->
            for (i in 0 until 10) px[i] = blue
        }
        assertFalse(SceneCounterDetector.hasCounter(pixels, 40, 40))
    }

    @Test fun nonBlueInkIsNotTheCounter() {
        val black = (0xFF shl 24) or (0x20 shl 16) or (0x20 shl 8) or 0x20
        val pixels = frame(40, 40) { px ->
            for (y in 8 until 32) for (x in 16 until 24) px[y * 40 + x] = black
        }
        assertFalse(SceneCounterDetector.hasCounter(pixels, 40, 40))
    }
}
