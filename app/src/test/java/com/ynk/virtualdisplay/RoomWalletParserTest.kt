package com.ynk.virtualdisplay

import com.ynk.virtualdisplay.automation.ObservedValue
import com.ynk.virtualdisplay.automation.RoomWalletParser
import com.ynk.virtualdisplay.automation.TextLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import android.graphics.Rect

/**
 * The room wallet reader feeds the care purchase gate, so it must never invent a balance: an
 * unreadable or ambiguous top bar has to come back Unknown so the purchase stops instead of being
 * attempted on a guess.
 */
class RoomWalletParserTest {

    private val width = 720
    private val height = 1600

    /** Builds a line at a frame-pixel centre without touching Rect's stubbed constructor. */
    private fun line(text: String, cx: Int, cy: Int, w: Int = 44, h: Int = 30): TextLine {
        val rect = Rect()
        rect.left = cx - w / 2
        rect.right = cx + w / 2
        rect.top = cy - h / 2
        rect.bottom = cy + h / 2
        return TextLine(text, rect)
    }

    @Test fun readsTheCoinNumberInTheTopBar() {
        val lines = listOf(
            line("4", 186, 260),          // level star, measured on the s21 room frame
            line("11", 380, 260),         // paw pill, measured x 370..391
            line("86", 524, 260),         // coin pill, measured x 512..537
            line("习武小童", 300, 355),
        )
        val value = RoomWalletParser.parse(lines, width, height, 1000L, "fixture")
        assertEquals(86, (value as ObservedValue.Known).value)
    }

    @Test fun unreadableRegionIsUnknownAndNeverZero() {
        val lines = listOf(line("4", 186, 260), line("智", 524, 260))
        val value = RoomWalletParser.parse(lines, width, height, 1000L, "fixture")
        assertTrue("a non-numeric coin area must be Unknown, not 0",
            value is ObservedValue.Unknown)
    }

    @Test fun twoCandidatesAreAmbiguousRatherThanGuessed() {
        val lines = listOf(line("86", 524, 260), line("68", 530, 262))
        val value = RoomWalletParser.parse(lines, width, height, 1000L, "fixture")
        assertTrue(value is ObservedValue.Unknown)
        assertTrue((value as ObservedValue.Unknown).reason.startsWith(
            "WALLET_COIN_REGION_AMBIGUOUS"))
    }

    @Test fun numbersOutsideTheCoinPillAreIgnored() {
        // The level "4" and the paw count "11" must not be mistaken for money.
        val lines = listOf(line("4", 186, 260), line("11", 380, 260), line("86", 524, 260))
        val value = RoomWalletParser.parse(lines, width, height, 1000L, "fixture")
        assertEquals(86, (value as ObservedValue.Known).value)
    }

    @Test fun zeroIsAReadableBalance() {
        val value = RoomWalletParser.parse(
            listOf(line("0", 524, 260)), width, height, 1000L, "fixture")
        assertEquals(0, (value as ObservedValue.Known).value)
    }

    @Test fun invalidFrameGeometryFailsClosed() {
        val value = RoomWalletParser.parse(emptyList(), 0, 0, 1000L, "fixture")
        assertTrue(value is ObservedValue.Unknown)
    }
}
