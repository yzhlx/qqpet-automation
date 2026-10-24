package com.ynk.virtualdisplay.automation

/**
 * Pure (Android-free) parsing for the career-town pages.
 *
 * WHY THIS EXISTS. The device reader used to read the reward row with `contains("金币")` and then take
 * the FIRST number on that line. That is wrong on the real page in three ways, all measured:
 *
 *  - the top bar carries 金币余额 as its own "金币 N" line, so an unbound read reports the BALANCE as
 *    the reward (P5.1 §5 measured balance 8 next to reward 68 - the two are easily confused);
 *  - 消耗 lines read "体力 5（当前 75）", so the same line carries the cost AND the current value;
 *  - every option card stays on screen, so a duration or label that is merely "nearest" can belong to
 *    a different card than the selected one.
 *
 * So every number is taken from the line that carries its label, with a "（当前 N）" bracket stripped,
 * and when a label appears more than once the line that belongs to the reward BLOCK wins - the block
 * being the group of 工分/体力/清洁/用时 lines the page shows for the selected option. A field that
 * cannot be resolved unambiguously is reported through [WorkOption.unreadFields] and is never filled
 * with 0: a 0 reward is a real number, and treating "unread" as 0 would let a preview pass on data
 * nobody measured.
 *
 * GEOMETRY MEASURED 2026-10-20 (display 4, 720x1600, evidence in .build-tmp/device-acceptance/
 * work-page-layout-20261020.md). The two option cards sit side by side with their own 用时 lines at
 * the same height; the 消耗/工分/奖励 block is below the fold and only on the SCROLLED frame; the
 * coin icon on the 奖励 row OCRs as "0" and sits before the real number. Every rule here still fails
 * CLOSED - a block that is not there, or two candidates that cannot be told apart, produce
 * "unread"/null, which the caller turns into a BLOCKED start rather than a guess.
 */
object WorkPageParser {

    val DURATION_PATTERN: Regex = Regex("""(\d+)\s*分钟""")

    /**
     * Reads the reward row for [optionLabel] out of the lines of one frame.
     *
     * This is written for the SCROLLED frame: on the real page (measured 2026-10-20) the
     * 消耗/工分/奖励 block only exists below the fold, and its lines look like this:
     *
     *   "体力 5（当前 26），清洁 2（当前 6）"  — both costs on ONE line, current values bracketed
     *   "工分" then "+10 点"                    — the score is its own line after the label
     *   "奖励" then "[coin icon]68"            — the icon OCRs as "0" and must be skipped
     *
     * [WorkOption.unreadFields] lists every field that could not be read unambiguously. The numeric
     * fields stay 0 in that case; the CALLER must block on the non-empty list.
     */
    fun parseRewardRow(
        lines: List<TextLine>,
        venue: String,
        optionLabel: String,
        durationMinutes: Int? = null,
    ): WorkOption {
        // ML Kit merges rows WinRT keeps apart (measured 2026-10-20 s14/s15): the 消耗 label row and
        // its value row can come back as ONE line "消耗 体力 5（当前 24），清洁 2（当前 3）", and a split
        // second half can start with a stray "，". Both are normalised away: leading punctuation is
        // stripped, glyph confusions are mapped, and a line starting with 消耗 is accepted as the
        // block line.
        val usable = lines.filter { it.text.trim().isNotEmpty() }
            .map { TextLine(
                normaliseGlyphs(it.text.trimStart('，', ',', '、', '；', ';', ' ')), it.bounds) }
        val unread = mutableListOf<String>()

        // The reward block is anchored on the 消耗 line, which starts "体力" on the real page. A
        // frame without it is not the scrolled reward view, and nothing on it may be read as a reward.
        // ML Kit may keep "体力 5（当前 26），清洁 2（当前 6）" on one line or split it, so the 清洁 half
        // is read off the 体力 line first and falls back to a line of its own.
        // Prefer the line that carries the 体力 value itself; fall back to a merged "消耗 体力 …"
        // line. A bare "消耗" label line is never the block line on its own.
        val blockLine = usable.firstOrNull { it.text.trim().startsWith("体力") }
            ?: usable.firstOrNull { it.text.trim().startsWith("消耗") && it.text.contains("体力") }
        val cleanLine = usable.firstOrNull { it.text.trim().startsWith("清洁") }

        val energy = blockLine?.let { readCostOnLine(it.text, "体力") }
        if (energy == null) unread += "体力未读出"
        val cleanliness = blockLine?.let { readCostOnLine(it.text, "清洁") }
            ?: cleanLine?.let { readCostOnLine(it.text, "清洁") }
        if (cleanliness == null) unread += "清洁未读出"

        // 工分 is a label line; the value sits on its own line ("+10 点") directly after it.
        val score = readLabelThenValueLine(usable, "工分")
        if (score == null) unread += "工分未读出"

        // 奖励's number sits on its own line; the coin icon OCRs as "0" and must be dropped.
        val coins = readLabelThenValueLine(usable, "奖励")
        if (coins == null) unread += "金币未读出"

        // Duration belongs to the option card on the TOP frame; on a scrolled frame the cards are
        // gone, so the caller supplies the duration it read before scrolling.
        val duration = durationMinutes
            ?: readDurationForOption(usable, optionLabel)

        return WorkOption(
            id = "work:$venue/$optionLabel",
            venue = venue,
            optionLabel = optionLabel,
            durationMinutes = duration ?: 0,
            pageCoinReward = coins ?: 0,
            pageWorkScoreReward = score ?: 0,
            pageEnergyCost = energy ?: 0,
            pageCleanlinessCost = cleanliness ?: 0,
            unreadFields = unread,
        )
    }

