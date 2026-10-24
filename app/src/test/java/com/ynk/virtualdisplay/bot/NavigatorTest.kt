package com.ynk.virtualdisplay.bot

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigatorTest {
    private fun home(game: FakeGame) = runBlocking { Navigator(game, game).goHome() }

    @Test fun everyKnownPageLeadsHome() {
        val pages = Page.values().filter { it != Page.LEARNING && it != Page.UNKNOWN }
        for (start in pages) {
            val game = FakeGame(start)
            assertEquals("from $start", Page.ROOM, home(game).page)
        }
    }

    @Test fun roomNeedsNoInput() {
        val game = FakeGame(Page.ROOM)
        home(game)
        assertTrue(game.taps.isEmpty())
    }

    @Test fun resultWalksBackThroughTheAcademy() {
        val game = FakeGame(Page.RESULT)
        home(game)
        assertEquals(listOf(Page.RESULT, Page.COURSE_LIST, Page.ACADEMY, Page.OUTDOOR), game.taps.map { it.first })
    }

    @Test fun closedMiniProgramIsReopened() {
        val game = FakeGame(Page.QQ_HOST)
        home(game)
        assertEquals(listOf(Page.QQ_HOST to Layout.reopenPetApp), game.taps)
    }

    @Test fun storyBubbleOverTheOutdoorBackArrowIsCleared() {
        // The first back does nothing (bubble on top); tapping the bubble clears it.
        var bubble = true
        val moves = FakeGame.standardMoves.toMutableMap().apply { remove(Page.OUTDOOR to Layout.back) }
        val game = FakeGame(Page.OUTDOOR, moves)
        game.onPause = { g ->
            if (g.taps.lastOrNull() == (Page.OUTDOOR to Layout.outdoorOverlay)) bubble = false
            if (!bubble && g.taps.lastOrNull() == (Page.OUTDOOR to Layout.back)) g.page = Page.ROOM
        }
        home(game)
        assertEquals(listOf(Layout.back, Layout.outdoorOverlay, Layout.back), game.taps.map { it.second })
    }

    @Test fun runningLessonIsWaitedOutNotInterrupted() {
        val game = FakeGame(Page.LEARNING)
        var waits = 0
        game.onPause = { g -> if (g.page == Page.LEARNING && ++waits == 3) g.page = Page.RESULT }
        home(game)
        assertTrue(game.taps.none { it.first == Page.LEARNING })
        assertEquals(Page.ROOM, game.page)
    }

    @Test fun oneUnknownFrameIsWaitedOutWithoutInput() {
        val game = FakeGame(Page.ROOM)
        game.interjections += Page.UNKNOWN
        home(game)
        assertTrue(game.taps.isEmpty())
    }

    @Test fun anUnknownPageGetsABackTap() {
        val moves = FakeGame.standardMoves + ((Page.UNKNOWN to Layout.back) to Page.OUTDOOR)
        val game = FakeGame(Page.UNKNOWN, moves)
        home(game)
        assertEquals(Page.UNKNOWN to Layout.back, game.taps.first())
    }

    @Test(expected = NavigationFailed::class)
    fun aPageThatStaysUnknownStops() {
        home(FakeGame(Page.UNKNOWN))
    }

    @Test(expected = NavigationFailed::class)
    fun aPageThatNeverLeadsHomeStops() {
        home(FakeGame(Page.ACADEMY, emptyMap()))
    }
}
