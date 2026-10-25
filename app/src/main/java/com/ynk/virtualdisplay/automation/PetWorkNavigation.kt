package com.ynk.virtualdisplay.automation

import android.graphics.PointF
import kotlinx.coroutines.delay

/**
 * Career-town navigation for P5.3.
 *
 * Measured facts this class is built on (docs/P5-01-career-discovery.md §2, §7):
 *
 *  - The town map is a HORIZONTALLY SCROLLING map. Venue label coordinates change with the scroll
 *    position, so nothing here caches a coordinate: every tap re-locates the label first.
 *  - Venue detail page judgement: 用时 / 规则说明 / 去打工 / 工分 / 升星条件 present.
 *  - Town list page judgement: 宠物职业小镇 AND >= 3 known venue names AND none of the detail keys.
 *    The detail page ALSO carries the town title in its sticky header, so "title present" is never
 *    sufficient on its own.
 *  - Tapping a work option card switches the whole page's data (简介 / 消耗 / 工分 / 奖励), so the
 *    reward row MUST be re-read after the selection settles.
 *  - Venue tap targets are not concentric with their labels, and a coordinate that worked once can
 *    stop working at a different scroll position.
 *
 * The page-identity and text-reading primitives are supplied by [WorkPageReader], which is injected
 * so the navigation logic stays testable without a capture session.
 */
