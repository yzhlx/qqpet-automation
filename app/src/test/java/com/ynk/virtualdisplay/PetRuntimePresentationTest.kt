package com.ynk.virtualdisplay

import com.ynk.virtualdisplay.automation.HealthObservations
import com.ynk.virtualdisplay.automation.ManagedRuntimeState
import com.ynk.virtualdisplay.automation.ObservationSource
import com.ynk.virtualdisplay.automation.ObservedValue
import com.ynk.virtualdisplay.automation.PetManagedRuntime
import com.ynk.virtualdisplay.automation.PetRuntime
import com.ynk.virtualdisplay.automation.PetStudyHealthPolicy
import com.ynk.virtualdisplay.automation.SessionRunState
import com.ynk.virtualdisplay.ui.screens.HealthDisplayStatus
import com.ynk.virtualdisplay.ui.screens.ManagedLifecycle
import com.ynk.virtualdisplay.ui.screens.ManagedLifecycleTone
import com.ynk.virtualdisplay.ui.screens.ManagedRuntimePresentation
import com.ynk.virtualdisplay.ui.screens.ManagedSessionPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Pure JVM coverage for the read-only managed-runtime presentation mapping. No device, no Compose. */
class PetRuntimePresentationTest {
    private val wall = 1_000_000L
    private val mono = 500_000L

    private fun known(value: Int, observedAt: Long = wall) =
        ObservedValue.Known(value, observedAt, 1.0, "fixture-frame", ObservationSource.DIGIT_RECOGNITION)

    private fun running(status: String = "Managed session") =
        PetRuntime.Snapshot(running = true, runState = SessionRunState.RUNNING, displayId = 4, status = status)

    private fun idleLegacy() = PetRuntime.Snapshot()

    private fun healthy(state: ManagedRuntimeState) =
        state.copy(energy = known(95), cleanliness = known(96), mood = known(80))

    private fun viewOf(state: ManagedRuntimeState, legacy: PetRuntime.Snapshot,
                       wallNow: Long = wall, elapsedNow: Long = mono) =
        ManagedRuntimePresentation.of(state, legacy, wallNow, elapsedNow)

    // --- lifecycle coverage --------------------------------------------------------------------

    @Test fun everyRunStateMapsToOneDistinctLifecycle() {
        val expected = mapOf(
            SessionRunState.IDLE to ManagedLifecycle.NOT_STARTED,
            SessionRunState.RUNNING to ManagedLifecycle.RUNNING,
            SessionRunState.PAUSED to ManagedLifecycle.PAUSED,
            SessionRunState.RESUMING to ManagedLifecycle.RESUMING,
            SessionRunState.COMPLETED to ManagedLifecycle.COMPLETED,
            SessionRunState.STOPPED to ManagedLifecycle.STOPPED,
            SessionRunState.FAILED to ManagedLifecycle.FAILED,
        )
        assertEquals(SessionRunState.values().size, expected.size)
        SessionRunState.values().forEach { runState ->
            assertEquals(expected.getValue(runState), ManagedRuntimePresentation.lifecycleOf(runState))
        }
        assertEquals(expected.size, expected.values.toSet().size)
    }

    @Test fun everyLifecycleHasNonBlankDistinctLabel() {
        val labels = ManagedLifecycle.values().map { it to ManagedRuntimePresentation.lifecycleText(it) }
        labels.forEach { (_, text) -> assertTrue("blank label", text.isNotBlank()) }
        val nonNotStarted = labels.filter { it.first != ManagedLifecycle.NOT_STARTED }.map { it.second }
        assertEquals(nonNotStarted.size, nonNotStarted.toSet().size)
    }

