package com.ynk.virtualdisplay

import com.ynk.virtualdisplay.automation.PetReentry
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * How a cold virtual display reaches the pet room. The screen-state -> action decision is pure,
 * so it is unit-tested here; the taps and template matching live in PetNavigation.enterPetApp.
 *
 * MEASURED 2026-11-06 VD47: message search box (360,271) opens the search page, the history word
 * "宠物" (58,632) fills the box, and the "宠物 - 功能" row icon (98,502) lands on the room. An
 * unknown page must STOP rather than tap into a QQ conversation.
 */
class PetReentryTest {
    @Test fun roomNeedsNoAction() {
        assertEquals(PetReentry.Action.DONE,
            PetReentry.next(PetReentry.Screen.ROOM, historyTaps = 0))
    }

    @Test fun messageSearchBoxOpensSearch() {
        assertEquals(PetReentry.Action.OPEN_SEARCH,
            PetReentry.next(PetReentry.Screen.MESSAGE_SEARCH_BOX, historyTaps = 0))
    }

    @Test fun searchHistoryPetFillsTheBox() {
        assertEquals(PetReentry.Action.TAP_HISTORY_PET,
            PetReentry.next(PetReentry.Screen.SEARCH_HISTORY_PET, historyTaps = 0))
    }

    @Test fun searchResultPetRowEntersTheRoom() {
        assertEquals(PetReentry.Action.TAP_PET_ROW,
            PetReentry.next(PetReentry.Screen.SEARCH_RESULT_PET, historyTaps = 1))
    }

    @Test fun unknownPageStopsInsteadOfTapping() {
        assertEquals(PetReentry.Action.STOP,
            PetReentry.next(PetReentry.Screen.UNKNOWN, historyTaps = 0))
    }

    @Test fun anotherQqTabGoesBackToTheMessageTabOnce() {
        assertEquals(PetReentry.Action.TAP_MESSAGE_TAB,
            PetReentry.next(PetReentry.Screen.OTHER_TAB, historyTaps = 0, tabTaps = 0))
        assertEquals(PetReentry.Action.STOP,
            PetReentry.next(PetReentry.Screen.OTHER_TAB, historyTaps = 0, tabTaps = PetReentry.MAX_TAB_TAPS))
    }

    @Test fun historyTapsAreBounded() {
        assertEquals(PetReentry.Action.STOP,
            PetReentry.next(PetReentry.Screen.SEARCH_HISTORY_PET,
                historyTaps = PetReentry.MAX_HISTORY_TAPS))
    }
}
