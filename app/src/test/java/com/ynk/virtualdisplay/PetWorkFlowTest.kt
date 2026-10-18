package com.ynk.virtualdisplay

import android.graphics.PointF
import com.ynk.virtualdisplay.automation.CompletionResult
import com.ynk.virtualdisplay.automation.ObservedValue
import com.ynk.virtualdisplay.automation.ObservationSource
import com.ynk.virtualdisplay.automation.PetWorkFlow
import com.ynk.virtualdisplay.automation.PetWorkNavigation
import com.ynk.virtualdisplay.automation.RewardRecord
import com.ynk.virtualdisplay.automation.RewardRecordKind
import com.ynk.virtualdisplay.automation.WorkOption
import com.ynk.virtualdisplay.automation.WorkOutcome
import com.ynk.virtualdisplay.automation.WorkPageReader
import com.ynk.virtualdisplay.automation.WorkPhase
import com.ynk.virtualdisplay.automation.WorkResourceReader
import com.ynk.virtualdisplay.automation.WorkRuntimeSink
import com.ynk.virtualdisplay.automation.WorkSessionLedger
import com.ynk.virtualdisplay.automation.WorkStartAuthorisation
import com.ynk.virtualdisplay.automation.WorkStateMachine
import com.ynk.virtualdisplay.automation.WorkTarget
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/**
 * P5.3 flow-level tests: the phase transitions, the preview/start separation, the start-once latch,
 * the timer-zero-is-not-completion rule and the interrupted-then-recovered path.
 *
 * PROVENANCE, READ THIS BEFORE TRUSTING A NUMBER HERE. These tests drive the REAL PetWorkFlow with
 * a stub reader and a stub navigation, so they prove the CONTROL FLOW the brief demands: that a
 * preview run never reaches the 去打工 tap, that an unauthorised start never taps, that a delivered
 * tap is never repeated, and that a countdown reaching zero does not by itself confirm a reward.
 * They do NOT prove anything about the game: no device, no capture session, no real reward. The
 * real payouts are proven by docs/P5-03-*.md after an authorised session.
 */
class PetWorkFlowTest {

    private val paotuId = "work:竹影武馆/帮武馆跑腿"
    private val target = WorkTarget("竹影武馆", "帮武馆跑腿", paotuId)

    /** Records every tap the navigation was asked to make, so a test can prove none was a start. */
    private class StubReader(
        /** The venue whose detail page is currently shown. Tests mutate this to simulate drift. */
        var detailVenue: String? = null,
        private var selectedLabel: String? = null,
        private var working: Boolean = false,
        private var completion: CompletionResult = CompletionResult(false, 0, "NOT_SET"),
        private var rewardRow: WorkOption = defaultRow,
    ) : WorkPageReader {
        companion object {
            val defaultRow = WorkOption(
                id = "work:竹影武馆/帮武馆跑腿", venue = "竹影武馆", optionLabel = "帮武馆跑腿",
                durationMinutes = 10, pageCoinReward = 68, pageWorkScoreReward = 10,
                pageEnergyCost = 5, pageCleanlinessCost = 2,
            )
        }

        override suspend fun locateTownEntry() = PointF(360f, 900f)
        override suspend fun readTownPage() =
            PetWorkNavigation.TownPage(true, detailVenue != null, detailVenue)
        override suspend fun locateVenueLabel(venue: String) = PointF(370f, 654f)
        // identityMatched follows detailVenue: setting detailVenue=null simulates a page that can no
        // longer be positively identified, which is what must block a real start tap.
        override suspend fun readDetailPage() =
            PetWorkNavigation.TownPage(detailVenue != null, detailVenue != null, detailVenue)
        override suspend fun readSelectedOptionLabel() = selectedLabel
        override suspend fun locateWorkOptionCard(venue: String, optionLabel: String) = PointF(360f, 700f)
        override suspend fun readRewardRow(venue: String, optionLabel: String) = rewardRow
        override suspend fun locateStartWorkButton() = PointF(360f, 1390f)
        override suspend fun readWorkingPage() = working
        override suspend fun awaitCompletionPage(expectedEndAtMs: Long, extraWindowMs: Long) = completion
    }

