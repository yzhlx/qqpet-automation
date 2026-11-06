package com.ynk.virtualdisplay.automation

import android.graphics.PointF

/**
 * 冒险画面的文本行解析器（CORE-11）：
 * 包含户外地图入口定位、冒险大厅页面识别、模式选择、出发按钮定位以及结算弹窗提取。
 */
object AdventureParser {

    const val ADVENTURE_ENTRY_LABEL = "冒险"
    private val START_BUTTON_LABELS = listOf("开始冒险", "立即出发", "出发", "开始探索", "前往探索")
    private val SETTLEMENT_KEYWORDS = listOf("冒险归来", "冒险结算", "获得奖励", "奇遇结算", "探索成功", "收获")
    private val LIMIT_KEYWORDS = listOf("今日次数已达上限", "次数已用尽", "体力不足", "正在冒险中", "冷却中", "明日再来", "上限已满", "无法冒险", "次数不足")

    private fun point(x: Float, y: Float): PointF = PointF().apply {
        this.x = x
        this.y = y
    }

    data class SettlementResult(
        val summary: String,
        val coinAward: Int? = null,
        val items: List<String> = emptyList(),
    )

    /** 从户外地图识别「冒险」建筑入口 */
    fun locateAdventureEntry(lines: List<TextLine>, width: Int = 720, height: Int = 1600): PointF? {
        val hit = WorkPageParser.locateJoinedRun(lines, ADVENTURE_ENTRY_LABEL) ?: return null
        val (top, bottom, left, right) = hit
        if (!WorkPageParser.validBounds(top, bottom, left, right, width, height)) return null
        return point((left + right) / 2f, (top + bottom) / 2f)
    }

    /**
     * 判断当前页面是否为冒险面板。
     *
     * 户外地图自己就带「冒险」标签，所以单有「冒险」二字不能证明面板已打开（点击落空时地图仍在）。
     * 面板必有两张模式卡（附近走走 / 诗和远方）；OCR 漏读卡片时，才退而要求标题与面板里的
     * 其它字（规则说明 / 出发按钮）同时出现。
     */
    fun isAdventurePage(lines: List<TextLine>): Boolean {
        val texts = lines.map { it.text.filterNot { c -> c.isWhitespace() } }
        if (texts.any { it.contains("附近走走") || it.contains("诗和远方") }) return true
        val title = texts.any { it.contains("冒险") || it.contains("探索之旅") }
        val panelText = texts.any { t -> t.contains("规则说明") || START_BUTTON_LABELS.any { t.contains(it) } }
        return title && panelText
    }

    /**
     * What the outdoor map shows while the pet is out adventuring.
     * [events] is the panel's log, newest first, without the time stamps.
     */
    data class Progress(val inProgress: Boolean, val remainingSec: Int?, val events: List<String>)

    private val REMAINING = Regex("""剩余\|?(\d{1,2})[:：.](\d{2})[:：.](\d{2})""")
    private val TIME_ONLY = Regex("""^\d{1,2}[:：]\d{2}$""")
    private val LEADING_TIME = Regex("""^\d{1,2}\s*[:：]\s*\d{2}\s*""")
    private val EVENT_NOISE = listOf("剩余", "召回", "正在冒险", "冒险中")
    private val FOUND_WORDS = listOf("捡到", "获得", "拾得", "得到", "收到")

    /**
     * MEASURED 2026-11-07: while out, the map carries a bubble "冒险中" and a bottom panel
     * "正在冒险 / 剩余 00:00:25 / 召回" over a log of "03:37 我捡到了一个袋子！". Any one of those
     * marks the adventure as running, because ML Kit misreads individual labels frame to frame;
     * the pre-start sheet and the room carry none of them.
     */
    fun readProgress(lines: List<TextLine>): Progress {
        val texts = lines.map { it.text.filterNot { c -> c.isWhitespace() } }
        val joined = texts.joinToString("|")
        val remaining = REMAINING.find(joined)?.let {
            it.groupValues[1].toInt() * 3600 + it.groupValues[2].toInt() * 60 + it.groupValues[3].toInt()
        }
        val title = lines.firstOrNull { it.text.contains("正在冒险") }
        val recall = lines.firstOrNull { it.text.filterNot { c -> c.isWhitespace() } == "召回" }
        val running = title != null || recall != null || remaining != null || texts.any { it.contains("冒险中") }
        if (!running) return Progress(false, null, emptyList())
        // The log sits under the panel's title row; without a title the 召回 button marks the same row.
        val panelBottom = (title ?: recall)?.bounds?.bottom
        val events = if (panelBottom == null) emptyList() else lines
            .filter { it.bounds.top > panelBottom }
            .sortedWith(compareBy({ it.bounds.top / WorkPageParser.ROW_BUCKET_PX }, { it.bounds.left }))
            .map { LEADING_TIME.replace(it.text.trim(), "").trim() }
            .filter { it.isNotEmpty() && !TIME_ONLY.matches(it) && EVENT_NOISE.none { n -> it.contains(n) } }
        return Progress(true, remaining, events)
    }

