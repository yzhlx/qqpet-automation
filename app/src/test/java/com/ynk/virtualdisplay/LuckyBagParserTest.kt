package com.ynk.virtualdisplay

import android.graphics.Rect
import com.ynk.virtualdisplay.automation.LuckyBagParser
import com.ynk.virtualdisplay.automation.TextLine
import org.junit.Assert.*
import org.junit.Test

class LuckyBagParserTest {

    private fun line(text: String, left: Int, top: Int, right: Int, bottom: Int): TextLine {
        val box = Rect()
        box.left = left
        box.top = top
        box.right = right
        box.bottom = bottom
        return TextLine(text, box)
    }

    @Test
    fun locateLuckyBagEntryFindsKeywordInRoom() {
        val lines = listOf(
            line("我的房间", 50, 100, 200, 140),
            line("幸运福袋", 550, 250, 680, 310),
            line("每日签到", 550, 350, 680, 410),
        )
        val hit = LuckyBagParser.locateLuckyBagEntry(lines)
        assertNotNull(hit)
        assertEquals(615f, hit!!.x, 0.5f)
        assertEquals(280f, hit.y, 0.5f)
    }

    @Test
    fun locateLuckyBagEntryReturnsNullWhenAbsent() {
        val lines = listOf(
            line("我的房间", 50, 100, 200, 140),
            line("学习", 100, 600, 200, 660),
        )
        assertNull(LuckyBagParser.locateLuckyBagEntry(lines))
    }

    @Test
    fun isLimitReachedDetectsLimitKeywordsAndZeroRounds() {
        val linesLimit = listOf(
            line("福袋奖励", 200, 100, 400, 150),
            line("今日次数已用完，明日再来", 100, 300, 500, 360),
        )
        assertTrue(LuckyBagParser.isLimitReached(linesLimit))

        val linesZero = listOf(
            line("幸运福袋", 200, 100, 400, 150),
            line("剩余 0 次", 250, 400, 350, 440),
        )
        assertTrue(LuckyBagParser.isLimitReached(linesZero))

        val linesNormal = listOf(
            line("幸运福袋", 200, 100, 400, 150),
            line("剩余 3 次", 250, 400, 350, 440),
        )
        assertFalse(LuckyBagParser.isLimitReached(linesNormal))
    }

    @Test
    fun parseCooldownSecondsParsesVariousFormats() {
        val linesMmSs = listOf(
            line("冷却倒计时 05:20 后可开启", 100, 400, 600, 450)
        )
        assertEquals(320L, LuckyBagParser.parseCooldownSeconds(linesMmSs))

        val linesText = listOf(
            line("5分钟20秒", 200, 400, 400, 450)
        )
        assertEquals(320L, LuckyBagParser.parseCooldownSeconds(linesText))

        val linesSec = listOf(
            line("45秒后可开启", 200, 400, 400, 450)
        )
        assertEquals(45L, LuckyBagParser.parseCooldownSeconds(linesSec))

        val linesNoCooldown = listOf(
            line("立即开启福袋", 200, 500, 400, 560)
        )
        assertNull(LuckyBagParser.parseCooldownSeconds(linesNoCooldown))
    }

    @Test
    fun parseRemainingCountExtractsCount() {
        val lines1 = listOf(
            line("今日剩余 4 次", 200, 300, 400, 350)
        )
        assertEquals(4, LuckyBagParser.parseRemainingCount(lines1))

        val lines2 = listOf(
            line("开福袋 2/5", 200, 300, 400, 350)
        )
        assertEquals(2, LuckyBagParser.parseRemainingCount(lines2))
    }

    @Test
    fun locateClaimButtonFindsButton() {
        val lines = listOf(
            line("幸运福袋", 200, 100, 400, 150),
            line("拆福袋", 250, 600, 450, 680),
        )
        val hit = LuckyBagParser.locateClaimButton(lines)
        assertNotNull(hit)
        assertEquals(350f, hit!!.x, 0.5f)
        assertEquals(640f, hit.y, 0.5f)
    }

    @Test
    fun locateDismissButtonFindsButton() {
        val lines = listOf(
            line("恭喜获得", 200, 100, 400, 150),
            line("收下奖励", 200, 500, 400, 560),
            line("我知道了", 200, 700, 400, 760),
        )
        val hit = LuckyBagParser.locateDismissButton(lines)
        assertNotNull(hit)
        assertEquals(300f, hit!!.x, 0.5f)
        assertEquals(730f, hit.y, 0.5f)
    }

    @Test
    fun parseLuckyBagRewardExtractsCoinsAndItems() {
        val lines = listOf(
            line("恭喜获得福袋奖励", 150, 100, 500, 150),
            line("获得金币 +188", 200, 300, 450, 350),
            line("获得道具：体力饼干x2", 150, 380, 500, 430),
        )
        val reward = LuckyBagParser.parseLuckyBagReward(lines)
        assertEquals("恭喜获得福袋奖励", reward.summary)
        assertEquals(188, reward.coinAward)
        assertEquals(1, reward.items.size)
        assertTrue(reward.items[0].contains("饼干"))
    }

    /** SOC-05：只有读到奖励或今日上限，才算这次福袋有结果。 */
    @Test
    fun claimConfirmedRequiresRewardOrLimit() {
        val silent = listOf(line("幸运福袋", 200, 300, 500, 350))
        assertFalse(LuckyBagParser.claimConfirmed(silent, LuckyBagParser.parseLuckyBagReward(silent)))
        val rewarded = listOf(line("获得金币+88", 200, 300, 500, 350))
        assertTrue(LuckyBagParser.claimConfirmed(rewarded, LuckyBagParser.parseLuckyBagReward(rewarded)))
        val limit = listOf(line("今日已满", 200, 300, 500, 350))
        assertTrue(LuckyBagParser.claimConfirmed(limit, LuckyBagParser.parseLuckyBagReward(limit)))
    }
}
