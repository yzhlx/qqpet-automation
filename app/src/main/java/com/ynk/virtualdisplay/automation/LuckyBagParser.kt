package com.ynk.virtualdisplay.automation

import android.graphics.PointF

/**
 * 宠物福袋任务文本行解析器（SOC-05）：
 * 识别房间福袋入口、开启冷却/上限检测、福袋弹窗、开福袋按钮及奖励解析。
 */
object LuckyBagParser {

    private val LUCKY_BAG_ENTRY_KEYWORDS = listOf("福袋", "幸运福袋", "开福袋", "每日福袋", "惊喜福袋", "福利福袋")
    private val LIMIT_REACHED_KEYWORDS = listOf("今日已满", "上限已满", "次数已用完", "明日再来", "明日可领", "已开完", "今日已领完", "今日上限")
    private val CLAIM_BUTTON_LABELS = listOf("开福袋", "拆福袋", "立即开启", "开启", "免费开启", "免费开", "开启福袋", "领取", "开心收下", "收下奖励")
    private val DISMISS_BUTTON_LABELS = listOf("关闭", "确定", "返回", "我知道了", "收下", "好的", "X")
    private val LUCKY_BAG_DIALOG_KEYWORDS = listOf("福袋", "幸运福袋", "开启福袋", "福袋奖励", "恭喜获得", "开福袋")

    data class LuckyBagRewardResult(
        val summary: String,
        val coinAward: Int? = null,
        val items: List<String> = emptyList(),
    )

    private fun point(x: Float, y: Float): PointF = PointF().apply {
        this.x = x
        this.y = y
    }

    /** 从房间页面识别「福袋」入口按钮 */
    fun locateLuckyBagEntry(lines: List<TextLine>, width: Int = 720, height: Int = 1600): PointF? {
        for (kw in LUCKY_BAG_ENTRY_KEYWORDS) {
            val hit = WorkPageParser.locateJoinedRun(lines, kw)
            if (hit != null) {
                val (top, bottom, left, right) = hit
                if (WorkPageParser.validBounds(top, bottom, left, right, width, height)) {
                    return point((left + right) / 2f, (top + bottom) / 2f)
                }
            }
        }
        return null
    }

    /** 检查是否已达今日福袋上限 */
    fun isLimitReached(lines: List<TextLine>): Boolean {
        val joined = lines.joinToString(";") { it.text }
        if (LIMIT_REACHED_KEYWORDS.any { joined.contains(it) }) return true
        if (Regex("""(?:剩余\s*0\s*次|0\s*/\s*[1-9]\d*)""").containsMatchIn(joined)) return true
        return false
    }

    /**
     * 解析冷却倒计时剩余秒数。
     * 如 "05:20" -> 320 秒，"5分20秒" -> 320 秒，"30秒" -> 30 秒。若无冷却则返回 null。
     */
    fun parseCooldownSeconds(lines: List<TextLine>): Long? {
        for (line in lines) {
            val text = line.text.filterNot { it.isWhitespace() }
            val mmssMatch = Regex("""(?:倒计时|冷却)?\s*(\d{1,2}):(\d{2})""").find(text)
            if (mmssMatch != null) {
                val m = mmssMatch.groupValues[1].toLongOrNull() ?: 0L
                val s = mmssMatch.groupValues[2].toLongOrNull() ?: 0L
                return m * 60L + s
            }
            val textMatch = Regex("""(\d+)\s*分(?:钟)?(?:\s*(\d+)\s*秒)?""").find(text)
            if (textMatch != null) {
                val m = textMatch.groupValues[1].toLongOrNull() ?: 0L
                val s = textMatch.groupValues[2].takeIf { it.isNotEmpty() }?.toLongOrNull() ?: 0L
                return m * 60L + s
            }
            val secMatch = Regex("""(\d+)\s*秒(?:后|可开启)?""").find(text)
            if (secMatch != null) {
                return secMatch.groupValues[1].toLongOrNull()
            }
        }
        return null
    }

    /** 解析今日剩余可开次数 */
    fun parseRemainingCount(lines: List<TextLine>): Int? {
        for (line in lines) {
            val text = line.text.filterNot { it.isWhitespace() }
            val match = Regex("""(?:剩余\s*(\d+)\s*次|(\d+)\s*/\s*\d+)""").find(text)
            if (match != null) {
                val countStr = match.groupValues[1].takeIf { it.isNotEmpty() } ?: match.groupValues[2]
                val count = countStr.toIntOrNull()
                if (count != null) return count
            }
        }
        return null
    }

    /** 判断当前是否已打开福袋弹窗 */
    fun isLuckyBagDialog(lines: List<TextLine>): Boolean {
        val joined = lines.joinToString(";") { it.text }
        return LUCKY_BAG_DIALOG_KEYWORDS.any { joined.contains(it) }
    }

    /** 定位开启/拆福袋/领取按钮 */
    fun locateClaimButton(lines: List<TextLine>, width: Int = 720, height: Int = 1600): PointF? {
        for (label in CLAIM_BUTTON_LABELS) {
            val hit = WorkPageParser.locateJoinedRun(lines, label)
            if (hit != null) {
                val (top, bottom, left, right) = hit
                if (WorkPageParser.validBounds(top, bottom, left, right, width, height)) {
                    return point((left + right) / 2f, (top + bottom) / 2f)
                }
            }
        }
        return null
    }

    /** 定位关闭/我知道了按钮 */
    fun locateDismissButton(lines: List<TextLine>, width: Int = 720, height: Int = 1600): PointF? {
        for (label in DISMISS_BUTTON_LABELS) {
            val hit = WorkPageParser.locateJoinedRun(lines, label)
            if (hit != null) {
                val (top, bottom, left, right) = hit
                if (WorkPageParser.validBounds(top, bottom, left, right, width, height)) {
                    return point((left + right) / 2f, (top + bottom) / 2f)
                }
            }
        }
        return null
    }

    /** 解析福袋奖励弹窗内容 */
    fun parseLuckyBagReward(lines: List<TextLine>): LuckyBagRewardResult {
        var coins: Int? = null
        val items = mutableListOf<String>()

        for (line in lines) {
            val text = line.text.filterNot { it.isWhitespace() }
            if (text.contains("金币")) {
                val match = Regex("""(?:金币[+＋]?|[+＋])(\d+)|\b(\d+)金币""").find(text)
                if (match != null) {
                    coins = (match.groupValues[1].takeIf { it.isNotEmpty() } ?: match.groupValues[2]).toIntOrNull()
                }
            } else if (!LUCKY_BAG_DIALOG_KEYWORDS.any { text.contains(it) } && !text.contains("恭喜") &&
                (text.contains("道具") || text.contains("经验") || text.contains("饼干") || text.contains("香皂") || text.contains("礼包") || text.contains("获得"))) {
                items.add(text)
            }
        }

        val summary = lines.firstOrNull { l -> LUCKY_BAG_DIALOG_KEYWORDS.any { l.text.contains(it) } }?.text ?: "福袋开启完成"
        return LuckyBagRewardResult(summary = summary, coinAward = coins, items = items)
    }

    /** SOC-05：读到金币、道具或已达今日上限，才算这次福袋有结果；读不到就是未知。 */
    fun claimConfirmed(lines: List<TextLine>, reward: LuckyBagRewardResult): Boolean =
        reward.coinAward != null || reward.items.isNotEmpty() || isLimitReached(lines)
}
