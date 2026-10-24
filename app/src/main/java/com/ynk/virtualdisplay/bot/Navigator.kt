package com.ynk.virtualdisplay.bot

class NavigationFailed(message: String) : IllegalStateException(message)

/**
 * Gets the pet back to its room from wherever the game is. This is the bot's one recovery:
 * every action ends with [goHome], and any action that fails is followed by [goHome], so no
 * failure needs its own bespoke way back.
 *
 * Each step looks, takes the one known way out of the current page, and looks again. A page the
 * bot does not know is first given time to settle (most unknown frames are mid-animation), then a
 * back tap, which every pet sub-page honours; only when the page stays unknown or the trip takes
 * too many steps does navigation give up.
 */
class Navigator(
    private val eyes: Eyes,
    private val hands: Hands,
    private val maxSteps: Int = 12,
    private val maxUnknownInARow: Int = 4,
    private val lessonWaitMs: Long = 30_000L,
) {
    /** Returns once the room is showing. A running lesson is waited out, never interrupted. */
    suspend fun goHome(): Observation {
        var unknownInARow = 0
        var lastPage: Page? = null
        var samePageInARow = 0
        repeat(maxSteps) {
            val seen = eyes.look()
            if (seen.page == Page.ROOM) return seen
            samePageInARow = if (seen.page == lastPage) samePageInARow + 1 else 0
            lastPage = seen.page
            unknownInARow = if (seen.page == Page.UNKNOWN) unknownInARow + 1 else 0
            if (unknownInARow >= maxUnknownInARow) throw NavigationFailed("GO_HOME_UNKNOWN_PAGE")
            if (seen.page == Page.UNKNOWN && unknownInARow == 1) hands.pause(Layout.SETTLE_MS)
            else step(seen.page, samePageInARow)
        }
        throw NavigationFailed("GO_HOME_TOO_MANY_STEPS:$lastPage")
    }

    /** One move away from [page]. [retries] counts how often the previous move left it unchanged. */
    private suspend fun step(page: Page, retries: Int) {
        when (page) {
            Page.ROOM -> return
            Page.LEARNING -> { hands.pause(lessonWaitMs); return }
            Page.QQ_HOST -> hands.tap(Layout.reopenPetApp)
            Page.HEALTH_PANEL -> hands.tap(Layout.closeHealth)
            Page.RESULT -> hands.tap(Layout.resultContinue)
            Page.UPGRADE_POPUP -> hands.tap(Layout.dismissUpgrade)
            Page.CARE_UPGRADE_POPUP -> hands.tap(Layout.dismissCareUpgrade)
            // A story bubble can cover the outdoor back arrow; a back that did nothing clears it first.
            Page.OUTDOOR -> hands.tap(if (retries % 2 == 1) Layout.outdoorOverlay else Layout.back)
            Page.FOOD_SCENE, Page.BATH_SCENE, Page.ACADEMY, Page.COURSE_LIST, Page.COURSE_DETAIL,
            Page.TOWN, Page.UNKNOWN -> hands.tap(Layout.back)
        }
        hands.pause(Layout.SETTLE_MS)
    }
}
