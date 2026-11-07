package com.ynk.virtualdisplay

import com.ynk.virtualdisplay.automation.ExchangeDialogRenderPolicy
import com.ynk.virtualdisplay.automation.ExchangeSheetLayout
import com.ynk.virtualdisplay.automation.TemplateMatcher
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The exchange sheet is not nailed to fixed screen rows. Measured 2026-11-05 on
 * DKS9K23420020242: the same sheet sat 22 px higher on virtual display 40 than on display 39
 * (title ink 840..873 instead of 798..895, quantity digit 1267..1286 instead of 1289..1308, pay
 * button 1348..1443 instead of 1370..1465). The title template scores 0.9967 at -22 and only
 * 0.6533 at 0, and the coarse 8 px search cannot reach 22 at all, so the managed run stopped
 * with EXCHANGE_DIALOG_UNCONFIRMED:0.629.
 *
 * The fixtures are the two recorded frames: /exchange-dialog/dialog-complete.png is the
 * display-39 frame the sheet was calibrated on, /exchange-dialog/sheet-display40.png is the
 * display-40 frame with the same sheet 22 px higher.
 */
class ExchangeSheetDriftTest {

    private fun image(resource: String): BufferedImage {
        val stream = javaClass.getResourceAsStream(resource) ?: error("missing fixture " + resource)
        return stream.use { ImageIO.read(it) }
    }

    /** Box-average downsample to 24x24: the shape TemplateMatcher compares. */
    private fun downsample(image: BufferedImage, size: Int): IntArray {
        val out = IntArray(size * size)
        for (y in 0 until size) for (x in 0 until size) {
            val x0 = x * image.width / size
            val x1 = ((x + 1) * image.width / size).coerceIn(x0 + 1, image.width)
            val y0 = y * image.height / size
            val y1 = ((y + 1) * image.height / size).coerceIn(y0 + 1, image.height)
            var red = 0L; var green = 0L; var blue = 0L; var count = 0L
            for (py in y0 until y1) for (px in x0 until x1) {
                val color = image.getRGB(px, py)
                red += (color shr 16) and 0xFF; green += (color shr 8) and 0xFF; blue += color and 0xFF
                count++
            }
            out[y * size + x] = ((red / count).toInt() shl 16) or ((green / count).toInt() shl 8) or
                (blue / count).toInt()
        }
        return out
    }

    private fun similarity(live: BufferedImage, template: BufferedImage): Double {
        val a = downsample(live, 24); val b = downsample(template, 24)
        var diff = 0.0
        for (i in a.indices) {
            diff += abs(((a[i] shr 16) and 0xFF) - ((b[i] shr 16) and 0xFF)).toDouble()
            diff += abs(((a[i] shr 8) and 0xFF) - ((b[i] shr 8) and 0xFF)).toDouble()
            diff += abs((a[i] and 0xFF) - (b[i] and 0xFF)).toDouble()
        }
        return (1.0 - diff / (24.0 * 24.0 * 3.0 * 255.0)).coerceIn(0.0, 1.0)
    }

    /** The drift of the sheet, searched to the pixel like the exact title probe. */
    private fun bestDrift(frame: BufferedImage, template: BufferedImage,
        region: ExchangeSheetLayout.Region): Pair<Int, Double> {
        var best = -1.0; var drift = 0
        for (dy in -64..64) {
            val top = region.top + dy
            if (top < 0 || top + region.height > frame.height) continue
            val score = similarity(frame.getSubimage(region.left, top, region.width, region.height), template)
            if (score > best) { best = score; drift = dy }
        }
        return drift to best
    }

    private fun pixels(frame: BufferedImage, region: ExchangeSheetLayout.Region): IntArray {
        val out = IntArray(region.width * region.height)
        frame.getRGB(region.left, region.top, region.width, region.height, out, 0, region.width)
        return out
    }

    private fun countDark(frame: BufferedImage, region: ExchangeSheetLayout.Region): Int {
        var count = 0
        for (color in pixels(frame, region)) {
            val red = (color shr 16) and 0xFF; val green = (color shr 8) and 0xFF; val blue = color and 0xFF
            if (maxOf(red, green, blue) < 100) count++
        }
        return count
    }

    private fun countWhite(frame: BufferedImage, region: ExchangeSheetLayout.Region): Int {
        var count = 0
        for (color in pixels(frame, region)) {
            val red = (color shr 16) and 0xFF; val green = (color shr 8) and 0xFF; val blue = color and 0xFF
            if (red > 200 && green > 200 && blue > 200) count++
        }
        return count
    }

