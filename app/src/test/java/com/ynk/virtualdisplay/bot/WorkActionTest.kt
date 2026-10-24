package com.ynk.virtualdisplay.bot

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WorkActionTest {
    private val exit = Point(600f, 1340f)
    private val townEntry = Point(520f, 700f)
    private val venue = Point(300f, 820f)
    private val option = Point(180f, 640f)
    private val start = Point(360f, 1480f)
    private val nowhere = Point(0f, 0f)

    private enum class Town { MAP, DETAIL, SELECTED, WORKING, PAYROLL }

    /** The career town as text screens. [venueAfterScrolls] scrolls are needed before the venue shows. */
    private class World(val pet: FakePet, var town: Town = Town.MAP, var scrolls: Int = 0)

    private fun world(
        venueAfterScrolls: Int = 0,
        starts: Boolean = true,
        payroll: String? = "打工完成 帮武馆跑腿 用时10分钟 金币 +68",
    ): World {
        val pet = FakePet(energy = 90, cleanliness = 90, extraMoves = mapOf(
            (Page.ROOM to exit) to Page.OUTDOOR,
            (Page.OUTDOOR to townEntry) to Page.TOWN,
        ))
        val w = World(pet)
        pet.texts[Page.ROOM] = mutableMapOf("出门" to exit)
        pet.texts[Page.OUTDOOR] = mutableMapOf("职业小镇" to townEntry)
        pet.screen = {
            if (pet.game.page != Page.TOWN) pet.texts[pet.game.page].orEmpty()
            else when (w.town) {
                Town.MAP -> if (w.scrolls >= venueAfterScrolls) mapOf("宠物职业小镇" to nowhere, "竹影武馆" to venue) else mapOf("宠物职业小镇" to nowhere)
                Town.DETAIL, Town.SELECTED -> mapOf("帮武馆跑腿" to option, "去打工" to start)
                Town.WORKING -> mapOf("打工中" to nowhere)
                Town.PAYROLL -> mapOf((payroll ?: "") to nowhere)
            }
        }
        pet.game.onDrag = { g -> if (g.page == Page.TOWN && w.town == Town.MAP) w.scrolls++ }
        pet.game.onTap = { g, p ->
            if (g.page == Page.TOWN) when {
                w.town == Town.MAP && p == venue -> w.town = Town.DETAIL
                w.town == Town.DETAIL && p == option -> w.town = Town.SELECTED
                w.town == Town.SELECTED && p == start && starts -> w.town = Town.WORKING
                // Back out of the town: detail -> map -> outdoor.
                p == Layout.back && w.town == Town.MAP -> g.page = Page.OUTDOOR
                p == Layout.back -> w.town = Town.MAP
            }
        }
        pet.game.onPause = { g ->
            if (w.town == Town.WORKING && g.pausedMs >= 10 * 60_000L && payroll != null) w.town = Town.PAYROLL
        }
        return w
    }

    private fun work(w: World) = runBlocking {
        val g = w.pet.game
        WorkAction(g, g, w.pet, Navigator(g, g)).run(Job.RUN_ERRANDS)
    }

    @Test fun aShiftRunsToItsPayrollAndHome() {
        val w = world()
        val result = work(w)
        assertEquals(WorkStop.COMPLETED, result.stop)
        assertEquals(68, result.coinsEarned)
        assertEquals(Page.ROOM, w.pet.game.page)
    }

    @Test fun theTownMapIsScrolledToFindTheVenue() {
        val w = world(venueAfterScrolls = 2)
        assertEquals(WorkStop.COMPLETED, work(w).stop)
        assertEquals(2, w.scrolls)
    }

    @Test fun aVenueThatNeverShowsStops() {
        val w = world(venueAfterScrolls = 99)
        assertEquals(WorkStop.VENUE_NOT_FOUND, work(w).stop)
        assertEquals(Page.ROOM, w.pet.game.page)
    }

    @Test fun aShiftThatDoesNotStartIsReported() {
        val w = world(starts = false)
        assertEquals(WorkStop.NOT_STARTED, work(w).stop)
        assertEquals(Page.ROOM, w.pet.game.page)
    }

    @Test fun aPayrollWithoutAReadableAwardStillCompletes() {
        val w = world(payroll = "打工完成 帮武馆跑腿")
        val result = work(w)
        assertEquals(WorkStop.COMPLETED, result.stop)
        assertNull(result.coinsEarned)
    }

    @Test fun noPayrollIsReported() {
        val w = world(payroll = null)
        val result = work(w)
        assertEquals(WorkStop.NO_PAYROLL, result.stop)
        assertEquals(Page.ROOM, w.pet.game.page)
    }
}
