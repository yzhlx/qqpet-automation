package com.ynk.virtualdisplay

import com.ynk.virtualdisplay.automation.PlanV2
import com.ynk.virtualdisplay.automation.RunOrigin
import com.ynk.virtualdisplay.automation.RunRequest
import com.ynk.virtualdisplay.automation.RunSnapshot
import com.ynk.virtualdisplay.automation.PlanRunJsonCodec
import com.ynk.virtualdisplay.automation.StoredRun
import com.ynk.virtualdisplay.automation.StudyStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** STR-08：今日停止线必须跟着方案一起存进运行记录，重开后还在。 */
class PlanRunJsonCodecTest {

    private fun roundTrip(cap: Int?): PlanV2 {
        val plan = PlanV2(planId = "p", name = "方案", dailyBudgetMinutes = 120,
            steps = listOf(StudyStep("s", "study:田径运动课", 2)), dailyStudyRoundCap = cap)
        val request = RunRequest("req", plan, "dev", 3, RunOrigin.PLAN, 1L)
        val stored = StoredRun(RunSnapshot("run", request), emptyList(), emptyMap())
        return PlanRunJsonCodec.decode(PlanRunJsonCodec.encode(stored)).snapshot.request.planSnapshot
    }

    @Test fun theDailyStudyCapSurvivesTheRunStore() {
        val decoded = roundTrip(5)
        assertEquals(5, decoded.dailyStudyRoundCap)
        assertEquals(2, (decoded.steps.single() as StudyStep).rounds)
    }

    @Test fun aPlanWithoutTheCapKeepsItOff() {
        assertNull(roundTrip(null).dailyStudyRoundCap)
    }
}