class PetWorkNavigation(
    private val flow: WorkRuntimeSink,
    private val reader: WorkPageReader,
    private val tap: suspend (PointF, Long) -> Unit,
    private val swipe: suspend (Int, Int, Int, Int, Long) -> Unit,
) {
    /**
     * Page identity for the career town. A repainting game element may be a tap TARGET but never
     * page IDENTITY, so this only ever reports what the reader positively matched.
     */
    data class TownPage(val identityMatched: Boolean, val isDetailPage: Boolean, val venue: String?)

    /** Result of selecting a work option: the reward row as re-read AFTER the selection settled. */
    data class SelectedOption(
        val identityMatched: Boolean,
        val rewardRow: WorkOption,
        val optionSelected: Boolean,
    )

    suspend fun enterCareerTown() = with(flow) {
        state = "ENTER_TOWN"
        // QQ pet -> 互动 page -> back -> pet room -> outdoor map -> town. The room/outdoor hops reuse
        // the verified chain from the study flow; the final hop is the town entry on the outdoor map.
        emit("enter-town-start", "PASS", null)
        // The reader's locate already retries fresh frames (ML Kit's read of the outdoor map is not
        // stable frame to frame, measured 2026-10-20). Read-only until a positive match.
        val entry = reader.locateTownEntry()
        check(entry != null) { "CAREER_TOWN_ENTRY_NOT_FOUND" }
        tap(entry, TOWN_SETTLE_MS)
        val page = reader.readTownPage()
        check(page.identityMatched) { "CAREER_TOWN_PAGE_UNCONFIRMED" }
        emit("enter-town-ok", if (page.isDetailPage) "DETAIL" else "LIST", null)
    }

    /** Opens one venue's detail page. Never assumes a cached coordinate: re-locates every time. */
    suspend fun navigateToVenue(venue: String) = with(flow) {
        state = "SELECT_VENUE"
        var lastError: String? = null
        for (attempt in 1..MAX_VENUE_ATTEMPTS) {
            val page = reader.readTownPage()
            if (page.isDetailPage && page.venue == venue) {
                emit("venue-already-open", "PASS", null)
                return@with
            }
            val target = reader.locateVenueLabel(venue)
            if (target == null) {
                // Not visible at this scroll position: scroll once and re-locate.
                swipe(SCROLL_FROM_X, SCROLL_Y, SCROLL_TO_X, SCROLL_Y, SCROLL_MS)
                delay(TOWN_SETTLE_MS)
                continue
            }
            tap(target, VENUE_SETTLE_MS)
            val opened = reader.readTownPage()
            if (opened.isDetailPage && opened.venue == venue) {
                emit("venue-open", "PASS", null)
                return@with
            }
            lastError = "VENUE_OPEN_UNCONFIRMED:${opened.venue}"
        }
        error("CAREER_VENUE_NAVIGATION_FAILED:$venue:$lastError")
    }

    /**
     * Selects the work option card, waits for the page to settle, then scrolls the reward block into
     * view and RE-READS it.
     *
     * Measured 2026-10-20: the 消耗/工分/奖励 block lives below the fold, so the reward read happens
     * on the SCROLLED frame. The selection itself is proven by the card's own 用时 value on the top
     * frame: the cards sit side by side, and the card whose duration equals the one the page shows
     * for the selected option is the selected one (see [WorkPageParser.parseSelectedOptionLabel]).
     */
    suspend fun selectWorkOption(venue: String, optionLabel: String, expectedMinutes: Int): SelectedOption = with(flow) {
        state = "SELECT_WORK_OPTION"
        val card = reader.locateWorkOptionCard(venue, optionLabel)
        check(card != null) { "WORK_OPTION_CARD_NOT_FOUND:$venue/$optionLabel" }
        tap(card, OPTION_SETTLE_MS)

        val settled = reader.readDetailPage()
        check(settled.identityMatched) { "WORK_DETAIL_PAGE_UNCONFIRMED_AFTER_SELECTION" }
        val selected = reader.readSelectedOptionLabel(expectedMinutes)
        check(selected == optionLabel) { "WORK_OPTION_NOT_SELECTED:$selected!=$optionLabel" }

        val rewardRow = scrollRewardIntoViewAndRead(venue, optionLabel, expectedMinutes)
        emit("work-option-selected", "PASS", "label=$selected minutes=$expectedMinutes")
        SelectedOption(
            identityMatched = true,
            rewardRow = rewardRow,
            optionSelected = true,
        )
    }

    /**
     * Swipes the detail page up once and reads the reward block that lives below the fold.
     *
     * The single swipe must move the 消耗/工分/奖励 lines into view without losing the 去打工 button
     * (it stays fixed at the page bottom, measured 2026-10-20). After the swipe the frame is checked
     * for the reward block; the read is refused when it cannot be positively confirmed.
     *
     * [durationMinutes] is the duration read off the TOP frame, carried over because the option
     * cards scroll out of view and their 用时 lines are not on the scrolled frame.
     */
    private suspend fun scrollRewardIntoViewAndRead(venue: String, optionLabel: String, durationMinutes: Int): WorkOption = with(flow) {
        swipe(DETAIL_SCROLL_FROM_X, DETAIL_SCROLL_FROM_Y, DETAIL_SCROLL_TO_X, DETAIL_SCROLL_TO_Y, DETAIL_SCROLL_MS)
        delay(OPTION_SETTLE_MS)
        val row = reader.readRewardRow(venue, optionLabel, durationMinutes)
        check(row.unreadFields.isEmpty()) { "WORK_REWARD_ROW_UNREAD:${row.unreadFields.joinToString(",")}" }
        row
    }

    /**
     * Re-confirms the page identity and the selected option immediately before a real start tap.
     * Returns identityMatched=false when anything cannot be positively matched, so the caller stops
     * instead of sending input on a stale frame.
     *
     * Runs on the frame the caller leaves the page in (the scrolled reward view): the fixed bottom
     * 去打工/升星 lines carry the page identity, and the reward numbers are re-read against the
     * catalog. The duration is carried over from the top frame for the same reason as above.
     */
    suspend fun confirmWorkOptionSelected(venue: String, optionLabel: String, durationMinutes: Int): SelectedOption = with(flow) {
        val page = reader.readDetailPage()
        if (!page.identityMatched) return@with SelectedOption(false, emptyRewardRow(venue, optionLabel), false)
        val rewardRow = reader.readRewardRow(venue, optionLabel, durationMinutes)
        SelectedOption(true, rewardRow, rewardRow.unreadFields.isEmpty())
    }

    /**
     * Locates the 去打工 button. Null when it is not there.
     *
     * The flow resolves this BEFORE it reserves any budget, so a start whose button cannot be found
     * spends nothing.
     */
    suspend fun locateStartWorkButton(): PointF? = reader.locateStartWorkButton()

    /**
     * THE START TAP. Called only by PetWorkFlow.start() after an explicit grant, with the [button]
     * the flow located on the frame that passed its checks.
     *
     * There is deliberately no retry here and no wrapper that could hide a failure: one call, one
     * tap. The caller decides what an unconfirmed outcome means.
     */
    suspend fun tapStartWork(button: PointF) = with(flow) {
        state = "START_WORK"
        tap(button, WORKING_SETTLE_MS)
        emit("work-start-tap", "DELIVERED", null)
    }

    /**
     * Positive proof the work is running. A matched "working" surface is required; a timeout is a
     * failure, never a silent pass.
     */
    suspend fun awaitWorkingPage(): Boolean = with(flow) {
        val deadline = System.currentTimeMillis() + WORKING_CONFIRM_WINDOW_MS
        while (System.currentTimeMillis() < deadline) {
            if (reader.readWorkingPage()) {
                emit("work-working-confirmed", "PASS", null)
                return@with true
            }
            delay(POLL_INTERVAL_MS)
        }
        emit("work-working-unconfirmed", "FAIL", "WORKING_CONFIRM_WINDOW_EXPIRED")
        false
    }

    /**
     * Waits for the work to finish and returns what it POSITIVELY observed.
     *
     * [expectedEndAtMs] is a prediction from the page's own duration value; it only decides when to
     * start looking. [extraWindowMs] bounds how long past that prediction the reader keeps looking,
     * so a late settlement is waited out rather than guessed.
     *
     * matched=false + TIMER_EXPIRED_PAGE_NOT_FINISHED means the countdown ran out but no completion
     * surface ever appeared. That is a page anomaly or a late settlement, and the caller must never
     * treat it as a success.
     */
    suspend fun awaitCompletionPage(
        expectedEndAtMs: Long,
        extraWindowMs: Long = COMPLETION_EXTRA_WINDOW_MS,
    ): CompletionResult = with(flow) {
        state = "WAIT_COMPLETION"
        val result = reader.awaitCompletionPage(expectedEndAtMs, extraWindowMs)
        if (result.matched) {
            emit("work-completion-observed", "PASS", null)
        } else {
            emit("work-completion-unobserved", "FAIL", result.reason)
        }
        result
    }

    private fun emptyRewardRow(venue: String, optionLabel: String) = WorkOption(
        id = "work:$venue/$optionLabel",
        venue = venue,
        optionLabel = optionLabel,
        durationMinutes = 0,
        pageCoinReward = 0,
        pageWorkScoreReward = 0,
        pageEnergyCost = 0,
        unreadFields = listOf("页面未确认"),
    )

    companion object {
        const val TOWN_SETTLE_MS = 2400L
        const val VENUE_SETTLE_MS = 2400L
        const val OPTION_SETTLE_MS = 1200L
        const val WORKING_SETTLE_MS = 2000L
        const val WORKING_CONFIRM_WINDOW_MS = 120_000L
        const val POLL_INTERVAL_MS = 1000L
        const val MAX_VENUE_ATTEMPTS = 4
        const val COMPLETION_EXTRA_WINDOW_MS = 180_000L
        const val SCROLL_FROM_X = 150
        const val SCROLL_TO_X = 620
        const val SCROLL_Y = 900
        const val SCROLL_MS = 400L

        /** Detail-page reward scroll: swipe up inside the scrollable body (below the fixed header). */
        const val DETAIL_SCROLL_FROM_X = 360
        const val DETAIL_SCROLL_TO_X = 360
        const val DETAIL_SCROLL_FROM_Y = 1100
        const val DETAIL_SCROLL_TO_Y = 600
        const val DETAIL_SCROLL_MS = 400L
    }
}

