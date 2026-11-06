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
