package com.ynk.virtualdisplay.automation

/**
 * How to bring the QQ pet mini-program onto the display when it is not there yet.
 *
 * The entry chain is anchored on what the screen actually shows, never on blind taps,
 * because a wrong tap inside QQ can open a conversation and send a message. The three
 * anchors are template probes ([PetPageProbes.qqMessageSearchBox],
 * [PetPageProbes.qqSearchHistoryPet], [PetPageProbes.qqHostSearch],
 * [PetPageProbes.qqSearchPetRow]) plus the final pet [PetPageProbes.room].
 *
 * The decision is pure (screen-state -> next action) so it can be unit-tested on the host;
 * the taps and waits live in [PetNavigation.enterPetApp].
 *
 * MEASURED 2026-11-06 on VD47 (720x1600): the search box opens the search page, the
 * history word "宠物" sits in the recent-searches row, tapping it fills the box and shows the
 * "宠物 - 功能" row, and one tap on that row's orange icon lands on the pet ROOM page.
 * Shell text injection into the QQ search box was tried and REJECTED: `input text 宠物` throws
 * a NullPointerException (the box refuses non-IME injection), which is why the history word
 * is used instead.
 */
object PetReentry {
    /** The screen is on one of the recognised QQ pages, or none of them. */
    enum class Screen {
        /** QQ message tab, search box visible. */
        MESSAGE_SEARCH_BOX,
        /** QQ search page, recent-searches "宠物" visible. */
        SEARCH_HISTORY_PET,
        /** QQ search results page, "宠物 - 功能" row visible. */
        SEARCH_RESULT_PET,
        /** The pet room is already showing: nothing to do. */
        ROOM,
        /**
         * QQ's bottom tab bar is showing but not on the message tab (联系人, 频道, 动态): a tap on the
         * bar's own 消息 label brings the message tab back. Read from text, see [PetEntryText.messageTabPoint].
         */
        OTHER_TAB,
        /** None of the above: stop rather than tap into an unknown page. */
        UNKNOWN,
    }

    /** One recovery step; the chain halts on [Action.STOP]. */
    enum class Action {
        /** Open the search page from the message tab. */
        OPEN_SEARCH,
        /** Tap the recent-searches "宠物" word to fill the box. */
        TAP_HISTORY_PET,
        /** Tap the "宠物 - 功能" row's orange icon to enter the pet room. */
        TAP_PET_ROW,
        /** Tap the bottom bar's 消息 tab to get back to the message page. */
        TAP_MESSAGE_TAB,
        /** Already on the room: done. */
        DONE,
        /** Unknown page after the allowed steps: stop the chain. */
        STOP,
    }

    /**
     * @param screen what the current frame actually matched
     * @param historyTaps how many times the history word has been tapped this chain
     * @param tabTaps how many times the message tab has been tapped this chain
     */
    fun next(screen: Screen, historyTaps: Int, tabTaps: Int = 0): Action = when(screen) {
        Screen.ROOM -> Action.DONE
        Screen.OTHER_TAB -> if(tabTaps < MAX_TAB_TAPS) Action.TAP_MESSAGE_TAB else Action.STOP
        Screen.MESSAGE_SEARCH_BOX -> Action.OPEN_SEARCH
        Screen.SEARCH_HISTORY_PET -> if(historyTaps < MAX_HISTORY_TAPS) Action.TAP_HISTORY_PET else Action.STOP
        Screen.SEARCH_RESULT_PET -> Action.TAP_PET_ROW
        Screen.UNKNOWN -> Action.STOP
    }

    /** How many times the history word may be tapped before the chain gives up. */
    const val MAX_HISTORY_TAPS = 3

    /** The message tab is tapped once: if that does not bring the message page, something else is wrong. */
    const val MAX_TAB_TAPS = 1
}
