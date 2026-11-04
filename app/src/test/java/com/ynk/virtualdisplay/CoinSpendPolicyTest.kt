package com.ynk.virtualdisplay

import com.ynk.virtualdisplay.automation.CoinSpendPolicy
import org.junit.Assert.*
import org.junit.Test

class CoinSpendPolicyTest {

    @Test
    fun bothGatesDisabledAlwaysAllows() {
        val d = CoinSpendPolicy.evaluate(balanceCoins = 50, tuitionCoins = 100, remainingRounds = 3,
            minCoinFloor = null, maxDailyNetSpend = null, alreadySpentToday = 0)
        assertEquals(CoinSpendPolicy.Decision.Allowed, d)
    }

    @Test
    fun minCoinFloorRejectsWhenAfterSpendBelowFloor() {
        val d = CoinSpendPolicy.evaluate(balanceCoins = 250, tuitionCoins = 100, remainingRounds = 2,
            minCoinFloor = 100, maxDailyNetSpend = null, alreadySpentToday = 0)
        val r = d as CoinSpendPolicy.Decision.Rejected
        assertTrue(r.reason.startsWith("COIN_FLOOR_BREACH"))
    }

    @Test
    fun minCoinFloorAllowsWhenAfterSpendEqualsFloor() {
        val d = CoinSpendPolicy.evaluate(balanceCoins = 300, tuitionCoins = 100, remainingRounds = 2,
            minCoinFloor = 100, maxDailyNetSpend = null, alreadySpentToday = 0)
        assertEquals(CoinSpendPolicy.Decision.Allowed, d)
    }

    @Test
    fun dailyCapCountsPlannedSpendPlusConfirmedHistory() {
        val d = CoinSpendPolicy.evaluate(balanceCoins = 5000, tuitionCoins = 100, remainingRounds = 3,
            minCoinFloor = null, maxDailyNetSpend = 400, alreadySpentToday = 200)
        val r = d as CoinSpendPolicy.Decision.Rejected
        assertTrue(r.reason.startsWith("COIN_SPEND_DAILY_CAP_EXCEEDED"))
    }

    @Test
    fun dailyCapAllowsAtExactBoundary() {
        val d = CoinSpendPolicy.evaluate(balanceCoins = 5000, tuitionCoins = 100, remainingRounds = 2,
            minCoinFloor = null, maxDailyNetSpend = 400, alreadySpentToday = 200)
        assertEquals(CoinSpendPolicy.Decision.Allowed, d)
    }

    @Test
    fun invalidInputIsRejectedLoudly() {
        try {
            CoinSpendPolicy.evaluate(balanceCoins = -1, tuitionCoins = 100, remainingRounds = 1,
                minCoinFloor = null, maxDailyNetSpend = null, alreadySpentToday = 0)
            fail("expected rejection")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("COIN_SPEND_POLICY_INPUT_INVALID"))
        }
    }
}