    @Test fun onlyCompletedReadsAsSuccess() {
        val failures = mutableListOf<String>()
        SessionRunState.values().forEach { runState ->
            val view = viewOf(ManagedRuntimeState(sessionId = "s-$runState", runState = runState), running())
            if (runState == SessionRunState.COMPLETED) {
                if (!view.successful || view.tone != ManagedLifecycleTone.SUCCESS)
                    failures += "$runState must be SUCCESS/true"
            } else if (view.successful || view.tone == ManagedLifecycleTone.SUCCESS) {
                failures += "$runState must not read as success"
            }
        }
        assertTrue(failures.joinToString("; "), failures.isEmpty())
    }

    @Test fun pausedResumingAndFailedAreNotPlainSuccess() {
        listOf(SessionRunState.PAUSED, SessionRunState.RESUMING, SessionRunState.FAILED).forEach { runState ->
            val view = viewOf(ManagedRuntimeState(sessionId = "s", runState = runState), running())
            assertFalse(view.successful)
            assertFalse("$runState labeled 运行中/已完成", view.lifecycleText == "运行中" || view.lifecycleText == "已完成")
        }
        assertEquals("已暂停", ManagedRuntimePresentation.lifecycleText(ManagedLifecycle.PAUSED))
        assertEquals("恢复中", ManagedRuntimePresentation.lifecycleText(ManagedLifecycle.RESUMING))
        assertEquals("失败", ManagedRuntimePresentation.lifecycleText(ManagedLifecycle.FAILED))
        assertEquals(ManagedLifecycleTone.ATTENTION, ManagedRuntimePresentation.toneOf(ManagedLifecycle.PAUSED))
        assertEquals(ManagedLifecycleTone.FAILURE, ManagedRuntimePresentation.toneOf(ManagedLifecycle.FAILED))
    }

    // --- finding 1: ownership must not come from legacy.running --------------------------------

    @Test fun oldCompletedSnapshotPlusNewRunningServiceIsAStartupTransitionNotCurrent() {
        // Regression: the previous session settled (COMPLETED) and the new service is already
        // running, but the new session has not published a managed snapshot yet. `legacy.running`
        // alone used to mark this stale terminal snapshot as the current state.
        val old = healthy(ManagedRuntimeState(sessionId = "old-session", runState = SessionRunState.COMPLETED,
            currentFlow = "CONTROL", currentStep = "DONE", round = 1, totalRounds = 1, completedRounds = 1,
            startedAt = wall - 300_000L, startedElapsedMs = mono - 300_000L, runtimeMs = 300_000L,
            nextStep = "会话结束"))
        val view = viewOf(old, running())
        assertEquals(ManagedSessionPhase.STARTING, view.sessionPhase)
        assertFalse("settled snapshot must never be current", view.sessionIsCurrent)
        assertTrue(view.sessionIsStartingTransition)
        assertFalse(view.sessionIsHistorical)
        assertTrue(view.sessionLabelText.contains("启动过渡"))
        assertTrue(view.sessionLabelText.contains("上一次已结束会话"))
        assertEquals(ManagedLifecycle.COMPLETED, view.lifecycle)
        // Its readings belong to the previous session, so none of them is a current observation.
        assertTrue(view.health.none { it.currentReading })
        assertTrue(view.confirmedReadingsText.startsWith("当前读数未确认"))
        assertFalse(view.health.any { it.valueText.contains("当前读数") })
    }

    @Test fun eachSettledStateTriggersTheStartupTransition() {
        listOf(SessionRunState.COMPLETED, SessionRunState.FAILED, SessionRunState.STOPPED).forEach { runState ->
            val view = viewOf(ManagedRuntimeState(sessionId = "old", runState = runState), running())
            assertEquals("$runState", ManagedSessionPhase.STARTING, view.sessionPhase)
            assertFalse("$runState", view.sessionIsCurrent)
            assertTrue("$runState", view.sessionIsStartingTransition)
        }
        // A never-started snapshot while the service boots is also a transition, not a result.
        val idleStart = viewOf(ManagedRuntimeState(), running())
        assertEquals(ManagedSessionPhase.STARTING, idleStart.sessionPhase)
        assertEquals("启动过渡（新会话尚未发布状态，显示的是上一次已结束会话的终态）", idleStart.sessionLabelText)
        assertFalse(idleStart.sessionIsCurrent)
    }

