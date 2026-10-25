package com.ynk.virtualdisplay.automation

import android.graphics.PointF
import kotlinx.coroutines.delay

/**
 * [WorkPageReader] over the live capture, using the same text reader the course scan uses.
 *
 * Page identity follows the measured rules in docs/P5-01-career-discovery.md §2 and §7, which exist
 * precisely because the naive ones are wrong:
 *
 *  - the town LIST page shows the title 宠物职业小镇 together with at least three known venue names;
 *  - the venue DETAIL page carries that same title in a sticky header, so the title alone proves
 *    nothing, and a detail page is recognised by 去打工 plus 用时;
 *  - venue labels move as the map scrolls, so every tap target is located on the current frame and
 *    nothing here remembers a coordinate.
 *
 * A frame that matches neither rule reports identityMatched=false. That is a stop, never a guess.
 *
 * All field parsing is delegated to [WorkPageParser] so the risky parts (which number on a line is the
 * reward and which is the balance, which card is the selected one) are pure and testable without a
 * device. Nothing here invents a number: a field the page did not show comes back unread.
 */
class DeviceWorkPageReader(
    private val flow: PetFlowContext,
    private val reader: CourseNameReader,
    private val clock: () -> Long = { System.currentTimeMillis() },
) : WorkPageReader {
    private var completedPayroll: PayrollNavigationPolicy.Proof? = null
    private var payrollReturnAttempted = false

    override suspend fun locateTownEntry(): PointF? =
        locateJoined(TOWN_ENTRY_LABEL, "town-entry")

    /** Locates a label whose glyphs ML Kit may split across consecutive lines. */
    private suspend fun locateJoined(label: String, tag: String): PointF? {
        // ML Kit's line split is not stable frame to frame (measured 2026-10-20: the 职业小镇 label
        // region read fine at 03:44 and returned nothing across 4 fresh frames at 05:25). Consecutive
        // lines whose joined text contains the label count as one match, so a split "职业" + "小镇"
        // is found too. Still read-only: no tap ever follows a failed locate.
        repeat(LOCATE_ATTEMPTS) { attempt ->
            val lines = readLines().sortedBy { it.bounds.top }
            val hit = joinedHit(lines, label)
            if (hit != null) {
                val (top, bottom, left, right) = hit
                check(WorkPageParser.validBounds(top, bottom, left, right, 720, 1600)) {
                    "WORK_TARGET_BOUNDS_INVALID:$tag:$left,$top,$right,$bottom"
                }
                val point = PointF((left.toFloat() + right) / 2f, (top.toFloat() + bottom) / 2f)
                flow.emit("locate-$tag-hit", result = "point=(${point.x},${point.y})")
                return point
            }
            if (attempt == LOCATE_ATTEMPTS - 1) {
                flow.emit("locate-unmatched-$tag",
                    result = lines.joinToString(";") { it.text })
            }
            delay(LOCATE_RETRY_DELAY_MS)
        }
        return null
    }

    /**
     * Normalises the glyph confusions ML Kit shows on this page before any text comparison. The map
     * lives in [WorkPageParser.normaliseGlyphs] so the parser and the reader share one truth.
     */
    private fun normaliseGlyphs(text: String): String = WorkPageParser.normaliseGlyphs(text)

    /**
     * First run of consecutive lines whose joined text contains [label]; null when absent.
     *
     * The bounds fold lives in [WorkPageParser.locateJoinedRun] so its seed rule is pinned by a
     * JVM test. Measured 2026-10-20 s12: the fold that seeded ALL accumulators at Int.MAX_VALUE
     * left bottom/right pinned there, every center overflowed to (min + Int.MAX_VALUE) / 2, and
     * the town-entry tap died with "tap outside pet display". Still read-only: no tap ever
     * follows a failed locate.
     */
    private fun joinedHit(lines: List<TextLine>, label: String): IntArray? =
        WorkPageParser.locateJoinedRun(
            lines.map { TextLine(normaliseGlyphs(it.text), it.bounds) },
            label,
        )

    override suspend fun readTownPage(): PetWorkNavigation.TownPage = classify(readLines())

    override suspend fun locateVenueLabel(venue: String): PointF? = locate { it.text.trim() == venue }

    override suspend fun readDetailPage(): PetWorkNavigation.TownPage {
        val page = classify(readLines())
        return if (page.isDetailPage) page else PetWorkNavigation.TownPage(false, false, null)
    }

    override suspend fun readSelectedOptionLabel(expectedMinutes: Int?): String? =
        WorkPageParser.parseSelectedOptionLabel(readLines(), expectedMinutes)

    override suspend fun locateWorkOptionCard(venue: String, optionLabel: String): PointF? =
        locate { it.text.trim() == optionLabel }

    override suspend fun readRewardRow(venue: String, optionLabel: String, durationMinutes: Int?): WorkOption {
        // One read is not enough on this page: the detail swipe is a fling with momentum, and a
        // capture takes ~1.3s through the Shizuku fallback (measured s14, 2026-10-20: the session
        // read the block as absent at 1.2s after the swipe while a frame captured a few seconds
        // later shows "体力 5（当前 24），清洁 2（当前 3）" exactly where the parser expects it).
        // So the block is re-read on FRESH frames until every field resolves or the attempts run
        // out. A read that never resolves is still reported as unread, never as a guessed 0.
        var lines = readLines()
        var best = WorkPageParser.parseRewardRow(lines, venue, optionLabel, durationMinutes)
        var attempts = 1
        while (best.unreadFields.isNotEmpty() && attempts < REWARD_READ_ATTEMPTS) {
            delay(REWARD_RETRY_DELAY_MS)
            lines = readLines()
            best = WorkPageParser.parseRewardRow(lines, venue, optionLabel, durationMinutes)
            attempts++
        }
        if (best.unreadFields.isNotEmpty()) {
            // The lines ML Kit actually produced decide the next parser fix, so a failed read must
            // leave them in the log. A merged "消耗 体力 5（当前…）" line looks nothing like the
            // split rows, and without this the failure is invisible from outside the device.
            flow.emit("work-reward-read-unresolved",
                result = "FAIL", reason = best.unreadFields.joinToString(",") +
                    "|lines=" + lines.joinToString(";") { it.text })
        }
        return best
    }

    override suspend fun locateStartWorkButton(): PointF? = locate { it.text.contains("去打工") }

    /**
     * Positive proof the pet is now working.
     *
     * 打工中 is the marker; 剩余 is NOT accepted, because the study page writes 剩余 too and a lesson
     * screen must never confirm a work shift.
     */
    override suspend fun readWorkingPage(): Boolean =
        WorkPageParser.parseWorkingPage(readLines())

    /**
     * Waits for the work to finish and returns what it POSITIVELY observed.
     *
     * The payroll surface is only accepted when it NAMES the option the flow is waiting for, so a
     * payroll belonging to a different session cannot be booked as this one's payout. The minutes are
     * read off that same surface; a surface that states no duration is not accepted, because the
     * countdown that reached zero is a prediction and must never stand in for the work's own length.
     */
    override suspend fun awaitCompletionPage(expectedEndAtMs: Long, extraWindowMs: Long): CompletionResult {
        completedPayroll = null
        payrollReturnAttempted = false
        val waitUntil = expectedEndAtMs - COMPLETION_LEAD_MS
        val remaining = waitUntil - clock()
        if (remaining > 0) delay(remaining)
        val deadline = expectedEndAtMs + extraWindowMs
        // Once the payroll surface appears, its OWN dialog may still be rendering, and ML Kit's
        // read of a live frame is not stable frame to frame (measured 2026-10-20: the same region
        // read fine on one frame and returned nothing on the next). A first frame that shows the
        // markers but not the minutes is therefore NOT a failure - it is the dialog arriving. Keep
        // polling until one frame provides markers, a unique option name AND a positive minute
        // count together; only a window that closes with none of that is unconfirmed.
        var payrollSeen = false
        while (clock() < deadline) {
            val surface = WorkPageParser.parseCompletionSurface(readLines())
            if (surface != null) {
                payrollSeen = true
                val minutes = surface.minutes
                if (minutes != null) {
                    completedPayroll = PayrollNavigationPolicy.Proof(true, surface.optionLabel, minutes, surface.coinAward)
                    return CompletionResult(
                        matched = true,
                        actualMinutes = minutes,
                        reason = "COMPLETION_SURFACE_MATCHED",
                        optionLabel = surface.optionLabel,
                        coinAward = surface.coinAward,
                    )
                }
            }
            delay(COMPLETION_POLL_MS)
        }
        return if (payrollSeen) {
            CompletionResult(true, 0, "COMPLETION_MINUTES_UNREAD")
        } else {
            CompletionResult(false, 0, "TIMER_EXPIRED_PAGE_NOT_FINISHED")
        }
    }

    /**
     * Reads the coin BALANCE off the CURRENT frame and returns it as one record.
     *
     * Called by the resource reader through [DeviceWorkResourceReader]'s injected lambda, once before
     * the start tap and once after the payroll page appears, so the caller can difference the two.
     *
     * SEMANTICS, THIS IS THE PART THAT USED TO BE WRONG. Only the BALANCE may be read here. A payroll
     * line such as "金币+68" or "基础工资 68" is the SESSION'S REWARD, not the balance: differencing a
     * reward against a balance produces a number that means nothing, and booking it would credit the
     * pet coins that may already have been spent. So this method reads the coin figure ONLY from a line
     * that states a balance, and refuses every other coin line.
     *
     * The 工分 half is UNKNOWN on purpose. The two surfaces that write it are a per-STAGE progress
     * counter on the detail page ("工分 10/200", P5.1 §3.3) and the payroll's own award ("工分 +10"):
     * neither is a cumulative total, so differencing them produces a number that means nothing. It
     * stays unread until one cumulative 工分 total is proven on both sides of a session, which no
     * measured frame states. A session is therefore coin-verified only, which is what keeps the record
     * set from claiming a reward half nobody measured. [WorkPageParser] still parses 工分 off the
     * detail page, but only as the option's PAGE_READING rung, never as a delta.
     *
     * DEVICE-UNCERTAIN. On the frames measured so far the top bar shows the coin ICON and a bare number
     * with no 金币 word at all, so a balance may be unreadable on a real page. In that case the value
     * comes back [ObservedValue.Unknown] and the session is refused - which is the correct outcome,
     * because a payout that cannot be differenced must not be claimed. The stable balance region and
     * its exact wording still need one real frame to confirm (recorded as a follow-up in the report).
     */
    suspend fun snapshotResources(at: String): RewardRecord {
        val lines = readLines()
        val balance = lines.firstNotNullOfOrNull { line ->
            WorkPageParser.parseBalanceLine(line.text)
        }
        if (balance == null) {
            // The lines ML Kit actually produced decide the next BALANCE_LINE fix, so a failed
            // read must leave them in the log. DEVICE-UNCERTAIN: the payroll frame's balance row
            // has never been observed under ML Kit yet, so this dump is the evidence that will
            // either pin the regex or prove the row does not exist on the frame.
            flow.emit("work-balance-read-unresolved",
                result = "FAIL",
                reason = "at=$at|lines=" + lines.joinToString(";") { it.text })
        }
        return RewardRecord(
            kind = RewardRecordKind.OBSERVED_DELTA,
            at = at,
            coins = balance?.observed(at) ?: ObservedValue.Unknown("COIN_BALANCE_UNREAD", clock(), at),
            // Never read off a frame: neither 工分 surface is a cumulative total, so a delta would
            // mean nothing. See the method doc.
            workScore = ObservedValue.Unknown("WORK_SCORE_NOT_A_CUMULATIVE_BALANCE", clock(), at),
            // Energy is the care flow's own reading, so it is not re-derived from this frame.
            energy = ObservedValue.Unknown("ENERGY_READ_BY_CARE_FLOW", clock(), at),
            evidence = "frame-resource-read",
        )
    }

    /** Compatibility surface: a venue detail page is deliberately NOT a completed home return. */
    suspend fun dismissCompletion(): Boolean = dismissCompletionResult().homeReached

    /** One recognised payroll back arrow, one input, then observation-only destination checks. */
    suspend fun dismissCompletionResult(): PayrollNavigationPolicy.ExitResult = with(flow) {
        if (payrollReturnAttempted) {
            return@with PayrollNavigationPolicy.ExitResult(PayrollNavigationPolicy.Page.UNKNOWN, "PAYROLL_RETURN_ALREADY_ATTEMPTED")
        }
        val current = frame()
        val target = try {
            val lines = reader.read(current).map { it.copy(text = normaliseGlyphs(it.text)) }
            val surface = WorkPageParser.parseCompletionSurface(lines)
            val proof = PayrollNavigationPolicy.Proof(
                payroll = surface != null && lines.any { it.text.contains("工资明细") },
                option = surface?.optionLabel, minutes = surface?.minutes, award = surface?.coinAward,
            )
            val located = PayrollNavigationPolicy.returnTarget(proof, completedPayroll, current.width, current.height) { x, y -> current.getPixel(x, y) }
            if (located != null) save(current, "work-payroll-before-return")
            located
        } finally { current.recycle() }
        if (target == null) {
            emit("work-completion-dismiss", result = "FAIL", reason = "PAYROLL_SURFACE_OR_BACK_ARROW_UNCONFIRMED")
            return@with PayrollNavigationPolicy.ExitResult(PayrollNavigationPolicy.Page.UNKNOWN, "PAYROLL_SURFACE_OR_BACK_ARROW_UNCONFIRMED")
        }
        safety()
        // Reserve the one return attempt before dispatch: a possibly delivered tap is never repeated.
        payrollReturnAttempted = true
        input.tap(target.x, target.y)
        delay(DISMISS_SETTLE_MS)
        repeat(4) {
            val after = frame(latest = true)
            val page = try {
                val lines = reader.read(after).map { it.copy(text = normaliseGlyphs(it.text)) }
                val payrollStillVisible = WorkPageParser.parseCompletionSurface(lines) != null
                val detail = classify(lines).let { it.identityMatched && it.isDetailPage }
                val destination = PayrollNavigationPolicy.exitPage(
                    outdoor = score(after, listOf(outdoor)) >= profile.matchThreshold,
                    room = score(after, listOf(room)) >= profile.matchThreshold,
                    workDetail = detail,
                    payroll = payrollStillVisible,
                )
                if (destination != PayrollNavigationPolicy.Page.UNKNOWN) save(after, "work-payroll-return-${destination.name.lowercase()}")
                destination
            } finally { after.recycle() }
            if (page != PayrollNavigationPolicy.Page.UNKNOWN) {
                val reason = "PAYROLL_RETURNED_${page.name}"
                val result = PayrollNavigationPolicy.ExitResult(page, reason)
                emit("work-completion-dismiss", result = if (result.homeReached) "PASS" else "OBSERVED", reason = reason)
                return@with result
            }
            if (it < 3) delay(300)
        }
        emit("work-completion-dismiss", result = "FAIL", reason = "PAYROLL_RETURN_DESTINATION_UNCONFIRMED")
        PayrollNavigationPolicy.ExitResult(PayrollNavigationPolicy.Page.UNKNOWN, "PAYROLL_RETURN_DESTINATION_UNCONFIRMED")
    }

    private suspend fun readLines(): List<TextLine> = with(flow) {
        val frame = frame()
        try { reader.read(frame).map { it.copy(text = normaliseGlyphs(it.text)) } } finally { frame.recycle() }
    }

    private suspend fun locate(matches: (TextLine) -> Boolean): PointF? {
        // ML Kit's read of a live frame is not stable frame to frame (measured 2026-10-20: the same
        // 职业小镇 label region read fine at 03:44 and returned nothing at 05:14), so a locate is a
        // few fresh-frame attempts. Read-only: no tap ever follows a failed locate.
        repeat(LOCATE_ATTEMPTS) { attempt ->
            val line = readLines().firstOrNull(matches)
            if (line != null) {
                return PointF(
                    (line.bounds.left + line.bounds.right) / 2f,
                    (line.bounds.top + line.bounds.bottom) / 2f,
                )
            }
            if (attempt < LOCATE_ATTEMPTS - 1) delay(LOCATE_RETRY_DELAY_MS)
        }
        return null
    }

    /**
     * Delegates the page-identity rule to [WorkPageParser.parseTownPage], so the reader and the
     * pure tests share one truth. Only the DETAIL branch still needs the venue name here, and it
     * keeps its measured rule: a detail frame that names no known venue is NOT confirmed, because
     * the flow selects a venue by name and an unnamed page cannot prove which one it opened.
     */
    private fun classify(lines: List<TextLine>): PetWorkNavigation.TownPage {
        val venue = WorkPageParser.parseTownVenue(lines)
        return when (WorkPageParser.parseTownPage(lines)) {
            WorkPageParser.TownPageKind.DETAIL -> PetWorkNavigation.TownPage(venue != null, true, venue)
            WorkPageParser.TownPageKind.LIST -> PetWorkNavigation.TownPage(true, false, null)
            WorkPageParser.TownPageKind.NONE -> PetWorkNavigation.TownPage(false, false, null)
        }
    }

    private fun Int.observed(at: String) =
        ObservedValue.Known(this, clock(), 1.0, at, ObservationSource.DIGIT_RECOGNITION)

    /**
     * Any known option label the payroll page mentions, so the surface can be attributed even when
     * the option's own card is not on screen at that moment.
     */
    private fun String.optionLabelHint(): List<String> =
        KNOWN_OPTION_LABELS.filter { contains(it) }

    companion object {
        /** Fresh-frame locate attempts; the OCR read is unstable, the page is not. */
        const val LOCATE_ATTEMPTS = 4
        const val LOCATE_RETRY_DELAY_MS = 900L
        /** Reward-row reads on fresh frames; the swipe is a fling, so the first read may catch drift. */
        const val REWARD_READ_ATTEMPTS = 4
        const val REWARD_RETRY_DELAY_MS = 1500L

        /** The outdoor-map label that opens the town. Located per frame, never cached. */
        const val TOWN_ENTRY_LABEL = "职业小镇"
        /** Joined-run gap lives in [WorkPageParser.JOINED_LINE_GAP_PX]; keep the reader's copy in sync. */
        const val JOINED_LINE_GAP_PX = WorkPageParser.JOINED_LINE_GAP_PX
        const val COMPLETION_LEAD_MS = 30_000L
        const val COMPLETION_POLL_MS = 5_000L
        const val DISMISS_SETTLE_MS = 1200L

        /** The page-identity rule and its vocabulary live in [WorkPageParser]; one truth, testable. */
        val KNOWN_OPTION_LABELS = WorkPageParser.KNOWN_OPTION_LABELS

    }
}
