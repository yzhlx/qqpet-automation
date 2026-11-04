package com.ynk.virtualdisplay

import com.ynk.virtualdisplay.automation.StudyAttributeFact
import com.ynk.virtualdisplay.ui.data.SessionRunRecord
import com.ynk.virtualdisplay.ui.records.RecordsPresentation
import com.ynk.virtualdisplay.ui.records.VerdictTone
import org.junit.Assert.assertEquals
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
}
