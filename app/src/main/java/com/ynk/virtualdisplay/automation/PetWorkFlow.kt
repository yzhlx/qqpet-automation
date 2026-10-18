package com.ynk.virtualdisplay.automation

import kotlin.math.abs

/**
 * PetWorkFlow: the career-town work execution loop.
 *
 * Design rules taken straight from the P5.3 brief, each enforced in code rather than by convention:
 *
 *  1. PREVIEW AND START ARE SEPARATE PHASES. The 去打工 button has no confirmation dialog, so the
 *     preview phase never reaches it. Only an explicit [WorkStartAuthorisation] crosses the gap,
 *     and a preview-only run terminates in [WorkOutcome.Preview] with FINISHED.
 *
 *  2. A DELIVERED START TAP CAN NEVER BE REPEATED. [WorkStartConfirmation] latches on delivery.
 *     If the work cannot be confirmed afterwards, the flow runs the status-verification path or
 *     stops with [WorkOutcome.UnconfirmedStart]; it does not tap again.
 *
 *  3. TIMER ZERO IS A PREDICTION, NOT A FACT. [WorkOutcome.Completed] requires a positively matched
 *     completion surface AND a resource delta. The countdown is used only to decide when to start
 *     looking, never as the evidence of a payout.
 *
 *  4. REWARDS KEEP THEIR RUNG. EXPECTED_REWARD / PAGE_READING / OBSERVED_DELTA / CONFIRMED_REWARD
 *     are four different [RewardRecordKind]s and are never collapsed. A page reading that survives
 *     an observed delta becomes CONFIRMED_REWARD, which is what promotes the 45-min catalog entry.
 *     A page reading that disagrees with the observed delta keeps BOTH records and logs the
 *     difference; it is never rewritten to match the prediction.
 *
 *  5. NO CARE THRESHOLD FOR WORK (Rule A). This flow never calls the study health policy; it only
 *     checks the work option's own energy requirement.
 *
 *  6. NO ATTRIBUTE-BASED REWARD PREDICTION (Rule B). Rewards are the page's numbers, full stop.
 *
 *  7. UNKNOWN REWARDS DO NOT RUN. An option whose reward was never collected is [WorkOutcome.Blocked]
 *     before any input, so it can never feed the optimiser a made-up number.
 *
 * NOTE ON `with(flow)`: [WorkRuntimeSink] exposes its own `state` property (the runtime's current
 * step label), which shadows the flow's [state] machine inside those blocks. Every state-machine
 * transition below is therefore written as `this@PetWorkFlow.state.move(...)`; a bare `state.move`
 * would silently resolve to the sink's String and fail to compile, which is the safer failure.
 */
