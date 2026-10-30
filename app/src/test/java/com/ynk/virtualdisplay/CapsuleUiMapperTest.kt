package com.ynk.virtualdisplay

import com.ynk.virtualdisplay.automation.ManagedRuntimeState
import com.ynk.virtualdisplay.automation.PetRuntime
import com.ynk.virtualdisplay.automation.SessionRunState
import com.ynk.virtualdisplay.floating.CapsuleUiMapper
import com.ynk.virtualdisplay.floating.CapsuleVisualState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CapsuleUiMapperTest {
    @Test
    fun mapsRunningStateFromManagedRuntime() {
        val managed = ManagedRuntimeState(
            sessionId = "s1",
            runState = SessionRunState.RUNNING,
            currentFlow = "STUDY",
            currentStep = "WAIT_RESULT",
            round = 1,
            totalRounds = 3,
            completedRounds = 0,
            startedAt = 1L,
            startedElapsedMs = 10L,
            nextStep = "等待结算",
        )
        val legacy = PetRuntime.Snapshot(running = true, runState = SessionRunState.RUNNING, displayId = 7)
        val model = CapsuleUiMapper.from(managed, legacy, wallNowMs = 1000L, elapsedNowMs = 1000L)
        assertTrue(model.visible)
        assertTrue(model.canStop)
        assertEquals(CapsuleVisualState.WAITING, model.visual)
        assertEquals("运行中", model.statusText)
    }

    @Test
    fun idleWithoutSessionIsHidden() {
        val model = CapsuleUiMapper.from(
            ManagedRuntimeState(),
            PetRuntime.Snapshot(),
            wallNowMs = 1L,
            elapsedNowMs = 1L,
        )
        assertFalse(model.visible)
        assertFalse(model.canStop)
    }
}
