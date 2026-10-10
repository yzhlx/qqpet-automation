package com.ynk.virtualdisplay

import com.ynk.virtualdisplay.automation.ManagedRuntimeState
import com.ynk.virtualdisplay.automation.ObservationSource
import com.ynk.virtualdisplay.automation.ObservedValue
import com.ynk.virtualdisplay.automation.SessionRunState
import com.ynk.virtualdisplay.ui.data.CareStats
import com.ynk.virtualdisplay.ui.data.HealthSnapshot
import com.ynk.virtualdisplay.ui.data.RoundRecord
import com.ynk.virtualdisplay.ui.data.SessionRunRecord
import com.ynk.virtualdisplay.ui.records.RecordsPresentation
import com.ynk.virtualdisplay.ui.records.VerdictTone
import com.ynk.virtualdisplay.ui.screens.HealthStatStatus
import com.ynk.virtualdisplay.ui.screens.HealthStatPresentation
import com.ynk.virtualdisplay.ui.screens.ManagedControlsPresentation
import com.ynk.virtualdisplay.ui.screens.ManagedRuntimePresentation
import com.ynk.virtualdisplay.ui.screens.RoundProgressPresentation
import com.ynk.virtualdisplay.ui.screens.StatusPillPresentation
import com.ynk.virtualdisplay.ui.screens.StatusPillTone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM coverage for the v0.1 presentation rules. These tests exist to pin down the honesty
 * contract of the product UI: a missing reading can never become a number, a sent input can never
 * become a confirmed effect, and the pause control can never become operable.
 */
class PetHomePresentationTest {

    private val wall = 5_000_000L
    private fun at(hhmm: Long) = { ms: Long -> if (ms == hhmm) "21:31" else "00:00" }

    private fun known(value: Int, observedAt: Long = wall) =
        ObservedValue.Known(value, observedAt, 1.0, "frame.png", ObservationSource.DIGIT_RECOGNITION)

    // --- health stats --------------------------------------------------------------------------

    @Test
    fun `unknown readings never render a number`() {
        val state = ManagedRuntimeState()
        val stats = HealthStatPresentation.stats(state, wall, false, at(wall))
        stats.forEach { stat ->
            assertEquals(HealthStatStatus.UNKNOWN, stat.status)
            assertEquals("—", stat.valueText)
            assertEquals("未确认", stat.meta)
        }
    }

    @Test
    fun `fresh known reading of the current session shows the number and its time`() {
        val state = ManagedRuntimeState(energy = known(96, wall), cleanliness = known(94, wall), mood = known(82, wall))
        val stats = HealthStatPresentation.stats(state, wall, true, at(wall))
        assertEquals("≈96", stats[0].valueText)
        assertEquals("21:31 确认", stats[0].meta)
        assertTrue(stats[0].current)
        assertTrue(stats[0].status == HealthStatStatus.KNOWN)
    }

    @Test
    fun `known reading from an earlier session is not labelled as confirmed now`() {
        val state = ManagedRuntimeState(energy = known(91, wall))
        val stats = HealthStatPresentation.stats(state, wall, belongsToCurrentSession = false, at(wall))
        assertEquals("≈91", stats[0].valueText)
        assertEquals("上次 21:31", stats[0].meta)
        assertFalse(stats[0].current)
    }

    @Test
    fun `stale reading reports the previous value as expired, never as current`() {
        val old = wall - ManagedRuntimePresentation.HEALTH_MAX_AGE_MS - 1
        val state = ManagedRuntimeState(
            energy = ObservedValue.Stale(known(90, old), wall, "FRAME_TOO_OLD"),
        )
        val stats = HealthStatPresentation.stats(state, wall, true, at(old))
        assertEquals(HealthStatStatus.STALE, stats[0].status)
        assertTrue(stats[0].valueText.startsWith("≈"))
        assertTrue(stats[0].meta.contains("已过期"))
        assertFalse(stats[0].current)
    }

    @Test
    fun `unsupported reading says so instead of showing zero`() {
        val state = ManagedRuntimeState(
            mood = ObservedValue.Unsupported("NO_READER_IN_SLICE1"),
        )
        val stats = HealthStatPresentation.stats(state, wall, true, at(wall))
        assertEquals(HealthStatStatus.UNSUPPORTED, stats[2].status)
        assertEquals("—", stats[2].valueText)
        assertEquals("不支持", stats[2].meta)
    }

    // --- controls ------------------------------------------------------------------------------

    @Test
    fun `pause is never operable in v0_1`() {
        val controls = ManagedControlsPresentation.of(
            legacyActive = true, runState = SessionRunState.RUNNING, canStart = true,
        )
        assertFalse(controls.pauseEnabled)
        assertFalse(controls.manualPauseAvailable)
        assertFalse(controls.showStartPrimary)
        assertTrue(controls.stopEnabled)
        assertTrue(controls.pauseUnavailableReason.contains("未开放手动暂停"))
    }