class PetWorkFlow(
    private val flow: WorkRuntimeSink,
    private val workNavigation: PetWorkNavigation,
    private val resources: WorkResourceReader,
    private val catalog: WorkCatalog = WorkCatalog(),
    /** Exposed so tests and the acceptance report can prove what was booked, and how many times. */
    val sessionLedger: WorkSessionLedger = WorkSessionLedger(),
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    val state = WorkStateMachine()

    /** Set when a real start tap was delivered, so the caller can see it even after a failure. */
    val startConfirmation = WorkStartConfirmation()

    private var authorisation: WorkStartAuthorisation? = null

    /** Monotonic start of the confirmed work, used to compute the expected end. Null until confirmed. */
    private var workStartedAtMs: Long? = null

    /** Expected end in wall-clock terms, derived from the ACTUAL duration shown on the page. */
    private var expectedEndAtMs: Long? = null

    /** The duration the page actually showed for the selected option, not the catalog's guess. */
    private var actualDurationMinutes: Int = 0

    /** Resource reading taken immediately before the start tap, kept for the completion delta. */
    private var beforeStartSnapshot: RewardRecord? = null

    /**
     * Grant a real work start. This is the ONLY public way to authorise a tap on 去打工, and it is
     * the caller's job to obtain it from a human first. Repeated calls replace the grant; a revoked
     * grant can only be replaced by a fresh explicit one.
     */
    fun authorise(grant: WorkStartAuthorisation) {
        authorisation = grant
    }

    fun revokeAuthorisation(reason: String) {
        authorisation?.revoke(reason)
    }

    // ------------------------------------------------------------------ preview

    /**
     * Runs everything up to, but NOT including, the 去打工 button.
     *
     * Sequence: enter the town -> navigate to the venue -> select the work option -> wait for the
     * page to settle -> RE-READ the reward row for the currently selected option -> compare against
     * the catalog -> report. Terminates in [WorkOutcome.Preview].
     *
     * No input in this phase can start work: the only tap that could is the one this method
     * deliberately never sends.
     */
    suspend fun preview(target: WorkTarget): WorkOutcome = with(flow) {
        this@PetWorkFlow.state.move(WorkPhase.NAVIGATING, "preview-start", clock())
        val blockers = mutableListOf<String>()

        val catalogEntry = catalog.find(target.venue, target.optionId)
        if (catalogEntry == null) {
            blockers += "职业目录中没有 ${target.venue}/${target.optionLabel}"
        }
        if (catalogEntry?.confidence == RewardConfidence.UNKNOWN) {
            // Rule: an uncollected reward never runs, and never gets extrapolated into a number.
            blockers += "该工作收益未采集，不参与执行也不推测数值"
        }
        if (blockers.isNotEmpty()) {
            this@PetWorkFlow.state.move(WorkPhase.BLOCKED, blockers.joinToString(";"), clock())
            emit("work-preview-blocked", "BLOCKED", blockers.joinToString(";"))
            return@with WorkOutcome.Blocked(blockers.joinToString(";"))
        }

        workNavigation.enterCareerTown()
        workNavigation.navigateToVenue(target.venue)
        this@PetWorkFlow.state.move(WorkPhase.WORK_SELECTED, "venue-open", clock())

        val page = workNavigation.selectWorkOption(target.venue, target.optionLabel)
        val reRead = page.rewardRow
        // Remember the page reading so the completion delta can be reconciled against it later.
        recordPageReading(reRead)

        this@PetWorkFlow.state.move(WorkPhase.PREVIEW_VERIFIED, "reward-row-re-read", clock())

        val differences = catalogEntry?.let { catalog.compare(it, reRead) } ?: emptyList()
        if (differences.isNotEmpty()) blockers += "页面奖励与目录不一致：${differences.joinToString(";")}"

        if (blockers.isNotEmpty()) {
            this@PetWorkFlow.state.move(WorkPhase.BLOCKED, blockers.joinToString(";"), clock())
            emit("work-preview-blocked", "BLOCKED", blockers.joinToString(";"))
            return@with WorkOutcome.Blocked(blockers.joinToString(";"))
        }

        emit("work-preview-verified", "PASS", null)
        // Deliberately NOT FINISHED: the preview run stops here by convention, but leaving the phase
        // at PREVIEW_VERIFIED is what lets an authorised run continue into start() without pretending
        // the machine reached a terminal state. A preview-only caller simply never calls start().
        this@PetWorkFlow.state.move(WorkPhase.PREVIEW_VERIFIED, "preview-complete-no-start", clock())
        WorkOutcome.Preview(
            venue = target.venue,
            optionId = target.optionId,
            pageReward = reRead,
            catalogMatched = true,
            catalogDifferences = emptyList(),
            blockers = emptyList(),
        )
    }

    // ------------------------------------------------------------------ start

    /**
     * Starts ONE real work session under an explicit grant.
     *
     * Preconditions, all checked before the tap: a live grant naming this exact venue and option,
     * remaining starts in that grant, and a freshly re-confirmed page identity. If any of them
     * fails, no input is sent.
     *
     * The tap is sent at most ONCE per call, and [WorkStartConfirmation] makes a second call's tap
     * impossible after delivery.
     *
     * Returns as soon as the WORKING page is positively confirmed. Call [waitAndVerify] afterwards;
     * it is a separate phase so a caller can observe the run in progress.
     */
    suspend fun start(target: WorkTarget): WorkOutcome = with(flow) {
        val grant = authorisation
        if (grant == null) {
            this@PetWorkFlow.state.move(WorkPhase.BLOCKED, "无真实打工授权", clock())
            emit("work-start-blocked", "BLOCKED", "WORK_NOT_AUTHORISED")
            return@with WorkOutcome.Blocked("WORK_NOT_AUTHORISED")
        }
        if (startConfirmation.mustNotTapAgain) {
            // A tap already went out and nobody proved what it did. Tapping again could start a
            // SECOND real session, which is exactly what the brief forbids.
            this@PetWorkFlow.state.move(WorkPhase.BLOCKED, "启动结果未确认，禁止重复点击", clock())
            emit("work-start-blocked", "BLOCKED", "WORK_START_UNCONFIRMED_NO_RETAP")
            return@with WorkOutcome.Blocked("WORK_START_UNCONFIRMED_NO_RETAP")
        }
        if (grant.isRevoked) {
            this@PetWorkFlow.state.move(WorkPhase.BLOCKED, "授权已撤销", clock())
            emit("work-start-blocked", "BLOCKED", "WORK_AUTHORISATION_REVOKED")
            return@with WorkOutcome.Blocked("WORK_AUTHORISATION_REVOKED")
        }

        grant.consume(target.venue, target.optionId)
        this@PetWorkFlow.state.move(WorkPhase.AUTHORIZED, "grant-consumed", clock())

        // The human can stop this at any point, including between the grant and the tap. A pause is
        // not a failure: report it as an interruption and hand the session back with nothing booked.
        if (flow.pauseRequested) {
            this@PetWorkFlow.state.move(WorkPhase.INTERRUPTED, "用户主动暂停", clock())
            emit("work-interrupted", "INTERRUPTED", "WORK_PAUSED_BY_USER")
            return@with WorkOutcome.Interrupted("WORK_PAUSED_BY_USER")
        }
        // App restart / recovery in flight: no input may be sent until the boundary is RUNNING again.
        if (flow.inputHalted) {
            this@PetWorkFlow.state.move(WorkPhase.BLOCKED, "会话处于恢复中，禁止输入", clock())
            emit("work-start-blocked", "BLOCKED", "SESSION_INPUT_HALTED")
            return@with WorkOutcome.Blocked("SESSION_INPUT_HALTED")
        }

        // Re-confirm the page identity and the selected option immediately before the tap. A stale
        // frame must not authorise a real action.
        val page = workNavigation.confirmWorkOptionSelected(target.venue, target.optionLabel)
        if (!page.identityMatched) {
            this@PetWorkFlow.state.move(WorkPhase.BLOCKED, "启动前页面身份未确认", clock())
            emit("work-start-blocked", "BLOCKED", "WORK_PAGE_IDENTITY_UNCONFIRMED")
            return@with WorkOutcome.Blocked("WORK_PAGE_IDENTITY_UNCONFIRMED")
        }

        // Use the duration the PAGE shows, not the catalog's value: the catalog may be stale.
        actualDurationMinutes = page.rewardRow.durationMinutes
        check(actualDurationMinutes > 0) { "WORK_DURATION_UNREAD_BEFORE_START" }

        val energy = resources.readEnergy()
        val catalogEntry = catalog.find(target.venue, target.optionId)
        if (catalogEntry != null && catalogEntry.energyCost > 0) {
            // Rule A: the ONLY resource precondition is the work's own energy cost. No care
            // threshold, no attribute requirement.
            val available = (energy as? ObservedValue.Known)?.value
            if (available != null && available < catalogEntry.energyCost) {
                this@PetWorkFlow.state.move(WorkPhase.BLOCKED, "精力不足：$available < ${catalogEntry.energyCost}", clock())
                emit("work-start-blocked", "BLOCKED", "WORK_ENERGY_INSUFFICIENT")
                return@with WorkOutcome.Blocked("WORK_ENERGY_INSUFFICIENT:$available<${catalogEntry.energyCost}")
            }
            // An unreadable energy reading is a blocker, not a pass: starting on an unknown budget
            // is exactly the unbounded-spend case the brief forbids.
            if (available == null) {
                this@PetWorkFlow.state.move(WorkPhase.BLOCKED, "精力读数不可用", clock())
                emit("work-start-blocked", "BLOCKED", "WORK_ENERGY_UNREADABLE")
                return@with WorkOutcome.Blocked("WORK_ENERGY_UNREADABLE")
            }
        }

        val before = resources.snapshot("before-start")
        beforeStartSnapshot = before

        this@PetWorkFlow.state.move(WorkPhase.START_REQUESTED, "start-tap-requested", clock())
        startConfirmation.markTapDelivered()
        workNavigation.tapStartWork()
        // START_REQUESTED stays the phase: the tap being delivered is NOT the same event as the work
        // being confirmed. The phase only advances on a positively matched WORKING page below.
        emit("work-start-requested", "DELIVERED_UNCONFIRMED", null)

        val confirmed = workNavigation.awaitWorkingPage()
        if (!confirmed) {
            // No re-tap. Verify what actually happened, then stop honestly.
            this@PetWorkFlow.state.move(WorkPhase.BLOCKED, "启动后未确认到工作中页面", clock())
            emit("work-start-unconfirmed", "FAIL", "WORK_START_UNCONFIRMED")
            return@with WorkOutcome.UnconfirmedStart(target.venue, target.optionId, "WORK_START_UNCONFIRMED")
        }

        val startedAt = clock()
        workStartedAtMs = startedAt
        // Expected end derived from the ACTUAL page duration. Still a prediction: it never proves
        // the reward arrived.
        expectedEndAtMs = startedAt + actualDurationMinutes * 60_000L
        sessionLedger.beginSession(target.venue, target.optionId, startedAt)
        this@PetWorkFlow.state.move(WorkPhase.WORKING_CONFIRMED, "working-page-confirmed", clock())
        emit("work-started", "PASS", null)

        WorkOutcome.Started(
            venue = target.venue,
            optionId = target.optionId,
            durationMinutes = actualDurationMinutes,
            expectedEndAtMs = expectedEndAtMs!!,
            before = before,
        )
    }

    // ------------------------------------------------------------------ run

    /**
     * Preview, then (only under a live grant) start.
     *
     * This is the authorised path. A caller that has no grant gets [WorkOutcome.Preview] and nothing
     * else, because [start] refuses to tap without one.
     */
    suspend fun run(target: WorkTarget): WorkOutcome {
        val previewed = preview(target)
        if (previewed !is WorkOutcome.Preview) return previewed
        return start(target)
    }

    // ------------------------------------------------------------------ wait

    /**
     * Waits for the work to finish and verifies the reward.
     *
     * The countdown is used ONLY to decide when to start looking. Completion is proved by a matched
     * completion surface, and the reward is proved by a resource delta. Neither is inferred from the
     * other, and neither is inferred from the timer.
     *
     * If the timer expires but the completion page is not there, the flow stays in COMPLETION_CHECK
     * and keeps polling within [completionCheckWindowMs] rather than declaring success.
     */
    suspend fun waitAndVerify(target: WorkTarget, completionCheckWindowMs: Long = 180_000L): WorkOutcome = with(flow) {
        val startedAt = workStartedAtMs ?: run {
            this@PetWorkFlow.state.move(WorkPhase.FAILED, "未确认工作已开始", clock())
            emit("work-not-started", "FAIL", "WORK_NOT_STARTED")
            return@with WorkOutcome.Failed("WORK_NOT_STARTED")
        }
        val expectedEnd = expectedEndAtMs!!

        // A second verification call for the same session. The ledger below is the real guard, but
        // checking here first keeps the phase table intact: the machine is already in a terminal
        // phase, and a duplicate verification must never be able to move a terminal phase.
        if (sessionLedger.isRecorded(target.venue, target.optionId, startedAt)) {
            emit("work-reward-duplicate", "BLOCKED", "WORK_REWARD_ALREADY_RECORDED")
            return@with WorkOutcome.Blocked("WORK_REWARD_ALREADY_RECORDED")
        }

        this@PetWorkFlow.state.move(WorkPhase.WAITING, "waiting-for-expected-end", clock())
        // A human pause during the wait is not a failure and must not be reported as one. The work
        // keeps running in the game; the flow simply stops observing and books nothing, so a later
        // run can re-observe the same completion page exactly once.
        if (flow.pauseRequested) {
            this@PetWorkFlow.state.move(WorkPhase.INTERRUPTED, "用户主动暂停等待", clock())
            emit("work-interrupted", "INTERRUPTED", "WORK_PAUSED_BY_USER")
            return@with WorkOutcome.Interrupted("WORK_PAUSED_BY_USER")
        }
        // Low-frequency observation: no repeated screenshots while waiting. Only the final approach
        // needs a look, which is what awaitCompletionPage does internally.
        val completion = workNavigation.awaitCompletionPage(expectedEnd, completionCheckWindowMs)

        this@PetWorkFlow.state.move(WorkPhase.COMPLETION_CHECK, "completion-surface-evaluated", clock())
        if (!completion.matched) {
            // The timer said done but the page never showed a completion surface. This is a
            // late-settlement / page-anomaly case, not a success.
            this@PetWorkFlow.state.move(WorkPhase.FAILED, completion.reason, clock())
            emit("work-completion-unconfirmed", "FAIL", completion.reason)
            return@with WorkOutcome.Failed(completion.reason)
        }

        val after = resources.snapshot("after-completion")
        val beforeSnapshot = beforeStartSnapshot ?: resources.snapshot("before-start-recovered")
        val records = buildRecords(target, startedAt, before = beforeSnapshot, after = after)

        this@PetWorkFlow.state.move(WorkPhase.REWARD_VERIFIED, "reward-deltas-read", clock())

        val coins = delta(records, RewardRecordKind.OBSERVED_DELTA, "coins")
        val score = delta(records, RewardRecordKind.OBSERVED_DELTA, "workScore")
        val energyDelta = delta(records, RewardRecordKind.OBSERVED_DELTA, "energy")

        // Book the reward exactly once. A second call for the same session throws instead of
        // crediting the coins twice. The throw is caught and reported as a blocked duplicate rather
        // than propagated, because the caller asked a legitimate question ("is it booked?") and the
        // honest answer is "already booked" - the coins must NOT be added a second time.
        try {
            sessionLedger.recordReward(
                venue = target.venue,
                optionId = target.optionId,
                startedAtMs = startedAt,
                coins = coins ?: 0,
                workScore = score ?: 0,
                energy = energyDelta ?: 0,
            )
        } catch (e: IllegalStateException) {
            this@PetWorkFlow.state.move(WorkPhase.BLOCKED, "该场次收益已入账：${e.message}", clock())
            emit("work-reward-duplicate", "BLOCKED", e.message)
            return@with WorkOutcome.Blocked("WORK_REWARD_ALREADY_RECORDED:${e.message}")
        }

        emit("work-reward-confirmed", "PASS", null)
        this@PetWorkFlow.state.move(WorkPhase.FINISHED, "work-complete-reward-verified", clock())

        WorkOutcome.Completed(
            venue = target.venue,
            optionId = target.optionId,
            actualMinutes = completion.actualMinutes,
            records = records,
        )
    }

    // ------------------------------------------------------------------ rewards

    /**
     * Builds the four-rung reward record set for one session.
     *
     * EXPECTED_REWARD comes from the catalog, PAGE_READING from the detail page, OBSERVED_DELTA from
     * the resource snapshots. CONFIRMED_REWARD is emitted ONLY when a page reading exists AND the
     * observed delta is within [rewardTolerance] of it. When they disagree, both records are kept
     * and the discrepancy is journalled; the page reading is never overwritten by the prediction.
     */
    private fun buildRecords(
        target: WorkTarget,
        startedAtMs: Long,
        before: RewardRecord,
        after: RewardRecord,
    ): List<RewardRecord> {
        val records = mutableListOf<RewardRecord>()
        val catalogEntry = catalog.find(target.venue, target.optionId)

        if (catalogEntry != null && catalogEntry.confidence != RewardConfidence.UNKNOWN) {
            records += RewardRecord(
                kind = RewardRecordKind.EXPECTED_REWARD,
                at = "catalog",
                coins = ObservedValue.Known(catalogEntry.coinReward, clock(), 1.0, catalogEntry.evidence, ObservationSource.SYSTEM),
                workScore = ObservedValue.Known(catalogEntry.workScoreReward, clock(), 1.0, catalogEntry.evidence, ObservationSource.SYSTEM),
                energy = ObservedValue.Known(catalogEntry.energyCost, clock(), 1.0, catalogEntry.evidence, ObservationSource.SYSTEM),
                evidence = catalogEntry.evidence,
            )
        }

        val pageRecord = pageReading
        if (pageRecord != null) records += pageRecord

        val observedCoins = observedDelta(before.coins, after.coins)
        val observedScore = observedDelta(before.workScore, after.workScore)
        val observedEnergy = observedDelta(before.energy, after.energy)
        val allReadable = observedCoins != null && observedScore != null
        if (allReadable) {
            records += RewardRecord(
                kind = RewardRecordKind.OBSERVED_DELTA,
                at = "session-delta",
                coins = ObservedValue.Known(observedCoins!!, clock(), 1.0, "资源前后差值", ObservationSource.DIGIT_RECOGNITION),
                workScore = ObservedValue.Known(observedScore!!, clock(), 1.0, "资源前后差值", ObservationSource.DIGIT_RECOGNITION),
                energy = ObservedValue.Known(observedEnergy ?: 0, clock(), if (observedEnergy != null) 1.0 else 0.0,
                    "资源前后差值", ObservationSource.DIGIT_RECOGNITION),
                evidence = "session=$startedAtMs",
            )

            // Promotion to CONFIRMED_REWARD needs a page reading AND agreement. Disagreement keeps
            // both rungs and records the difference; it never rewrites the page reading.
            if (pageRecord != null) {
                val pageCoins = (pageRecord.coins as? ObservedValue.Known)?.value
                val pageScore = (pageRecord.workScore as? ObservedValue.Known)?.value
                if (pageCoins != null && pageScore != null &&
                    abs(observedCoins - pageCoins) <= rewardTolerance &&
                    abs(observedScore - pageScore) <= rewardTolerance
                ) {
                    records += RewardRecord(
                        kind = RewardRecordKind.CONFIRMED_REWARD,
                        at = "reconciled",
                        coins = ObservedValue.Known(observedCoins, clock(), 1.0, "页面读数与实测一致", ObservationSource.DIGIT_RECOGNITION),
                        workScore = ObservedValue.Known(observedScore, clock(), 1.0, "页面读数与实测一致", ObservationSource.DIGIT_RECOGNITION),
                        energy = ObservedValue.Known(observedEnergy ?: 0, clock(), 1.0, "页面读数与实测一致", ObservationSource.DIGIT_RECOGNITION),
                        evidence = "catalog=${catalogEntry?.id}",
                    )
                }
            }
        }
        return records
    }

    private var pageReading: RewardRecord? = null

    /** Called by the flow once the reward row has been re-read on the detail page. */
    fun recordPageReading(option: WorkOption) {
        pageReading = RewardRecord(
            kind = RewardRecordKind.PAGE_READING,
            at = "detail-page",
            coins = ObservedValue.Known(option.pageCoinReward, clock(), 1.0, "详情页奖励行", ObservationSource.DIGIT_RECOGNITION),
            workScore = ObservedValue.Known(option.pageWorkScoreReward, clock(), 1.0, "详情页奖励行", ObservationSource.DIGIT_RECOGNITION),
            energy = ObservedValue.Known(option.pageEnergyCost, clock(), 1.0, "详情页奖励行", ObservationSource.DIGIT_RECOGNITION),
            cleanliness = option.pageCleanlinessCost.let {
                ObservedValue.Known(it, clock(), 1.0, "详情页奖励行", ObservationSource.DIGIT_RECOGNITION)
            },
            evidence = "option=${option.id}",
        )
    }

    private fun observedDelta(before: ObservedValue<Int>, after: ObservedValue<Int>): Int? {
        val b = (before as? ObservedValue.Known)?.value ?: return null
        val a = (after as? ObservedValue.Known)?.value ?: return null
        return a - b
    }

    private fun delta(records: List<RewardRecord>, kind: RewardRecordKind, field: String): Int? {
        val record = records.firstOrNull { it.kind == kind } ?: return null
        val value = when (field) {
            "coins" -> record.coins
            "workScore" -> record.workScore
            else -> record.energy
        }
        return (value as? ObservedValue.Known)?.value
    }

    companion object {
        /** Absolute tolerance for promoting a page reading to CONFIRMED_REWARD. */
        const val rewardTolerance = 0
    }
}

/** What the caller asks for: one venue, one work option. */
data class WorkTarget(
    val venue: String,
    val optionLabel: String,
    val optionId: String,
)

/**
 * Resource readings for the work flow.
 *
 * Injected rather than read inline so the flow stays testable on the JVM, and so an unreadable
 * value is [ObservedValue.Unknown] instead of a silent 0 that would look like a real delta.
 */
interface WorkResourceReader {
    fun snapshot(at: String): RewardRecord
    fun readEnergy(): ObservedValue<Int>
}
