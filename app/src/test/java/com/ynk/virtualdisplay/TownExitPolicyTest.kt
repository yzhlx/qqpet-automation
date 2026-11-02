package com.ynk.virtualdisplay

import com.ynk.virtualdisplay.automation.RestockConfirmation
import com.ynk.virtualdisplay.automation.TownExitPolicy
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class TownExitPolicyTest {
    @Test
    fun townPageGoesBackUntilTheLimitThenStops() {
        assertEquals(TownExitPolicy.Step.BACK, TownExitPolicy.next(TownExitPolicy.Page.TOWN, 0, 2))
        assertEquals(TownExitPolicy.Step.BACK, TownExitPolicy.next(TownExitPolicy.Page.TOWN, 1, 2))
        assertEquals(TownExitPolicy.Step.STOP, TownExitPolicy.next(TownExitPolicy.Page.TOWN, 2, 2))
    }

    @Test
    fun outdoorMapLeavesForTheRoomEvenAtTheLimit() {
        assertEquals(TownExitPolicy.Step.LEAVE_MAP, TownExitPolicy.next(TownExitPolicy.Page.OUTDOOR, 2, 2))
    }

    @Test
    fun aBackThatOvershotToTheRoomIsArrivalNotFailure() {
        assertEquals(TownExitPolicy.Step.DONE, TownExitPolicy.next(TownExitPolicy.Page.ROOM, 2, 2))
    }

    @Test
    fun lateRepaintIsConfirmedByReadingAgain() = runBlocking {
        val readings = ArrayDeque(listOf(0, 0, 1))
        var waits = 0
        val confirmed = RestockConfirmation.await(6, { readings.removeFirst() }, { it > 0 }, { waits++ }) { "NO" }
        assertEquals(1, confirmed)
        assertEquals(2, waits)
    }

    @Test
    fun stockThatNeverAppearsStopsAfterTheLastRead() {
        var reads = 0
        val failure = assertThrows(IllegalStateException::class.java) {
            runBlocking {
                RestockConfirmation.await<Int>(3, { reads++; 0 }, { it > 0 }, {}) { "CARE_RESTOCK_NOT_CONFIRMED:COOKIE" }
            }
        }
        assertEquals("CARE_RESTOCK_NOT_CONFIRMED:COOKIE", failure.message)
        assertEquals(3, reads)
    }
}