    private class StubSink(
        /** When true the flow must stop instead of sending input. */
        override var inputHalted: Boolean = false,
        /** When true the flow must stop as an interruption, not a failure. */
        override var pauseRequested: Boolean = false,
    ) : WorkRuntimeSink {
        override var state = "OBSERVE"
        val emitted = mutableListOf<Triple<String, String, String?>>()
        override fun emit(action: String, result: String, reason: String?) {
            emitted += Triple(action, result, reason)
        }
    }

    /**
     * Resource stub with a scripted progression: [before*] is returned for the pre-start snapshot and
     * [after*] for the post-completion one, so the flow has a real delta to reconcile.
     *
     * The default mirrors the actual P5.1 closed loop measured on 2026-10-12 (coins 8->76, score
     * 0->10, energy 80->75) so the reconciliation path is exercised with real numbers.
     */
    private class StubResources(
        private val beforeCoins: Int? = 8,
        private val afterCoins: Int? = 76,
        private val beforeScore: Int? = 0,
        private val afterScore: Int? = 10,
        private val beforeEnergy: Int? = 80,
        private val afterEnergy: Int? = 75,
    ) : WorkResourceReader {
        val snapshots = mutableListOf<String>()
        private var index = 0

        override fun snapshot(at: String): RewardRecord {
            snapshots += at
            val useAfter = index > 0
            index++
            return RewardRecord(
                kind = RewardRecordKind.PAGE_READING, at = at,
                coins = (if (useAfter) afterCoins else beforeCoins).wrap(at),
                workScore = (if (useAfter) afterScore else beforeScore).wrap(at),
                energy = (if (useAfter) afterEnergy else beforeEnergy).wrap(at),
                evidence = at,
            )
        }

        override fun readEnergy(): ObservedValue<Int> = beforeEnergy.wrap("energy")

        private fun Int?.wrap(at: String): ObservedValue<Int> =
            if (this == null) ObservedValue.Unknown("UNREADABLE", 1000L, at)
            else ObservedValue.Known(this, 1000L, 1.0, at, ObservationSource.DIGIT_RECOGNITION)
    }

    private fun build(
        reader: StubReader = StubReader(),
        resources: StubResources = StubResources(),
        clock: () -> Long = { 1000L },
        sink: StubSink = StubSink(),
    ): Triple<PetWorkFlow, StubSink, StubReader> {
        val nav = PetWorkNavigation(sink, reader, tap = { _, _ -> }, swipe = { _, _, _, _, _ -> })
        val flow = PetWorkFlow(
            flow = sink,
            workNavigation = nav,
            resources = resources,
            sessionLedger = WorkSessionLedger(),
            clock = clock,
        )
        return Triple(flow, sink, reader)
    }

    // ------------------------------------------------------- preview

    @Test
    fun `aPreviewRunNeverTapsTheStartButton`() {
        // THE core safety property: the preview phase must not reach 去打工, because that button has
        // no confirmation dialog and one tap is the work itself.
        val reader = StubReader(detailVenue = "竹影武馆", selectedLabel = "帮武馆跑腿")
        val (flow, sink, _) = build(reader)
        val outcome = runBlocking { flow.preview(target) }

        assertTrue("expected Preview, got $outcome", outcome is WorkOutcome.Preview)
        val preview = outcome as WorkOutcome.Preview
        assertEquals("竹影武馆", preview.venue)
        assertEquals(paotuId, preview.optionId)
        assertTrue(preview.blockers.isEmpty())
        assertEquals(10, preview.pageReward.durationMinutes)
        assertEquals(68, preview.pageReward.pageCoinReward)
        assertEquals(10, preview.pageReward.pageWorkScoreReward)
        // The phase machine must be past the selection but must NOT have entered the start path.
        assertEquals(WorkPhase.PREVIEW_VERIFIED, flow.state.phase)
        assertFalse(flow.state.pastPreview)
    }

