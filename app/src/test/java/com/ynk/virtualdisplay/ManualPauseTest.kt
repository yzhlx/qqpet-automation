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
    @Test
    fun `controls disappear after the last boundary but keep a pending pause cancellable`() {
        assertEquals("暂停", ManualPause.actionLabel(true, 1, ManualPauseState.NONE))
        assertEquals(null, ManualPause.actionLabel(true, 0, ManualPauseState.NONE))
        assertEquals("取消暂停", ManualPause.actionLabel(true, 0, ManualPauseState.REQUESTED))
        assertEquals("继续", ManualPause.actionLabel(true, 0, ManualPauseState.HELD))
        assertEquals(null, ManualPause.actionLabel(false, 1, ManualPauseState.HELD))
    }

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