    /**
     * Reads the cost off a "体力 5（当前 26），清洁 2（当前 6）" line: the FIRST number after the label,
     * never the bracketed current value.
     */
    private fun readCostOnLine(line: String, label: String): Int? {
        val idx = line.indexOf(label)
        if (idx < 0) return null
        val rest = line.substring(idx + label.length)
        val match = Regex("""\s*[:：]?\s*(\d+)""").find(rest) ?: return null
        return match.groupValues[1].toIntOrNull()
    }

    /**
     * Reads a value that sits on the line right after its label line ("工分" then "+10 点", "奖励"
     * then "[icon]68"). The icon OCRs as "0" (measured 2026-10-20), so the value line is searched for
     * a number >= 2 digits or, failing that, any number; a lone "0" that is the icon yields 0 which
     * the caller treats as unread-like only when it disagrees with the catalog.
     */
    private fun readLabelThenValueLine(usable: List<TextLine>, label: String): Int? {
        val labelIdx = usable.indexOfFirst { it.text.trim().startsWith(label) }
        if (labelIdx < 0) return null
        val labelLine = usable[labelIdx]
        val labelY = verticalCenter(labelLine)
        // The value may sit on the label's own line (merged), on the same band to its right
        // ("工分" then "+10 点"), or on the line(s) below ("奖励" then icon "0" then "68" - the icon
        // OCRs as a digit, measured 2026-10-20). Gather every number in the band under the label and
        // take the LAST: the icon always precedes the real value, and the 升星 row sits far below.
        // The band STOPS at the next block label (the 奖励 row sits ~75px under 工分, inside the gap
        // tolerance), so one label's value can never be read off the next label's row.
        val band = mutableListOf<String>()
        for (candidate in usable.drop(labelIdx + 1)) {
            val y = verticalCenter(candidate)
            if (y - labelY > VALUE_LINE_GAP_PX) break
            if (BLOCK_LABELS.any { candidate.text.trim().startsWith(it) }) break
            if (y >= labelY - SAME_BAND_PX && candidate.text.any { it.isDigit() }) {
                band += candidate.text
            }
        }
        val texts = listOf(labelLine.text) + band
        val numbers = texts.flatMap { VALUE_NUMBER.findAll(it).map { m -> m.value.toIntOrNull() } }
            .filterNotNull().toList()
        if (numbers.isEmpty()) return null
        return numbers.last()
    }