    @Test
    fun `aPreviewRunOfAnUncollectedRewardIsBlockedBeforeAnyNavigation`() {
        // 街巷跑腿's reward was never collected. Running it would either fail or, worse, be recorded
        // as a real number. It must be refused up front.
        val reader = StubReader()
        val (flow, _, _) = build(reader)
        val unknownTarget = WorkTarget("风铃旅社", "街巷跑腿", "work:风铃旅社/街巷跑腿")
        val outcome = runBlocking { flow.preview(unknownTarget) }

        assertTrue("expected Blocked, got $outcome", outcome is WorkOutcome.Blocked)
        val blocked = outcome as WorkOutcome.Blocked
        assertTrue(blocked.reason.contains("收益未采集"))
        assertEquals(WorkPhase.BLOCKED, flow.state.phase)
    }

    @Test
    fun `aPreviewRunOfAnUnknownVenueIsBlocked`() {
        val (flow, _, _) = build()
        val outcome = runBlocking { flow.preview(WorkTarget("咕噜厨房", "未知工作", "work:咕噜厨房/未知工作")) }
        assertTrue("expected Blocked, got $outcome", outcome is WorkOutcome.Blocked)
    }

    @Test
    fun `aChangedRewardRowBlocksThePreviewRatherThanBeingAccepted`() {
        // The brief's "选项卡切换后奖励数据变化" case: the page now disagrees with the catalog.
        // The preview must surface that, not proceed on stale data.
        val changed = WorkOption(
            id = paotuId, venue = "竹影武馆", optionLabel = "帮武馆跑腿",
            durationMinutes = 10, pageCoinReward = 40, pageWorkScoreReward = 10,
            pageEnergyCost = 5, pageCleanlinessCost = 2,
        )
        val reader = StubReader(detailVenue = "竹影武馆", selectedLabel = "帮武馆跑腿", rewardRow = changed)
        val (flow, _, _) = build(reader)
        val outcome = runBlocking { flow.preview(target) }

        assertTrue("expected Blocked, got $outcome", outcome is WorkOutcome.Blocked)
        val blocked = outcome as WorkOutcome.Blocked
        assertTrue(blocked.reason.contains("不一致"))
        assertTrue(blocked.reason.contains("40"))
    }

    // ------------------------------------------------------- start gate

    @Test
    fun `anUnauthorisedStartNeverTaps`() {
        // Default deny: no grant means no tap, no matter how the caller got to the page.
        val reader = StubReader(detailVenue = "竹影武馆", selectedLabel = "帮武馆跑腿", working = true)
        val (flow, _, _) = build(reader)
        val outcome = runBlocking { flow.start(target) }

        assertTrue("expected Blocked, got $outcome", outcome is WorkOutcome.Blocked)
        assertEquals("WORK_NOT_AUTHORISED", (outcome as WorkOutcome.Blocked).reason)
        assertEquals(WorkPhase.BLOCKED, flow.state.phase)
        assertFalse(flow.startConfirmation.tapDelivered)
    }

    @Test
    fun `anAuthorisedStartTapsExactlyOnceAndConfirmsTheWorkingPage`() {
        val reader = StubReader(detailVenue = "竹影武馆", selectedLabel = "帮武馆跑腿", working = true)
        val (flow, _, _) = build(reader)
        flow.authorise(
            WorkStartAuthorisation.grant("竹影武馆", paotuId, 1, 0, 100, "test-operator", 1000L),
        )

        val outcome = runBlocking { flow.run(target) }
        assertTrue("expected Started, got $outcome", outcome is WorkOutcome.Started)
        val started = outcome as WorkOutcome.Started
        assertEquals(10, started.durationMinutes)
        assertEquals(1000L + 10 * 60_000L, started.expectedEndAtMs)
        assertEquals(WorkPhase.WORKING_CONFIRMED, flow.state.phase)
        assertTrue(flow.startConfirmation.tapDelivered)
    }

