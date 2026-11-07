package com.ynk.virtualdisplay.automation

/**
 * Reads QQ's search page by its TEXT, so the cold-start chain does not depend on where the
 * recent-searches word happens to be drawn.
 *
 * The template probe [PetPageProbes.qqSearchHistoryPet] only knows the first slot of the
 * recent-searches list. QQ keeps that list in last-searched order, so one manual search of
 * anything else pushes "宠物" into the second slot (x≈380) or the next row, and the template then
 * reads the page as unknown. The page itself never changes: it carries 取消, 搜索指定内容, the
 * scope icons and the 最近搜索 heading, and the word is whichever line under them says 宠物.
 *
 * The OCR reading is NOT stable: measured on the real page (2026-11-07 01:58) one frame read
 * 最近搜索 as "最近機素" and 页面设置 as "页面設置". So no single label may decide anything. The page
 * is proven by several independent anchors agreeing, and the heading is only used to place the
 * list when it was read; without it the list is placed from the scope-icon row above it.
 *
 * Pure so it is pinned by host tests; the OCR lines come from [WorkPageReader.readPageLines].
 * Nothing here taps: the caller still has to land on a positively identified page first.
 */
object PetEntryText {
    const val WORD = "宠物"
    private const val CANCEL = "取消"

    /**
     * Labels that only QQ's search landing page carries. The results page shares 取消 and the tab
     * names (小程序, 表情) with it, so those are deliberately NOT anchors.
     */
    private val ANCHORS = listOf("搜索指定内容", "找人/群", "漫剧", "页面设置", "问元宝")

    /** Scope-icon labels: the row that sits right above the recent-searches heading. */
    private val SCOPE_ROW = listOf("找人/群", "漫剧")

    /** The recent-searches list is a few rows under its heading; anything further is another section. */
    private const val LIST_DEPTH_PX = 520

    /** Without a heading the list starts a little under the scope-icon row. */
    private const val BELOW_SCOPE_ROW_PX = 40

    /** Where the heading sits when neither it nor the scope row was read (measured 534-566, 720x1600). */
    private const val DEFAULT_LIST_TOP_PX = 520

    /** ML Kit's known glyph confusions on QQ's plain UI text (traditional forms for simplified ones). */
    private fun clean(text: String): String = text
        .replace("機素", "搜索")
        .replace("搜素", "搜索")
        .replace('設', '设')
        .replace('劇', '剧')
        .replace('問', '问')
        .replace('頁', '页')
        .replace('頻', '频')

    private fun isHeading(line: TextLine): Boolean {
        val t = clean(line.text).trim()
        return t.length <= 6 && t.contains("最近")
    }

    /**
     * QQ's search landing page: 取消 with two of the page's own labels, or three of the labels
     * without 取消 (a misread 取消 must not hide a page that is otherwise plainly this one).
     */
    fun isSearchPage(lines: List<TextLine>): Boolean {
        val texts = lines.map { clean(it.text) }
        val cancel = texts.any { it.contains(CANCEL) }
        val anchors = ANCHORS.count { a -> texts.any { it.contains(a) } } + if (lines.any { isHeading(it) }) 1 else 0
        return (cancel && anchors >= 2) || anchors >= 3
    }

    /** A tap target (frame pixels) on the 宠物 word in the recent-searches list, or null when it is not listed. */
    fun historyWordPoint(lines: List<TextLine>, frameWidth: Int = 720, frameHeight: Int = 1600): Pair<Float, Float>? {
        if (!isSearchPage(lines)) return null
        val listTop = listTop(lines)
        val below = lines.filter {
            it.bounds.top >= listTop - 4 && it.bounds.top <= listTop + LIST_DEPTH_PX && !isHeading(it)
        }
        for (line in below.sortedWith(compareBy({ it.bounds.top }, { it.bounds.left }))) {
            val x = wordCentreX(line) ?: continue
            val y = (line.bounds.top + line.bounds.bottom) / 2f
            // Same rule the work reader applies to every OCR-derived tap: a corrupt box must not
            // turn into a real tap somewhere else.
            if (x < 0f || x > frameWidth || y < 0f || y > frameHeight) continue
            return x to y
        }
        return null
    }

