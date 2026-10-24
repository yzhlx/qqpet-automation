package com.ynk.virtualdisplay.bot

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class SchedulerTest {
    private val healthy = CareResult(Health(80, 80), 0, 0, 0, CareStop.DONE)
    private val calls = mutableListOf<String>()

    private fun run(
        config: BotConfig,
        ledger: DailyLedger = MemoryLedger(),
        studies: MutableList<StudyStop> = mutableListOf(),
        work: WorkResult = WorkResult(WorkStop.COMPLETED, 68),
        lost: Int = 0,
    ): RunReport = runBlocking {
        var lostLeft = lost
        Scheduler(
            care = { e, c -> calls += "care $e/$c"; healthy },
            study = { _, _ ->
                if (lostLeft-- > 0) throw NavigationFailed("GO_HOME_UNKNOWN_PAGE")
                calls += "study"
                StudyResult(studies.removeFirstOrNull() ?: StudyStop.COMPLETED)
            },
            work = { calls += "work"; work },
            goHome = { calls += "home" },
            ledger = ledger,
        ).run(config)
    }

    @Test fun eachLessonIsPrecededByCare() {
        val report = run(BotConfig(lessons = 3))
        assertEquals(RunReport(3, 0, 0, RunStop.DONE), report)
        assertEquals(listOf("care 70/70", "study", "care 70/70", "study", "care 70/70", "study"), calls)
    }

    @Test fun aLessonThatDoesNotStartGetsOneShiftThenARetry() {
        val ledger = MemoryLedger()
        val report = run(BotConfig(lessons = 1), ledger, mutableListOf(StudyStop.NOT_STARTED))
        assertEquals(RunReport(1, 1, 68, RunStop.DONE), report)
        assertEquals(listOf("care 70/70", "study", "care 70/70", "work", "care 70/70", "study"), calls)
        assertEquals(20, ledger.minutesUsed())
    }

    @Test fun stillShortAfterAShiftStops() {
        val report = run(BotConfig(lessons = 2), studies = mutableListOf(StudyStop.NOT_STARTED, StudyStop.NOT_STARTED))
        assertEquals(RunStop.NOT_ENOUGH_COINS, report.stop)
        assertEquals(1, report.shifts)
    }

    @Test fun workCanBeTurnedOff() {
        val report = run(BotConfig(workWhenShort = false), studies = mutableListOf(StudyStop.NOT_STARTED))
        assertEquals(RunStop.NOT_ENOUGH_COINS, report.stop)
        assertEquals(0, calls.count { it == "work" })
    }

    @Test fun theDailyCapIsRespected() {
        val report = run(BotConfig(lessons = 5), ledger = MemoryLedger(used = 460))
        assertEquals(RunReport(2, 0, 0, RunStop.DAILY_LIMIT), report)
    }

    @Test fun noShiftWhenItWouldLeaveNoRoomForTheLesson() {
        val report = run(BotConfig(), ledger = MemoryLedger(used = 465), studies = mutableListOf(StudyStop.NOT_STARTED))
        assertEquals(RunStop.DAILY_LIMIT, report.stop)
        assertEquals(0, calls.count { it == "work" })
    }

    @Test fun aMissingCourseEndsTheRun() {
        assertEquals(RunStop.COURSE_NOT_FOUND, run(BotConfig(course = "x"), studies = mutableListOf(StudyStop.COURSE_NOT_FOUND)).stop)
    }

    @Test fun gettingLostOnceIsRecoveredFrom() {
        val report = run(BotConfig(lessons = 1), lost = 1)
        assertEquals(RunReport(1, 0, 0, RunStop.DONE), report)
        assertEquals(1, calls.count { it == "home" })
    }

    @Test fun repeatedFailuresEndTheRun() {
        assertEquals(RunStop.TOO_MANY_FAILURES, run(BotConfig(), lost = 5).stop)
    }

    @Test fun aLessonWithoutAReportCardStillCountsItsMinutes() {
        val ledger = MemoryLedger()
        val report = run(BotConfig(lessons = 1), ledger, mutableListOf(StudyStop.NO_RESULT))
        assertEquals(1, report.lessons)
        assertEquals(10, ledger.minutesUsed())
    }
}
