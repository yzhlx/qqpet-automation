package com.ynk.virtualdisplay

import com.ynk.virtualdisplay.automation.PetBackNavigation
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The pet mini-program's own back arrow can pop the whole mini-program and leave the QQ search
 * page behind (measured 2026-10-21, session 68564b34: the outdoor map's back arrow ended the
 * session with PAGE_MISMATCH:...:51-health-return-home-after). The recovery decision is pure so
 * it is unit-tested here; the live navigation lives in PetNavigation.returnHomeFromPage.
 */
class PetBackNavigationTest {
    @Test fun aClosedMiniProgramIsReopenedNotAbandoned() {
        assertEquals(PetBackNavigation.Recovery.REENTER_MINI_PROGRAM,
            PetBackNavigation.recovery(hostPage = true, stillOnMap = false, attempt = 1))
    }

    @Test fun theHostPageWinsEvenWhenTheMapProbeAlsoFired() {
        // The host page is the stronger fact: a map probe hit at 0.97 on the QQ search page
        // would be a false identity, and re-opening the pet app is the safe reading either way.
        assertEquals(PetBackNavigation.Recovery.REENTER_MINI_PROGRAM,
            PetBackNavigation.recovery(hostPage = true, stillOnMap = true, attempt = 1))
    }

    @Test fun aCoveredBackArrowIsDismissedAndRetried() {
        assertEquals(PetBackNavigation.Recovery.DISMISS_OVERLAY_AND_RETRY,
            PetBackNavigation.recovery(hostPage = false, stillOnMap = true, attempt = 1))
    }

    @Test fun retriesAreBounded() {
        assertEquals(PetBackNavigation.Recovery.STOP,
            PetBackNavigation.recovery(hostPage = false, stillOnMap = true, attempt = PetBackNavigation.MAX_ATTEMPTS))
    }

    @Test fun anUnknownPageStopsInsteadOfTapping() {
        assertEquals(PetBackNavigation.Recovery.STOP,
            PetBackNavigation.recovery(hostPage = false, stillOnMap = false, attempt = 1))
    }
}
