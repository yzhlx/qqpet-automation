package com.ynk.virtualdisplay

import com.ynk.virtualdisplay.automation.StudyCompletionGate as Gate
import com.ynk.virtualdisplay.automation.StudyResultPageParser as Parser
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class StudyCompletionGateTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private fun epoch(time: String) = LocalDateTime.parse(time).atZone(zone).toInstant().toEpochMilli()
    private val attempt = Gate.Attempt("own-1", "study:田径运动课", epoch("2026-10-22T02:25:34"), 30,
        1000, "own-1", "study:田径运动课", 3000, 1, 1)
    private fun page(time: String = "2026-10-22T02:25", course: String = "study:田径运动课") =
        Parser.Result(Parser.Status.PARSED, course, LocalDateTime.parse(time), "fixture")
    private fun verify(a: Gate.Attempt = attempt, p: Parser.Result = page(),
        semantic: Gate.PrintedTimeSemantics = Gate.PrintedTimeSemantics.VERIFIED_REQUEST_MINUTE,
        evidence: String? = "hypothetical independent semantic measurement, not current production evidence",
        elapsed: Long = 1_803_000, now: Long = epoch("2026-10-22T02:55:40"),
        attempts: Set<String> = emptySet(), results: Set<String> = emptySet()) =
        Gate.verify(a, p, now, elapsed, zone, semantic, evidence, attempts, results)

    @Test fun unknownRealTimeSemanticsCannotCountAResultEvenWithOtherwiseCompleteProof() {
        assertEquals("RESULT_TIME_SEMANTICS_UNKNOWN", verify(semantic = Gate.PrintedTimeSemantics.UNKNOWN).reason)
        assertFalse(verify(evidence = null).accepted)
        val defaultDecision = Gate.verify(attempt, page(), epoch("2026-10-22T02:55:40"), 1_803_000,
            zone, attributedAttemptIds = emptySet(), attributedResultKeys = emptySet())
        assertFalse(defaultDecision.accepted)
    }
    @Test fun fullDateAndExactRequestMinuteCanAttributeOnlyUnderExplicitlyVerifiedSemantics() {
        assertTrue(verify().accepted)
        // This checks the future contract, not a claim that printed time semantics are established.
    }
    @Test fun measured0038ReportCannotBelongToD53AttemptAt0204() {
        val d53 = attempt.copy(requestedAtMs = epoch("2026-10-22T02:04:13"),
            confirmedStartAttemptId = null, confirmedStartElapsedMs = null, consumingRequestCount = 0)
        assertEquals(Gate.Decision.STALE_RESULT, verify(d53, page("2026-10-22T00:38")).decision)
    }
    @Test fun sameCourseEarlierAndLaterMinuteReportsAreBothRejected() {
        assertEquals(Gate.Decision.STALE_RESULT, verify(p = page("2026-10-22T02:24")).decision)
        assertEquals("RESULT_REQUEST_MINUTE_MISMATCH", verify(p = page("2026-10-22T02:26")).reason)
    }
    @Test fun explicitOldAndFutureDatesNeverGetReassignedToNearestDay() {
        assertEquals(Gate.Decision.STALE_RESULT, verify(p = page("2026-10-21T02:25")).decision)
        assertEquals(Gate.Decision.FUTURE_RESULT, verify(p = page("2026-10-23T02:25")).decision)
        assertEquals(Gate.Decision.FUTURE_RESULT, verify(p = page("2026-10-22T03:25")).decision)
    }
    @Test fun realOtherCourseReportDoesNotMatchOwnCourse() {
        assertEquals(Gate.Decision.WRONG_COURSE,
            verify(p = page("2026-10-20T22:01", "study:蹦蹦跳跳体能课")).decision)
    }
    @Test fun reportWithoutOwnPositiveSingleStartProofCannotCount() {
        listOf(attempt.copy(confirmedStartElapsedMs = null), attempt.copy(confirmedStartAttemptId = "old"),
            attempt.copy(confirmedStartCourseId = "study:旧课程"), attempt.copy(consumingRequestCount = 0),
            attempt.copy(consumingRequestCount = 2), attempt.copy(matchingMinuteStartCandidates = 2),
            attempt.copy(matchingMinuteStartCandidates = null)).forEach { assertFalse(verify(it).accepted) }
    }
    @Test fun missingDurationAndInsufficientMonotonicElapsedAreUnconfirmed() {
        assertEquals("COURSE_DURATION_UNKNOWN", verify(attempt.copy(durationMinutes = null)).reason)
        assertEquals("COURSE_DURATION_NOT_ELAPSED", verify(elapsed = 1_802_999).reason)
        assertFalse(verify(attempt.copy(confirmedStartElapsedMs = 999)).accepted)
        assertFalse(verify(elapsed = 2999).accepted)
    }
    @Test fun previouslyAttributedAttemptOrReportCannotCountTwice() {
        assertEquals(Gate.Decision.ALREADY_ATTRIBUTED, verify(attempts = setOf("own-1")).decision)
        assertEquals(Gate.Decision.ALREADY_ATTRIBUTED,
            verify(results = setOf("study:田径运动课@2026-10-22T02:25")).decision)
    }
    @Test fun midnightUsesPrintedFullDateAndMonotonicDurationWithoutGuessing() {
        val midnight = attempt.copy(requestedAtMs = epoch("2026-10-21T23:50:34"))
        assertTrue(verify(midnight, page("2026-10-21T23:50"), now = epoch("2026-10-22T00:20:40")).accepted)
        assertFalse(verify(midnight, page("2026-10-22T23:50"), now = epoch("2026-10-22T00:20:40")).accepted)
    }
    @Test fun absentOrAmbiguousPageIsNeverCompletion() {
        assertEquals(Gate.Decision.NO_RESULT, verify(p = Parser.Result(Parser.Status.NOT_RESULT, reason = "missing")).decision)
        assertFalse(verify(p = Parser.Result(Parser.Status.UNCONFIRMED, reason = "multiple dates")).accepted)
    }
}

