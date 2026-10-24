package com.ynk.virtualdisplay.bot

import com.ynk.virtualdisplay.automation.WorkPageParser

/** One work option in the career town, with what the game charges and pays for it. */
data class Job(
    val venue: String,
    val option: String,
    val minutes: Int,
    val coins: Int,
    val energyCost: Int,
    val cleanCost: Int,
) {
    companion object {
        /** Verified on device 2026-10-12 and 2026-10-21 (WorkCatalog). */
        val RUN_ERRANDS = Job("竹影武馆", "帮武馆跑腿", minutes = 10, coins = 68, energyCost = 5, cleanCost = 2)
    }
}

enum class WorkStop { COMPLETED, NO_TOWN, VENUE_NOT_FOUND, OPTION_NOT_FOUND, NOT_STARTED, NO_PAYROLL }

/** [coinsEarned] is the award the payroll page named, when it could be read. */
data class WorkResult(val stop: WorkStop, val coinsEarned: Int? = null)

/**
 * Works one shift: room -> outdoor -> 职业小镇 -> venue -> option -> 去打工, waits out the shift and
 * reads the payroll. The town pages have no templates, so every step is found by its text; a shift
 * that started is always waited out to its payroll.
 */
class WorkAction(
    private val eyes: Eyes,
    private val hands: Hands,
    private val gauges: Gauges,
    private val navigator: Navigator,
    private val pollMs: Long = 5_000L,
) {
    suspend fun run(job: Job): WorkResult {
        navigator.goHome()
        val stop = startShift(job)
        if (stop != null) return home(WorkResult(stop))
        val award = awaitPayroll(job) ?: return home(WorkResult(WorkStop.NO_PAYROLL))
        return home(WorkResult(WorkStop.COMPLETED, award.takeIf { it > 0 }))
    }

    private suspend fun home(result: WorkResult): WorkResult {
        navigator.goHome()
        return result
    }

    /** Null once the shift is confirmed running, otherwise why it did not start. */
    private suspend fun startShift(job: Job): WorkStop? {
        val exit = gauges.findText("出门") ?: return WorkStop.NO_TOWN
        hands.tap(exit)
        if (!await(Page.OUTDOOR)) return WorkStop.NO_TOWN
        val town = retryFind("职业小镇") ?: return WorkStop.NO_TOWN
        hands.tap(town)
        hands.pause(TOWN_SETTLE_MS)
        if (!openVenue(job.venue)) return WorkStop.VENUE_NOT_FOUND
        val option = retryFind(job.option) ?: return WorkStop.OPTION_NOT_FOUND
        hands.tap(option)
        hands.pause(OPTION_SETTLE_MS)
        val start = retryFind("去打工") ?: return WorkStop.NOT_STARTED
        hands.tap(start)
        repeat(WORKING_CONFIRM_POLLS) {
            hands.pause(2_000)
            if (gauges.screenText().contains("打工中")) return null
        }
        return WorkStop.NOT_STARTED
    }

    /** The venue's detail page is proven by its 去打工 button. The town map scrolls, so look page by page. */
    private suspend fun openVenue(venue: String): Boolean {
        repeat(MAX_TOWN_PAGES) {
            val label = gauges.findText(venue)
            if (label == null) {
                hands.drag(Layout.townScroll, 50L)
                hands.pause(TOWN_SETTLE_MS)
                return@repeat
            }
            hands.tap(label)
            hands.pause(TOWN_SETTLE_MS)
            if (gauges.screenText().contains("去打工")) return true
        }
        return false
    }

    /** Waits for the shift to end, then for the payroll dialog. Returns its coin award, 0 when unread. */
    private suspend fun awaitPayroll(job: Job): Int? {
        hands.pause(job.minutes * 60_000L - PAYROLL_LEAD_MS)
        repeat(((PAYROLL_LEAD_MS + PAYROLL_GRACE_MS) / pollMs).toInt()) {
            val text = gauges.screenText()
            if (WorkPageParser.PAYROLL_MARKERS.any { text.contains(it) }) {
                return WorkPageParser.parsePayrollCoinAward(listOf(text)) ?: 0
            }
            hands.pause(pollMs)
        }
        return null
    }

    /** OCR on game pages is not stable frame to frame; a label missing once is looked for again. */
    private suspend fun retryFind(text: String): Point? {
        repeat(4) {
            gauges.findText(text)?.let { return it }
            hands.pause(900)
        }
        return null
    }

    private suspend fun await(page: Page, tries: Int = 10): Boolean {
        repeat(tries) {
            hands.pause(Layout.SETTLE_MS / 3)
            if (eyes.look().page == page) return true
        }
        return false
    }

    companion object {
        const val TOWN_SETTLE_MS = 2_400L
        const val OPTION_SETTLE_MS = 1_200L
        const val MAX_TOWN_PAGES = 4
        const val WORKING_CONFIRM_POLLS = 30
        const val PAYROLL_LEAD_MS = 30_000L
        const val PAYROLL_GRACE_MS = 180_000L
    }
}
