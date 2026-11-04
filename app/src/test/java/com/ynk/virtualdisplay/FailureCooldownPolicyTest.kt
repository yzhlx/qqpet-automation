package com.ynk.virtualdisplay

import com.ynk.virtualdisplay.automation.FailureCooldownPolicy
import com.ynk.virtualdisplay.automation.RunState
import org.junit.Assert.*
import org.junit.Test

class FailureCooldownPolicyTest {

    private fun snap(planId: String, state: RunState, endedAtMs: Long?) =
        FailureCooldownPolicy.Snapshot(planId, state, endedAtMs)

    @Test
    fun disabledCooldownAlwaysAllows() {
        val d = FailureCooldownPolicy.evaluate(
            listOf(snap("p1", RunState.FAILED, 1000L)), "p1", null, nowMs = 2000L)
        assertEquals(FailureCooldownPolicy.Decision.Allowed, d)
    }

    @Test
    fun recentFailureWithinCooldownIsRejected() {
        val history = listOf(snap("p1", RunState.FAILED, 10_000L))
        val d = FailureCooldownPolicy.evaluate(history, "p1", 30, nowMs = 10_000L + 5 * 60_000L)
        val c = d as FailureCooldownPolicy.Decision.Cooling
        assertTrue(c.remainingMinutes in 1..30)
        assertEquals(RunState.FAILED, c.lastStopReasonState)
    }

    @Test
    fun failureOutsideCooldownAllows() {
        val history = listOf(snap("p1", RunState.FAILED, 10_000L))
        val d = FailureCooldownPolicy.evaluate(history, "p1", 30, nowMs = 10_000L + 31 * 60_000L)
        assertEquals(FailureCooldownPolicy.Decision.Allowed, d)
    }

    @Test
    fun userStopDoesNotTriggerCooldown() {
        val history = listOf(snap("p1", RunState.STOPPED, 10_000L))
        val d = FailureCooldownPolicy.evaluate(history, "p1", 30, nowMs = 11_000L)
        assertEquals(FailureCooldownPolicy.Decision.Allowed, d)
    }

    @Test
    fun onlySamePlanIdCounts() {
        val history = listOf(snap("other", RunState.FAILED, 10_000L))
        val d = FailureCooldownPolicy.evaluate(history, "p1", 30, nowMs = 11_000L)
        assertEquals(FailureCooldownPolicy.Decision.Allowed, d)
    }

    @Test
    fun latestFailureWinsOverOlderSuccess() {
        val history = listOf(
            snap("p1", RunState.FAILED, 50_000L),
            snap("p1", RunState.COMPLETED, 10_000L),
        )
        val d = FailureCooldownPolicy.evaluate(history, "p1", 30, nowMs = 51_000L)
        assertTrue(d is FailureCooldownPolicy.Decision.Cooling)
    }

    @Test
    fun failureWithoutEndedAtIsIgnored() {
        val history = listOf(snap("p1", RunState.FAILED, null))
        val d = FailureCooldownPolicy.evaluate(history, "p1", 30, nowMs = 1000L)
        assertEquals(FailureCooldownPolicy.Decision.Allowed, d)
    }
}