class StudyResultPageParserTest {
    // Transcribed from real 720x1600 first-resume-study-stop.png. Boxes are test token examples
    // inside measured field ROIs, not a claim of ML Kit OCR success on that image.
    private fun fields(date: String = "2026 / 10 / 22 00:38", course: String = "课程 田径运动课") = listOf(
        Parser.Token("宠物成绩单", Parser.Box(222, 235, 493, 281)),
        Parser.Token(date, Parser.Box(256, 302, 463, 323)),
        Parser.Token(course, Parser.Box(347, 638, 599, 672)))
    private fun parse(tokens: List<Parser.Token> = fields(), width: Int = 720, height: Int = 1600, scene: Boolean = true) =
        Parser.parse(width, height, tokens, scene)

    @Test fun measuredReportFieldsIncludeFullDateAndCourse() {
        val result = parse()
        assertEquals(Parser.Status.PARSED, result.status)
        assertEquals(LocalDateTime.of(2026, 10, 22, 0, 38), result.printedDateTime)
        assertEquals("study:田径运动课", result.courseId)
        assertTrue(result.reason.endsWith("SEMANTICS_UNKNOWN"))
    }
    @Test fun independentOlderReportUsesItsOwnCourseAndDate() {
        val result = parse(fields("2026 / 10 / 20 22:01", "课程 蹦蹦跳跳体能课"))
        assertEquals("study:蹦蹦跳跳体能课", result.courseId)
        assertEquals(LocalDateTime.of(2026, 10, 20, 22, 1), result.printedDateTime)
    }
    @Test fun unrelatedClockAndTeacherCourseTextOutsideRoisAreIgnored() {
        val extra = listOf(Parser.Token("15:52", Parser.Box(20, 10, 90, 40)),
            Parser.Token("2027 / 01 / 01 12:34", Parser.Box(20, 1100, 300, 1130)),
            Parser.Token("课程 其他课", Parser.Box(50, 1390, 300, 1420)))
        assertEquals(parse(), parse(extra + fields()))
    }
    @Test fun dateAndTimeSplitIntoSeparateTokensOnSameLineAreJoined() {
        val split = fields().filterNot { it.bounds.top == 302 } + listOf(
            Parser.Token("2026 / 10 / 22", Parser.Box(256, 302, 390, 323)),
            Parser.Token("00:38", Parser.Box(409, 302, 463, 323)))
        assertEquals(parse().printedDateTime, parse(split.reversed()).printedDateTime)
    }
    @Test fun timeOnlyOrInvalidDateOrDuplicateDateDoesNotProduceAttributionFields() {
        listOf("00:38", "2026 / 02 / 30 00:38", "2026 / 10 / 22 25:38",
            "2026 / 10 / 22 00:38 2026 / 10 / 22 02:25").forEach {
            assertEquals(Parser.Status.UNCONFIRMED, parse(fields(it)).status)
        }
        assertEquals(Parser.Status.UNCONFIRMED, parse(fields() + fields()[1]).status)
    }
    @Test fun duplicatedOrPartialCourseIsAmbiguousRatherThanSubstringMatch() {
        assertEquals(Parser.Status.UNCONFIRMED, parse(fields(course = "课程 田径运动课 蹦蹦跳跳体能课")).status)
        assertEquals(Parser.Status.UNCONFIRMED, parse(fields(course = "课程 田径运动")).status)
        assertEquals(Parser.Status.UNCONFIRMED, parse(fields() + fields()[2]).status)
    }
    @Test fun wrongResolutionAndUnconfirmedSceneAndPartialRoiTokensFailClosed() {
        assertEquals(Parser.Status.UNCONFIRMED, parse(width = 1084, height = 2412).status)
        assertEquals(Parser.Status.NOT_RESULT, parse(scene = false).status)
        val straddling = fields().filterNot { it.bounds.top == 302 } +
            Parser.Token("2026 / 10 / 22 00:38", Parser.Box(220, 302, 500, 323))
        assertEquals(Parser.Status.UNCONFIRMED, parse(straddling).status)
    }
}