    @Test fun activeSnapshotIsCurrentOnlyWhileTheServiceRuns() {
        listOf(SessionRunState.RUNNING, SessionRunState.PAUSED, SessionRunState.RESUMING).forEach { runState ->
            val active = healthy(ManagedRuntimeState(sessionId = "s", runState = runState))
            assertEquals("$runState", ManagedSessionPhase.CURRENT, viewOf(active, running()).sessionPhase)
            assertEquals("$runState after stop", ManagedSessionPhase.HISTORICAL, viewOf(active, idleLegacy()).sessionPhase)
        }
        val historical = viewOf(healthy(ManagedRuntimeState(sessionId = "s", runState = SessionRunState.RUNNING)), idleLegacy())
        assertTrue(historical.sessionIsHistorical)
        assertTrue(historical.sessionLabelText.contains("历史读数"))
        assertFalse(historical.health.any { it.currentReading })
        assertEquals("上一会话读数 95（非本会话）", historical.health.single { it.label == "体力" }.valueText)
    }

    @Test fun sessionPhaseNeverTreatsASettledSnapshotAsCurrent() {
        listOf(SessionRunState.COMPLETED, SessionRunState.FAILED, SessionRunState.STOPPED).forEach {
            assertTrue(ManagedRuntimePresentation.isTerminal(it))
            assertFalse(ManagedRuntimePresentation.isActive(it))
        }
        listOf(SessionRunState.RUNNING, SessionRunState.PAUSED, SessionRunState.RESUMING).forEach {
            assertTrue(ManagedRuntimePresentation.isActive(it))
            assertFalse(ManagedRuntimePresentation.isTerminal(it))
        }
        SessionRunState.values().forEach { runState ->
            val view = viewOf(ManagedRuntimeState(sessionId = "s", runState = runState), running())
            if (ManagedRuntimePresentation.isTerminal(runState)) {
                assertFalse("$runState must not be current while running", view.sessionIsCurrent)
            }
        }
    }

    // --- finding 2: terminal duration is frozen ------------------------------------------------

    @Test fun terminalDurationStaysAtTheValuePublishedAtFinalization() {
        listOf(SessionRunState.COMPLETED, SessionRunState.FAILED, SessionRunState.STOPPED).forEach { runState ->
            val settled = ManagedRuntimeState(sessionId = "s", runState = runState, startedAt = wall - 120_000L,
                startedElapsedMs = mono - 120_000L, runtimeMs = 120_000L)
            val immediate = viewOf(settled, running())
            // The screen keeps rendering long after the session settled.
            val muchLater = viewOf(settled, running(), wall + 10_000_000L, mono + 10_000_000L)
            assertEquals("$runState", "运行时长：02:00", immediate.durationText)
            assertEquals("$runState duration must not advance", immediate.durationText, muchLater.durationText)
            assertEquals("$runState", 120_000L,
                ManagedRuntimePresentation.displayedRuntimeMs(settled, mono + 10_000_000L))
        }
        // Active states keep following the monotonic clock.
        val active = ManagedRuntimeState(sessionId = "s", runState = SessionRunState.RUNNING,
            startedElapsedMs = mono - 120_000L, runtimeMs = 120_000L)
        assertEquals(120_000L, ManagedRuntimePresentation.displayedRuntimeMs(active, mono))
        assertEquals(180_000L, ManagedRuntimePresentation.displayedRuntimeMs(active, mono + 60_000L))
        assertEquals("运行时长：03:00", ManagedRuntimePresentation.durationText(active, mono + 60_000L))
        listOf(SessionRunState.PAUSED, SessionRunState.RESUMING).forEach { runState ->
            val state = active.copy(runState = runState)
            assertEquals("$runState", 180_000L, ManagedRuntimePresentation.displayedRuntimeMs(state, mono + 60_000L))
        }
    }