    @Test
    fun `automatic recovery is reported as recovery, not as a manual pause`() {
        val paused = ManagedControlsPresentation.of(
            legacyActive = true, runState = SessionRunState.PAUSED, canStart = false,
        )
        assertTrue(paused.automaticRecoveryActive)
        val running = ManagedControlsPresentation.of(
            legacyActive = true, runState = SessionRunState.RUNNING, canStart = false,
        )
        assertFalse(running.automaticRecoveryActive)
    }

    @Test
    fun `start is disabled when the environment cannot start, with the real reason`() {
        val blocked = ManagedControlsPresentation.of(
            legacyActive = false, runState = SessionRunState.IDLE, canStart = false,
            profileBlockers = listOf("continue 坐标未校准"), displayReady = true,
        )
        assertFalse(blocked.startEnabled)
        assertEquals("尚未校准：continue 坐标未校准", blocked.blockerNote)
        assertFalse(blocked.batchRoundsSelectable)

        val noDisplay = ManagedControlsPresentation.of(
            legacyActive = false, runState = SessionRunState.IDLE, canStart = false, displayReady = false,
        )
        assertTrue(noDisplay.blockerNote!!.contains("虚拟屏"))
    }

    @Test
    fun `start is enabled only when idle and the environment is ready`() {
        val ready = ManagedControlsPresentation.of(
            legacyActive = false, runState = SessionRunState.IDLE, canStart = true,
        )
        assertTrue(ready.startEnabled)
        assertNull(ready.blockerNote)
        assertTrue(ready.batchRoundsSelectable)
    }

    // --- progress ------------------------------------------------------------------------------

    @Test
    fun `progress with no rounds is not faked as zero percent complete`() {
        val progress = RoundProgressPresentation.of(totalRounds = 0, completedRounds = 0)
        assertEquals("未开始", progress.roundsText)
        assertEquals("—", progress.percentText)
        assertEquals(0f, progress.fraction, 0.0001f)
    }

    @Test
    fun `progress reflects the real completed fraction`() {
        val progress = RoundProgressPresentation.of(totalRounds = 3, completedRounds = 2)
        assertEquals(2, progress.completed)
        assertEquals(3, progress.total)
        assertEquals(66, progress.percentText.removeSuffix("%").toInt())
    }

    // --- status pill ---------------------------------------------------------------------------

    @Test
    fun `idle shows the neutral idle pill`() {
        val state = ManagedRuntimeState()
        val legacy = com.ynk.virtualdisplay.automation.PetRuntime.Snapshot()
        val view = ManagedRuntimePresentation.of(state, legacy, wall, wall)
        val pill = StatusPillPresentation.of(view, legacyActive = false)
        assertEquals("空闲", pill.text)
        assertEquals(StatusPillTone.NEUTRAL, pill.tone)
    }

    // --- records -------------------------------------------------------------------------------

    private fun round(
        n: Int,
        beforeStamina: Int?,
        afterStamina: Int?,
        beforeClean: Int? = 90,
        afterClean: Int? = 92,
        mood: Int? = 20,
        feeds: Int = 0,
    ) = RoundRecord(
        round = n,
        before = HealthSnapshot(beforeStamina, beforeClean, mood),
        ready = HealthSnapshot(beforeStamina, beforeClean, mood),
        after = HealthSnapshot(afterStamina, afterClean, mood),
        care = CareStats(feeds, 0, 0L, 0L),
        grade = "UNKNOWN",
        encouragementSent = 10,
        courseMs = 600_000L,
    )

    @Test
    fun `delta is computed from the real readings`() {
        val changes = RecordsPresentation.changes(listOf(round(1, 86, 96)))
        val stamina = changes.first { it.label == "体力" }
        assertTrue(stamina.confirmed)
        assertEquals("86", stamina.beforeText)
        assertEquals("96", stamina.afterText)
        assertEquals(10, stamina.delta)
        assertEquals("+10", stamina.deltaText)
    }

    @Test
    fun `a drop is reported as a negative delta rather than hidden`() {
        val changes = RecordsPresentation.changes(listOf(round(1, 90, 88, beforeClean = 97, afterClean = 95)))
        val stamina = changes.first { it.label == "体力" }
        assertEquals(-2, stamina.delta)
        assertEquals("−2", stamina.deltaText)
    }

    @Test
    fun `a change without two readings is not confirmed`() {
        val changes = RecordsPresentation.changes(listOf(round(1, null, 96)))
        val stamina = changes.first { it.label == "体力" }
        assertFalse(stamina.confirmed)
        assertEquals("—", stamina.beforeText)
        assertEquals("—", stamina.deltaText)
        assertNull(stamina.delta)
    }

    @Test
    fun `no rounds at all reports no paired reading instead of zero`() {
        val changes = RecordsPresentation.changes(emptyList())
        assertTrue(changes.all { !it.confirmed })
        assertTrue(changes.all { it.delta == null })
        assertTrue(changes.first().meta.contains("没有成对的读数"))
    }

