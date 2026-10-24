package com.ynk.virtualdisplay.bot

/**
 * A scripted stand-in for the phone. [moves] says where each tap leads from each page; a tap
 * with no entry leaves the page unchanged, as a tap on nothing does in the real game.
 */
class FakeGame(
    var page: Page,
    private val moves: Map<Pair<Page, Point>, Page> = standardMoves,
) : Eyes, Hands {
    val taps = mutableListOf<Pair<Page, Point>>()
    var pausedMs = 0L
    /** Pages to show on the next looks before [page], e.g. animation frames. */
    val interjections = ArrayDeque<Page>()
    /** Called on every pause, so a test can let time move the game on. */
    var onPause: (FakeGame) -> Unit = {}
    private var clock = 0L

    override suspend fun look(): Observation = Observation(interjections.removeFirstOrNull() ?: page, clock++)

    override suspend fun tap(point: Point) {
        taps += page to point
        moves[page to point]?.let { page = it }
    }

    override suspend fun drag(points: List<Point>, stepMs: Long) {}

    override suspend fun pause(ms: Long) {
        pausedMs += ms
        onPause(this)
    }

    companion object {
        val standardMoves: Map<Pair<Page, Point>, Page> = mapOf(
            (Page.QQ_HOST to Layout.reopenPetApp) to Page.ROOM,
            (Page.HEALTH_PANEL to Layout.closeHealth) to Page.ROOM,
            (Page.FOOD_SCENE to Layout.back) to Page.ROOM,
            (Page.BATH_SCENE to Layout.back) to Page.ROOM,
            (Page.CARE_UPGRADE_POPUP to Layout.dismissCareUpgrade) to Page.FOOD_SCENE,
            (Page.OUTDOOR to Layout.back) to Page.ROOM,
            (Page.ACADEMY to Layout.back) to Page.OUTDOOR,
            (Page.COURSE_LIST to Layout.back) to Page.ACADEMY,
            (Page.COURSE_DETAIL to Layout.back) to Page.ACADEMY,
            (Page.RESULT to Layout.resultContinue) to Page.COURSE_LIST,
            (Page.UPGRADE_POPUP to Layout.dismissUpgrade) to Page.ACADEMY,
            (Page.TOWN to Layout.back) to Page.OUTDOOR,
        )
    }
}