    @Test fun elapsedClockAdvanceNeverInflatesASettledDuration() {
        val completed = healthy(ManagedRuntimeState(sessionId = "s", runState = SessionRunState.COMPLETED,
            startedElapsedMs = mono - 65_000L, runtimeMs = 65_000L, round = 1, totalRounds = 1, completedRounds = 1))
        val atFinalize = viewOf(completed, running())
        assertEquals("运行时长：01:05", atFinalize.durationText)
        // Advance elapsedNow by an hour while the wall clock also moves: the shown duration holds.
        val oneHourLater = viewOf(completed, running(), wall + 3_600_000L, mono + 3_600_000L)
        assertEquals(atFinalize.durationText, oneHourLater.durationText)
        assertEquals("01:05", ManagedRuntimePresentation.formatDuration(
            ManagedRuntimePresentation.displayedRuntimeMs(completed, mono + 3_600_000L)))
    }

    @Test fun durationFormattingIsStableAcrossBoundaries() {
        assertEquals("00:00", ManagedRuntimePresentation.formatDuration(0))
        assertEquals("00:59", ManagedRuntimePresentation.formatDuration(59_999))
        assertEquals("01:00", ManagedRuntimePresentation.formatDuration(60_000))
        assertEquals("1:00:00", ManagedRuntimePresentation.formatDuration(3_600_000))
        assertEquals("00:00", ManagedRuntimePresentation.formatDuration(-5))
        assertEquals("运行时长：未启动", ManagedRuntimePresentation.durationText(ManagedRuntimeState(), mono))
    }

    // --- finding 3: the screen keeps re-evaluating freshness, incl. after stop ------------------

    @Test fun knownReadingsExpireAtTheObservationDeadline() {
        val state = healthy(ManagedRuntimeState(sessionId = "s", runState = SessionRunState.RUNNING))
        assertEquals(wall + ManagedRuntimePresentation.HEALTH_MAX_AGE_MS,
            ManagedRuntimePresentation.nextHealthExpiryAtMs(state)!!)
        assertNull(ManagedRuntimePresentation.nextHealthExpiryAtMs(ManagedRuntimeState()))
        // Mixed freshness: the earliest Known reading decides the deadline.
        val mixed = state.copy(mood = ObservedValue.Stale(known(80, wall - 120_000L), wall, "OBSERVATION_EXPIRED"))
        assertEquals(wall + ManagedRuntimePresentation.HEALTH_MAX_AGE_MS,
            ManagedRuntimePresentation.nextHealthExpiryAtMs(mixed)!!)
    }

    @Test fun freshnessTickerKeepsRunningAfterStopUntilTheReadingExpires() {
        val state = healthy(ManagedRuntimeState(sessionId = "s", runState = SessionRunState.STOPPED))
        assertTrue("while running", ManagedRuntimePresentation.needsFreshnessTicker(state, true, wall + 1_000_000L))
        assertTrue("just after stop", ManagedRuntimePresentation.needsFreshnessTicker(state, false, wall + 1_000L))
        assertTrue("one ms before expiry", ManagedRuntimePresentation.needsFreshnessTicker(state, false, wall + 59_999L))
        assertTrue("exactly at expiry", ManagedRuntimePresentation.needsFreshnessTicker(state, false, wall + 60_000L))
        assertFalse("well past expiry", ManagedRuntimePresentation.needsFreshnessTicker(state, false, wall + 60_001L))
        assertFalse("no Known reading at all", ManagedRuntimePresentation.needsFreshnessTicker(ManagedRuntimeState(), false, wall))
        // A wall clock behind the observation instant is bounded instead of ticking forever.
        assertFalse("clock behind", ManagedRuntimePresentation.needsFreshnessTicker(state, false, wall - 1L))
    }

