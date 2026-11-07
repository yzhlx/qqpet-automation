package com.ynk.virtualdisplay

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ynk.virtualdisplay.automation.EventJson
import com.ynk.virtualdisplay.automation.StrategyExplanation
import com.ynk.virtualdisplay.ui.data.SessionRunRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * STR-16 on device: the run record the controller writes must read back with the same per-action
 * facts. org.json exists only on the device, so this write/read round trip is checked here and not
 * in the JVM suite.
 */
@RunWith(AndroidJUnit4::class)
class StrategyRecordJsonTest {

    @Test fun writtenStrategyActionsComeBackWithTheSameOutcomeAndReason() {
        val actions = listOf(
            StrategyExplanation.Action("学习", StrategyExplanation.Outcome.COMPLETED, null),
            StrategyExplanation.Action("打工", StrategyExplanation.Outcome.SKIPPED, "FUNDS_SUFFICIENT"),
            StrategyExplanation.Action("冒险", StrategyExplanation.Outcome.NOT_SELECTED, null))
        val json = EventJson.encode(mapOf<String, Any?>(
            "sessionId" to "s-1",
            "terminalState" to "COMPLETED",
            "strategyActions" to actions.map {
                mapOf("label" to it.label, "outcome" to it.outcome.name, "reason" to it.reason)}))
        val parsed = SessionRunRecord.parse(json)
        assertNotNull(parsed)
        assertEquals(actions.size, parsed!!.strategyActions.size)
        assertEquals(StrategyExplanation.Outcome.COMPLETED, parsed.strategyActions[0].outcome)
        assertNull(parsed.strategyActions[0].reason)
        assertEquals(StrategyExplanation.Outcome.SKIPPED, parsed.strategyActions[1].outcome)
        assertEquals("FUNDS_SUFFICIENT", parsed.strategyActions[1].reason)
        assertEquals(StrategyExplanation.Outcome.NOT_SELECTED, parsed.strategyActions[2].outcome)
        assertNull(parsed.strategyActions[2].reason)
    }

    @Test fun anUnreadableEntryIsDroppedInsteadOfGuessed() {
        val json = """{"terminalState":"COMPLETED","strategyActions":[{"label":"学习","outcome":"COMPLETED"},{"label":"","outcome":"COMPLETED"},{"label":"冒险","outcome":"WHAT_IS_THIS"}]}"""
        val parsed = SessionRunRecord.parse(json)
        assertNotNull(parsed)
        assertEquals(1, parsed!!.strategyActions.size)
        assertEquals("学习", parsed.strategyActions.single().label)
    }

    @Test fun theStudyFactsComeBackFromTheRunRecord() {
        val json = EventJson.encode(mapOf<String, Any?>(
            "sessionId" to "s-2",
            "terminalState" to "COMPLETED",
            "studyRoundsCompleted" to 3,
            "targetAttribute" to "智力"))
        val parsed = SessionRunRecord.parse(json)
        assertNotNull(parsed)
        assertEquals(3, parsed!!.studyRoundsCompleted)
        assertEquals("智力", parsed.targetAttribute)
    }

    @Test fun anOlderRecordWithoutStudyFactsStaysUnknown() {
        val parsed = SessionRunRecord.parse("""{"terminalState":"COMPLETED","rounds":1}""")
        assertNotNull(parsed)
        assertNull(parsed!!.studyRoundsCompleted)
        assertNull(parsed.targetAttribute)
    }

    @Test fun aNullTargetAttributeStaysUnknown() {
        val json = EventJson.encode(mapOf<String, Any?>(
            "sessionId" to "s-3",
            "terminalState" to "COMPLETED",
            "studyRoundsCompleted" to 1,
            "targetAttribute" to null))
        val parsed = SessionRunRecord.parse(json)
        assertNotNull(parsed)
        assertEquals(1, parsed!!.studyRoundsCompleted)
        assertNull(parsed.targetAttribute)
    }

    @Test fun theRecordedAttributeChangesComeBack() {
        val json = EventJson.encode(mapOf<String, Any?>(
            "sessionId" to "s-4",
            "terminalState" to "COMPLETED",
            "studyAttributes" to mapOf(
                "力量" to mapOf("rounds" to 2, "gain" to 4, "lessonsWithGain" to 2),
                "智力" to mapOf("rounds" to 1, "gain" to null, "lessonsWithGain" to 0))))
        val parsed = SessionRunRecord.parse(json)
        assertNotNull(parsed)
        val facts = parsed!!.studyAttributes
        assertEquals(2, facts.size)
        val strength = facts.first { it.attribute == "力量" }
        assertEquals(2, strength.rounds)
        assertEquals(4, strength.gain)
        val intellect = facts.first { it.attribute == "智力" }
        assertEquals(1, intellect.rounds)
        assertNull(intellect.gain)
    }

    @Test fun aRecordWithoutStrategyFactsStillReadsBack() {
        val parsed = SessionRunRecord.parse("""{"terminalState":"COMPLETED","rounds":1}""")
        assertNotNull(parsed)
        assertEquals(0, parsed!!.strategyActions.size)
    }
}
