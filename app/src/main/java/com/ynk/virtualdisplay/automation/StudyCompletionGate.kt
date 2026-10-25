package com.ynk.virtualdisplay.automation

/**
 * Read-only attribution policy, NOT connected to live counters.
 * first-resume-study-stop.png prints 2026/10/22 00:38, 田径运动课. The older request
 * was at 00:38:33; positive learning observation at 00:38:35. 00:43:30 was recovery
 * exhaustion. d53ab509 met the old report at 02:04:23 without a new start.
 * Printed time MAY be request time. e3df has no archived report PNG to settle this.
 */
object StudyCompletionGate {
    enum class PrintedTimeSemantics { UNKNOWN, VERIFIED_REQUEST_MINUTE }
    enum class Decision { ACCEPTED, WRONG_COURSE, STALE_RESULT, FUTURE_RESULT, NO_RESULT, UNCONFIRMED, ALREADY_ATTRIBUTED }
    data class Verdict(val decision: Decision, val reason: String) {
        val accepted: Boolean get() = decision == Decision.ACCEPTED
    }
    data class Attempt(
        val attemptId: String,
        val requestedCourseId: String,
        val requestedAtMs: Long,
        val durationMinutes: Int?,
        val requestElapsedMs: Long,
        val confirmedStartAttemptId: String?,
        val confirmedStartCourseId: String?,
        val confirmedStartElapsedMs: Long?,
        val consumingRequestCount: Int,
        val matchingMinuteStartCandidates: Int?,
    )

    /**
     * Caller must persist attribution before counting; both sets come from durable records.
     * Current evidence cannot enable VERIFIED_REQUEST_MINUTE. No nearest-date inference.
     */
    fun verify(
        attempt: Attempt,
        page: StudyResultPageParser.Result,
        observedAtMs: Long,
        observedElapsedMs: Long,
        zone: java.time.ZoneId,
        semantics: PrintedTimeSemantics = PrintedTimeSemantics.UNKNOWN,
        semanticsEvidence: String? = null,
        attributedAttemptIds: Set<String>,
        attributedResultKeys: Set<String>,
    ): Verdict {
        fun reject(reason: String) = Verdict(Decision.UNCONFIRMED, reason)
        if (page.status == StudyResultPageParser.Status.NOT_RESULT) return Verdict(Decision.NO_RESULT, page.reason)
        if (page.status != StudyResultPageParser.Status.PARSED) return reject(page.reason)
        val course = page.courseId ?: return reject("RESULT_COURSE_UNREAD")
        if (course != attempt.requestedCourseId) return Verdict(Decision.WRONG_COURSE, "RESULT_COURSE_MISMATCH:$course")
        val printed = page.printedDateTime ?: return reject("RESULT_FULL_DATE_TIME_UNREAD")
        val key = "$course@$printed"
        if (attempt.attemptId in attributedAttemptIds || key in attributedResultKeys) {
            return Verdict(Decision.ALREADY_ATTRIBUTED, "RESULT_OR_ATTEMPT_ALREADY_ATTRIBUTED")
        }
        if (attempt.attemptId.isBlank() || attempt.requestedAtMs <= 0 || attempt.requestElapsedMs < 0) return reject("ATTEMPT_INVALID")
        val offsets = zone.rules.getValidOffsets(printed)
        if (offsets.size != 1) return reject("RESULT_LOCAL_TIME_AMBIGUOUS")
        val printedEpoch = printed.toInstant(offsets.single()).toEpochMilli()
        if (printedEpoch > observedAtMs) return Verdict(Decision.FUTURE_RESULT, "RESULT_FROM_FUTURE")
        val requestMinute = java.time.Instant.ofEpochMilli(attempt.requestedAtMs).atZone(zone).toLocalDateTime()
            .withSecond(0).withNano(0)
        if (printed < requestMinute) return Verdict(Decision.STALE_RESULT, "RESULT_PREDATES_OWN_REQUEST_MINUTE")
        if (semantics != PrintedTimeSemantics.VERIFIED_REQUEST_MINUTE || semanticsEvidence.isNullOrBlank()) {
            return reject("RESULT_TIME_SEMANTICS_UNKNOWN")
        }
        if (printed != requestMinute) return Verdict(Decision.STALE_RESULT, "RESULT_REQUEST_MINUTE_MISMATCH")
        if (attempt.consumingRequestCount != 1 || attempt.matchingMinuteStartCandidates != 1) return reject("START_NOT_UNIQUE")
        if (attempt.confirmedStartAttemptId != attempt.attemptId || attempt.confirmedStartCourseId != course) return reject("OWN_START_NOT_CONFIRMED")
        val startElapsed = attempt.confirmedStartElapsedMs ?: return reject("OWN_START_NOT_CONFIRMED")
        if (startElapsed < attempt.requestElapsedMs || observedElapsedMs < startElapsed) return reject("START_CLOCK_INVALID")
        val duration = attempt.durationMinutes ?: return reject("COURSE_DURATION_UNKNOWN")
        if (duration !in 1..480) return reject("COURSE_DURATION_INVALID")
        if (observedElapsedMs - startElapsed < duration * 60_000L) return reject("COURSE_DURATION_NOT_ELAPSED")
        return Verdict(Decision.ACCEPTED, "OWN_SINGLE_START_AND_REQUEST_MINUTE_MATCH")
    }
}
