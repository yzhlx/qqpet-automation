package com.ynk.virtualdisplay

import com.ynk.virtualdisplay.automation.SessionMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The three stage entries are independent, so what each one is allowed to DO is the contract.
 *
 * v0.1 bundled 健康检查 + 护理 + 学习 into one 开始托管, which meant reading the pet's current
 * 体力/清洁 also spent coins on care and burned the shared 480-minute budget on a lesson. These
 * tests pin the separation: a cheaper mode must never gain a costlier stage's capability, and an
 * unknown mode name must fail closed instead of silently upgrading to a costlier stage.
 */
class SessionModeTest {

    @Test
    fun `health check spends nothing and runs no stage`() {
        val mode = SessionMode.HEALTH_CHECK
        assertFalse(mode.runsCare)
        assertFalse(mode.runsStudy)
    }

    @Test
    fun `care runs care but never a lesson`() {
        val mode = SessionMode.CARE
        assertTrue(mode.runsCare)
        // The whole point of the split: care must not touch the shared daily budget.
        assertFalse(mode.runsStudy)
    }

    @Test
    fun `full runs both stages`() {
        val mode = SessionMode.FULL
        assertTrue(mode.runsCare)
        assertTrue(mode.runsStudy)
    }

    @Test
    fun `stages are strictly nested`() {
        // A mode that runs a lesson must also run care, because a lesson requires a verified
        // >=90 reading and producing that reading is the care stage.
        for (mode in SessionMode.entries) {
            if (mode.runsStudy) assertTrue("$mode runs a lesson without care", mode.runsCare)
        }
    }

    @Test
    fun `wire names round trip`() {
        assertEquals("health_check", SessionMode.HEALTH_CHECK.wire)
        assertEquals("care", SessionMode.CARE.wire)
        assertEquals("full", SessionMode.FULL.wire)
        for (mode in SessionMode.entries) {
            assertEquals(mode, SessionMode.fromWire(mode.wire))
        }
    }

    @Test
    fun `a missing mode name is the historical full session`() {
        // Existing callers that never passed a mode keep their v0.1 behaviour.
        assertEquals(SessionMode.FULL, SessionMode.fromWire(null))
    }

    @Test(expected = IllegalStateException::class)
    fun `an unknown mode name fails closed instead of running a costlier stage`() {
        SessionMode.fromWire("delete_everything")
    }

    @Test
    fun `aliases resolve to the documented modes`() {
        assertEquals(SessionMode.HEALTH_CHECK, SessionMode.fromWire("healthcheck"))
        assertEquals(SessionMode.HEALTH_CHECK, SessionMode.fromWire("check"))
        assertEquals(SessionMode.HEALTH_CHECK, SessionMode.fromWire("HEALTH_CHECK"))
    }
}
