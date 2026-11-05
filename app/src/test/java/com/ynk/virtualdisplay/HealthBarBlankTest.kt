@file:Suppress("MagicNumber")
package com.ynk.virtualdisplay

import com.ynk.virtualdisplay.automation.PetHealthCheck
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * An EMPTY health bar carries no fill of its own colour, so the cap rule (own colour, or the blank
 * grey right after it) can never prove it. Recorded on 2026-11-05 on virtual display 44: 体力 66 /
 * 清洁 8 / 心情 0, panel matched at 0.9920, and the managed session still stopped with
 * BAR_UNCONFIRMED, because 心情 0 is drawn as nothing but the track - the pet that most needs care
 * could not be read at all. The fixture is that frame: /health-panel/panel-room-display44-empty-mood.png.
 */
class HealthBarBlankTest {

    private fun frame(name: String): BufferedImage {
        val stream = javaClass.getResourceAsStream(name) ?: error("missing fixture $name")
        return stream.use { ImageIO.read(it) }
    }

    private fun row(image: BufferedImage, y: Int): IntArray {
        val out = IntArray(505 - PetHealthCheck.BAR_CAP_LEFT + 1)
        for (x in PetHealthCheck.BAR_CAP_LEFT..505) out[x - PetHealthCheck.BAR_CAP_LEFT] = image.getRGB(x, y)
        return out
    }

    @Test
    fun `the empty mood bar is present and reads zero`() {
        val image = frame("/health-panel/panel-room-display44-empty-mood.png")
        val mood = row(image, 396)
        assertTrue("blank bar refused", PetHealthCheck.barPresent(mood, 0xFF8000))
        assertNull("a blank bar must carry no fill", PetHealthCheck.fillEnd(mood, 0xFF8000))
        assertEquals(0, PetHealthCheck.barValue(PetHealthCheck.BAR_LEFT_EDGE - 1))
    }

    /**
     * The boundary is the bar's own length, not a shade of grey: one pixel short of the minimum is
     * a scene patch, one pixel more is a bar. Built from the measured colours (track 214,208,197,
     * panel white 252,246,231, beige wallpaper 237,208,151).
     */
    @Test
    fun `the blank bar gate sits between the patch and the track`() {
        val track = 0xFFD6C9C5.toInt()
        fun rowWith(trackPx: Int) = IntArray(505 - PetHealthCheck.BAR_CAP_LEFT + 1) {
            if (it >= 2 && it < 2 + trackPx) track else 0xFFF6F6E7.toInt()
        }
        assertTrue(PetHealthCheck.barPresent(rowWith(PetHealthCheck.BAR_TRACK_MIN_PX), 0xFF8000))
        assertFalse(PetHealthCheck.barPresent(rowWith(PetHealthCheck.BAR_TRACK_MIN_PX - 1), 0xFF8000))
        // A grey that starts after the cap is a scene patch, not a bar's left end.
        val late = IntArray(505 - PetHealthCheck.BAR_CAP_LEFT + 1) {
            if (it > PetHealthCheck.BAR_CAP_RIGHT - PetHealthCheck.BAR_CAP_LEFT) track else 0xFFF6F6E7.toInt()
        }
        assertFalse(PetHealthCheck.barPresent(late, 0xFF8000))
        // The white panel and the beige wallpaper are never a track.
        assertFalse(PetHealthCheck.blankTrack(0xFFF9EFDE.toInt()))
        assertFalse(PetHealthCheck.blankTrack(0xFFFFFFFA.toInt()))
        assertTrue(PetHealthCheck.blankTrack(0xFFD6C9C5.toInt()))
        assertTrue(PetHealthCheck.blankTrack(0xFFCCC8BE.toInt()))
    }

    @Test
    fun `the filled bars on the same frame still read their own colour`() {
        val image = frame("/health-panel/panel-room-display44-empty-mood.png")
        assertTrue(PetHealthCheck.barPresent(row(image, 244), 0x0099FF))
        assertTrue(PetHealthCheck.barPresent(row(image, 320), 0x64C900))
    }

    @Test
    fun `grey that is not a bar is still refused`() {
        val d44 = frame("/health-panel/panel-room-display44-empty-mood.png")
        // The panel body between the bars is a shorter grey band: 84 px at y 280 against the
        // 184 px track, so the bar length is what separates them.
        assertFalse(PetHealthCheck.barPresent(row(d44, 280), 0xFF8000))
        assertFalse(PetHealthCheck.barPresent(row(d44, 200), 0xFF8000))
        val d41 = frame("/health-panel/panel-bath-display41.png")
        assertFalse(PetHealthCheck.barPresent(row(d41, 100), 0x0099FF))
    }
}