    /** QQ's bottom tab bar names; the message tab is the one to go back to. */
    private const val MESSAGE_TAB = "消息"
    private val OTHER_TABS = listOf("联系人", "频道", "动态", "看点", "小世界")

    /** The tab bar is the bottom ~9% of the frame (measured band 1450-1600 on 720x1600). */
    private const val TAB_BAR_TOP_FRACTION = 0.9f

    /**
     * A tap target on the 消息 label of QQ's bottom tab bar, or null when no tab bar is on screen.
     *
     * Used when QQ was left on another tab (联系人, 动态…): the tab bar is the only thing every such
     * page shares, and tapping a tab opens no conversation and sends nothing. The bar is proven by
     * the 消息 label together with at least one other tab name in the bottom band, so a page that
     * merely contains the word 消息 (a chat, a settings row) is not mistaken for it, and neither is
     * the pet mini-program, which draws no QQ tab bar.
     */
    fun messageTabPoint(lines: List<TextLine>, frameWidth: Int = 720, frameHeight: Int = 1600): Pair<Float, Float>? {
        val bandTop = frameHeight * TAB_BAR_TOP_FRACTION
        val bottom = lines.filter { it.bounds.top >= bandTop }
        val tokens = bottom.flatMap { it.text.trim().split(Regex("\\s+")) }.filter { it.isNotEmpty() }
        if (OTHER_TABS.none { tab -> tokens.any { it.startsWith(tab) } }) return null
        for (line in bottom) {
            val text = line.text.trim()
            val parts = text.split(Regex("\\s+")).filter { it.isNotEmpty() }
            // ML Kit may report the whole bar as one line ("消息 频道 联系人 动态"); then the label is
            // located by its share of the characters, which is plenty for a tab-wide touch target.
            val x = when {
                text == MESSAGE_TAB -> (line.bounds.left + line.bounds.right) / 2f
                parts.size >= 2 && MESSAGE_TAB in parts ->
                    line.bounds.left + (line.bounds.right - line.bounds.left).toFloat() *
                        (text.indexOf(MESSAGE_TAB) + MESSAGE_TAB.length / 2f) / text.length
                else -> continue
            }
            val y = (line.bounds.top + line.bounds.bottom) / 2f
            if (x < 0f || x > frameWidth || y < 0f || y > frameHeight) continue
            return x to y
        }
        return null
    }

    /** The header of QQ's message page names the user's presence ("在线 - WiFi"); no other tab has it. */
    private val PRESENCE_WORDS = listOf("在线", "离线", "隐身", "忙碌", "离开", "勿扰")
    private val NETWORK_WIFI = Regex("(?i)wi-?fi")
    private val NETWORK_MOBILE = Regex("(?i)(^|[^a-z0-9])[345]g($|[^a-z0-9])")

    /** Buttons of a dialog that covers the page: a tap under a modal is never the page's own. */
    private val DIALOG_BUTTONS = listOf(
        "立即体验", "近期不提醒", "我知道了", "暂不升级", "暂不更新", "立即更新", "立即升级", "以后再说",
        "取消", "确定", "允许", "禁止",
    )

    /** The search box shows its hint, optionally behind the magnifier ML Kit reads as a letter ("Q搜索"). */
    private val SEARCH_BOX_TEXT = Regex("^[A-Za-z0-9@©|]{0,2}搜索$")

    /** The message page's header and search box live in the top part of the frame (measured y 90-400 on 720x1600). */
    private const val HEADER_ZONE_FRACTION = 0.25f
    private const val SEARCH_BOX_TOP_FRACTION = 0.05f
    private const val SEARCH_BOX_BOTTOM_FRACTION = 0.40f
    private const val SEARCH_BOX_X_FROM = 0.30f
    private const val SEARCH_BOX_X_TO = 0.70f