    @Test
    fun `a change missing one side is labelled as unconfirmable`() {
        val changes = RecordsPresentation.changes(listOf(round(1, 86, null)))
        val stamina = changes.first { it.label == "体力" }
        assertTrue(stamina.meta.contains("缺一侧读数"))
    }

    @Test
    fun `actions never claim an effect the layer did not confirm`() {
        val record = SessionRunRecord(
            sessionId = "s", terminalState = "COMPLETED", runner = "PetSessionController",
            rounds = 1, completedRounds = 1, pauseCount = 0, startedAtMs = wall, runtimeMs = 600_000L,
            stopReason = null, moodPolicy = "OBSERVATION_ONLY",
            energy = null, cleanliness = null, mood = null,
            care = CareStats(feedCount = 1, cleanCount = 0, feedDurationMs = 1, cleanDurationMs = 0),
            encouragementSent = 30, courseStarts = 1, requestedRounds = 1,
        )
        // Stamina actually fell across the round, so the feed must NOT be labelled 生效.
        val actions = RecordsPresentation.actions(listOf(round(1, 96, 90, feeds = 1)), record)
        val feed = actions.first { it.label == "喂食小饼干" }
        assertEquals("待确认", feed.verdict)
        assertEquals(VerdictTone.PENDING, feed.verdictTone)

        val encouragement = actions.first { it.label.startsWith("鼓励") }
        assertEquals("待确认", encouragement.verdict)
        assertTrue(encouragement.meta.contains("无法确认"))
    }

    @Test
    fun `actions confirm an effect only when the reading moved in the expected direction`() {
        val record = SessionRunRecord(
            sessionId = "s", terminalState = "COMPLETED", runner = "PetSessionController",
            rounds = 1, completedRounds = 1, pauseCount = 0, startedAtMs = wall, runtimeMs = 600_000L,
            stopReason = null, moodPolicy = "OBSERVATION_ONLY",
            energy = null, cleanliness = null, mood = null,
            care = CareStats(feedCount = 1, cleanCount = 1, feedDurationMs = 1, cleanDurationMs = 1),
            encouragementSent = 10, courseStarts = 1, requestedRounds = 1,
        )
        val actions = RecordsPresentation.actions(listOf(round(1, 86, 96, 91, 97, feeds = 1)), record)
        assertEquals("生效", actions.first { it.label == "喂食小饼干" }.verdict)
        assertEquals("生效", actions.first { it.label == "洗澡" }.verdict)
    }

    @Test
    fun `inventory is reported as unknown because the game exposes no reading`() {
        val actions = RecordsPresentation.actions(listOf(round(1, 86, 96)), null)
        val inventory = actions.first { it.label == "剩余库存" }
        assertEquals("未知", inventory.verdict)
        assertEquals(VerdictTone.UNKNOWN, inventory.verdictTone)
    }

    @Test
    fun `history rows describe the real terminal state`() {
        fun session(id: String, record: SessionRunRecord?) = com.ynk.virtualdisplay.ui.data.SessionEvidence(
            directory = java.io.File("/tmp/$id"), record = record, rounds = emptyList(),
            screenshots = listOf(java.io.File("/tmp/$id/a.png"), java.io.File("/tmp/$id/b.png")),
            runtimeStateJson = null,
        )
        val completed = SessionRunRecord(
            sessionId = "a", terminalState = "COMPLETED", runner = "PetSessionController",
            rounds = 3, completedRounds = 3, pauseCount = 0, startedAtMs = wall, runtimeMs = 120_000L,
            stopReason = null, moodPolicy = "OBSERVATION_ONLY", energy = null, cleanliness = null,
            mood = null, care = CareStats(1, 0, 0L, 0L), encouragementSent = 10, courseStarts = 3,
            requestedRounds = 3,
        )
        val failed = completed.copy(terminalState = "FAILED", stopReason = "PAGE_MISMATCH")
        val rows = RecordsPresentation.history(
            listOf(session("a", completed), session("b", failed), session("c", null)),
            { "21:00" }, { "10-10" },
        )
        assertEquals(2, rows.size) // a session with no run record is never invented as history
        assertEquals("已完成", rows[0].outcomeText)
        assertEquals(VerdictTone.CONFIRMED, rows[0].outcomeTone)
        assertEquals(2, rows[0].screenshots)
        assertEquals("失败", rows[1].outcomeText)
        assertTrue(rows[1].detail.contains("PAGE_MISMATCH"))
    }

    @Test
    fun `summary counts only sessions that really produced a record`() {
        val summary = RecordsPresentation.summary(emptyList(), { "21:00" }, { "10-10" })
        assertNull(summary.rangeText)
        assertNull(summary.minutesText)
        assertEquals(0, summary.courses)
        assertEquals(0, summary.careCount)
    }
}