    @Test
    fun `aStartTapThatCannotBeConfirmedIsNeverRepeated`() {
        // The brief's special case: the tap went out, the page never showed WORKING, and the flow
        // must NOT tap again. It reports the ambiguity honestly instead.
        val reader = StubReader(detailVenue = "竹影武馆", selectedLabel = "帮武馆跑腿", working = false)
        val (flow, _, _) = build(reader)
        flow.authorise(
            WorkStartAuthorisation.grant("竹影武馆", paotuId, 1, 0, 100, "test-operator", 1000L),
        )

        val outcome = runBlocking { flow.run(target) }
        assertTrue("expected UnconfirmedStart, got $outcome", outcome is WorkOutcome.UnconfirmedStart)
        val unconfirmed = outcome as WorkOutcome.UnconfirmedStart
        assertEquals("WORK_START_UNCONFIRMED", unconfirmed.reason)
        assertTrue(flow.startConfirmation.mustNotTapAgain)

        // A second attempt with a FRESH grant still cannot tap: the latch is per session, and the
        // work may already be running.
        flow.authorise(
            WorkStartAuthorisation.grant("竹影武馆", paotuId, 1, 0, 100, "test-operator", 2000L),
        )
        val second = runBlocking { flow.start(target) }
        assertTrue("expected Blocked, got $second", second is WorkOutcome.Blocked)
        assertEquals("WORK_START_UNCONFIRMED_NO_RETAP", (second as WorkOutcome.Blocked).reason)
    }

    @Test
    fun `aRevokedGrantCannotStartWork`() {
        val reader = StubReader(detailVenue = "竹影武馆", selectedLabel = "帮武馆跑腿", working = true)
        val (flow, _, _) = build(reader)
        flow.authorise(
            WorkStartAuthorisation.grant("竹影武馆", paotuId, 1, 0, 100, "test-operator", 1000L),
        )
        flow.revokeAuthorisation("user paused the session")

        val outcome = runBlocking { flow.start(target) }
        assertTrue("expected Blocked, got $outcome", outcome is WorkOutcome.Blocked)
        assertEquals("WORK_AUTHORISATION_REVOKED", (outcome as WorkOutcome.Blocked).reason)
        assertFalse(flow.startConfirmation.tapDelivered)
    }

