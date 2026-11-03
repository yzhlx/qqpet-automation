package com.ynk.virtualdisplay.automation

import android.graphics.PointF

/**
 * 宠物信箱文本行解析器（SOC-04）：
 * 识别房间信箱入口、待领红点标记、信箱弹窗、待领信件及一键领取。
 */
object MailboxParser {

    private val MAILBOX_ENTRY_KEYWORDS = listOf("信箱", "邮件", "邮箱")
    private val EMPTY_MAILBOX_KEYWORDS = listOf("暂无邮件", "没有邮件", "暂无待领", "没有待领取的奖励", "空空如也", "暂无新邮件")
    private val CLAIM_ALL_BUTTON_LABELS = listOf("一键领取", "全部领取", "领取全部", "批量领取", "快速领取")
    private val CLAIM_BUTTON_LABELS = listOf("领取")
    private val DISMISS_BUTTON_LABELS = listOf("关闭", "确定", "返回", "我知道了", "X")
    private val MAILBOX_DIALOG_KEYWORDS = listOf("信箱", "邮件", "我的信箱", "系统邮件", "收件箱")

    data class MailboxRewardResult(
        val summary: String,
        val coinAward: Int? = null,
        val items: List<String> = emptyList(),
    )

    private fun point(x: Float, y: Float): PointF = PointF().apply {
        this.x = x
        this.y = y
    }

    /** 从房间页面识别「信箱」入口 */
    fun locateMailboxEntry(lines: List<TextLine>, width: Int = 720, height: Int = 1600): PointF? {
        for (kw in MAILBOX_ENTRY_KEYWORDS) {
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

    /** 检查信箱旁是否有待领数字或红点标记 */
    fun hasUnreadBadge(lines: List<TextLine>): Boolean {
        val entry = locateMailboxEntry(lines) ?: return false
        return lines.any { line ->
            val t = line.text.trim()
            val cx = (line.bounds.left + line.bounds.right) / 2f
            val cy = (line.bounds.top + line.bounds.bottom) / 2f
            val near = kotlin.math.abs(cx - entry.x) < 80 && kotlin.math.abs(cy - entry.y) < 80
            near && (t.matches(Regex("""\d+""")) || t.contains("!") || t.contains("新") || t.contains("件"))
        }
    }

    /** 判断当前是否已打开信箱弹窗 */
    fun isMailboxDialog(lines: List<TextLine>): Boolean {
        val joined = lines.joinToString(";") { it.text }
        return MAILBOX_DIALOG_KEYWORDS.any { joined.contains(it) }
    }

    /** 检查信箱是否为空 */
    fun isEmptyMailbox(lines: List<TextLine>): Boolean {
        val joined = lines.joinToString(";") { it.text }
        return EMPTY_MAILBOX_KEYWORDS.any { joined.contains(it) }
    }

    /** 定位「一键领取」按钮 */
    fun locateClaimAllButton(lines: List<TextLine>, width: Int = 720, height: Int = 1600): PointF? {
        for (label in CLAIM_ALL_BUTTON_LABELS) {
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

    /** 定位单个「领取」按钮 */
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

    /** 定位关闭/返回按钮 */
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

    /** 解析信箱领取的奖励内容 */
    fun parseMailboxReward(lines: List<TextLine>): MailboxRewardResult {
        var coins: Int? = null
        val items = mutableListOf<String>()

        for (line in lines) {
            val text = line.text.filterNot { it.isWhitespace() }
            if (text.contains("金币")) {
                val match = Regex("""(?:金币[+＋]?|[+＋])(\d+)|\b(\d+)金币""").find(text)
                if (match != null) {
                    coins = (match.groupValues[1].takeIf { it.isNotEmpty() } ?: match.groupValues[2]).toIntOrNull()
                }
            } else if (text.contains("获得") || text.contains("道具") || text.contains("经验") || text.contains("附件") || text.contains("礼物")) {
                items.add(text)
            }
        }

        val summary = lines.firstOrNull { l -> MAILBOX_DIALOG_KEYWORDS.any { l.text.contains(it) } }?.text ?: "信箱奖励领取完成"
        return MailboxRewardResult(summary = summary, coinAward = coins, items = items)
    }
}