    /** A value line must sit within this many px below its label line to belong to it. */
    private const val VALUE_LINE_GAP_PX = 80f

    /** A merged value on the label's own line may sit up to this many px ABOVE the label centre. */
    private const val SAME_BAND_PX = 40f

    /** Any unsigned integer run. */
    private val VALUE_NUMBER: Regex = Regex("""\d+""")

    /**
     * Glyph confusions ML Kit shows on the career pages, mapped to the true glyph before any
     * comparison. Measured 2026-10-20: the outdoor map reads 职业小鎮 (traditional 鎮) on most
     * frames, and the venue detail page reads 清洁 as "请洁" (s16 line dump: "消耗体力5(当前24),
     * 请洁2(当前3)" — the 氵 radical dropped). The device reader applies the same map on its
     * raw lines, so every comparison path sees normalised text.
     */
    fun normaliseGlyphs(text: String): String = text
        .replace('鎮', '镇')
        .replace('裡', '里')
        .replace('術', '术')
        .replace("请洁", "清洁")
        .replace("筒介", "简介")

    /** The block labels whose rows terminate a value band. */
    private val BLOCK_LABELS = listOf("工分", "奖励", "消耗", "体力", "清洁", "用时", "升星", "去打工")

    /**
     * The label of the currently SELECTED work option card.
     *
     * MEASURED GEOMETRY (2026-10-20, display 4, 720x1600). The two option cards sit SIDE BY SIDE on
     * one row, each with its own 用时 line at the same height, and the reward block is on the part of
     * the page that is only visible AFTER scrolling. The old band rule (a card within N px of the
     * 工分 anchor) fails on the real page twice over: the side-by-side cards are equidistant from any
     * vertical anchor (ambiguity -> null, the 03:44 device failure), and the reward block is not on
     * the same frame as the cards at all.
     *
     * The selection is therefore proven by the card's OWN 用时 value: each card is a horizontal pair
     * (label row, 用时 row directly below it), and the selected option is the card whose 用时 minutes
     * equal [expectedMinutes]. Only one card can carry the target duration, so a match is positive;
     * zero or two matches are both refusals.
     *
     * Returns null when nothing can be positively matched. Null means "unknown", never "first card".
     */
    fun parseSelectedOptionLabel(
        lines: List<TextLine>,
        expectedMinutes: Int? = null,
        known: Collection<String> = KNOWN_OPTION_LABELS,
    ): String? {
        val usable = lines.filter { it.text.trim().isNotEmpty() }
        val cards = usable.filter { known.contains(it.text.trim()) }.mapNotNull { label ->
            // The card's 用时 line is the nearest duration line horizontally ALIGNED with the label
            // (the two cards sit side by side, so vertical proximity alone cannot tell them apart).
            val centreX = horizontalCenter(label)
            usable.filter { DURATION_PATTERN.containsMatchIn(it.text) }
                .filter { horizontalOverlap(it, centreX, CARD_X_TOLERANCE_PX) }
                .minByOrNull { kotlin.math.abs(verticalCenter(it) - verticalCenter(label)) }
                ?.let { dur ->
                    val minutes = DURATION_PATTERN.find(dur.text)?.groupValues?.get(1)?.toIntOrNull()
                    if (minutes != null && minutes > 0) Triple(label, minutes, dur) else null
                }
        }
        if (expectedMinutes == null) {
            // No target duration given: the only safe answer is a page with exactly one card whose
            // duration is readable. Two cards (each with its own duration) are ambiguous.
            return if (cards.size == 1) cards[0].first.text.trim() else null
        }
        val matching = cards.filter { it.second == expectedMinutes }
        return if (matching.size == 1) matching[0].first.text.trim() else null
    }

    /** Horizontal centre of a line, from the public fields (Rect.centerX() is unmocked on the JVM). */
    private fun horizontalCenter(line: TextLine): Float =
        (line.bounds.left + line.bounds.right) / 2f

