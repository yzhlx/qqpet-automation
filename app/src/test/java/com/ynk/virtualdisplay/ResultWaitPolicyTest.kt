package com.ynk.virtualdisplay

import com.ynk.virtualdisplay.automation.ResultWaitPolicy
import com.ynk.virtualdisplay.automation.ResultWaitPolicy.Decision
import org.junit.Assert.assertEquals
import org.junit.Test

class ResultWaitPolicyTest {
    private val duration = 600_000L

    @Test fun `the measured nine second gap keeps waiting`() {
        assertEquals(Decision.KEEP_WAITING, ResultWaitPolicy.decide(9_000, 609_000, duration, false))
    }

    @Test fun `an unknown page stops at the limit`() {
        assertEquals(Decision.STOP, ResultWaitPolicy.decide(60_000, 660_000, duration, false))
    }

    @Test fun `a known page after the lesson ends it without a result page`() {
        assertEquals(Decision.COURSE_OVER_NO_RESULT, ResultWaitPolicy.decide(15_000, 615_000, duration, true))
    }

    @Test fun `a known page must hold before it counts`() {
        assertEquals(Decision.KEEP_WAITING, ResultWaitPolicy.decide(14_999, 615_000, duration, true))
    }

    @Test fun `a known page before the lesson ends never completes it`() {
        assertEquals(Decision.KEEP_WAITING, ResultWaitPolicy.decide(20_000, 300_000, duration, true))
        assertEquals(Decision.STOP, ResultWaitPolicy.decide(60_000, 340_000, duration, true))
    }
}
