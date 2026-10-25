package com.ynk.virtualdisplay

import com.ynk.virtualdisplay.automation.sessionErrorEventPayload
import org.junit.Assert.assertEquals
import org.junit.Test

class SessionEventPayloadTest {
    @Test fun errorPayloadPersistsResultAndReason() {
        val payload = sessionErrorEventPayload(
            state = "WAIT_COMPLETION",
            action = "work-completion-dismiss",
            result = "PASS",
            reason = "PAYROLL_RETURNED_OUTDOOR",
        )

        assertEquals("WAIT_COMPLETION", payload["state"])
        assertEquals("work-completion-dismiss", payload["action"])
        assertEquals("PASS", payload["result"])
        assertEquals("PAYROLL_RETURNED_OUTDOOR", payload["reason"])
    }
}
