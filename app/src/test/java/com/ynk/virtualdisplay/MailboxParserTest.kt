package com.ynk.virtualdisplay

import android.graphics.Rect
import com.ynk.virtualdisplay.automation.MailboxParser
import com.ynk.virtualdisplay.automation.TextLine
import org.junit.Assert.*
import org.junit.Test

class MailboxParserTest {

    private fun line(text: String, left: Int, top: Int, right: Int, bottom: Int): TextLine {
        val box = Rect()
        box.left = left
        box.top = top
        box.right = right
        box.bottom = bottom
        return TextLine(text, box)
    }

    @Test
    fun locateMailboxEntryFindsEntry() {
        val lines = listOf(
            line("我的房间", 50, 100, 200, 140),
            line("信箱", 600, 400, 700, 450),
        )
        val hit = MailboxParser.locateMailboxEntry(lines)
        assertNotNull(hit)
        assertEquals(650f, hit!!.x, 0.5f)
        assertEquals(425f, hit.y, 0.5f)
    }

    @Test
    fun hasUnreadBadgeDetectsNearbyBadge() {
        val linesWithBadge = listOf(
            line("信箱", 600, 400, 700, 450),
            line("1", 680, 390, 710, 420),
        )
        assertTrue(MailboxParser.hasUnreadBadge(linesWithBadge))

        val linesWithoutBadge = listOf(
            line("信箱", 600, 400, 700, 450),
            line("其他文字", 100, 100, 200, 140),
        )
        assertFalse(MailboxParser.hasUnreadBadge(linesWithoutBadge))
    }

    @Test
    fun isEmptyMailboxRecognizesKeywords() {
        assertTrue(MailboxParser.isEmptyMailbox(listOf(line("暂无邮件", 200, 500, 400, 550))))
        assertTrue(MailboxParser.isEmptyMailbox(listOf(line("空空如也", 200, 500, 400, 550))))
        assertTrue(MailboxParser.isEmptyMailbox(listOf(line("暂无待领", 200, 500, 400, 550))))
        assertFalse(MailboxParser.isEmptyMailbox(listOf(line("系统邮件（1/5）", 200, 500, 400, 550))))
    }

    @Test
    fun locateClaimAllButtonFindsButton() {
        val lines = listOf(
            line("我的信箱", 200, 300, 500, 350),
            line("一键领取", 260, 1100, 460, 1160),
        )
        val claimAll = MailboxParser.locateClaimAllButton(lines)
        assertNotNull(claimAll)
        assertEquals(360f, claimAll!!.x, 0.5f)
        assertEquals(1130f, claimAll.y, 0.5f)
    }

    @Test
    fun parseMailboxRewardExtractsCoinsAndItems() {
        val lines = listOf(
            line("邮件附件奖励", 200, 300, 500, 350),
            line("获得金币+200", 200, 450, 500, 500),
            line("获得饼干x1", 200, 520, 500, 570),
            line("关闭", 260, 900, 460, 960),
        )
        val reward = MailboxParser.parseMailboxReward(lines)
        assertNotNull(reward)
        assertEquals(200, reward.coinAward)
        assertTrue(reward.items.any { it.contains("饼干") })
    }

    /** SOC-04：信箱既没变空、也没读到奖励时，这次领取仍是未知。 */
    @Test
    fun claimConfirmedRequiresEmptyBoxOrReward() {
        val untouched = listOf(line("信箱", 200, 300, 500, 350))
        assertFalse(MailboxParser.claimConfirmed(untouched, MailboxParser.parseMailboxReward(untouched)))
        val emptied = listOf(line("暂无邮件", 200, 300, 500, 350))
        assertTrue(MailboxParser.claimConfirmed(emptied, MailboxParser.parseMailboxReward(emptied)))
        val rewarded = listOf(line("获得金币+200", 200, 300, 500, 350))
        assertTrue(MailboxParser.claimConfirmed(rewarded, MailboxParser.parseMailboxReward(rewarded)))
    }
}