    /**
     * A tap target on the search box of QQ's MESSAGE page, read from text, or null.
     *
     * Two templates know the box at y 223-298, but QQ's header grows (a promo banner, notice rows
     * under the box), and measured 2026-11-07 11:01 the box then sat at y≈367 where both templates
     * miss. The page was taken for "another tab", the already selected 消息 tab was tapped, QQ
     * scrolled the list and the box left the screen. The page is the message page when ALL of:
     *  - QQ's tab bar is on screen ([messageTabPoint]), so this is QQ and not a chat or the pet;
     *  - the header carries the user's presence line (在线 - WiFi), which no other tab has: the
     *    contacts page has a 搜索 box too but opens another search;
     *  - no dialog button is on screen;
     *  - a line that reads 搜索 (the box's hint) sits in the upper part of the frame, near the middle.
     * The point is the centre of that line, so it follows the box wherever the header put it.
     */
    fun messageSearchBoxPoint(lines: List<TextLine>, frameWidth: Int = 720, frameHeight: Int = 1600): Pair<Float, Float>? {
        if (messageTabPoint(lines, frameWidth, frameHeight) == null) return null
        val texts = lines.map { clean(it.text) }
        // A button is a short line of its own; a chat preview that merely says "确定" is not a dialog.
        if (texts.any { t -> val s = t.trim(); DIALOG_BUTTONS.any { b -> s.length <= b.length + 2 && s.contains(b) } }) return null
        val headerBottom = frameHeight * HEADER_ZONE_FRACTION
        val presence = lines.any { l ->
            if (l.bounds.top >= headerBottom) return@any false
            val t = clean(l.text)
            PRESENCE_WORDS.any { t.contains(it) } || NETWORK_WIFI.containsMatchIn(t) || NETWORK_MOBILE.containsMatchIn(t)
        }
        if (!presence) return null
        val top = frameHeight * SEARCH_BOX_TOP_FRACTION
        val bottom = frameHeight * SEARCH_BOX_BOTTOM_FRACTION
        val candidates = lines.filter { l ->
            val t = clean(l.text).trim().replace(" ", "")
            val cx = (l.bounds.left + l.bounds.right) / 2f
            val cy = (l.bounds.top + l.bounds.bottom) / 2f
            SEARCH_BOX_TEXT.matches(t) && cy in top..bottom && cx in frameWidth * SEARCH_BOX_X_FROM..frameWidth * SEARCH_BOX_X_TO
        }
        val box = candidates.minByOrNull { it.bounds.top } ?: return null
        val x = (box.bounds.left + box.bounds.right) / 2f
        val y = (box.bounds.top + box.bounds.bottom) / 2f
        if (x < 0f || x > frameWidth || y < 0f || y > frameHeight) return null
        return x to y
    }

    /** The y where the recent-searches entries start: under the heading, else under the scope row, else the default. */
    private fun listTop(lines: List<TextLine>): Int {
        lines.filter { isHeading(it) }.minByOrNull { it.bounds.top }?.let { return it.bounds.bottom }
        val scope = lines.filter { l -> SCOPE_ROW.any { clean(l.text).contains(it) } }
        if (scope.isNotEmpty()) return scope.maxOf { it.bounds.bottom } + BELOW_SCOPE_ROW_PX
        return DEFAULT_LIST_TOP_PX
    }

    /**
     * Horizontal centre of the word inside one OCR line. A line that is exactly the word is its own
     * box. ML Kit may also fold neighbouring list entries into one line ("宠物 QQ邮箱"); then the
     * word is located by its share of the characters, which is good enough for a touch target that
     * is a whole list cell wide. A line where the word is only part of a longer entry ("宠物店")
     * is a different search, not the word.
     */
    private fun wordCentreX(line: TextLine): Float? {
        val text = line.text.trim()
        if (text == WORD) return (line.bounds.left + line.bounds.right) / 2f
        val tokens = text.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (tokens.size < 2 || WORD !in tokens) return null
        val start = text.indexOf(WORD)
        // The word must be a whole token, not the head of a longer one.
        val before = if (start == 0) ' ' else text[start - 1]
        val after = if (start + WORD.length >= text.length) ' ' else text[start + WORD.length]
        if (!before.isWhitespace() || !after.isWhitespace()) return null
        val width = (line.bounds.right - line.bounds.left).toFloat()
        return line.bounds.left + width * (start + WORD.length / 2f) / text.length
    }
}
