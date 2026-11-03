package com.ynk.virtualdisplay

import com.ynk.virtualdisplay.automation.ManualPause
import com.ynk.virtualdisplay.automation.ManualPauseState
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ManualPauseTest {
    @After
    fun reset() = ManualPause.release()

    @Test
    fun `a boundary passes straight through when no pause is requested`() = runBlocking {
        var held = 0
        assertFalse(ManualPause.hold { held++ })
        assertEquals(0, held)
    }

    @Test
    fun `a requested pause holds at the boundary until continued`() = runBlocking {
        ManualPause.request()
        var held = 0
        val boundary = async(start = CoroutineStart.UNDISPATCHED) { ManualPause.hold { held++ } }
        yield()
        assertEquals(1, held)
        assertEquals(ManualPauseState.HELD, ManualPause.state.value)
        assertFalse(boundary.isCompleted)

        ManualPause.release()
        assertTrue(boundary.await())
        assertEquals(ManualPauseState.NONE, ManualPause.state.value)
    }

    @Test
    fun `a withdrawn request never holds`() = runBlocking {
        ManualPause.request()
        ManualPause.release()
        assertFalse(ManualPause.hold {})
    }

    @Test
    fun `stopping the session cancels a held boundary`() = runBlocking {
        ManualPause.request()
        val boundary = launch(start = CoroutineStart.UNDISPATCHED) { ManualPause.hold {} }
        yield()
        assertEquals(ManualPauseState.HELD, ManualPause.state.value)
        boundary.cancel()
        boundary.join()
        assertTrue(boundary.isCancelled)
    }
}
