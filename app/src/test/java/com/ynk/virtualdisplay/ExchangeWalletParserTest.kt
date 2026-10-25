package com.ynk.virtualdisplay

import android.graphics.Rect
import com.ynk.virtualdisplay.automation.*
import org.junit.Assert.*
import org.junit.Test

class ExchangeWalletParserTest {
    private fun line(text:String,l:Int=638,t:Int=865,r:Int=676,b:Int=895):TextLine {
        val rect=Rect();rect.left=l;rect.top=t;rect.right=r;rect.bottom=b
        return TextLine(text,rect)
    }
    private fun parse(lines:List<TextLine>,verified:Boolean=true,w:Int=720,h:Int=1600)=
        ExchangeWalletParser.parse(lines,w,h,10_000L,"wallet-crop",verified)
    @Test fun readsSheetWalletFromItsOwnNumericPill() {
        assertEquals(86,(parse(listOf(line("86"))) as ObservedValue.Known).value)
    }
    @Test fun exactZeroIsObservedNotAssumed() {
        assertEquals(0,(parse(listOf(line("0"))) as ObservedValue.Known).value)
    }
    @Test fun roomWalletAndProductPriceDoNotSupplyDialogWallet() {
        assertTrue(parse(listOf(line("287",510,245,548,275),line("5",110,1150,140,1180))) is ObservedValue.Unknown)
    }
    @Test fun missingWalletIsUnknown() { assertTrue(parse(emptyList()) is ObservedValue.Unknown) }
    @Test fun twoCandidatesAreAmbiguous() { assertTrue(parse(listOf(line("86"),line("68"))) is ObservedValue.Unknown) }
    @Test fun unverifiedDialogCannotAuthorizeKnownWallet() { assertTrue(parse(listOf(line("86")),false) is ObservedValue.Unknown) }
    @Test fun unsupportedDisplayCannotReuseReferenceRoi() { assertTrue(parse(listOf(line("86")),w=1084,h=2412) is ObservedValue.Unknown) }
    @Test fun malformedOrPartlyOutsideBoundsAreRejected() {
        assertTrue(parse(listOf(line("86",638,865,637,895))) is ObservedValue.Unknown)
        assertTrue(parse(listOf(line("86",620,865,676,895))) is ObservedValue.Unknown)
    }
    @Test fun clippedDigitAtCropEdgeCannotAuthorizeAResourceCount() {
        assertTrue(parse(listOf(line("86",638,865,692,895))) is ObservedValue.Unknown)
        // A digit line that merely grazes the ROI edge still counts when its centre is inside.
        val inventory=SceneInventoryParser.parse(InventoryScenePage.BATH,
            listOf(line("5",415,1218,470,1262)),720,1600,10_000L,"crop")
        assertEquals(5,(inventory.soapCount as ObservedValue.Known).value)
    }
    @Test fun nonNumericTextIsNotMoney() { assertTrue(parse(listOf(line("金币"))) is ObservedValue.Unknown) }
    @Test fun inventoryCannotReuseItsRoiOnDifferentResolution() {
        assertFalse(SceneInventoryParser.validateRoi(InventoryScenePage.FOOD,1084,2412))
        assertTrue(SceneInventoryParser.parse(InventoryScenePage.FOOD,listOf(line("0",452,1192,478,1224)),
            1084,2412,10_000L,"inventory-crop").cookieCount is ObservedValue.Unknown)
    }
}
