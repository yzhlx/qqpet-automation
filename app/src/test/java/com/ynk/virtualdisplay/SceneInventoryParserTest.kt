package com.ynk.virtualdisplay

import com.ynk.virtualdisplay.automation.CareItem
import com.ynk.virtualdisplay.automation.InventoryObservation
import com.ynk.virtualdisplay.automation.InventoryScenePage
import com.ynk.virtualdisplay.automation.ObservedValue
import com.ynk.virtualdisplay.automation.SceneInventoryParser
import com.ynk.virtualdisplay.automation.TextLine
import org.junit.Assert.*
import org.junit.Test
import android.graphics.Rect

/**
 * The scene stock rules, pinned to the measured s21-reenter fixtures. This parser is the only door a
 * purchase gate trusts for "how many do I own": a wrong number here either buys a duplicate the pet
 * already holds or refuses a care a poor pet could have performed from stock.
 */
class SceneInventoryParserTest {

    private val now = 1_000_000L

    /** One OCR line. Rect fields are set directly (the mockable android.Rect ignores ctor args). */
    private fun line(text: String, l: Int, t: Int, r: Int, b: Int): TextLine {
        val rect = Rect(); rect.left = l; rect.top = t; rect.right = r; rect.bottom = b
        return TextLine(text, rect)
    }

    private fun knownCount(value: ObservedValue<Int>) =
        (value as? ObservedValue.Known)?.value

    @Test fun bathSoapFiveIsExact() {
        // 2026-10-22 pixel analysis: the soap counter digit is at x=448..471, y=1224..1255.
        val inv = SceneInventoryParser.parse(InventoryScenePage.BATH,
            listOf(line("5", 448, 1224, 471, 1255)), 720, 1600, now, "ev")
        assertEquals(5, knownCount(inv.soapCount))
        assertTrue("an unread cookie on the bath page is not 0", inv.cookieCount is ObservedValue.Unknown)
    }

    @Test fun bathSoapAfterOneScrubIsFour() {
        val inv = SceneInventoryParser.parse(InventoryScenePage.BATH,
            listOf(line("4", 448, 1224, 471, 1255)), 720, 1600, now, "ev")
        assertEquals(4, knownCount(inv.soapCount))
    }

    @Test fun aDigitOutsideTheMeasuredSoapRegionIsNotStock() {
        // The soap counter is the blue glyph at x=448..471, y=1224..1255; a number elsewhere on
        // the bath page is some other UI element and must not become soap stock.
        val inv = SceneInventoryParser.parse(InventoryScenePage.BATH,
            listOf(line("1", 448, 1140, 471, 1170)), 720, 1600, now, "ev")
        assertTrue(inv.soapCount is ObservedValue.Unknown)
    }

    @Test fun foodCookieZeroIsExactAndIsNotTreatedAsAbsent() {
        // round-1-29-leave-food: cookie ART is visible but the count digit reads 0.
        val inv = SceneInventoryParser.parse(InventoryScenePage.FOOD,
            listOf(line("0", 452, 1192, 478, 1224)), 720, 1600, now, "ev")
        assertEquals(0, knownCount(inv.cookieCount))
        assertTrue(inv.soapCount is ObservedValue.Unknown)
    }

    @Test fun aSceneWithNoCountDigitIsUnknownNeverOne() {
        // The 「＋ 兑换食物」 food bubble has no digit: seeing the cookie art proves nothing.
        val inv = SceneInventoryParser.parse(InventoryScenePage.FOOD,
            listOf(line("＋", 300, 1180, 350, 1230), line("兑换食物", 300, 1235, 520, 1290)),
            720, 1600, now, "ev")
        assertTrue(inv.cookieCount is ObservedValue.Unknown)
        assertNull(knownCount(inv.cookieCount))
    }

    @Test fun aTopBarPillOutsideTheItemRoiIsNeverTheItemCount() {
        // Coin/paw pills live at the very top; the soap ROI is near y=1200, so a "81" up top is not stock.
        val inv = SceneInventoryParser.parse(InventoryScenePage.BATH,
            listOf(line("81", 500, 250, 560, 290)), 720, 1600, now, "ev")
        assertTrue(inv.soapCount is ObservedValue.Unknown)
    }

    @Test fun twoDigitsInsideTheRoiAreAmbiguousNotAPick() {
        val inv = SceneInventoryParser.parse(InventoryScenePage.BATH,
            listOf(line("5", 425, 1220, 455, 1258), line("6", 462, 1220, 492, 1258)),
            720, 1600, now, "ev")
        assertTrue(inv.soapCount is ObservedValue.Unknown)
    }

    @Test fun malformedFrameSizeIsUnknownNotZero() {
        val inv = SceneInventoryParser.parse(InventoryScenePage.FOOD, emptyList(), 0, 0, now, "ev")
        assertTrue(inv.cookieCount is ObservedValue.Unknown)
    }

    @Test fun itemAndScenePairingIsEnforced() {
        assertEquals(CareItem.COOKIE, SceneInventoryParser.itemOf(InventoryScenePage.FOOD))
        assertEquals(CareItem.SOAP, SceneInventoryParser.itemOf(InventoryScenePage.BATH))
    }

    @Test fun roiStaysInsideTheReferenceFrame() {
        assertTrue(SceneInventoryParser.validateRoi(InventoryScenePage.FOOD, 720, 1600))
        assertTrue(SceneInventoryParser.validateRoi(InventoryScenePage.BATH, 720, 1600))
        assertFalse(SceneInventoryParser.validateRoi(InventoryScenePage.BATH, 300, 300))
    }
}
