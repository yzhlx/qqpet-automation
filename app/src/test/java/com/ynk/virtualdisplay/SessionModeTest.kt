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
        assertFalse(mode.runsWork)
    }

    @Test
    fun `care runs care but never a lesson or a shift`() {
        val mode = SessionMode.CARE
        assertTrue(mode.runsCare)
        // The whole point of the split: care must not touch the shared daily budget.
        assertFalse(mode.runsStudy)
        assertFalse(mode.runsWork)
    }

    @Test
    fun `standalone work runs exactly one shift`() {
        val mode = SessionMode.WORK
        assertTrue(mode.runsWork)
        // 打工 spends the job's own 体力/清洁 for coins: no course, no care, no lesson.
        assertFalse(mode.runsStudy)
        assertFalse(mode.runsCare)
    }

    @Test
    fun `standalone study runs a lesson without care`() {
        val mode = SessionMode.STUDY
        assertTrue(mode.runsStudy)
        // User rule 2026-10-21: a lesson needs course + tuition + minutes, not 体力/清洁, so study is
        // startable on its own. It must NOT quietly run care or a funding shift.
        assertFalse(mode.runsCare)
        assertFalse(mode.runsWork)
    }

    @Test
    fun `full runs both stages`() {
        val mode = SessionMode.FULL
        assertTrue(mode.runsCare)
        assertTrue(mode.runsStudy)
        assertFalse("托管 works only as a funding step, not as its own action", mode.runsWork)
    }

    @Test
    fun `no standalone mode gains a second action`() {
        // Each of the three independent entries performs exactly one action; only FULL combines two.
        assertEquals(1, listOf(SessionMode.CARE.runsCare, SessionMode.CARE.runsStudy,
            SessionMode.CARE.runsWork).count { it })
        assertEquals(1, listOf(SessionMode.WORK.runsCare, SessionMode.WORK.runsStudy,
            SessionMode.WORK.runsWork).count { it })
        assertEquals(1, listOf(SessionMode.STUDY.runsCare, SessionMode.STUDY.runsStudy,
            SessionMode.STUDY.runsWork).count { it })
        assertEquals(0, listOf(SessionMode.HEALTH_CHECK.runsCare,
            SessionMode.HEALTH_CHECK.runsStudy, SessionMode.HEALTH_CHECK.runsWork).count { it })
    }

    @Test
    fun `wire names round trip`() {
        assertEquals("health_check", SessionMode.HEALTH_CHECK.wire)
        assertEquals("care", SessionMode.CARE.wire)
        assertEquals("work", SessionMode.WORK.wire)
        assertEquals("study", SessionMode.STUDY.wire)
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
