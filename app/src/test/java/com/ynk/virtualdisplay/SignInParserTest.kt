package com.ynk.virtualdisplay

import android.graphics.Rect
import com.ynk.virtualdisplay.automation.SignInParser
import com.ynk.virtualdisplay.automation.TextLine
import org.junit.Assert.*
import org.junit.Test

class SignInParserTest {

    private fun line(text: String, left: Int, top: Int, right: Int, bottom: Int): TextLine {
        val box = Rect()
        box.left = left
        box.top = top
        box.right = right
        box.bottom = bottom
        return TextLine(text, box)
    }

    @Test
    fun locateSignInEntryFindsKeywordInRoom() {
        val lines = listOf(
            line("我的房间", 50, 100, 200, 140),
            line("每日签到", 600, 300, 700, 350),
            line("信箱", 600, 400, 700, 450),
        )
        val hit = SignInParser.locateSignInEntry(lines)
        assertNotNull(hit)
        assertEquals(650f, hit!!.x, 0.5f)
        assertEquals(325f, hit.y, 0.5f)
    }

    @Test
    fun locateSignInEntryReturnsNullWhenAbsent() {
        val lines = listOf(
            line("我的房间", 50, 100, 200, 140),
            line("信箱", 600, 400, 700, 450),
        )
        assertNull(SignInParser.locateSignInEntry(lines))
    }

    @Test
    fun isAlreadySignedInRecognizesStatus() {
        assertTrue(SignInParser.isAlreadySignedIn(listOf(line("今日已签到，明日再来", 100, 200, 400, 240))))
        assertTrue(SignInParser.isAlreadySignedIn(listOf(line("已签到", 600, 300, 700, 350))))
        assertTrue(SignInParser.isAlreadySignedIn(listOf(line("明日再来", 200, 500, 400, 550))))
        assertTrue(SignInParser.isAlreadySignedIn(listOf(line("已领取", 200, 500, 400, 550))))
        assertFalse(SignInParser.isAlreadySignedIn(listOf(line("立即签到", 200, 500, 400, 550))))
    }

    @Test
    fun isSignInDialogRecognizesKeywords() {
        assertTrue(SignInParser.isSignInDialog(listOf(line("每日签到福利", 200, 300, 500, 350))))
        assertTrue(SignInParser.isSignInDialog(listOf(line("签到奖励", 200, 300, 500, 350))))
        assertFalse(SignInParser.isSignInDialog(listOf(line("我的信箱", 200, 300, 500, 350))))
    }

    @Test
    fun locateClaimButtonFindsButton() {
        val lines = listOf(
            line("X", 620, 280, 660, 320),
            line("每日签到", 200, 300, 500, 350),
            line("立即签到", 260, 900, 460, 960),
        )
        val claim = SignInParser.locateClaimButton(lines)
        assertNotNull(claim)
        assertEquals(360f, claim!!.x, 0.5f)
        assertEquals(930f, claim.y, 0.5f)
    }

    @Test
    fun locateDismissButtonFindsButton() {
        val lines = listOf(
            line("签到成功", 200, 300, 500, 350),
            line("我知道了", 260, 900, 460, 960),
        )
        val dismiss = SignInParser.locateDismissButton(lines)
        assertNotNull(dismiss)
        assertEquals(360f, dismiss!!.x, 0.5f)
        assertEquals(930f, dismiss.y, 0.5f)
    }

    @Test
    fun parseSignInRewardExtractsCoinsAndProps() {
        val lines = listOf(
            line("每日签到奖励", 200, 300, 500, 350),
            line("获得金币+150", 200, 450, 500, 500),
            line("获得香皂x2", 200, 520, 500, 570),
            line("确定", 260, 900, 460, 960),
        )
        val res = SignInParser.parseSignInReward(lines)
        assertNotNull(res)
        assertEquals(150, res.coinAward)
        assertTrue(res.items.any { it.contains("香皂") })
    }
}