    @Test fun screenStateCrossingSixtySecondsAfterStopTurnsKnownIntoStale() {
        // Regression: the service stopped, the historical snapshot stays on screen, and the screen
        // frame crosses the 60s freshness window. The readings must stop being "confirmed".
        val stopped = healthy(ManagedRuntimeState(sessionId = "s", runState = SessionRunState.STOPPED,
            currentFlow = "CONTROL", currentStep = "DONE", round = 1, totalRounds = 1, completedRounds = 1,
            startedElapsedMs = mono - 200_000L, runtimeMs = 200_000L))
        val legacy = PetRuntime.Snapshot(running = false, status = "会话结束")

        val atStop = viewOf(stopped, legacy, wall + 1_000L, mono)
        val energyAtStop = atStop.health.single { it.label == "体力" }
        assertEquals(HealthDisplayStatus.KNOWN, energyAtStop.status)
        assertFalse("a stopped session's readings are never current", energyAtStop.currentReading)
        assertTrue(atStop.confirmedReadingsText.startsWith("当前读数未确认"))

        val pastExpiry = viewOf(stopped, legacy, wall + 60_001L, mono)
        val energyPast = pastExpiry.health.single { it.label == "体力" }
        assertEquals(HealthDisplayStatus.STALE, energyPast.status)
        assertFalse(energyPast.currentReading)
        assertTrue(energyPast.valueText.contains("历史读数"))
        assertTrue(energyPast.valueText.contains("已过期"))
        assertFalse("must not still claim confirmation", pastExpiry.confirmedReadingsText.contains("已确认"))
        assertEquals("当前读数未确认（显示的不是本会话正在观测的读数）", pastExpiry.confirmedReadingsText)
        // The frozen terminal duration is unaffected by the expiry sweep.
        assertEquals(atStop.durationText, pastExpiry.durationText)
    }

    @Test fun currentSessionKnownReadingStillExpiresWhileRunning() {
        val state = healthy(ManagedRuntimeState(sessionId = "s", runState = SessionRunState.RUNNING,
            startedElapsedMs = mono - 5_000L, runtimeMs = 5_000L))
        val fresh = viewOf(state, running(), wall + 1_000L, mono)
        assertEquals(HealthDisplayStatus.KNOWN, fresh.health.single { it.label == "体力" }.status)
        assertTrue(fresh.health.single { it.label == "体力" }.currentReading)
        assertTrue(fresh.confirmedReadingsText.startsWith("当前读数已确认"))
        val aged = viewOf(state, running(), wall + 61_000L, mono)
        assertEquals(HealthDisplayStatus.STALE, aged.health.single { it.label == "体力" }.status)
        assertFalse(aged.health.any { it.currentReading })
        assertFalse(aged.confirmedReadingsText.contains("已确认"))
    }

    // --- health display: Known / Unknown / Stale / Unsupported ---------------------------------

    @Test fun allFourHealthStatusesStayDistinct() {
        val statuses = listOf(
            ManagedRuntimePresentation.healthLine("体力", known(95), true).status,
            ManagedRuntimePresentation.healthLine("体力", ObservedValue.Unknown("AMBIGUOUS_DIGIT", wall, "fx"), true).status,
            ManagedRuntimePresentation.healthLine("体力", ObservedValue.Stale(known(95), wall, "OBSERVATION_EXPIRED_OR_CLOCK_REVERSED"), true).status,
            ManagedRuntimePresentation.healthLine("体力", ObservedValue.Unsupported("NO_READER"), true).status,
        )
        assertEquals(
            listOf(HealthDisplayStatus.KNOWN, HealthDisplayStatus.UNKNOWN, HealthDisplayStatus.STALE, HealthDisplayStatus.UNSUPPORTED),
            statuses)
        assertEquals(statuses.size, statuses.toSet().size)
    }

    @Test fun onlyFreshKnownReadingOfTheCurrentSessionCountsAsCurrent() {
        assertTrue(ManagedRuntimePresentation.healthLine("体力", known(95), true).currentReading)
        assertFalse(ManagedRuntimePresentation.healthLine("体力", known(95), false).currentReading)
        assertFalse(ManagedRuntimePresentation.healthLine("体力", ObservedValue.Unknown("LOW_DIGIT_CONFIDENCE", wall, "fx"), true).currentReading)
        assertFalse(ManagedRuntimePresentation.healthLine("体力", ObservedValue.Stale(known(95), wall, "OBSERVATION_EXPIRED"), true).currentReading)
        assertFalse(ManagedRuntimePresentation.healthLine("体力", ObservedValue.Unsupported("NO_READER"), true).currentReading)
    }