/**
 * All screen reading for the career town, injected so the navigation and the flow stay testable.
 *
 * Implementations must follow the identity-vs-target rule: a game element that repaints with state
 * (a work option card, the 去打工 button) may be returned as a tap TARGET, but page IDENTITY must
 * come from a stable region. Returning identityMatched=true without a positive match is the one bug
 * that could authorise a real work start on the wrong page.
 */
interface WorkPageReader {
    /** Locates the town entry on the outdoor map. Null when not found. */
    suspend fun locateTownEntry(): PointF?

    /** Reads the current town page: list or detail, and which venue if detail. */
    suspend fun readTownPage(): PetWorkNavigation.TownPage

    /** Locates a venue label at the current scroll position. Null when not visible. */
    suspend fun locateVenueLabel(venue: String): PointF?

    /** Reads the venue detail page identity. */
    suspend fun readDetailPage(): PetWorkNavigation.TownPage

    /**
     * The label of the work option card that is currently selected.
     *
     * [expectedMinutes] lets the reader prove the selection by the card's OWN 用时 value (the cards
     * sit side by side, so vertical proximity cannot tell them apart). When null, the reader may
     * only answer when exactly one candidate card carries a readable duration.
     */
    suspend fun readSelectedOptionLabel(expectedMinutes: Int? = null): String?

    /** Locates a work option card to tap. */
    suspend fun locateWorkOptionCard(venue: String, optionLabel: String): PointF?

    /**
     * Re-reads the reward row for the currently selected option.
     *
     * [durationMinutes] is the option's duration read off the TOP frame: the reward block sits
     * below the fold, and on the scrolled frame the option cards (with their 用时 lines) are gone.
     */
    suspend fun readRewardRow(venue: String, optionLabel: String, durationMinutes: Int? = null): WorkOption

    /** Locates the 去打工 button. Null when not found. */
    suspend fun locateStartWorkButton(): PointF?

    /** Positive proof the pet is now working. */
    suspend fun readWorkingPage(): Boolean

    /**
     * Waits for the work to finish and returns what it positively observed.
     *
     * [expectedEndAtMs] is a PREDICTION used only to decide when to start looking closely.
     * [extraWindowMs] bounds how long past that prediction the reader keeps looking, so a late
     * settlement is waited out rather than guessed.
     *
     * A matched=false result with reason TIMER_EXPIRED_PAGE_NOT_FINISHED means the countdown ran out
     * but the game never showed a completion surface: that is a late-settlement or page anomaly, and
     * it must never be reported as a success.
     */
    suspend fun awaitCompletionPage(expectedEndAtMs: Long, extraWindowMs: Long = 180_000L): CompletionResult
}

/**
 * What the completion wait actually proved.
 *
 * [optionLabel] is the work option the payroll page NAMED, or null when the surface matched but did
 * not name any option. A caller must not treat an unnamed match as proof of the work it started:
 * the game can show a payroll-style dialog for a different session.
 *
 * [coinAward] is the positive coin amount the payroll page itself named ("金币 +N" / "基础工资 N"),
 * or null when unread. It is evidence of the award line, never a balance reading.
 */
data class CompletionResult(
    val matched: Boolean,
    val actualMinutes: Int,
    val reason: String,
    val optionLabel: String? = null,
    val coinAward: Int? = null,
) {
    /** True when the matched surface explicitly names [option]. An unnamed surface never matches. */
    fun namesOption(option: String): Boolean =
        matched && optionLabel != null && optionLabel == option
}
