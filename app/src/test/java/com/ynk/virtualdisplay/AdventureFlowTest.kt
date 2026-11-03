package com.ynk.virtualdisplay

import android.graphics.Rect
import com.ynk.virtualdisplay.automation.*
import org.junit.Assert.*
import org.junit.Test

class AdventureFlowTest {

    private fun line(text: String, left: Int, top: Int, right: Int, bottom: Int): TextLine {
        val box = Rect()
        box.left = left
        box.top = top
        box.right = right
        box.bottom = bottom
        return TextLine(text, box)
    }

    @Test
    fun locateAdventureEntryFindsExactLabelOnOutdoorMap() {
        val lines = listOf(
            line("宠物学园", 144, 621, 253, 648),
            line("职业小镇", 513, 822, 623, 849),
            line("冒险", 289, 1170, 342, 1197),
        )
        val hit = AdventureParser.locateAdventureEntry(lines)
        assertNotNull(hit)
        assertEquals(315.5f, hit!!.x, 0.5f)
        assertEquals(1183.5f, hit.y, 0.5f)
    }

    @Test
    fun locateAdventureEntryReturnsNullWhenAbsent() {
        val lines = listOf(
            line("宠物学园", 144, 621, 253, 648),
            line("职业小镇", 513, 822, 623, 849),
        )
        assertNull(AdventureParser.locateAdventureEntry(lines))
    }

    @Test
    fun isAdventurePageRecognizesKeywords() {
        assertTrue(AdventureParser.isAdventurePage(listOf(line("附近走走", 100, 200, 200, 240))))
        assertTrue(AdventureParser.isAdventurePage(listOf(line("诗和远方", 100, 200, 200, 240))))
        assertTrue(AdventureParser.isAdventurePage(listOf(line("神秘冒险之旅", 100, 200, 300, 240))))
        assertFalse(AdventureParser.isAdventurePage(listOf(line("职业小镇", 100, 200, 200, 240), line("竹影武馆", 100, 300, 200, 340))))
    }

    @Test
    fun locateModeOptionLocatesTargetMode() {
        val lines = listOf(
            line("附近走走", 80, 500, 220, 560),
            line("诗和远方", 300, 500, 440, 560),
        )
        val nearby = AdventureParser.locateModeOption(lines, AdventureMode.NEARBY_WALK)
        assertNotNull(nearby)
        assertEquals(150f, nearby!!.x, 0.5f)
        assertEquals(530f, nearby.y, 0.5f)

        val poetry = AdventureParser.locateModeOption(lines, AdventureMode.POETRY_AND_DISTANT)
        assertNotNull(poetry)
        assertEquals(370f, poetry!!.x, 0.5f)
        assertEquals(530f, poetry.y, 0.5f)
    }

    @Test
    fun locateStartButtonFindsStartAction() {
        val lines = listOf(
            line("规则说明", 100, 200, 200, 240),
            line("立即出发", 260, 1100, 460, 1180),
        )
        val start = AdventureParser.locateStartButton(lines)
        assertNotNull(start)
        assertEquals(360f, start!!.x, 0.5f)
        assertEquals(1140f, start.y, 0.5f)
    }

    @Test
    fun detectLimitOrCooldownDetectsLimits() {
        val linesWithLimit = listOf(line("今日次数已达上限", 200, 500, 500, 550))
        assertEquals("今日次数已达上限", AdventureParser.detectLimitOrCooldown(linesWithLimit))

        val linesTired = listOf(line("体力不足，请先喂食", 200, 500, 500, 550))
        assertEquals("体力不足", AdventureParser.detectLimitOrCooldown(linesTired))

        val linesNormal = listOf(line("出发冒险", 200, 500, 500, 550))
        assertNull(AdventureParser.detectLimitOrCooldown(linesNormal))
    }

    @Test
    fun parseSettlementExtractsCoinsAndItems() {
        val lines = listOf(
            line("奇遇结算", 200, 400, 520, 450),
            line("本次冒险获得金币+60", 150, 500, 570, 550),
            line("拾得明信片·阳光沙滩", 150, 600, 570, 650),
            line("确定", 260, 800, 460, 860),
        )
        val settlement = AdventureParser.parseSettlement(lines)
        assertNotNull(settlement)
        assertEquals(60, settlement!!.coinAward)
        assertTrue(settlement.items.any { it.contains("明信片") })

        val dismiss = AdventureParser.locateDismissButton(lines)
        assertNotNull(dismiss)
        assertEquals(360f, dismiss!!.x, 0.5f)
    }

    @Test
    fun adventureModeEnumsAreProperlyConfigured() {
        assertEquals("附近走走", AdventureMode.NEARBY_WALK.label)
        assertEquals(5, AdventureMode.NEARBY_WALK.defaultDurationMinutes)
        assertEquals("诗和远方", AdventureMode.POETRY_AND_DISTANT.label)
        assertEquals(30, AdventureMode.POETRY_AND_DISTANT.defaultDurationMinutes)
        assertEquals(AdventureMode.NEARBY_WALK, AdventureMode.fromLabel("附近走走"))
    }
}
