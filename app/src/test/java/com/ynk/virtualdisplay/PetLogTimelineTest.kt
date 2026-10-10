package com.ynk.virtualdisplay

import androidx.compose.ui.graphics.Color
import com.ynk.virtualdisplay.ui.data.LogEntry
import com.ynk.virtualdisplay.ui.log.PetLogEntry
import com.ynk.virtualdisplay.ui.log.PetLogPresentation
import com.ynk.virtualdisplay.ui.log.collapseRepeats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The log timeline is the record a user consults when something went wrong, so it must stay
 * readable. These tests pin the two rules that make it so: capture chatter is dropped, and long
 * runs of an identical poll result collapse into one counted row.
 *
 * Only states whose rendering avoids `org.json` are used here — that class is an unmocked stub in
 * JVM unit tests, so any path through it would fail for reasons unrelated to the behaviour under
 * test (and would silently turn an assertion into a false pass).
 */
class PetLogPresentationTest {

    private fun entry(
        state: String,
        action: String,
        result: String = "{}",
        timestamp: String = "2026-10-10 22:45:36.247",
    ) = LogEntry(timestamp, state, action, result, null, null)

    private fun row(text: String, chip: String? = null) =
        PetLogEntry("22:45", text, Color.Gray, chip)

    @Test
    fun `frame telemetry is dropped from the readable timeline`() {
        val entries = listOf(
            entry("INITIAL_NAVIGATION", "round-1:FRAME", """{"displayId":4}"""),
            entry("INITIAL_NAVIGATION", "round-1:FRAME", """{"displayId":4}"""),
            entry("INITIAL_NAVIGATION", "round-1:nav"),
        )
        val rows = PetLogPresentation.timeline(entries)
        assertFalse(rows.any { it.text.contains("FRAME") })
        assertEquals(1, rows.size)
    }

    @Test
    fun `a batch completion is reported from the real DONE payload`() {
        val entries = listOf(entry("DONE", "round-1:SESSION_COMPLETE", "MANAGED_BATCH_PASS 1/1"))
        val rows = PetLogPresentation.timeline(entries)
        assertEquals(1, rows.size)
        assertTrue(rows.first().text.contains("批次完成"))
        assertEquals("已确认", rows.first().chip)
    }

    @Test
    fun `an explicit user stop is told apart from a batch pass`() {
        val stopped = PetLogPresentation.timeline(
            listOf(entry("SESSION", "none", "SESSION_STOP 用户停止")),
        )
        assertTrue(stopped.first().text.contains("已停止"))
        assertTrue(stopped.first().text.contains("用户停止"))
    }

    @Test
    fun `a long run of the same poll result collapses into one counted row`() {
        // This is the real shape of a 10-minute course: the same confirmation every ~2 seconds.
        val rows = collapseRepeats((1..40).map { row("学习画面已确认", "已确认") })
        assertEquals(1, rows.size)
        assertEquals(40, rows.first().occurrences)
    }

    @Test
    fun `a repeat after a different event is not swallowed by an earlier run`() {
        val rows = collapseRepeats(
            listOf(
                row("学习画面已确认"),
                row("开始课程"),
                row("学习画面已确认"),
            ),
        )
        assertEquals(3, rows.size)
        assertEquals(1, rows.first().occurrences)
        assertEquals(1, rows.last().occurrences)
    }

    @Test
    fun `the same text with a different chip is a different event`() {
        val rows = collapseRepeats(listOf(row("确认课程页", "已确认"), row("确认课程页", "待确认")))
        assertEquals(2, rows.size)
    }

    @Test
    fun `a single occurrence is never labelled as repeated`() {
        val rows = collapseRepeats(listOf(row("启动导航")))
        assertEquals(1, rows.first().occurrences)
    }
}
