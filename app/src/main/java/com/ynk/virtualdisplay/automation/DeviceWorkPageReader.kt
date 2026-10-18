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
 */
class DeviceWorkPageReader(
    private val flow: PetFlowContext,
    private val reader: CourseNameReader,
    private val clock: () -> Long = { System.currentTimeMillis() },
) : WorkPageReader {

    override suspend fun locateTownEntry(): PointF? = locate { it.text.contains(TOWN_ENTRY_LABEL) }

    override suspend fun readTownPage(): PetWorkNavigation.TownPage = classify(readLines())

    override suspend fun locateVenueLabel(venue: String): PointF? = locate { it.text.trim() == venue }

    override suspend fun readDetailPage(): PetWorkNavigation.TownPage {
        val page = classify(readLines())
        return if (page.isDetailPage) page else PetWorkNavigation.TownPage(false, false, null)
    }

    override suspend fun readSelectedOptionLabel(): String? {
        val lines = readLines()
        // The selected card is the one whose label sits nearest the reward block the page shows for
        // it. With only one card's data on screen at a time, the option label closest to 用时 is the
        // selected one.
        val anchor = lines.firstOrNull { DURATION_PATTERN.containsMatchIn(it.text) } ?: return null
        return lines
            .filter { KNOWN_OPTION_LABELS.contains(it.text.trim()) }
            .minByOrNull { kotlin.math.abs(it.bounds.centerY() - anchor.bounds.centerY()) }
            ?.text?.trim()
    }

    override suspend fun locateWorkOptionCard(venue: String, optionLabel: String): PointF? =
        locate { it.text.trim() == optionLabel }

    override suspend fun readRewardRow(venue: String, optionLabel: String): WorkOption {
        val lines = readLines()
        fun number(label: String): Int? = lines.firstOrNull { it.text.contains(label) }
            ?.let { NUMBER_PATTERN.find(it.text)?.value?.toIntOrNull() }
        val duration = lines.firstNotNullOfOrNull { DURATION_PATTERN.find(it.text)?.groupValues?.get(1)?.toIntOrNull() }
        val unread = mutableListOf<String>()
        if (duration == null) unread += "时长未读出"
        val coins = number("金币")
        if (coins == null) unread += "金币未读出"
        return WorkOption(
            id = "work:$venue/$optionLabel",
            venue = venue,
            optionLabel = optionLabel,
            durationMinutes = duration ?: 0,
            pageCoinReward = coins ?: 0,
            pageWorkScoreReward = number("工分") ?: 0,
            pageEnergyCost = number("体力") ?: 0,
            pageCleanlinessCost = number("清洁") ?: 0,
            unreadFields = unread,
        )
    }

    override suspend fun locateStartWorkButton(): PointF? = locate { it.text.contains("去打工") }

    override suspend fun readWorkingPage(): Boolean =
        readLines().any { it.text.contains("打工中") || it.text.contains("剩余") }

    override suspend fun awaitCompletionPage(expectedEndAtMs: Long, extraWindowMs: Long): CompletionResult {
        val waitUntil = expectedEndAtMs - COMPLETION_LEAD_MS
        val remaining = waitUntil - clock()
        if (remaining > 0) delay(remaining)
        val deadline = expectedEndAtMs + extraWindowMs
        while (clock() < deadline) {
            val lines = readLines()
            val text = lines.joinToString(" ") { it.text }
            if (text.contains("工资明细") || text.contains("基础工资") || text.contains("打工完成")) {
                val minutes = lines.firstNotNullOfOrNull {
                    DURATION_PATTERN.find(it.text)?.groupValues?.get(1)?.toIntOrNull()
                } ?: 0
                return CompletionResult(true, minutes, "COMPLETION_SURFACE_MATCHED")
            }
            delay(COMPLETION_POLL_MS)
        }
        return CompletionResult(false, 0, "TIMER_EXPIRED_PAGE_NOT_FINISHED")
    }

    private suspend fun readLines(): List<TextLine> = with(flow) {
        val frame = frame()
        try { reader.read(frame) } finally { frame.recycle() }
    }

    private suspend fun locate(matches: (TextLine) -> Boolean): PointF? {
        val line = readLines().firstOrNull(matches) ?: return null
        return PointF(
            (line.bounds.left + line.bounds.right) / 2f,
            (line.bounds.top + line.bounds.bottom) / 2f,
        )
    }

    private fun classify(lines: List<TextLine>): PetWorkNavigation.TownPage {
        val text = lines.joinToString(" ") { it.text }
        val detail = text.contains("去打工") && text.contains("用时")
        if (detail) {
            val venue = KNOWN_VENUES.firstOrNull { text.contains(it) }
            return PetWorkNavigation.TownPage(venue != null, true, venue)
        }
        val venuesSeen = KNOWN_VENUES.count { text.contains(it) }
        val list = text.contains(TOWN_TITLE) && venuesSeen >= LIST_PAGE_MIN_VENUES
        return PetWorkNavigation.TownPage(list, false, null)
    }

    companion object {
        const val TOWN_TITLE = "宠物职业小镇"
        /** The outdoor-map label that opens the town. Located per frame, never cached. */
        const val TOWN_ENTRY_LABEL = "职业小镇"
        const val LIST_PAGE_MIN_VENUES = 3
        const val COMPLETION_LEAD_MS = 30_000L
        const val COMPLETION_POLL_MS = 5_000L

        val KNOWN_VENUES = listOf("竹影武馆", "风铃旅社", "迷雾侦探所", "星尘魔法塔", "彩虹画室")
        val KNOWN_OPTION_LABELS = setOf("帮武馆跑腿", "陪练基础课", "替摊主看摊", "街巷跑腿")

        val DURATION_PATTERN: Regex = Regex("""(\d+)\s*分钟""")
        val NUMBER_PATTERN: Regex = Regex("""\d+""")
    }
}
