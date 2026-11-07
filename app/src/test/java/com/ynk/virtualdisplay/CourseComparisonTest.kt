package com.ynk.virtualdisplay

import com.ynk.virtualdisplay.automation.CourseAttributeGain
import com.ynk.virtualdisplay.automation.CourseComparison
import com.ynk.virtualdisplay.automation.CourseEntry
import com.ynk.virtualdisplay.automation.CourseReading
import com.ynk.virtualdisplay.automation.SessionPlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CourseComparisonTest {
    private val now = 1_700_000_000_000L

    private fun course(name: String, unlocked: Boolean = true) = CourseEntry(
        id = "study:$name", name = name, durationMinutes = 10, unlocked = unlocked,
        rowTop = 0, rowBottom = 100)

    private fun reading(name: String, tuition: Int, minutes: Int = 10,
        gain: CourseAttributeGain? = CourseAttributeGain("力量", 2),
        at: Long = 1_700_000_000_000L) =
        CourseReading("study:$name", name, minutes, tuition, gain, at)

    @Test fun `no readings means everything unknown`() {
        val summary = CourseComparison.compare(listOf(course("A"), course("B")), emptyMap(), now)
        assertFalse(summary.hasAnyReading)
        assertNull(summary.cheapestTuition)
        assertNull(summary.cheapestPerMinute)
        assertNull(summary.bestAttributeValue)
        assertEquals(listOf("A", "B"), summary.unknownNames)
        assertEquals(2, summary.rows.size)
    }

    @Test fun `cheapest tuition per minute and per attribute are ranked separately`() {
        val summary = CourseComparison.compare(
            listOf(course("A"), course("B"), course("C")),
            mapOf(
                "study:A" to reading("A", 20, 10, CourseAttributeGain("力量", 2)),
                "study:B" to reading("B", 10, 10, CourseAttributeGain("智力", 2)),
                "study:C" to reading("C", 40, 20, CourseAttributeGain("魅力", 5)),
            ),
            now,
        )
        assertEquals("B", summary.cheapestTuition?.name)
        assertEquals("B", summary.cheapestPerMinute?.name)
        assertEquals(1.0, summary.cheapestPerMinute?.coinsPerMinute ?: -1.0, 0.0001)
        assertEquals("B", summary.bestAttributeValue?.name)
        assertEquals(5.0, summary.bestAttributeValue?.coinsPerAttribute ?: -1.0, 0.0001)
        assertTrue(summary.unknownNames.isEmpty())
    }

    @Test fun `stale reading is shown as unknown`() {
        val stale = reading("A", 20, at = now - SessionPlan.CATALOG_MAX_AGE_MS - 1)
        val summary = CourseComparison.compare(listOf(course("A")), mapOf("study:A" to stale), now)
        assertFalse(summary.hasAnyReading)
        assertNull(summary.rows.single().tuitionCoins)
        assertFalse(summary.rows.single().readingFresh)
        assertEquals(listOf("A"), summary.unknownNames)
    }

    @Test fun `missing attribute gain keeps tuition but is not ranked per attribute`() {
        val summary = CourseComparison.compare(
            listOf(course("A"), course("B")),
            mapOf(
                "study:A" to reading("A", 10, gain = null),
                "study:B" to reading("B", 30, gain = CourseAttributeGain("智力", 2)),
            ),
            now,
        )
        assertEquals("A", summary.cheapestTuition?.name)
        assertEquals("B", summary.bestAttributeValue?.name)
        assertEquals(listOf("A"), summary.unknownNames)
    }

    @Test fun `locked courses never enter the comparison`() {
        val summary = CourseComparison.compare(
            listOf(course("Locked", unlocked = false), course("Open")),
            mapOf("study:Locked" to reading("Locked", 1)),
            now,
        )
        assertEquals(listOf("Open"), summary.rows.map { it.name })
        assertNull(summary.cheapestTuition)
    }

    @Test fun `invalid numbers stay unknown instead of ranking`() {
        val summary = CourseComparison.compare(
            listOf(course("A")),
            mapOf("study:A" to CourseReading("study:A", "A", 0, 10, null, now)),
            now,
        )
        assertFalse(summary.hasAnyReading)
        assertNull(summary.cheapestPerMinute)
    }
}
