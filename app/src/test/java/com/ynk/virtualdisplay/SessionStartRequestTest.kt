package com.ynk.virtualdisplay

import com.ynk.virtualdisplay.automation.SessionMode
import com.ynk.virtualdisplay.ui.data.SessionStartRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression cover for the bug where the home footer silently lost the 1/3/10 selector and every
 * hosted start hard-coded `rounds = 1`, so a user could no longer ask for 3 or 10 节.
 *
 * Scope note: a pure-JVM unit test cannot read `Intent` extras — `android.jar` is stubbed and
 * `Intent.putExtra` throws "not mocked" (measured, see the t2 probe log). These tests therefore pin
 * the decision the footer feeds into `PetAutomationService.startIntent`, not the Intent object
 * itself.
 */
class SessionStartRequestTest {

    /**
     * 1..10 must all be offered, because the plan sheet can hold any of them. The old list was
     * (1, 3, 10): a saved 2 or 4 was silently rewritten to 1 right before the service was asked.
     */
    @Test
    fun `all plan batch sizes are offered`() {
        assertEquals((1..10).toList(), SessionStartRequest.ROUND_OPTIONS)
    }

    /** 1 节 stays the default, so the cheapest option is what an untouched screen requests. */
    @Test
    fun `one round is the default and the fallback for unknown state`() {
        assertEquals(1, SessionStartRequest.DEFAULT_ROUNDS)
        assertEquals(1, SessionStartRequest.sanitize(0))
        assertEquals(1, SessionStartRequest.sanitize(11))
        assertEquals(1, SessionStartRequest.sanitize(-3))
    }

    /** Offered values survive sanitising untouched. */
    @Test
    fun `offered values are kept as selected`() {
        SessionStartRequest.ROUND_OPTIONS.forEach { n ->
            assertEquals(n, SessionStartRequest.sanitize(n))
        }
    }

    /** 2 and 4 are real requests, not typos that collapse to one lesson. */
    @Test
    fun `two and four round batches are not rewritten to one`() {
        assertEquals(2, SessionStartRequest.sanitize(2))
        assertEquals(4, SessionStartRequest.sanitize(4))
        assertEquals(2, SessionStartRequest.plannedRounds(SessionMode.FULL, 2))
        assertEquals(4, SessionStartRequest.plannedRounds(SessionMode.FULL, 4))
    }

    /**
     * The core regression: 托管 must actually carry the number the user picked. Hard-coding 1 for
     * every entry is exactly what this fails on.
     */
    @Test
    fun `full托管 carries the selected batch size`() {
        assertEquals(1, SessionStartRequest.plannedRounds(SessionMode.FULL, 1))
        assertEquals(3, SessionStartRequest.plannedRounds(SessionMode.FULL, 3))
        assertEquals(10, SessionStartRequest.plannedRounds(SessionMode.FULL, 10))
    }

    /** 健康检查 reads once and spends nothing, so a selected batch must never leak into it. */
    @Test
    fun `health check never inherits the selected batch size`() {
        SessionStartRequest.ROUND_OPTIONS.forEach { n ->
            assertEquals(1, SessionStartRequest.plannedRounds(SessionMode.HEALTH_CHECK, n))
        }
    }

    /** 护理 buys consumables but runs no lesson, so it also stays a single pass. */
    @Test
    fun `care never inherits the selected batch size`() {
        SessionStartRequest.ROUND_OPTIONS.forEach { n ->
            assertEquals(1, SessionStartRequest.plannedRounds(SessionMode.CARE, n))
        }
    }

    /**
     * Both lesson-running modes carry a batch; the single-pass entries never do.
     *
     * 2026-10-21: 学习 became its own entry (a lesson costs tuition, not 体力/清洁), so it carries the
     * selected 节数 exactly like 托管. 健康检查, 护理 and 打工 each run one pass and must ignore it.
     */
    @Test
    fun `only the lesson modes honour a batch`() {
        assertTrue(SessionMode.FULL.runsStudy)
        assertTrue(SessionMode.STUDY.runsStudy)
        assertFalse(SessionMode.HEALTH_CHECK.runsStudy)
        assertFalse(SessionMode.CARE.runsStudy)
        assertFalse(SessionMode.WORK.runsStudy)

        val modes = SessionMode.entries.toList()
        // 方案 (PLAN) is the sixth mode: the saved plan carries its own steps and lesson count, so
        // the mode itself runs no lesson and never inherits the selector.
        assertEquals(6, modes.size)
        assertEquals(2, modes.count { it.runsStudy })
        assertFalse(SessionMode.PLAN.runsStudy)
        // Every single-pass mode ignores the selector even when the user left it on a long batch.
        for (mode in modes.filter { !it.runsStudy }) {
            assertEquals("$mode must stay single-pass",
                SessionStartRequest.DEFAULT_ROUNDS, SessionStartRequest.plannedRounds(mode, 10))
        }
        assertEquals(10, SessionStartRequest.plannedRounds(SessionMode.STUDY, 10))
    }

    /**
     * Every offered batch size must be one the automation layer actually accepts, otherwise the UI
     * would offer a start request the service rejects. Mirrors `PetSessionController`'s
     * `require(totalRounds in 1..10)` and `runSession`'s `rounds !in 1..10` guard — this is the
     * boundary that makes restoring 10 节 a pure UI change.
     */
    @Test
    fun `every offered batch size is accepted by the documented 1 to 10 range`() {
        val accepted = 1..10
        SessionStartRequest.ROUND_OPTIONS.forEach { n ->
            assertTrue("offered $n 节 is outside the accepted $accepted range", n in accepted)
        }
        assertTrue(10 in SessionStartRequest.ROUND_OPTIONS)
    }
}
