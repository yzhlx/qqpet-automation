package com.ynk.virtualdisplay

import com.ynk.virtualdisplay.automation.SkipReason
import com.ynk.virtualdisplay.automation.StrategyExplanation
import com.ynk.virtualdisplay.ui.StrategyLineTone
import com.ynk.virtualdisplay.ui.StrategyPresentation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** STR-16 呈现层：只把事实翻译成中文，不新增任何结论。 */
class StrategyPresentationTest {

    private fun action(label: String, outcome: StrategyExplanation.Outcome, reason: String? = null) =
        StrategyExplanation.Action(label, outcome, reason)

    @Test fun completedIsDoneAndEverythingElseIsNot() {
        val lines = StrategyPresentation.lines(listOf(
            action("学习", StrategyExplanation.Outcome.COMPLETED),
            action("打工", StrategyExplanation.Outcome.SKIPPED, SkipReason.FUNDS_SUFFICIENT.name),
            action("冒险", StrategyExplanation.Outcome.NOT_SELECTED)))
        assertEquals(StrategyLineTone.DONE, lines[0].tone)
        assertEquals(StrategyLineTone.SKIPPED, lines[1].tone)
        assertEquals(StrategyLineTone.NOT_DONE, lines[2].tone)
        assertTrue(lines[0].text.contains("本轮已执行完成"))
        assertTrue(lines[1].text.contains("金币已经够"))
        assertTrue(lines[2].text.contains("本方案未勾选"))
        assertFalse(lines[2].text.contains("完成"))
    }

    @Test fun summaryNamesWhatWasDoneAndWhatWasSkipped() {
        val summary = StrategyPresentation.summary(listOf(
            action("学习", StrategyExplanation.Outcome.COMPLETED),
            action("打工", StrategyExplanation.Outcome.SKIPPED, SkipReason.ALREADY_SATISFIED.name),
            action("冒险", StrategyExplanation.Outcome.NOT_SELECTED)))
        assertTrue(summary.contains("执行：学习"))
        assertTrue(summary.contains("跳过：打工"))
        assertFalse(summary.contains("冒险"))
    }

    @Test fun unknownReasonCodeIsShownAsItIsInsteadOfAnInventedExplanation() {
        val lines = StrategyPresentation.lines(listOf(
            action("打工", StrategyExplanation.Outcome.FAILED, "SOME_NEW_REASON_CODE")))
        assertTrue(lines[0].text.contains("SOME_NEW_REASON_CODE"))
    }

    @Test fun notExecutedActionsSaySoWithTheirReason() {
        val lines = StrategyPresentation.lines(listOf(
            action("护理", StrategyExplanation.Outcome.NOT_EXECUTED, StrategyExplanation.STOPPED_BY_PREVIOUS)))
        assertTrue(lines[0].text.contains("本轮没有执行"))
        assertTrue(lines[0].text.contains("前一步没有完成"))
        assertEquals(StrategyLineTone.NOT_DONE, lines[0].tone)
    }

    @Test fun aBlockedActionSaysWhereTheRoundWentNext() {
        val lines = StrategyPresentation.lines(listOf(
            StrategyExplanation.Action("打工", StrategyExplanation.Outcome.BLOCKED,
                "WORK_ENERGY_INSUFFICIENT", "护理")))
        assertTrue(lines[0].text.contains("体力不足"))
        assertTrue(lines[0].text.contains("已转到护理"))
        assertEquals(StrategyLineTone.NOT_DONE, lines[0].tone)
    }

    @Test fun theSummaryNamesWhatWasBlockedAndWhatStillRan() {
        val summary = StrategyPresentation.summary(listOf(
            action("打工", StrategyExplanation.Outcome.BLOCKED, "WORK_ENERGY_INSUFFICIENT"),
            action("护理", StrategyExplanation.Outcome.COMPLETED)))
        assertTrue(summary.contains("受阻：打工"))
        assertTrue(summary.contains("执行：护理"))
    }

    @Test fun emptyActionsProduceAnHonestPlaceholder() {
        assertEquals("本轮没有可展示的策略说明", StrategyPresentation.summary(emptyList()))
        assertTrue(StrategyPresentation.lines(emptyList()).isEmpty())
    }

    @Test fun anExpiredHealthReadingIsNotShownAsARawCode() {
        // The 2026-11-07 04:38 run showed "HEALTH_STALE:OBSERVATION_EXPIRED_OR_CLOCK_REVERSED" on the home card.
        val stale = StrategyPresentation.lines(listOf(
            action("护理", StrategyExplanation.Outcome.UNCONFIRMED, "HEALTH_STALE:OBSERVATION_EXPIRED_OR_CLOCK_REVERSED")))
        assertTrue(stale[0].text.contains("没能确认宠物当前的体力和清洁"))
        assertFalse(stale[0].text.contains("HEALTH_STALE"))
        val locked = StrategyPresentation.lines(listOf(
            action("学习", StrategyExplanation.Outcome.BLOCKED, "PHONE_LOCKED")))
        assertTrue(locked[0].text.contains("手机锁屏了"))
        val timeout = StrategyPresentation.lines(listOf(
            action("学习", StrategyExplanation.Outcome.FAILED, "CAPTURE_TIMEOUT:no frame; displayId=50")))
        assertTrue(timeout[0].text.contains("后台画面没有及时返回"))
        assertFalse(timeout[0].text.contains("CAPTURE_TIMEOUT"))
    }

    @Test fun aFailedWalkHomeIsNotShownAsARawCode() {
        // The 2026-11-07 03:09 adventure run showed "RETURN_HOME_UNRECOVERED:adventure-page-unconfirmed" on the home card.
        val lines = StrategyPresentation.lines(listOf(
            action("冒险", StrategyExplanation.Outcome.UNCONFIRMED, "RETURN_HOME_UNRECOVERED:adventure-page-unconfirmed")))
        assertTrue(lines[0].text.contains("没能自动退回宠物房间"))
        assertFalse(lines[0].text.contains("RETURN_HOME"))
        val notOpened = StrategyPresentation.lines(listOf(
            action("冒险", StrategyExplanation.Outcome.UNCONFIRMED, "ADVENTURE_PAGE_NOT_CONFIRMED")))
        assertTrue(notOpened[0].text.contains("没能打开冒险面板"))
    }
}