    @Test fun unknownIsNeverRenderedAsZeroAndStaleIsMarkedHistorical() {
        val unknown = ManagedRuntimePresentation.healthLine("体力", ObservedValue.Unknown("AMBIGUOUS_DIGIT", wall, "fx"), true)
        assertEquals("未知（未观测到有效读数）", unknown.valueText)
        assertFalse("unknown must not show a number", unknown.valueText.contains("0"))
        val stale = ManagedRuntimePresentation.healthLine("体力",
            ObservedValue.Stale(known(88), wall, "OBSERVATION_EXPIRED_OR_CLOCK_REVERSED"), true)
        assertEquals("历史读数 88（已过期，非当前值）", stale.valueText)
        assertTrue(stale.valueText.contains("历史读数"))
        assertEquals("不支持读取", ManagedRuntimePresentation.healthLine("体力", ObservedValue.Unsupported("NO_READER"), true).valueText)
    }

    @Test fun aStaleSnapshotRendersStaleInsteadOfTheOldNumber() {
        val state = healthy(ManagedRuntimeState(sessionId = "session-1", runState = SessionRunState.RUNNING))
        val fresh = viewOf(state, running(), wall + 1_000L, mono)
        assertEquals(HealthDisplayStatus.KNOWN, fresh.health.single { it.label == "体力" }.status)
        assertTrue(fresh.confirmedReadingsText.contains("已确认"))
        val aged = viewOf(state, running(), wall + 61_000L, mono)
        assertEquals(HealthDisplayStatus.STALE, aged.health.single { it.label == "体力" }.status)
        assertFalse(aged.confirmedReadingsText.contains("已确认"))
    }

    @Test fun defaultSnapshotHealthIsUnknownNotZero() {
        val view = viewOf(ManagedRuntimeState(), idleLegacy())
        assertEquals(3, view.health.size)
        assertTrue(view.health.all { it.status == HealthDisplayStatus.UNKNOWN })
        assertTrue(view.health.none { it.currentReading })
    }

    // --- finding 4: wording is "readings confirmed", never "course ready" ----------------------

    @Test fun confirmationWordingNeverClaimsCourseReadiness() {
        val state = healthy(ManagedRuntimeState(sessionId = "s", runState = SessionRunState.RUNNING))
        val text = viewOf(state, running()).confirmedReadingsText
        assertTrue(text.startsWith("当前读数已确认"))
        assertTrue(text.contains("不代表开课就绪"))
        assertFalse("must not claim course readiness", text.contains("就绪依据：已确认"))
        assertFalse(text.contains("可开始") || text.contains("可以开课"))
        // The presentation layer exposes no readiness decision at all: the >=90 policy stays in
        // PetStudyHealthPolicy and is never duplicated (or weakened) in the UI mapping.
        assertFalse(ManagedRuntimePresentation::class.java.methods.any { it.name.contains("ready", ignoreCase = true) })
    }