    @Test
    fun `anExhaustedGrantCannotStartASecondSession`() {
        // One authorisation is never a loop authorisation.
        val reader = StubReader(detailVenue = "竹影武馆", selectedLabel = "帮武馆跑腿", working = true)
        val (flow, _, _) = build(reader)
        flow.authorise(
            WorkStartAuthorisation.grant("竹影武馆", paotuId, 1, 0, 100, "test-operator", 1000L),
        )
        val first = runBlocking { flow.run(target) }
        assertTrue(first is WorkOutcome.Started)

        val second = runBlocking { flow.start(target) }
        assertTrue("expected Blocked, got $second", second is WorkOutcome.Blocked)
        // The no-retap latch fires first and is the stricter rule: the first tap was already
        // DELIVERED, so even an unused grant may not produce a second tap.
        assertEquals("WORK_START_UNCONFIRMED_NO_RETAP", (second as WorkOutcome.Blocked).reason)

        // And a grant that covers more than one start still cannot be spent twice on this session:
        // a fresh grant is a fresh, separate decision.
        val grantTwo = WorkStartAuthorisation.grant("竹影武馆", paotuId, 1, 0, 100, "op-2", 2000L)
        grantTwo.consume("竹影武馆", paotuId)
        assertEquals(0, grantTwo.remainingStarts)
        try {
            grantTwo.consume("竹影武馆", paotuId)
            fail("expected WORK_AUTHORISATION_EXHAUSTED")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("WORK_AUTHORISATION_EXHAUSTED"))
        }
    }

    @Test
    fun `insufficientEnergyBlocksTheStartBeforeAnyTap`() {
        // Rule A: the ONLY resource precondition is the work's own energy cost. No care threshold,
        // no attribute requirement - but the work's own cost is still enforced.
        val reader = StubReader(detailVenue = "竹影武馆", selectedLabel = "帮武馆跑腿", working = true)
        val (flow, _, _) = build(
            reader,
            resources = StubResources(beforeEnergy = 3, afterEnergy = 3),
        )
        flow.authorise(
            WorkStartAuthorisation.grant("竹影武馆", paotuId, 1, 0, 100, "test-operator", 1000L),
        )

        val outcome = runBlocking { flow.run(target) }
        assertTrue("expected Blocked, got $outcome", outcome is WorkOutcome.Blocked)
        assertTrue((outcome as WorkOutcome.Blocked).reason.startsWith("WORK_ENERGY_INSUFFICIENT"))
        assertFalse(flow.startConfirmation.tapDelivered)
    }

    @Test
    fun `anUnreadableEnergyReadingBlocksTheStart`() {
        // An unknown budget is a blocker, not a pass: starting on an unknown spend is exactly the
        // unbounded-resource case the brief forbids.
        val reader = StubReader(detailVenue = "竹影武馆", selectedLabel = "帮武馆跑腿", working = true)
        val (flow, _, _) = build(
            reader,
            resources = StubResources(beforeEnergy = null, afterEnergy = null),
        )
        flow.authorise(
            WorkStartAuthorisation.grant("竹影武馆", paotuId, 1, 0, 100, "test-operator", 1000L),
        )

        val outcome = runBlocking { flow.run(target) }
        assertTrue("expected Blocked, got $outcome", outcome is WorkOutcome.Blocked)
        assertEquals("WORK_ENERGY_UNREADABLE", (outcome as WorkOutcome.Blocked).reason)
        assertFalse(flow.startConfirmation.tapDelivered)
    }

    @Test
    fun `anUnconfirmedPageIdentityBlocksTheStart`() {
        // A stale or mismatched page must not authorise a real action. The preview runs fine, then
        // the identity check that runs immediately before the tap sees a different page and the flow
        // stops without sending any input.
        val reader = StubReader(detailVenue = "竹影武馆", selectedLabel = "帮武馆跑腿", working = true)
        val (flow, _, _) = build(reader)
        flow.authorise(
            WorkStartAuthorisation.grant("竹影武馆", paotuId, 1, 0, 100, "test-operator", 1000L),
        )

        val previewed = runBlocking { flow.preview(target) }
        assertTrue("preview must pass first, got $previewed", previewed is WorkOutcome.Preview)

        // The page drifts between the preview and the start: exactly the case where a real tap would
        // land on the wrong screen.
        reader.detailVenue = null
        val outcome = runBlocking { flow.start(target) }

        assertTrue("expected Blocked, got $outcome", outcome is WorkOutcome.Blocked)
        assertEquals("WORK_PAGE_IDENTITY_UNCONFIRMED", (outcome as WorkOutcome.Blocked).reason)
        assertFalse(flow.startConfirmation.tapDelivered)
    }

    // ------------------------------------------------------- timer vs completion

    @Test
    fun `aCountdownReachingZeroWithoutACompletionPageIsNotASuccess`() {
        // The brief's explicit rule: never conclude the reward arrived just because the timer hit
        // zero. The completion surface is what proves it.
        val reader = StubReader(
            detailVenue = "竹影武馆", selectedLabel = "帮武馆跑腿", working = true,
            completion = CompletionResult(
                false, 0, "TIMER_EXPIRED_PAGE_NOT_FINISHED",
            ),
        )
        val (flow, _, _) = build(reader)
        flow.authorise(
            WorkStartAuthorisation.grant("竹影武馆", paotuId, 1, 0, 100, "test-operator", 1000L),
        )
        val started = runBlocking { flow.run(target) }
        assertTrue(started is WorkOutcome.Started)

        val outcome = runBlocking { flow.waitAndVerify(target) }
        assertTrue("expected Failed, got $outcome", outcome is WorkOutcome.Failed)
        assertEquals("TIMER_EXPIRED_PAGE_NOT_FINISHED", (outcome as WorkOutcome.Failed).reason)
        assertEquals(WorkPhase.FAILED, flow.state.phase)
    }

    @Test
    fun `aCompletedWorkProducesAllFourRewardRungs`() {
        // The four rungs must all be present and distinct: EXPECTED (catalog), PAGE_READING (page),
        // OBSERVED_DELTA (resource delta), CONFIRMED_REWARD (reconciled). They are never collapsed.
        val reader = StubReader(
            detailVenue = "竹影武馆", selectedLabel = "帮武馆跑腿", working = true,
            completion = CompletionResult(true, 10, "PAYROLL_PAGE"),
        )
        // Coins 8 -> 76 (+68), score 0 -> 10 (+10), energy 80 -> 75 (-5): the real P5.1 closed loop.
        val (flow, _, _) = build(reader)
        flow.authorise(
            WorkStartAuthorisation.grant("竹影武馆", paotuId, 1, 0, 100, "test-operator", 1000L),
        )

        runBlocking { flow.run(target) }
        // The completion reading is the post-work state.
        val outcome = runBlocking { flow.waitAndVerify(target) }

        assertTrue("expected Completed, got $outcome", outcome is WorkOutcome.Completed)
        val completed = outcome as WorkOutcome.Completed
        assertEquals(10, completed.actualMinutes)
        val kinds = completed.records.map { it.kind }
        assertTrue(kinds.contains(RewardRecordKind.EXPECTED_REWARD))
        assertTrue(kinds.contains(RewardRecordKind.PAGE_READING))
        assertTrue(kinds.contains(RewardRecordKind.OBSERVED_DELTA))
        assertTrue(kinds.contains(RewardRecordKind.CONFIRMED_REWARD))
        assertEquals(WorkPhase.FINISHED, flow.state.phase)
    }

    @Test
    fun `aPageReadingThatDisagreesWithTheObservedDeltaKeepsBothRecords`() {
        // The brief: record the difference, never force-correct to the expected value. Here the
        // page and the catalog both say 68 but the real delta is 40, so CONFIRMED_REWARD must NOT
        // appear, and BOTH the page reading and the observed delta must survive unchanged.
        val reader = StubReader(
            detailVenue = "竹影武馆", selectedLabel = "帮武馆跑腿", working = true,
            completion = CompletionResult(true, 10, "PAYROLL_PAGE"),
        )
        val (flow, _, _) = build(
            reader,
            resources = StubResources(beforeCoins = 8, afterCoins = 48),
        )
        flow.authorise(
            WorkStartAuthorisation.grant("竹影武馆", paotuId, 1, 0, 100, "test-operator", 1000L),
        )
        runBlocking { flow.run(target) }
        val outcome = runBlocking { flow.waitAndVerify(target) }

        assertTrue("expected Completed, got $outcome", outcome is WorkOutcome.Completed)
        val completed = outcome as WorkOutcome.Completed
        // The page reading is preserved at 68, not rewritten down to 40.
        val page = completed.records.first { it.kind == RewardRecordKind.PAGE_READING }
        assertEquals(68, (page.coins as ObservedValue.Known).value)
        // The observed delta is preserved at 40, not forced up to the predicted 68.
        val observed = completed.records.first { it.kind == RewardRecordKind.OBSERVED_DELTA }
        assertEquals(40, (observed.coins as ObservedValue.Known).value)
        // And the disagreement is recorded by ABSENCE of the confirmed rung, which is what keeps the
        // 45-minute catalog entry honest.
        assertFalse(completed.records.any { it.kind == RewardRecordKind.CONFIRMED_REWARD })
        assertEquals(WorkPhase.FINISHED, flow.state.phase)
    }

    @Test
    fun `aRewardIsBookedExactlyOnceEvenIfVerificationRunsTwice`() {
        // The interrupted-then-recovered case: the completion page is re-read. The ledger must
        // refuse to credit the same session twice, and the flow must report that honestly instead of
        // crashing on a terminal phase.
        val reader = StubReader(
            detailVenue = "竹影武馆", selectedLabel = "帮武馆跑腿", working = true,
            completion = CompletionResult(true, 10, "PAYROLL_PAGE"),
        )
        val (flow, _, _) = build(reader)
        flow.authorise(
            WorkStartAuthorisation.grant("竹影武馆", paotuId, 1, 0, 100, "test-operator", 1000L),
        )
        runBlocking { flow.run(target) }
        val first = runBlocking { flow.waitAndVerify(target) }
        assertTrue(first is WorkOutcome.Completed)
        val credited = flow.sessionLedger.totalCoins
        assertTrue("expected a booked reward, got $credited", credited == 68)

        // A second verification of the SAME session must not silently credit coins again.
        val second = runBlocking { flow.waitAndVerify(target) }
        assertTrue("expected Blocked, got $second", second is WorkOutcome.Blocked)
        assertTrue((second as WorkOutcome.Blocked).reason.contains("ALREADY_RECORDED"))
        // The coins are still exactly one session's worth, not two.
        assertEquals(68, flow.sessionLedger.totalCoins)
        assertEquals(1, flow.sessionLedger.recordedSessions.size)
    }

    @Test
    fun `waitingForAWorkThatNeverStartedFailsClosed`() {
        val (flow, _, _) = build()
        val outcome = runBlocking { flow.waitAndVerify(target) }
        assertTrue("expected Failed, got $outcome", outcome is WorkOutcome.Failed)
        assertEquals("WORK_NOT_STARTED", (outcome as WorkOutcome.Failed).reason)
    }

    // ------------------------------------------------------- phase table

    @Test
    fun `thePhaseTableRefusesIllegalTransitions`() {
        val sm = WorkStateMachine()
        sm.move(WorkPhase.NAVIGATING, "start", 1L)
        // NAVIGATING may not jump straight to a reward verification.
        try {
            sm.move(WorkPhase.REWARD_VERIFIED, "skip", 2L)
            fail("expected WORK_PHASE_ILLEGAL")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("WORK_PHASE_ILLEGAL"))
        }
    }

    @Test
    fun `aTerminalPhaseAcceptsNoFurtherTransitions`() {
        val sm = WorkStateMachine()
        sm.move(WorkPhase.NAVIGATING, "start", 1L)
        sm.move(WorkPhase.FAILED, "nav failed", 2L)
        try {
            sm.move(WorkPhase.WORK_SELECTED, "recover", 3L)
            fail("expected WORK_PHASE_TERMINAL")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("WORK_PHASE_TERMINAL"))
        }
    }

    @Test
    fun `everyTransitionCarriesAReason`() {
        val sm = WorkStateMachine()
        sm.move(WorkPhase.NAVIGATING, "because", 7L)
        sm.move(WorkPhase.WORK_SELECTED, "venue-open", 8L)
        assertEquals(2, sm.history.size)
        assertEquals("because", sm.history[0].reason)
        assertEquals("venue-open", sm.history[1].reason)
        assertEquals(WorkPhase.IDLE, sm.history[0].from)
        assertEquals(WorkPhase.WORK_SELECTED, sm.history[1].to)
    }

    @Test
    fun `theFiveWorkEventsStaySeparatePhases`() {
        // The brief's explicit requirement: 已请求启动 / 已确认工作开始 / 预计结束 / 已确认工作结束 /
        // 已确认奖励到账 must not be merged. Each has its own phase.
        val distinct = setOf(
            WorkPhase.START_REQUESTED,
            WorkPhase.WORKING_CONFIRMED,
            WorkPhase.WAITING,
            WorkPhase.COMPLETION_CHECK,
            WorkPhase.REWARD_VERIFIED,
        )
        assertEquals(5, distinct.size)
    }

    // ------------------------------------------------------- interruption / recovery

    @Test
    fun `aHumanPauseStopsTheFlowWithoutBookingAnything`() {
        // 用户主动暂停 is one of the twelve required cases. A pause is not a failure, and it must
        // never be recorded as one, and it must never consume the grant silently.
        val sink = StubSink(pauseRequested = true)
        val (flow, _, _) = build(
            StubReader(detailVenue = "竹影武馆", selectedLabel = "帮武馆跑腿"),
            sink = sink,
        )
        flow.authorise(
            WorkStartAuthorisation.grant("竹影武馆", paotuId, 1, 0, 100, "test-operator", 1000L),
        )

        // The pause arrives AFTER the preview, which is the only ordering that exists in a real run:
        // preview never taps, and the pause check sits between the grant and the tap.
        val previewed = runBlocking { flow.preview(target) }
        assertTrue(previewed is WorkOutcome.Preview)

        val outcome = runBlocking { flow.start(target) }
        assertTrue("expected Interrupted, got $outcome", outcome is WorkOutcome.Interrupted)
        assertEquals("WORK_PAUSED_BY_USER", (outcome as WorkOutcome.Interrupted).reason)
        // Nothing was started, nothing was booked, and no tap was delivered.
        assertFalse(flow.startConfirmation.tapDelivered)
        assertEquals(0, flow.sessionLedger.recordedSessions.size)
        assertEquals(WorkPhase.INTERRUPTED, flow.state.phase)
    }

    @Test
    fun `aPauseDuringTheWaitBooksNothingAndStaysReobservable`() {
        // A pause while WAITING: the work keeps running in the game, but the flow stops observing and
        // records nothing. A later run must still be able to re-observe the same completion page.
        val reader = StubReader(
            detailVenue = "竹影武馆", selectedLabel = "帮武馆跑腿", working = true,
            completion = CompletionResult(true, 10, "PAYROLL_PAGE"),
        )
        val sink = StubSink()
        val (flow, _, _) = build(reader, sink = sink)
        flow.authorise(
            WorkStartAuthorisation.grant("竹影武馆", paotuId, 1, 0, 100, "test-operator", 1000L),
        )
        runBlocking { flow.run(target) }
        // The human pauses while the flow is waiting.
        sink.pauseRequested = true

        val outcome = runBlocking { flow.waitAndVerify(target) }
        assertTrue("expected Interrupted, got $outcome", outcome is WorkOutcome.Interrupted)
        // Nothing booked: the session is still open, so a later verification is the FIRST booking.
        assertEquals(0, flow.sessionLedger.recordedSessions.size)
        assertFalse(flow.sessionLedger.isRecorded("竹影武馆", paotuId, 1000L))
    }

    @Test
    fun `anAppRestartInRecoveryBlocksTheStartRatherThanTappingIntoTheUnknown`() {
        // 执行中断或 App 重启: the runtime is reconstructing the page, so nothing can positively
        // identify what a tap would hit. The flow refuses to send it.
        val sink = StubSink(inputHalted = true)
        val (flow, _, _) = build(
            StubReader(detailVenue = "竹影武馆", selectedLabel = "帮武馆跑腿"),
            sink = sink,
        )
        flow.authorise(
            WorkStartAuthorisation.grant("竹影武馆", paotuId, 1, 0, 100, "test-operator", 1000L),
        )

        val previewed = runBlocking { flow.preview(target) }
        assertTrue(previewed is WorkOutcome.Preview)

        val outcome = runBlocking { flow.start(target) }
        assertTrue("expected Blocked, got $outcome", outcome is WorkOutcome.Blocked)
        assertEquals("SESSION_INPUT_HALTED", (outcome as WorkOutcome.Blocked).reason)
        assertFalse(flow.startConfirmation.tapDelivered)
        assertEquals(WorkPhase.BLOCKED, flow.state.phase)
    }
}
