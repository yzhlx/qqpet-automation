package com.ynk.virtualdisplay.bot

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StudyActionTest {
    private val exit = Point(600f, 1340f)
    private val academyLabel = Point(210f, 420f)
    private val otherCourse = Point(470f, 950f)

    /** A pet whose academy works like the real one; the lesson ends after [lessonMs] of pauses. */
    private fun world(lessonMs: Long = 10 * 60_000L, starts: Boolean = true, panelAlreadyOpen: Boolean = false): FakePet {
        val moves = mutableMapOf(
            (Page.ROOM to exit) to Page.OUTDOOR,
            (Page.OUTDOOR to Layout.enterAcademy) to (if (panelAlreadyOpen) Page.COURSE_LIST else Page.ACADEMY),
            (Page.ACADEMY to academyLabel) to Page.COURSE_LIST,
            (Page.COURSE_LIST to Layout.defaultCourse) to Page.COURSE_DETAIL,
            (Page.COURSE_LIST to otherCourse) to Page.COURSE_DETAIL,
        )
        if (starts) moves[Page.COURSE_DETAIL to Layout.startCourse] = Page.LEARNING
        val pet = FakePet(energy = 90, cleanliness = 90, extraMoves = moves)
        pet.texts[Page.ROOM] = mutableMapOf("出门" to exit)
        pet.texts[Page.ACADEMY] = mutableMapOf("初级学园" to academyLabel)
        var lessonStart = -1L
        pet.game.onPause = { g ->
            if (g.page == Page.LEARNING) {
                if (lessonStart < 0) lessonStart = g.pausedMs
                val inLesson = g.pausedMs - lessonStart
                if (inLesson >= 3_000) pet.highFiveShown = true
                if (inLesson >= lessonMs) g.page = Page.RESULT
            }
        }
        return pet
    }

    private fun study(pet: FakePet, course: String? = null, minutes: Int = 10) = runBlocking {
        val g = pet.game
        StudyAction(g, g, pet, Navigator(g, g)).run(course, minutes)
    }

    @Test fun defaultCourseRunsToTheReportCardAndHome() {
        val pet = world()
        val result = study(pet)
        assertEquals(StudyStop.COMPLETED, result.stop)
        assertEquals(StudyAction.HIGH_FIVE_TAPS, result.highFives)
        assertEquals(Page.ROOM, pet.game.page)
    }

    @Test fun anAlreadyOpenPanelIsUsedAsIs() {
        val pet = world(panelAlreadyOpen = true)
        assertEquals(StudyStop.COMPLETED, study(pet).stop)
        assertTrue(pet.game.taps.none { it.second == academyLabel })
    }

    @Test fun aNamedCourseIsFoundOnALaterPage() {
        val pet = world()
        var flings = 0
        pet.game.onDrag = { g -> if (g.page == Page.COURSE_LIST && ++flings == 2) pet.texts[Page.COURSE_LIST] = mutableMapOf("田径运动课" to otherCourse) }
        pet.texts[Page.COURSE_DETAIL] = mutableMapOf("田径运动课" to Point(200f, 800f))
        assertEquals(StudyStop.COMPLETED, study(pet, "田径运动课").stop)
        assertEquals(2, flings)
    }

    @Test fun aMissingCourseStopsWithoutStarting() {
        val pet = world()
        assertEquals(StudyStop.COURSE_NOT_FOUND, study(pet, "不存在的课").stop)
        assertTrue(pet.game.taps.none { it.second == Layout.startCourse })
        assertEquals(Page.ROOM, pet.game.page)
    }

    @Test fun aLessonThatDoesNotStartIsReported() {
        val pet = world(starts = false)
        assertEquals(StudyStop.NOT_STARTED, study(pet).stop)
        assertEquals(Page.ROOM, pet.game.page)
    }

    @Test fun noReportCardInTimeIsReportedAndTheLessonIsStillWaitedOut() {
        val pet = world(lessonMs = 13 * 60_000L)
        val result = study(pet, minutes = 10)
        assertEquals(StudyStop.NO_RESULT, result.stop)
        assertTrue(pet.game.taps.none { it.first == Page.LEARNING && it.second != Layout.highFive })
        assertEquals(Page.ROOM, pet.game.page)
    }

    @Test fun noExitLabelMeansNoCourseList() {
        val pet = world()
        pet.texts.remove(Page.ROOM)
        assertEquals(StudyStop.NO_COURSE_LIST, study(pet).stop)
    }
}
