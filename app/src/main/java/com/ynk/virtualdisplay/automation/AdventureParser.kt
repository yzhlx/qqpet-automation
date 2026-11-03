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

    /** 判断当前页面是否为冒险页面 */
    fun isAdventurePage(lines: List<TextLine>): Boolean {
        val texts = lines.map { it.text.filterNot { c -> c.isWhitespace() } }
        return texts.any { it.contains("附近走走") || it.contains("诗和远方") || it.contains("冒险") || it.contains("探索之旅") }
    }

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
