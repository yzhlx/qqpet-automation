package com.ynk.virtualdisplay

import com.ynk.virtualdisplay.core.ipc.DisplayUserService
import org.junit.Assert.assertEquals
import org.junit.Test

class StaleServiceProcessTest {
    @Test fun keepsSelfAndListsOthers() {
        assertEquals(listOf(100, 300), DisplayUserService.stalePids("100 200 300\n", 200))
    }
    @Test fun noOutputMeansNothingToKill() {
        assertEquals(emptyList<Int>(), DisplayUserService.stalePids("", 200))
    }
    @Test fun ignoresErrorText() {
        assertEquals(emptyList<Int>(), DisplayUserService.stalePids("pidof: not found", 200))
    }
}
