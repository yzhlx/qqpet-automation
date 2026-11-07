package com.ynk.virtualdisplay

import android.graphics.PointF
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
    fun locateAdventureEntryIgnoresOcrBlockOrder() {
        // Measured 2026-11-07: the real outdoor map came back with 冒险 BEFORE 职业小镇, and the
        // tap went to the town island. Whatever the order, the tap is the 冒险 label itself.
        val lines = listOf(
            line("冒险", 255, 1165, 375, 1205),
            line("职业小镇", 480, 815, 655, 855),
            line("宠物学园", 110, 605, 288, 665),
            line("出门", 330, 140, 390, 180),
        )
        val hit = AdventureParser.locateAdventureEntry(lines)!!
        assertEquals(315f, hit.x, 0.5f)
        assertEquals(1185f, hit.y, 0.5f)
    }

    @Test
    fun entryReadingsAgreeOnlyWhenTheyAreClose() {
        val a = PointF().apply { x = 315f; y = 1185f }
        val near = PointF().apply { x = 320f; y = 1190f }
        val far = PointF().apply { x = 340f; y = 1185f }
        assertTrue(AdventureParser.sameEntry(a, near))
        assertFalse(AdventureParser.sameEntry(a, far))
    }

    @Test
    fun isAdventurePageRecognizesTheSheet() {
        assertTrue(AdventureParser.isAdventurePage(listOf(line("附近走走", 100, 200, 200, 240))))
        assertTrue(AdventureParser.isAdventurePage(listOf(line("诗和远方", 100, 200, 200, 240))))
        // The cards were not read: the title together with the sheet's own text still proves it.
        assertTrue(AdventureParser.isAdventurePage(listOf(line("冒险", 330, 740, 400, 780), line("规则说明", 510, 740, 610, 770))))
        assertTrue(AdventureParser.isAdventurePage(listOf(line("冒险", 330, 740, 400, 780), line("出发", 330, 1430, 400, 1470))))
        assertFalse(AdventureParser.isAdventurePage(listOf(line("职业小镇", 100, 200, 200, 240), line("竹影武馆", 100, 300, 200, 340))))
    }

    @Test
    fun theOutdoorMapAloneIsNotTheAdventureSheet() {
        // The map carries its own 冒险 label; a tap that missed leaves exactly this on screen.
        val map = listOf(
            line("出门", 330, 140, 390, 180),
            line("宠物学园", 110, 605, 288, 665),
            line("职业小镇", 480, 815, 655, 855),
            line("冒险", 255, 1165, 375, 1205),
        )
        assertFalse(AdventureParser.isAdventurePage(map))
    }

    @Test
    fun theCareerTownJobSheetIsNotTheAdventureSheet() {
        // Real OCR of the page the 2026-11-07 run landed on (it also says 规则说明).
        val town = listOf(
            line("同伴", 560, 140, 620, 180),
            line("宠物职业小镇", 200, 140, 520, 180),
            line("习武小童☆1", 100, 400, 400, 440),
            line("帮武馆跑腿", 100, 470, 400, 510),
            line("陪练基础课", 100, 760, 400, 800),
            line("规则说明>", 500, 760, 640, 800),
            line("去打工", 280, 1400, 440, 1450),
        )
        assertFalse(AdventureParser.isAdventurePage(town))
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

        val linesZero = listOf(line("今日剩余: 0次", 200, 500, 500, 550))
        assertEquals("今日剩余次数为0", AdventureParser.detectLimitOrCooldown(linesZero))

        val linesNormal = listOf(line("出发冒险", 200, 500, 500, 550))
        assertNull(AdventureParser.detectLimitOrCooldown(linesNormal))
    }

    @Test
    fun parseRemainingRoundsExtractsCount() {
        val lines1 = listOf(line("今日剩余次数: 3", 200, 500, 500, 550))
        assertEquals(3, AdventureParser.parseRemainingRounds(lines1))

        val lines2 = listOf(line("剩余次数:0", 200, 500, 500, 550))
        assertEquals(0, AdventureParser.parseRemainingRounds(lines2))

        val lines3 = listOf(line("今日冒险 2/5 次", 200, 500, 500, 550))
        assertEquals(2, AdventureParser.parseRemainingRounds(lines3))
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