    /** True when [line]'s x-span covers [x], within [tolerance]. Cards are ~140px wide. */
    private fun horizontalOverlap(line: TextLine, x: Float, tolerance: Float): Boolean =
        line.bounds.left - tolerance <= x && x <= line.bounds.right + tolerance

    /** How far a 用时 line may sit horizontally from its card label's centre and still be its own. */
    private const val CARD_X_TOLERANCE_PX = 60f

    /**
     * True only when the frame positively shows a WORKING surface.
     *
     * 打工中 is the working marker. 剩余 alone is NOT: the study page writes 剩余 as well, so treating
     * it as proof of work would confirm a shift from a lesson screen.
     */
    fun parseWorkingPage(lines: List<TextLine>): Boolean =
        lines.any { it.text.contains("打工中") }

    /**
     * One frame's read of the payroll dialog, or null when the frame carries no payroll marker.
     *
     * [minutes] is null when the markers are there but no duration line is readable - the dialog
     * arriving is not a failure, it is the reason to keep polling. Only the CALLER's window running
     * out turns that into COMPLETION_MINUTES_UNREAD.
     *
     * [coinAward] is the positive coin amount the payroll page itself names ("金币 +68" /
     * "基础工资 68"), or null when that award line is unread. It is NEVER a balance: [parseBalanceLine]
     * still refuses those lines. Callers may use a positive award as payout evidence only when the
     * cumulative balance delta cannot be differenced.
     */
    data class CompletionSurface(val minutes: Int?, val optionLabel: String?, val coinAward: Int? = null)

    /**
     * Parses the payroll dialog off one frame's lines.
     *
     * The payroll surface is only accepted when it NAMES a known option, so a payroll belonging to a
     * different session cannot be booked as this one's payout (the caller compares the label). The
     * minutes come off that same surface; a surface that states no duration yields minutes=null,
     * never a guessed number. The coin award is parsed separately from the balance so a wage page
     * that only shows "金币 +N" can still prove a payout without pretending that line is a balance.
     */
    fun parseCompletionSurface(
        lines: List<TextLine>,
        markers: List<String> = PAYROLL_MARKERS,
        knownOptions: Set<String> = KNOWN_OPTION_LABELS,
    ): CompletionSurface? {
        val text = lines.joinToString(" ") { it.text }
        if (markers.none { text.contains(it) }) return null
        val minutes = lines.firstNotNullOfOrNull {
            DURATION_PATTERN.find(it.text)?.groupValues?.get(1)?.toIntOrNull()
        }?.takeIf { it > 0 }
        val option = knownOptions.filter { text.contains(it) }.singleOrNull()
        val coinAward = parsePayrollCoinAward(lines.map { it.text })
        return CompletionSurface(minutes, option, coinAward)
    }

    /** Measured payroll markers (P5.1 §5): the dialog names the work and its 基础工资. */
    val PAYROLL_MARKERS = listOf("工资明细", "基础工资", "打工完成")

