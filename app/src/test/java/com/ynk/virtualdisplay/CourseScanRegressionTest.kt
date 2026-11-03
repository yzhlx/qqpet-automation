package com.ynk.virtualdisplay

import android.graphics.Rect
import com.ynk.virtualdisplay.automation.*
import org.junit.Assert.*
import org.junit.Test

class CourseScanRegressionTest {
    private fun line(text: String, left: Int, right: Int, top: Int = 937, bottom: Int = 963): TextLine {
        val box = Rect()
        box.left = left; box.right = right; box.top = top; box.bottom = bottom
        return TextLine(text, box)
    }

    @Test fun measuredGeographyGlyphConfusionSharesOneCourseIdentity() {
        val courses = CourseListParser.completeCards(listOf(
            line("世界地理谋", 259, 397), line("用时:30分钟", 270, 386, 979, 998),
        ), 720, 1600) { false }
        assertEquals("study:世界地理课", courses.single().id)
        assertEquals(30, courses.single().durationMinutes)
        val exact = courses.single().copy(name = "世界地理课")
        assertEquals(0, CourseScanAccumulator.fold(courses, listOf(exact)).added)
    }

    @Test fun horizontalCardsKeepTheirOwnDurationsWhenOcrReturnsThemInReverseOrder() {
        val courses = CourseListParser.parse(listOf(
            line("田径运动课", 107, 300), line("看图识世界课", 385, 551),
            line("用时:10分钟", 412, 524, 979, 998), line("用时:30分钟", 148, 260, 979, 998),
        )) { false }
        assertEquals(listOf("田径运动课", "看图识世界课"), courses.map { it.name })
        assertEquals(listOf(30, 10), courses.map { it.durationMinutes })
    }

    @Test fun aDurationFromTheNextCardOrAnotherRowCannotAuthoriseACourse() {
        assertTrue(CourseListParser.parse(listOf(line("田径运动课", 107, 300),
            line("用时:30分钟", 412, 524, 979, 998))) { false }.isEmpty())
        assertTrue(CourseListParser.parse(listOf(line("田径运动课", 107, 300),
            line("用时:30分钟", 148, 260, 600, 620))) { false }.isEmpty())
        assertTrue(CourseListParser.parse(listOf(line("田径运动课", 107, 300),
            line("用时:30分钟", 148, 260, 1200, 1220))) { false }.isEmpty())
    }

    @Test fun completeCardFilteringRejectsBothEdgesAndKeepsTheWholeRightHandCard() {
        val courses = CourseListParser.completeCards(listOf(
            line("世界地理课", 49, 170), line("用时:30分钟", 50, 162, 979, 998),
            line("演说表达课", 385, 551), line("用时:30分钟", 412, 524, 979, 998),
            line("萌芽夏令营", 595, 700), line("用时:30分钟", 615, 720, 979, 998),
        ), 720, 1600) { false }
        assertEquals(listOf("演说表达课"), courses.map { it.name })
    }

    @Test fun mergedOcrSliversAndPunctuationCannotBecomeCourseNames() {
        val courses = CourseListParser.parse(listOf(
            line("|看图识世界误", 385, 551), line("用时:10分钟", 412, 524, 979, 998),
        )) { false }
        assertTrue(courses.isEmpty())
    }

    @Test fun duplicateNamesWithConflictingDurationsRefuseTheScan() {
        val first = CourseEntry("study:田径运动课", "田径运动课", 30, true, 937, 963)
        assertThrows(IllegalStateException::class.java) {
            CourseScanAccumulator.fold(listOf(first), listOf(first.copy(durationMinutes = 10)))
        }
        assertEquals(0, CourseScanAccumulator.fold(listOf(first), listOf(first)).added)
    }

    @Test fun onlyACompleteNonemptyFreshCatalogCanStartStudy() {
        val course = CourseEntry("study:田径运动课", "田径运动课", 30, true, 937, 963)
        assertFalse(CourseCatalog(1000, listOf(course)).isUsable(1100))
        assertTrue(CourseCatalog(1000, listOf(course), true).isUsable(1100))
        assertFalse(CourseCatalog(1200, listOf(course), true).isUsable(1100))
        assertFalse(CourseCatalog(1000, emptyList(), true).isUsable(1100))
    }

    @Test fun onlyAnUnmovingCardStripCountsAsABoundarySample() {
        val before = intArrayOf(10, 20, 30, 40)
        assertTrue(CourseStripMotion.same(before, before.copyOf()))
        assertTrue(CourseStripMotion.same(before, intArrayOf(11, 19, 31, 39)))
        assertFalse(CourseStripMotion.same(before, intArrayOf(80, 90, 100, 110)))
        assertFalse(CourseStripMotion.same(intArrayOf(), intArrayOf()))
    }
    @Test fun aMissedDurationStillLeavesBothWholeSlotsToRead() {
        assertEquals(listOf(204, 468), CourseCardSlots.centres(204, 720))
        assertEquals(listOf(204, 468), CourseCardSlots.centres(468, 720))
        assertEquals(listOf(252, 516), CourseCardSlots.centres(516, 720))
    }

    @Test fun slotsCutByEitherViewportEdgeAreNotReadAsWholeCards() {
        assertEquals(listOf(350), CourseCardSlots.centres(350, 720))
        assertFalse(CourseCardSlots.isWhole(106, 720))
        assertFalse(CourseCardSlots.isWhole(650, 720))
    }
}