    /** The newest thing the pet did that is worth saying (the "出门了" opener is not). */
    fun summarise(events: List<String>): String =
        events.firstOrNull { !it.startsWith("出门了") } ?: events.firstOrNull() ?: "冒险完成"

    /** Events that name something the pet brought back. */
    fun foundItems(events: List<String>): List<String> =
        events.filter { e -> FOUND_WORDS.any { e.contains(it) } }

    /** Two entry readings agree when they are within this many pixels on both axes. */
    const val ENTRY_AGREE_PX = 12f

    fun sameEntry(a: PointF, b: PointF): Boolean =
        kotlin.math.abs(a.x - b.x) <= ENTRY_AGREE_PX && kotlin.math.abs(a.y - b.y) <= ENTRY_AGREE_PX

    /** 定位目标冒险模式标签（如「附近走走」） */
    fun locateModeOption(lines: List<TextLine>, mode: AdventureMode): PointF? {
        val target = mode.label
        val direct = lines.firstOrNull { it.text.contains(target) }
        if (direct != null) {
            return point((direct.bounds.left + direct.bounds.right) / 2f, (direct.bounds.top + direct.bounds.bottom) / 2f)
        }
        val hit = WorkPageParser.locateJoinedRun(lines, target) ?: return null
        val (top, bottom, left, right) = hit
        return point((left + right) / 2f, (top + bottom) / 2f)
    }

    /** 定位出发/开始冒险按钮 */
    fun locateStartButton(lines: List<TextLine>): PointF? {
        for (label in START_BUTTON_LABELS) {
            val hit = WorkPageParser.locateJoinedRun(lines, label)
            if (hit != null) {
                val (top, bottom, left, right) = hit
                return point((left + right) / 2f, (top + bottom) / 2f)
            }
        }
        return null
    }

    /** 检查是否触发限制、疲劳或冷却（STR-14） */
    fun detectLimitOrCooldown(lines: List<TextLine>): String? {
        val remaining = parseRemainingRounds(lines)
        if (remaining == 0) return "今日剩余次数为0"
        val joined = lines.joinToString(";") { it.text }
        for (kw in LIMIT_KEYWORDS) {
            if (joined.contains(kw)) return kw
        }
        return null
    }

    /** 从冒险页面解析今日剩余探索次数（若有显示） */
    fun parseRemainingRounds(lines: List<TextLine>): Int? {
        for (line in lines) {
            val t = line.text.filterNot { it.isWhitespace() }
            val m1 = Regex("""(?:剩余次数|今日剩余)[：:]?(\d+)""").find(t)
            if (m1 != null) return m1.groupValues[1].toIntOrNull()
            val m2 = Regex("""(\d+)/(\d+)""").find(t)
            if (m2 != null && (t.contains("次") || t.contains("轮") || t.contains("冒险"))) {
                return m2.groupValues[1].toIntOrNull()
            }
        }
        return null
    }

    /** 解析结算弹窗奖励 */
    fun parseSettlement(lines: List<TextLine>): SettlementResult? {
        val joined = lines.joinToString(";") { it.text }
        val isSettlement = SETTLEMENT_KEYWORDS.any { joined.contains(it) }
        if (!isSettlement) return null

        var coins: Int? = null
        val items = mutableListOf<String>()

        for (line in lines) {
            val text = line.text.filterNot { it.isWhitespace() }
            if (text.contains("金币")) {
                val match = Regex("""(?:金币[+＋]?|[+＋])(\d+)""").find(text)
                if (match != null) {
                    coins = match.groupValues[1].toIntOrNull()
                }
            } else if (text.contains("获得") || text.contains("拾得") || text.contains("明信片")) {
                items.add(text)
            }
        }

        return SettlementResult(
            summary = lines.firstOrNull { l -> SETTLEMENT_KEYWORDS.any { l.text.contains(it) } }?.text ?: "冒险完成",
            coinAward = coins,
            items = items,
        )
    }

    /** 定位关闭/确认结算弹窗按钮 */
    fun locateDismissButton(lines: List<TextLine>): PointF? {
        val dismissLabels = listOf("确定", "知道了", "收下", "返回", "好的")
        for (label in dismissLabels) {
            val hit = WorkPageParser.locateJoinedRun(lines, label)
            if (hit != null) {
                val (top, bottom, left, right) = hit
                return point((left + right) / 2f, (top + bottom) / 2f)
            }
        }
        return null
    }
}
