package com.ynk.virtualdisplay.automation

import android.graphics.PointF

/**
 * 每日签到画面的文本解析器（SOC-03）：
 * 识别房间内的签到入口、已领取状态、签到弹窗确认以及奖励内容提取。
 */
object SignInParser {

    private val SIGN_IN_ENTRY_KEYWORDS = listOf("签到", "每日签到", "福利", "每日福利", "签到领奖")
    private val ALREADY_SIGNED_KEYWORDS = listOf("已签到", "今日已签", "明日再来", "明日签到", "已领取", "明日可领", "今日已领")
    private val CLAIM_BUTTON_LABELS = listOf("立即签到", "签到", "领取", "免费领取", "点击签到", "开心收下", "收下奖励")
    private val DISMISS_BUTTON_LABELS = listOf("我知道了", "确定", "关闭", "收下", "好的", "X")
    private val SIGN_IN_DIALOG_KEYWORDS = listOf("每日签到", "签到成功", "七日签到", "签到奖励", "今日签到", "福利签到")

    data class SignInRewardResult(
        val summary: String,
        val coinAward: Int? = null,
        val items: List<String> = emptyList(),
    )

    private fun point(x: Float, y: Float): PointF = PointF().apply {
        this.x = x
        this.y = y
    }

    /** 从房间页面识别「签到」入口按钮 */
    fun locateSignInEntry(lines: List<TextLine>, width: Int = 720, height: Int = 1600): PointF? {
        for (kw in SIGN_IN_ENTRY_KEYWORDS) {
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

    /** 检查当前是否已签到（在房间入口旁或弹窗内） */
    fun isAlreadySignedIn(lines: List<TextLine>): Boolean {
        val joined = lines.joinToString(";") { it.text }
        return ALREADY_SIGNED_KEYWORDS.any { joined.contains(it) }
    }

    /** 判断当前是否已打开签到弹窗 */
    fun isSignInDialog(lines: List<TextLine>): Boolean {
        val joined = lines.joinToString(";") { it.text }
        return SIGN_IN_DIALOG_KEYWORDS.any { joined.contains(it) }
    }

    /** 定位签到/领取按钮 */
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

    /** 定位弹窗关闭/我知道了按钮 */
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

    /** 解析签到奖励弹窗内容 */
    fun parseSignInReward(lines: List<TextLine>): SignInRewardResult {
        var coins: Int? = null
        val items = mutableListOf<String>()

        for (line in lines) {
            val text = line.text.filterNot { it.isWhitespace() }
            if (text.contains("金币")) {
                val match = Regex("""(?:金币[+＋]?|[+＋])(\d+)|\b(\d+)金币""").find(text)
                if (match != null) {
                    coins = (match.groupValues[1].takeIf { it.isNotEmpty() } ?: match.groupValues[2]).toIntOrNull()
                }
            } else if (text.contains("获得") || text.contains("道具") || text.contains("经验") || text.contains("饼干") || text.contains("香皂")) {
                items.add(text)
            }
        }

        val summary = lines.firstOrNull { l -> SIGN_IN_DIALOG_KEYWORDS.any { l.text.contains(it) } }?.text ?: "签到完成"
        return SignInRewardResult(summary = summary, coinAward = coins, items = items)
    }
}
