package com.ynk.virtualdisplay

import com.ynk.virtualdisplay.automation.PetHealthCheck
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The health panel is semi-transparent over the scene, and on virtual display 41 (2026-11-05) the
 * pet sat at 体力 95 / 清洁 27 / 心情 5 in the bath scene. The managed session read the panel
 * (open-health-after 0.9778) and still died in BAR_UNCONFIRMED: the old check demanded 8 px of
 * fill anywhere on the row, and 心情 5 is 7 px of fill; it also matched the blank bar's grey by
 * one exact colour that the blended panel never reproduces (d5d2d2 here, d6d0c5 in the room).
 *
 * The fixture is that recorded frame: /health-panel/panel-bath-display41.png.
 */
class HealthBarRowTest {

    private fun frame(): BufferedImage {
        val stream = javaClass.getResourceAsStream("/health-panel/panel-bath-display41.png")
            ?: error("missing fixture")
        return stream.use { ImageIO.read(it) }
    }

    /** The pixels of one bar row, from the cap to the end of the bar. */
    private fun row(image: BufferedImage, y: Int): IntArray {
        val out = IntArray(505 - PetHealthCheck.BAR_CAP_LEFT + 1)
        for (x in PetHealthCheck.BAR_CAP_LEFT..505) out[x - PetHealthCheck.BAR_CAP_LEFT] = image.getRGB(x, y)
        return out
    }

    private fun read(image: BufferedImage, y: Int, color: Int): Int {
        val pixels = row(image, y)
        assertTrue("no bar on row $y", PetHealthCheck.barPresent(pixels, color))
        val end = PetHealthCheck.fillEnd(pixels, color)
        assertNotNull("no fill on row $y", end)
        return PetHealthCheck.barValue(PetHealthCheck.BAR_CAP_LEFT + end!!)
    }

    @Test
    fun `the recorded panel reads the values the game shows`() {
        val image = frame()
        assertEquals(95, read(image, 244, 0x0099FF))
        assertEquals(27, read(image, 320, 0x64C900))
        assertEquals(5, read(image, 396, 0xFF8000))
    }

    @Test
    fun `a low bar is present even though its fill is shorter than the old run`() {
        val image = frame()
        val pixels = row(image, 396)
        val fill = PetHealthCheck.fillEnd(pixels, 0xFF8000)
        assertNotNull(fill)
        // 心情 5 is 7 px of fill: the old 8 px run could never accept it.
        assertTrue("fill length", fill!! + 1 <= 12)
        assertTrue(PetHealthCheck.barPresent(pixels, 0xFF8000))
    }

    @Test
    fun `the blank bar grey is matched by neutrality not by one colour`() {
        // The three greys measured on 2026-11-05: bath scene, beside it, and the room.
        assertTrue(PetHealthCheck.neutral(0xD5D2D2))
        assertTrue(PetHealthCheck.neutral(0xD3D6D9))
        assertFalse(PetHealthCheck.neutral(0xD6D0C5))
        // The beige wallpaper and the white panel are not greys.
        assertFalse(PetHealthCheck.neutral(0xF9EFDE))
        assertFalse(PetHealthCheck.neutral(0xFFFFFA))
    }

    @Test
    fun `a row without a bar is refused`() {
        val image = frame()
        // The bath wallpaper above the panel carries no bar.
        assertFalse(PetHealthCheck.barPresent(row(image, 100), 0x0099FF))
        assertNull(PetHealthCheck.fillEnd(row(image, 100), 0x0099FF))
    }
}
