package com.ynk.virtualdisplay

import com.ynk.virtualdisplay.automation.ExchangeDialogRenderPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * QQ paints the exchange sheet in two phases: the title appears first on a blank white body and
 * only a later frame carries the product tile, the quantity stepper and the pay button. The
 * title matches in both phases, so the flow has to wait for the body before it reads a number.
 *
 * The two recorded frames under test/resources/exchange-dialog are the real capture of that race
 * on DKS9K23420020242 (virtual display 39, 720x1600): the blank one stopped the managed run
 * with EXCHANGE_QUANTITY_UNREADABLE:DIALOG_DIGITS_ABSENT, the other one is the frame the cookie
 * was actually bought on.
 */
class ExchangeDialogRenderPolicyTest {

    /** The pay band of a recorded frame, cut exactly where production cuts it. */
    private fun bandOf(resource: String): IntArray {
        val stream = javaClass.getResourceAsStream(resource) ?: error("missing fixture " + resource)
        val image = stream.use { javax.imageio.ImageIO.read(it) }
        val width = ExchangeDialogRenderPolicy.BAND_RIGHT - ExchangeDialogRenderPolicy.BAND_LEFT
        val height = ExchangeDialogRenderPolicy.BAND_BOTTOM - ExchangeDialogRenderPolicy.BAND_TOP
        val pixels = IntArray(width * height)
        image.getRGB(ExchangeDialogRenderPolicy.BAND_LEFT, ExchangeDialogRenderPolicy.BAND_TOP,
            width, height, pixels, 0, width)
        return pixels
    }

    private fun band(painted: Int, total: Int = 58500): IntArray =
        IntArray(total) { if (it < painted) 0xFF3C78DC.toInt() else 0xFFFFFFFF.toInt() }

    @Test
    fun `the recorded blank sheet is not a usable dialog`() {
        assertFalse(ExchangeDialogRenderPolicy.rendered(bandOf("/exchange-dialog/dialog-half-rendered.png")))
    }

    @Test
    fun `the recorded complete sheet is a usable dialog`() {
        assertTrue(ExchangeDialogRenderPolicy.rendered(bandOf("/exchange-dialog/dialog-complete.png")))
    }

    @Test
    fun `an empty band is never rendered`() {
        assertFalse(ExchangeDialogRenderPolicy.rendered(IntArray(0)))
    }

    @Test
    fun `a white band is not rendered`() {
        assertFalse(ExchangeDialogRenderPolicy.rendered(band(0)))
    }

    @Test
    fun `a half painted band is still refused`() {
        // The recorded blank frame leaves 0 painted pixels; a band that is even 40% painted must
        // still be refused, so a half-drawn sheet can never pass as a usable dialog.
        assertFalse(ExchangeDialogRenderPolicy.rendered(band(40 * 58500 / 100)))
    }

    @Test
    fun `the measured complete band passes the threshold`() {
        // 55096/58500 painted pixels (94.2%) in the recorded complete frame.
        assertTrue(ExchangeDialogRenderPolicy.rendered(band(55096)))
    }
}