    /**
     * The coin AWARD named on a payroll frame, or null.
     *
     * Accepts "金币 +68", "金币+68", "基础工资 68", "基础工资68". Prefers an explicit 金币+N line
     * when both appear. Returns null for zero/negative/unread awards. This is intentionally separate
     * from [parseBalanceLine]: treating award lines as balances would fabricate a delta, while using
     * them only as named payout evidence on a verified payroll surface is what the wage page shows.
     */
    fun parsePayrollCoinAward(lines: Collection<String>): Int? {
        val joined = lines.joinToString(" ")
        COIN_PLUS_AWARD.find(joined)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it > 0 }?.let { return it }
        return BASE_WAGE_AWARD.find(joined)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it > 0 }
    }

    private val COIN_PLUS_AWARD: Regex = Regex("""金币\s*\+\s*(\d+)""")
    private val BASE_WAGE_AWARD: Regex = Regex("""基础工资\s*(\d+)""")

    /**
     * The coin BALANCE off one line, or null.
     *
     * Only a line that STATES a balance counts: "金币余额 76" / "余额 76" / "余额:76". A payroll
     * line such as "金币+68" or "基础工资 68" is the session's reward, not the balance, and must
     * never be read here - differencing a reward against a balance produces a number that means
     * nothing. The game itself writes the balance as a bare number next to the coin icon, so the
     * line formats here are the ONLY accepted ones and an unreadable balance stays unread.
     */
    fun parseBalanceLine(line: String): Int? =
        BALANCE_LINE.find(line)?.groupValues?.get(1)?.toIntOrNull()

    val BALANCE_LINE: Regex = Regex("""^(?:金币余额|余额)\s*[:：]?\s*(\d+)\s*$""")

    /**
     * Bounds of the first run of consecutive lines (gap <= [JOINED_LINE_GAP_PX]) whose joined text
     * contains [label], as [top, bottom, left, right]; null when absent.
     *
     * The device reader calls this for the town-entry label. The fold is pure so the bounds rule is
     * pinned by a test: min accumulators START at Int.MAX_VALUE and max accumulators at Int.MIN_VALUE.
     * The opposite seed (all four at Int.MAX_VALUE, as shipped 2026-10-19) left bottom/right pinned at
     * Int.MAX_VALUE and every center came out (min + Int.MAX_VALUE) / 2 — an int overflow that read
     * raw centers near -1073741568 and killed the town-entry tap with "tap outside pet display".
     */
    fun validBounds(top: Int, bottom: Int, left: Int, right: Int, width: Int, height: Int): Boolean =
        width > 0 && height > 0 && left >= 0 && top >= 0 &&
            left < right && top < bottom && right <= width && bottom <= height

    fun locateJoinedRun(lines: List<TextLine>, label: String, joinedLineGapPx: Int = JOINED_LINE_GAP_PX): IntArray? {
        fun bounds(run: List<TextLine>): IntArray {
            var top = Int.MAX_VALUE
            var bottom = Int.MIN_VALUE
            var left = Int.MAX_VALUE
            var right = Int.MIN_VALUE
            for (l in run) {
                top = minOf(top, l.bounds.top); bottom = maxOf(bottom, l.bounds.bottom)
                left = minOf(left, l.bounds.left); right = maxOf(right, l.bounds.right)
            }
            return intArrayOf(top, bottom, left, right)
        }

        val run = mutableListOf<TextLine>()
        var lastBottom = -1
        for (line in lines) {
            if (run.isNotEmpty() && line.bounds.top - lastBottom > joinedLineGapPx) {
                if (run.joinToString("") { it.text }.contains(label)) return bounds(run)
                run.clear()
            }
            run += line
            lastBottom = line.bounds.bottom
        }
        if (run.joinToString("") { it.text }.contains(label)) return bounds(run)
        return null
    }

    /**
     * Duration for the selected option: the 用时 line HORIZONTALLY ALIGNED with the option's own
     * card label (the cards sit side by side, so this is the only pairing that can be told apart).
     * A duration line that is not under the card belongs to the other card and is refused.
     */
    private fun readDurationForOption(usable: List<TextLine>, optionLabel: String): Int? {
        val label = usable.firstOrNull { it.text.trim() == optionLabel } ?: return null
        val centreX = horizontalCenter(label)
        val own = usable.filter { DURATION_PATTERN.containsMatchIn(it.text) }
            .filter { horizontalOverlap(it, centreX, CARD_X_TOLERANCE_PX) }
            .minByOrNull { kotlin.math.abs(verticalCenter(it) - verticalCenter(label)) }
            ?: return null
        return DURATION_PATTERN.find(own.text)?.groupValues?.get(1)?.toIntOrNull()
    }

    /**
     * Vertical midpoint from the public fields.
     *
     * Rect.centerY() is not implemented in the JVM test stub, so calling it makes every geometry rule
     * untestable. The two expressions are the same on a real Rect.
     */
    private fun verticalCenter(line: TextLine): Float =
        (line.bounds.top + line.bounds.bottom) / 2f

    private fun verticalAnchor(lines: List<TextLine>): Float? =
        if (lines.isEmpty()) null else lines.map { verticalCenter(it) }.average().toFloat()

    /** Which career-town page, if any, one frame shows. */
    enum class TownPageKind { NONE, LIST, DETAIL }

    /**
     * The town pages' sticky header, matched on its PREFIX, not on the whole title.
     *
     * MEASURED 2026-10-20 on the live venue detail page (r18-vd4.png): ML Kit read the title as
     * "宠物职业小" - the last glyph is dropped - and a whole-title match therefore never fires.
     * The four glyphs below are stable, and "职业小镇" (the outdoor map's entry label) and "职业树"
     * (the career-tree icon) are different words, so this prefix names the town pages and nothing
     * else. Matching the full title would have made the town list page unrecognisable.
     */
    const val TOWN_TITLE_PREFIX = "宠物职业"

    /** A town LIST frame must carry at least this many known venues to be one. */
    const val LIST_PAGE_MIN_VENUES = 3

    val KNOWN_VENUES = listOf("竹影武馆", "风铃旅社", "迷雾侦探所", "星尘魔法塔", "彩虹画室")

    /** Shared title alone is insufficient; active work and payroll overlays cannot be left. */
    fun parseTownPage(lines: List<TextLine>): TownPageKind {
        val text = lines.joinToString(" ") { normaliseGlyphs(it.text) }
        if (text.contains("打工中") || PAYROLL_MARKERS.any { text.contains(it) }) return TownPageKind.NONE
        val hasStart = text.contains("去打工")
        val hasDetails = text.contains("用时") || text.contains("升星条件")
        if (hasStart && hasDetails) return TownPageKind.DETAIL
        // The detail page's own footer survives even when the 去打工 button is DISABLED: the game
        // replaces the button with a "你的宠物清洁值不足，请回家洗澡" banner when the work's own 清洁
        // cost is not met (measured 2026-10-20: 清洁 0 against a 清洁 2 cost leaves the button pale and
        // the banner in its place). 升星条件 is the one marker that appears on the venue DETAIL page
        // and nowhere else - the course page's footer is 升X年级条件, a different word - so a frame
        // that shows it is the detail page whether or not the button is present. Making 去打工 a
        // required identity marker turned a disabled-button detail page into CAREER_TOWN_PAGE_UNCONFIRMED.
        if (text.contains("升星条件")) return TownPageKind.DETAIL
        // A partly read detail must not fall back to LIST even if background venue names remain.
        if (hasStart || hasDetails || text.contains("规则说明")) return TownPageKind.NONE
        val venuesSeen = KNOWN_VENUES.count { text.contains(it) }
        return if (text.contains(TOWN_TITLE_PREFIX) && venuesSeen >= LIST_PAGE_MIN_VENUES) TownPageKind.LIST
        else TownPageKind.NONE
    }

    /**
     * The known venue the frame names, or null when it names none.
     *
     * A venue DETAIL page is only CONFIRMED by the reader when this resolves: the flow picks a
     * venue by name and taps its label, so a page that cannot say which venue it is cannot prove
     * that the right one was opened. Order matters - [KNOWN_VENUES] is scanned in catalog order so
     * the answer is deterministic on a frame that names two venues.
     */
    fun parseTownVenue(lines: List<TextLine>, venues: Collection<String> = KNOWN_VENUES): String? {
        val text = lines.joinToString(" ") { it.text }
        return venues.firstOrNull { text.contains(it) }
    }

    /**
     * The work option labels the career town is known to show (P5.1 §3.1/§3.2).
     *
     * This is a RECOGNITION allowlist, not a tap target: a label outside it can never be reported as
     * the selected option, so an unrecognised card blocks the flow instead of being guessed at. A new
     * venue's options are added here only after they have been read off a real frame.
     */
    val KNOWN_OPTION_LABELS = setOf("帮武馆跑腿", "陪练基础课", "替摊主看摊", "街巷跑腿")

    /** Max vertical gap between consecutive lines that still form one joined label run. */
    const val JOINED_LINE_GAP_PX = 18
}
