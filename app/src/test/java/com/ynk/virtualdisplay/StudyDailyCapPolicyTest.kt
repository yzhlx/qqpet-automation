package com.ynk.virtualdisplay

import com.ynk.virtualdisplay.automation.StudyDailyCapPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * STR-08：今日停止线（纯函数）。
 *
 * 停止线是「今天一共上几节」的上限，所以今天已经上过的节数要从本次方案里扣掉；
 * 关闭停止线时，方案照旧按自己排的节数执行。
 */
class StudyDailyCapPolicyTest {

    @Test fun withoutACapThePlanKeepsItsOwnRounds() {
        assertEquals(3, StudyDailyCapPolicy.allowedRounds(confirmedToday = 0, cap = null, plannedRounds = 3))
        assertEquals(3, StudyDailyCapPolicy.allowedRounds(confirmedToday = 9, cap = null, plannedRounds = 3))
    }

    @Test fun theCapCountsWhatTodayAlreadyHad() {
        assertEquals(1, StudyDailyCapPolicy.allowedRounds(confirmedToday = 4, cap = 5, plannedRounds = 3))
        assertEquals(0, StudyDailyCapPolicy.allowedRounds(confirmedToday = 5, cap = 5, plannedRounds = 3))
        assertEquals(0, StudyDailyCapPolicy.allowedRounds(confirmedToday = 8, cap = 5, plannedRounds = 3))
    }

    @Test fun thePlanNeverRunsMoreRoundsThanItScheduled() {
        assertEquals(2, StudyDailyCapPolicy.allowedRounds(confirmedToday = 0, cap = 10, plannedRounds = 2))
    }

    @Test fun onlyTodaysConfirmedRoundsAreCounted() {
        val now = 1_700_000_000_000L
        val today = StudyDailyCapPolicy.dayKey(now)
        val earlier = StudyDailyCapPolicy.dayKey(now - 40L * 24 * 60 * 60 * 1000)
        assertNotEquals(today, earlier)
        val records = listOf(
            StudyDailyCapPolicy.ConfirmedRecord(now, 3, "力量"),
            StudyDailyCapPolicy.ConfirmedRecord(now - 40L * 24 * 60 * 60 * 1000, 9, "力量"),
            StudyDailyCapPolicy.ConfirmedRecord(now, 2, null))
        assertEquals(5, StudyDailyCapPolicy.sumConfirmedToday(records, today))
    }

    @Test fun aBrokenCountNeverMakesRoomForMoreLessons() {
        assertEquals(3, StudyDailyCapPolicy.allowedRounds(confirmedToday = -1, cap = null, plannedRounds = 3))
        assertEquals(2, StudyDailyCapPolicy.allowedRounds(confirmedToday = -1, cap = 2, plannedRounds = 3))
    }
}
