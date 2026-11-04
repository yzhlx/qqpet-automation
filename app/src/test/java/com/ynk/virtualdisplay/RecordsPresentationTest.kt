package com.ynk.virtualdisplay

import com.ynk.virtualdisplay.automation.StudyAttributeFact
import com.ynk.virtualdisplay.ui.data.SessionRunRecord
import com.ynk.virtualdisplay.ui.records.RecordsPresentation
import com.ynk.virtualdisplay.ui.records.RecordsSummary
import com.ynk.virtualdisplay.ui.records.VerdictTone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** STR-09：每个属性的变化在记录里如实显示，读不到就说读不到。 */
class RecordsPresentationTest {

    private fun record(facts: List<StudyAttributeFact>) = SessionRunRecord(
        sessionId = "s", terminalState = "COMPLETED", runner = "PetSessionController",
        rounds = 3, completedRounds = 3, pauseCount = 0, startedAtMs = 1L, runtimeMs = 1000,
        stopReason = null, moodPolicy = null, energy = null, cleanliness = null, mood = null,
        care = null, encouragementSent = 0, courseStarts = 3, requestedRounds = 3,
        studyAttributes = facts)

    @Test fun theRecordedAttributeGainsAreShown() {
        val actions = RecordsPresentation.actions(emptyList(), record(listOf(
            StudyAttributeFact("力量", 2, 4, 2), StudyAttributeFact("智力", 1, null, 0))))
        val strength = actions.first { it.label == "力量属性" }
        assertEquals("+4", strength.verdict)
        assertEquals(VerdictTone.CONFIRMED, strength.verdictTone)
        val intellect = actions.first { it.label == "智力属性" }
        assertEquals("收益未读到", intellect.verdict)
        assertEquals(VerdictTone.UNKNOWN, intellect.verdictTone)
    }

    @Test fun aPartiallyReadGainIsNotClaimedAsComplete() {
        val actions = RecordsPresentation.actions(emptyList(), record(listOf(StudyAttributeFact("魅力", 3, 5, 1))))
        val charm = actions.first { it.label == "魅力属性" }
        assertEquals(VerdictTone.UNKNOWN, charm.verdictTone)
    }

    @Test fun aSessionWithoutAttributeFactsShowsNoAttributeRow() {
        val actions = RecordsPresentation.actions(emptyList(), record(emptyList()))
        assertEquals(0, actions.count { it.label.endsWith("属性") })
    }

    /** 每日领取的计数只在真的做过时才占一行；没做过不显示 0，避免读成失败。 */
    private fun summary(signIn: Int = 0, mailbox: Int = 0, luckyBag: Int = 0) = RecordsSummary(
        rangeText = "今天", minutesText = null, courses = 0, careCount = 0, pauseCount = 0,
        signInCount = signIn, mailboxCount = mailbox, luckyBagCount = luckyBag)

    @Test fun todaysDailyClaimCountsAreShownTogether() {
        assertEquals("签到 1 次 · 信箱 2 次 · 福袋 3 个",
            RecordsPresentation.dailyRewardsText(summary(signIn = 1, mailbox = 2, luckyBag = 3)))
        assertEquals("福袋 1 个",
            RecordsPresentation.dailyRewardsText(summary(luckyBag = 1)))
    }

    @Test fun aDayWithoutDailyClaimsShowsNoLine() {
        assertNull(RecordsPresentation.dailyRewardsText(summary()))
    }

    /** OBS-06：昨天这一行只写确认过的数字，没做的分项不出现。 */
    private fun day(
        courses: Int = 0, work: Int = 0, adventure: Int = 0, care: Int = 0,
        signIn: Int = 0, mailbox: Int = 0, luckyBag: Int = 0, minutes: Long = 0,
    ) = SessionRunRecord(
        sessionId = "d", terminalState = "COMPLETED", runner = "PetSessionController",
        rounds = courses, completedRounds = courses, pauseCount = 0, startedAtMs = 1L,
        runtimeMs = minutes * 60_000L, stopReason = null, moodPolicy = null, energy = null,
        cleanliness = null, mood = null, care = null, encouragementSent = 0, courseStarts = courses,
        requestedRounds = courses, workCompleted = work, adventureCompleted = adventure,
        signInCompleted = signIn, mailboxCompleted = mailbox, luckyBagCompleted = luckyBag)

    @Test fun yesterdayLineListsOnlyConfirmedNumbers() {
        assertEquals("学习 2 节 · 打工 1 班 · 签到 1 次 · 运行 45 分钟",
            RecordsPresentation.dailyLine(listOf(day(courses = 2, work = 1, signIn = 1, minutes = 45))))
        assertEquals("福袋 1 个", RecordsPresentation.dailyLine(listOf(day(luckyBag = 1))))
    }

    @Test fun aYesterdayWithoutRecordsHasNoLine() {
        assertNull(RecordsPresentation.dailyLine(emptyList()))
    }
}