    @Test
    fun `the display 40 sheet is found 22 px higher than the calibration`() {
        val template = image("/exchange-dialog/dialog_food_title.png")
        val frame = image("/exchange-dialog/sheet-display40.png")
        val (drift, score) = bestDrift(frame, template, ExchangeSheetLayout.title(false, 0))
        assertEquals(-22, drift)
        assertTrue("score $score", score >= 0.95)
    }

    @Test
    fun `the display 39 sheet is still found at the calibration`() {
        val template = image("/exchange-dialog/dialog_food_title.png")
        val frame = image("/exchange-dialog/dialog-complete.png")
        val (drift, score) = bestDrift(frame, template, ExchangeSheetLayout.title(false, 0))
        assertEquals(0, drift)
        assertTrue("score $score", score >= 0.95)
    }

    @Test
    fun `the coarse search cannot reach the measured drift`() {
        assertTrue(-22 in TemplateMatcher.drifts(true))
        assertFalse(-22 in TemplateMatcher.drifts(false))
    }

    @Test
    fun `the quantity digits are only inside the drifted region`() {
        val frame = image("/exchange-dialog/sheet-display40.png")
        val drifted = countDark(frame, ExchangeSheetLayout.quantity(-22))
        val baseline = countDark(frame, ExchangeSheetLayout.quantity(0))
        assertTrue("drifted $drifted baseline $baseline", drifted >= 60)
        assertTrue("drifted $drifted baseline $baseline", drifted >= baseline * 3)
    }

    @Test
    fun `the pay amount is only inside the drifted region`() {
        val frame = image("/exchange-dialog/sheet-display40.png")
        val drifted = countWhite(frame, ExchangeSheetLayout.payTotal(-22))
        val baseline = countWhite(frame, ExchangeSheetLayout.payTotal(0))
        assertTrue("drifted $drifted baseline $baseline", drifted >= 250)
        assertTrue("drifted $drifted baseline $baseline", drifted >= baseline * 2)
    }

    @Test
    fun `the coin counter is inside the drifted region`() {
        val frame = image("/exchange-dialog/sheet-display40.png")
        assertTrue(countDark(frame, ExchangeSheetLayout.coin(-22)) > 0)
    }

    @Test
    fun `the pay band follows the drift and still proves a painted sheet`() {
        val frame = image("/exchange-dialog/sheet-display40.png")
        assertTrue(ExchangeDialogRenderPolicy.rendered(pixels(frame, ExchangeSheetLayout.payBand(-22))))
        // The band must stay on the button: the drifted band covers more of it than the stale one.
        val drifted = pixels(frame, ExchangeSheetLayout.payBand(-22)).size
        val baseline = pixels(frame, ExchangeSheetLayout.payBand(0)).size
        assertTrue(drifted >= baseline)
    }

    @Test
    fun `every region and tap of the sheet moves with the drift`() {
        assertEquals(ExchangeSheetLayout.Region(278, 848, 432, 902), ExchangeSheetLayout.title(false, 0))
        assertEquals(ExchangeSheetLayout.Region(278, 826, 432, 880), ExchangeSheetLayout.title(false, -22))
        assertEquals(ExchangeSheetLayout.Region(268, 824, 512, 884), ExchangeSheetLayout.title(true, -22))
        assertEquals(ExchangeSheetLayout.Region(30, 953, 205, 1178), ExchangeSheetLayout.selected(-22))
        assertEquals(ExchangeSheetLayout.Region(535, 1263, 625, 1293), ExchangeSheetLayout.quantity(-22))
        assertEquals(ExchangeSheetLayout.Region(390, 1378, 445, 1413), ExchangeSheetLayout.payTotal(-22))
        assertEquals(ExchangeSheetLayout.Region(616, 826, 692, 893), ExchangeSheetLayout.coin(-22))
        assertEquals(ExchangeSheetLayout.Region(35, 1358, 685, 1448), ExchangeSheetLayout.payBand(-22))
        assertEquals(517 to 1255, ExchangeSheetLayout.minusTap(-22))
        assertEquals(360 to 1403, ExchangeSheetLayout.payTap(-22))
        assertEquals(517 to 1277, ExchangeSheetLayout.minusTap(0))
        assertEquals(360 to 1425, ExchangeSheetLayout.payTap(0))
    }
}
