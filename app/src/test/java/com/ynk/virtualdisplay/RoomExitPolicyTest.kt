package com.ynk.virtualdisplay

import com.ynk.virtualdisplay.automation.RoomExitPolicy
import org.junit.Assert.*
import org.junit.Test

/** Pure geometry fixtures from the two 720x1600 room layouts; no Android Rect mocks. */
class RoomExitPolicyTest {
    private fun label(text: String = "出门", l: Int = 331, t: Int = 1290,
        r: Int = 389, b: Int = 1322) = RoomExitPolicy.Label(text, l, t, r, b)
    private fun select(vararg labels: RoomExitPolicy.Label) = RoomExitPolicy.select(labels.toList(), 720, 1600)

    @Test fun expandedDrawerClicksTheTextNotOldGlobePoint() {
        // final-room.png: globe ~1255; its label is lower, ~1305.
        assertEquals(RoomExitPolicy.Target(360f, 1306f), select(label()))
    }

    @Test fun collapsedDrawerClicksItsOwnLabel() {
        // resume-room.png: globe ~1390; the label is ~1445, not either stored globe y.
        assertEquals(RoomExitPolicy.Target(360f, 1445f), select(label(t = 1428, b = 1462)))
    }

    @Test fun ignoresUnrelatedNavigationLabelsAndNormalisesOcrWhitespaceOnly() {
        assertEquals(RoomExitPolicy.Target(360f, 1306f), select(
            label("学习", 160, 1430, 220, 1470), label("出 门\n"), label("打工", 330, 1430, 390, 1470)))
        assertNull(select(label("出门看看")))
        assertNull(select(label("户外")))
        assertNull(select())
    }

    @Test fun duplicateExactLabelsAreAmbiguousEvenWhenOneLooksValid() {
        assertNull(select(label(), label(t = 1428, b = 1462)))
        // Do not quietly discard an off-region duplicate and trust the other one.
        assertNull(select(label(), label(l = 10, r = 70)))
        assertNull(select(label(), label()))
    }

    @Test fun outsideRoomNavigationRegionNeverAuthorisesInput() {
        assertNull(select(label(t = 300, b = 340))) // map title / overlay
        assertNull(select(label(l = 580, r = 640))) // right care menu
        assertNull(select(label(t = 1510, b = 1550))) // below allowed navigation area
        assertNull(select(label(l = -5, r = 55)))
        assertNull(select(label(l = 690, r = 750)))
        assertNull(select(label(t = 1580, b = 1620)))
    }

    @Test fun wholeBoundsMustBeInsideNotJustCentre() {
        assertNull(select(label(l = 230, r = 310)))
        assertNull(select(label(t = 1170, b = 1220)))
        assertNull(select(label(t = 1500, b = 1540)))
    }

    @Test fun malformedOrImplausibleTextBoxesAreRejected() {
        assertNull(select(label(l = 389, r = 331)))
        assertNull(select(label(t = 1322, b = 1290)))
        assertNull(select(label(l = 350, r = 350)))
        assertNull(select(label(l = 350, r = 360)))
        assertNull(select(label(t = 1300, b = 1301)))
        assertNull(select(label(l = 240, r = 480)))
        assertNull(select(label(t = 1200, b = 1400)))
    }

    @Test fun uncalibratedDisplayDimensionsAreRejected() {
        assertNull(RoomExitPolicy.select(listOf(label()), 1084, 2412))
        assertNull(RoomExitPolicy.select(listOf(label()), 1600, 720))
        assertNull(RoomExitPolicy.select(listOf(label()), 0, 0))
    }

    @Test fun drawerMovementInvalidatesThePreviouslySelectedPoint() {
        val expanded = select(label())!!
        val collapsed = select(label(t = 1428, b = 1462))!!
        assertFalse(RoomExitPolicy.stable(expanded, collapsed))
        assertTrue(RoomExitPolicy.stable(expanded, RoomExitPolicy.Target(366f, 1310f)))
        assertFalse(RoomExitPolicy.stable(expanded, RoomExitPolicy.Target(373f, 1306f)))
        assertFalse(RoomExitPolicy.stable(expanded, RoomExitPolicy.Target(360f, 1319f)))
    }
}