    @Test fun uiNeverWeakensTheNinetyNinetyPolicyBoundary() {
        // 80/80 and 90/90 are decided by the authoritative policy, not by the UI layer.
        fun health(energy: Int, cleanliness: Int) = HealthObservations(
            energy = known(energy), cleanliness = known(cleanliness), mood = known(100),
            barEnergy = known(energy), barCleanliness = known(cleanliness), barMood = known(100),
            barReady = true, evidence = "fixture")
        assertFalse(PetStudyHealthPolicy.read(health(80, 80), wall).ready)
        assertFalse(PetStudyHealthPolicy.read(health(90, 89), wall).ready)
        assertFalse(PetStudyHealthPolicy.read(health(89, 90), wall).ready)
        assertTrue(PetStudyHealthPolicy.read(health(90, 90), wall).ready)
        assertEquals(90, PetStudyHealthPolicy.read(health(90, 90), wall).energy)

        // The same boundary values produce identical UI wording: the UI only reports that the two
        // readings are current observations, so it can never turn 80/80 into an authorization.
        val eighty = viewOf(healthy(ManagedRuntimeState(sessionId = "s", runState = SessionRunState.RUNNING))
            .copy(energy = known(80), cleanliness = known(80)), running())
        val ninety = viewOf(healthy(ManagedRuntimeState(sessionId = "s", runState = SessionRunState.RUNNING))
            .copy(energy = known(90), cleanliness = known(90)), running())
        assertEquals("当前读数已确认（仅指体力/清洁为本会话最新观测，不代表开课就绪）", eighty.confirmedReadingsText)
        assertEquals(eighty.confirmedReadingsText, ninety.confirmedReadingsText)
        assertTrue(eighty.health.all { it.status == HealthDisplayStatus.KNOWN })
        // The wording explicitly denies readiness; it never asserts it, for either value pair.
        assertTrue(eighty.confirmedReadingsText.contains("不代表开课就绪"))
        assertTrue(ninety.confirmedReadingsText.contains("不代表开课就绪"))

        // And a repeated (expired) 90/90 must not keep authorizing anything.
        try {
            PetStudyHealthPolicy.read(health(90, 90), wall + 60_001L)
            fail("an expired 90/90 reading must not be accepted")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message!!.startsWith("HEALTH_STALE"))
        }
    }

    @Test fun unknownReadingsNeverClaimConfirmation() {
        val base = ManagedRuntimeState(sessionId = "s", runState = SessionRunState.RUNNING,
            energy = known(95), cleanliness = known(96))
        assertTrue(viewOf(base, running()).confirmedReadingsText.contains("已确认"))
        val unknownEnergy = base.copy(energy = ObservedValue.Unknown("AMBIGUOUS_DIGIT", wall, "fx"))
        assertEquals("当前读数未确认（体力不是本会话最新有效读数）",
            viewOf(unknownEnergy, running()).confirmedReadingsText)
        val unsupportedClean = base.copy(cleanliness = ObservedValue.Unsupported("NO_READER"))
        assertEquals("当前读数未确认（清洁不是本会话最新有效读数）",
            viewOf(unsupportedClean, running()).confirmedReadingsText)
        val both = base.copy(energy = ObservedValue.Unknown("A", wall, "fx"), cleanliness = ObservedValue.Unknown("B", wall, "fx"))
        assertEquals("当前读数未确认（体力与清洁都不是本会话最新有效读数）",
            viewOf(both, running()).confirmedReadingsText)
    }

    // --- flow / step / rounds / pause / next step ----------------------------------------------

    @Test fun flowStepRoundsDurationPauseAndNextStepAreShown() {
        val state = healthy(ManagedRuntimeState(
            sessionId = "session-1", runState = SessionRunState.PAUSED, currentFlow = "STUDY",
            currentStep = "HIGH_FIVE", round = 2, totalRounds = 3, completedRounds = 1,
            startedAt = wall - 125_000L, startedElapsedMs = mono - 125_000L, runtimeMs = 125_000L,
            nextStep = "等待成绩", pauseReason = "RECOVERY_PAGE_MISMATCH", pauseCount = 2))
        val view = viewOf(state, running())
        assertEquals("STUDY", view.flowText)
        assertEquals("HIGH_FIVE", view.stepText)
        assertEquals("轮次：2/3", view.roundText)
        assertEquals("已完成：1/3", view.completedText)
        assertEquals("运行时长：02:05", view.durationText)
        assertEquals("暂停：2 次 · 原因：RECOVERY_PAGE_MISMATCH", view.pauseText)
        assertEquals("等待成绩", view.nextStepText)
        assertEquals(ManagedSessionPhase.CURRENT, view.sessionPhase)
    }

    @Test fun notStartedSessionShowsPlaceholdersInsteadOfZeroRounds() {
        val view = viewOf(ManagedRuntimeState(), idleLegacy())
        assertEquals("轮次：未开始", view.roundText)
        assertEquals("已完成：0 节", view.completedText)
        assertEquals("运行时长：未启动", view.durationText)
        assertEquals("暂停：0 次", view.pauseText)
        assertEquals(ManagedLifecycle.NOT_STARTED, view.lifecycle)
        assertEquals(ManagedSessionPhase.NOT_STARTED, view.sessionPhase)
        assertFalse(view.sessionIsHistorical)
        assertFalse(view.sessionIsCurrent)
        assertFalse(view.sessionIsStartingTransition)
        assertEquals("尚未运行托管会话", view.sessionLabelText)
    }

    // --- legacy status / early start error coexistence -----------------------------------------

    @Test fun legacyStartErrorStaysVisibleNextToManagedState() {
        val legacy = PetRuntime.Snapshot(running = false, runState = SessionRunState.STOPPED,
            displayId = 4, status = "配置未完成：[display density must be 320dpi]", cycles = 0)
        val view = viewOf(ManagedRuntimeState(sessionId = null, runState = SessionRunState.IDLE), legacy)
        assertEquals("配置未完成：[display density must be 320dpi]", view.legacyStatusText)
        assertFalse(view.legacyActive)
        assertEquals(ManagedLifecycle.NOT_STARTED, view.lifecycle)
        assertFalse(view.successful)
        assertEquals("尚未运行托管会话", view.sessionLabelText)
    }

    @Test fun historicalSnapshotIsLabelledAsHistoryNotAsCurrentState() {
        val state = healthy(ManagedRuntimeState(sessionId = "old-session", runState = SessionRunState.COMPLETED,
            round = 1, totalRounds = 1, completedRounds = 1))
        val view = viewOf(state, PetRuntime.Snapshot(running = false, status = "BATCH_PASS 1/1"))
        assertFalse(view.sessionIsCurrent)
        assertTrue(view.sessionIsHistorical)
        assertTrue(view.sessionLabelText.contains("历史读数"))
        assertTrue(view.sessionLabelText.contains("不代表当前状态"))
        assertTrue(view.legacyStatusText.contains("BATCH_PASS"))
    }

    @Test fun currentSessionIsLabelledCurrent() {
        val state = healthy(ManagedRuntimeState(sessionId = "abcdefgh-1234", runState = SessionRunState.RUNNING))
        val view = viewOf(state, running())
        assertTrue(view.sessionIsCurrent)
        assertFalse(view.sessionIsHistorical)
        assertFalse(view.sessionIsStartingTransition)
        assertEquals("当前会话 · abcdefgh", view.sessionLabelText)
    }

    @Test fun legacyStatusIsNeverMaskedByAConflictingManagedState() {
        val legacy = PetRuntime.Snapshot(running = true, runState = SessionRunState.RUNNING, status = "拒绝默认/无效 displayId")
        val view = viewOf(ManagedRuntimeState(sessionId = "s", runState = SessionRunState.RUNNING), legacy)
        assertEquals("拒绝默认/无效 displayId", view.legacyStatusText)
        assertNotEquals(view.legacyStatusText, view.lifecycleText)
        assertTrue(view.legacyActive)
    }

    // --- read-only guarantee -------------------------------------------------------------------

    @Test fun presentationNeverPublishesManagedRuntimeState() {
        val before = PetManagedRuntime.state.value
        val state = healthy(ManagedRuntimeState(sessionId = "session-1", runState = SessionRunState.RUNNING))
        ManagedRuntimePresentation.of(state, running(), wall, mono)
        assertEquals(before, PetManagedRuntime.state.value)
        assertNull(before.sessionId)
        assertEquals(SessionRunState.IDLE, before.runState)
    }
}
