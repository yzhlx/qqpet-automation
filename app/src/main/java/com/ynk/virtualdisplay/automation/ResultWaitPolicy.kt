package com.ynk.virtualdisplay.automation

/**
 * What to do with a frame that is neither the result page nor the running class.
 *
 * Measured 2026-11-01 (WO-072 r4 V9): the page after a lesson held a steady unrecognised frame for
 * more than 8 seconds and the session stopped, while the lesson had in fact been charged and run.
 * Waiting costs nothing because no input is sent, so the limit only has to stay inside the
 * 90-second grace [PetStudyFlow.waitResult] allows after the lesson's own duration.
 */
object ResultWaitPolicy {
    enum class Decision { KEEP_WAITING, COURSE_OVER_NO_RESULT, STOP }

    const val SETTLE_LIMIT_MS = 60_000L
    /** The result page can open over the academy, so a known page must hold before it counts. */
    const val LEFT_COURSE_HOLD_MS = 15_000L

    fun decide(unconfirmedMs: Long, courseElapsedMs: Long, courseDurationMs: Long, knownPageAfterCourse: Boolean): Decision =
        when {
            knownPageAfterCourse && courseElapsedMs >= courseDurationMs && unconfirmedMs >= LEFT_COURSE_HOLD_MS ->
                Decision.COURSE_OVER_NO_RESULT
            unconfirmedMs >= SETTLE_LIMIT_MS -> Decision.STOP
            else -> Decision.KEEP_WAITING
        }
}
