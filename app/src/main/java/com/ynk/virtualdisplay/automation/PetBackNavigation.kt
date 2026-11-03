package com.ynk.virtualdisplay.automation

/**
 * What a failed back-arrow navigation may try next.
 *
 * The pet mini-program's own back arrow can pop one level too far and leave the QQ host page
 * behind instead of the pet room (measured 2026-10-21, session 68564b34: the outdoor map's back
 * arrow landed on the QQ search page and the session died with
 * `PAGE_MISMATCH:SELECT_COURSE:51-health-return-home-after`). The decision is pure so it can be
 * unit-tested without a device; the recovery itself needs a live frame and lives in
 * `PetNavigation.returnHomeFromMap`.
 */
object PetBackNavigation {
    /** How many back attempts one chain may make before it gives up. */
    const val MAX_ATTEMPTS = 3

    enum class Recovery {
        /** The mini-program is gone: re-open it through its own QQ entry and continue. */
        REENTER_MINI_PROGRAM,

        /** The map is still there, so the back arrow was covered: dismiss and try once more. */
        DISMISS_OVERLAY_AND_RETRY,

        /** Neither: the page is unknown, so the chain stops instead of tapping into the dark. */
        STOP,
    }

    /**
     * @param hostPage the QQ host page is showing, i.e. the mini-program was closed
     * @param stillOnMap the pet map page is still showing, i.e. the back arrow was covered
     * @param attempt how many back taps have already been made (1 = the first one failed)
     */
    fun recovery(hostPage: Boolean, stillOnMap: Boolean, attempt: Int): Recovery = when {
        hostPage -> Recovery.REENTER_MINI_PROGRAM
        stillOnMap && attempt < MAX_ATTEMPTS -> Recovery.DISMISS_OVERLAY_AND_RETRY
        else -> Recovery.STOP
    }
}
