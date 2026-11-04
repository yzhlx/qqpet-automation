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

    @Test fun emptyActionsProduceAnHonestPlaceholder() {
        assertEquals("本轮没有可展示的策略说明", StrategyPresentation.summary(emptyList()))
        assertTrue(StrategyPresentation.lines(emptyList()).isEmpty())
    }
}
