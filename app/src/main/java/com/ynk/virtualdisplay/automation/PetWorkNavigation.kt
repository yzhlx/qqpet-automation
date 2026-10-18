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
     * Selects the work option card, waits for the page to settle, and RE-READS the reward row.
     *
     * The re-read is the whole point: selecting the card switches every number on the page, so the
     * reward row read before the selection is the WRONG option's data.
     */
    suspend fun selectWorkOption(venue: String, optionLabel: String): SelectedOption = with(flow) {
        state = "SELECT_WORK_OPTION"
        val card = reader.locateWorkOptionCard(venue, optionLabel)
        check(card != null) { "WORK_OPTION_CARD_NOT_FOUND:$venue/$optionLabel" }
        tap(card, OPTION_SETTLE_MS)

        val settled = reader.readDetailPage()
        check(settled.identityMatched) { "WORK_DETAIL_PAGE_UNCONFIRMED_AFTER_SELECTION" }
        val selected = reader.readSelectedOptionLabel()
        check(selected == optionLabel) { "WORK_OPTION_NOT_SELECTED:$selected!=$optionLabel" }

        SelectedOption(
            identityMatched = true,
            rewardRow = reader.readRewardRow(venue, optionLabel),
            optionSelected = true,
        )
    }

    /**
     * Re-confirms the page identity and the selected option immediately before a real start tap.
     * Returns identityMatched=false when anything cannot be positively matched, so the caller stops
     * instead of sending input on a stale frame.
     */
    suspend fun confirmWorkOptionSelected(venue: String, optionLabel: String): SelectedOption = with(flow) {
        val page = reader.readDetailPage()
        if (!page.identityMatched) return@with SelectedOption(false, emptyRewardRow(venue, optionLabel), false)
        val selected = reader.readSelectedOptionLabel()
        if (selected != optionLabel) {
            return@with SelectedOption(false, emptyRewardRow(venue, optionLabel), false)
        }
        SelectedOption(true, reader.readRewardRow(venue, optionLabel), true)
    }

    /**
     * THE START TAP. Called only by PetWorkFlow.start() after an explicit grant.
     *
     * There is deliberately no retry here and no wrapper that could hide a failure: one call, one
     * tap. The caller decides what an unconfirmed outcome means.
     */
    suspend fun tapStartWork() = with(flow) {
        state = "START_WORK"
        val button = reader.locateStartWorkButton()
        check(button != null) { "WORK_START_BUTTON_NOT_FOUND" }
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

    /** The label of the work option card that is currently selected. */
    suspend fun readSelectedOptionLabel(): String?

    /** Locates a work option card to tap. */
    suspend fun locateWorkOptionCard(venue: String, optionLabel: String): PointF?

    /** Re-reads the reward row for the currently selected option. */
    suspend fun readRewardRow(venue: String, optionLabel: String): WorkOption

    /** Locates the 去打工 button. */
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

/** What the completion wait actually proved. */
data class CompletionResult(
    val matched: Boolean,
    val actualMinutes: Int,
    val reason: String,
)
