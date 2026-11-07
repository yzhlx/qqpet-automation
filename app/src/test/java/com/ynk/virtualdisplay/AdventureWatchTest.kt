package com.ynk.virtualdisplay

import android.graphics.Rect
import com.ynk.virtualdisplay.automation.AdventureParser
import com.ynk.virtualdisplay.automation.AdventureWatch
import com.ynk.virtualdisplay.automation.TextLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fixtures follow the OCR of the real game's outdoor map while the pet was out on 附近走走
 * (measured 2026-11-07, 720x1600): bottom panel "正在冒险 / 剩余 00:00:25 / 召回" over an event log,
 * newest first. There is no settlement popup; when the countdown ends the pet is back in the room.
 */
class AdventureWatchTest {

    private fun line(text: String, left: Int, top: Int, right: Int, bottom: Int): TextLine {
        val box = Rect()
        box.left = left
        box.top = top
        box.right = right
        box.bottom = bottom
        return TextLine(text, box)
    }

    private fun map() = listOf(
        line("出门", 330, 140, 390, 180),
        line("宠物学园", 110, 605, 288, 665),
        line("职业小镇", 480, 815, 655, 855),
        line("冒险", 255, 1165, 375, 1205),
    )

    private fun panel(remaining: String = "剩余 00:00:25") = listOf(
        line("冒险中", 300, 700, 420, 740),
        line("正在冒险", 60, 1170, 260, 1210),
        line(remaining, 60, 1215, 330, 1255),
        line("召回", 580, 1230, 650, 1275),
        line("03:37 我捡到了一个袋子！", 60, 1320, 520, 1360),
        line("03:12 出门了，去附近走走", 60, 1380, 560, 1420),
    )

    private fun room() = listOf(
        line("出门", 330, 1420, 390, 1470),
        line("福袋", 40, 1000, 120, 1040),
    )

    // ---- readProgress -------------------------------------------------------------------

    @Test
    fun readProgressSeesThePanelCountdownAndLog() {
        val p = AdventureParser.readProgress(panel())
        assertTrue(p.inProgress)
        assertEquals(25, p.remainingSec)
        assertEquals(listOf("我捡到了一个袋子！", "出门了，去附近走走"), p.events)
    }

    @Test
    fun readProgressIgnoresOcrBlockOrder() {
        // ML Kit returns blocks in its own order; the log must still come out top to bottom.
        val p = AdventureParser.readProgress(panel().reversed())
        assertEquals(listOf("我捡到了一个袋子！", "出门了，去附近走走"), p.events)
        assertEquals(25, p.remainingSec)
    }

    @Test
    fun readProgressParsesHoursMinutesSeconds() {
        assertEquals(7200, AdventureParser.readProgress(panel("剩余 02:00:00")).remainingSec)
        assertEquals(3725, AdventureParser.readProgress(panel("剩余01:02:05")).remainingSec)
    }

    @Test
    fun readProgressJoinsLabelAndTimeSplitAcrossLines() {
        val lines = listOf(
            line("正在冒险", 60, 1170, 260, 1210),
            line("剩余", 60, 1215, 130, 1255),
            line("00:00:09", 140, 1215, 330, 1255),
            line("召回", 580, 1230, 650, 1275),
        )
        assertEquals(9, AdventureParser.readProgress(lines).remainingSec)
    }

    @Test
    fun anyOneMarkerIsEnoughToCallItRunning() {
        // ML Kit drops labels frame to frame: the bubble, the title or the 召回 button alone count.
        assertTrue(AdventureParser.readProgress(listOf(line("冒险中", 300, 700, 420, 740))).inProgress)
        assertTrue(AdventureParser.readProgress(listOf(line("正在冒险", 60, 1170, 260, 1210))).inProgress)
        assertTrue(AdventureParser.readProgress(listOf(line("召回", 580, 1230, 650, 1275))).inProgress)
    }

    @Test
    fun theMapAndTheRoomAreNotInProgress() {
        assertFalse(AdventureParser.readProgress(map()).inProgress)
        assertFalse(AdventureParser.readProgress(room()).inProgress)
        assertNull(AdventureParser.readProgress(map()).remainingSec)
    }

    @Test
    fun theSheetBeforeDepartureIsNotInProgress() {
        val sheet = listOf(
            line("冒险", 330, 740, 400, 780),
            line("附近走走", 100, 800, 300, 840),
            line("用时45秒", 100, 850, 300, 890),
            line("诗和远方", 400, 800, 600, 840),
            line("用时2小时", 400, 850, 600, 890),
            line("出发", 330, 1430, 400, 1470),
        )
        assertFalse(AdventureParser.readProgress(sheet).inProgress)
    }

    @Test
    fun summariseSkipsTheDepartureLine() {
        assertEquals("出门了，去附近走走", AdventureParser.summarise(listOf("出门了，去附近走走")))
        assertEquals(
            "我捡到了一个袋子！",
            AdventureParser.summarise(listOf("我捡到了一个袋子！", "出门了，去附近走走")),
        )
        assertEquals("冒险完成", AdventureParser.summarise(emptyList()))
    }

    @Test
    fun foundItemsKeepsOnlyWhatThePetBroughtBack() {
        val found = AdventureParser.foundItems(listOf("我捡到了一个袋子！", "出门了，去附近走走", "获得金币30"))
        assertEquals(listOf("我捡到了一个袋子！", "获得金币30"), found)
    }

    // ---- AdventureWatch -----------------------------------------------------------------

    @Test
    fun keepsWaitingWhileThePanelIsUp() {
        val w = AdventureWatch(startedAtMs = 0)
        assertEquals(AdventureWatch.Verdict.Waiting, w.observe(2_000, panel("剩余 00:00:40")))
        assertEquals(AdventureWatch.Verdict.Waiting, w.observe(4_000, panel("剩余 00:00:38")))
        assertTrue(w.sawProgress)
        assertEquals(38, w.lastRemainingSec)
    }

    @Test
    fun finishesWhenThePanelWasSeenAndIsGoneTwiceInARow() {
        val w = AdventureWatch(startedAtMs = 0)
        w.observe(2_000, panel("剩余 00:00:40"))
        assertEquals(AdventureWatch.Verdict.Waiting, w.observe(44_000, room()))
        val v = w.observe(46_000, room())
        assertTrue(v is AdventureWatch.Verdict.Finished)
        val events = (v as AdventureWatch.Verdict.Finished).events
        assertEquals("我捡到了一个袋子！", AdventureParser.summarise(events))
    }

    @Test
    fun oneMissedReadDoesNotEndTheWait() {
        // A single frame where OCR lost the whole panel must not be mistaken for the pet coming home.
        val w = AdventureWatch(startedAtMs = 0)
        w.observe(2_000, panel("剩余 00:00:40"))
        assertEquals(AdventureWatch.Verdict.Waiting, w.observe(4_000, map()))
        assertEquals(AdventureWatch.Verdict.Waiting, w.observe(6_000, panel("剩余 00:00:34")))
        assertEquals(AdventureWatch.Verdict.Waiting, w.observe(8_000, map()))
        assertEquals(AdventureWatch.Verdict.Waiting, w.observe(10_000, panel("剩余 00:00:30")))
    }

    @Test
    fun neverSeeingThePanelTimesOutInsteadOfCallingItDone() {
        // The first version took "no popup" for a result; a room that never showed the panel is unknown.
        val w = AdventureWatch(startedAtMs = 0, unseenTimeoutMs = 90_000)
        assertEquals(AdventureWatch.Verdict.Waiting, w.observe(30_000, room()))
        assertEquals(AdventureWatch.Verdict.Waiting, w.observe(89_000, room()))
        assertEquals(AdventureWatch.Verdict.TimedOut, w.observe(90_000, room()))
        assertFalse(w.sawProgress)
    }

    @Test
    fun aTwoHourCountdownIsNotWaitedFor() {
        val w = AdventureWatch(startedAtMs = 0)
        assertEquals(AdventureWatch.Verdict.Waiting, w.observe(2_000, panel("剩余 01:59:50")))
        assertEquals(AdventureWatch.Verdict.TooLong(7188), w.observe(4_000, panel("剩余 01:59:48")))
    }

    @Test
    fun oneMisreadLongCountdownDoesNotSendThePetAwayForGood() {
        val w = AdventureWatch(startedAtMs = 0)
        assertEquals(AdventureWatch.Verdict.Waiting, w.observe(2_000, panel("剩余 10:00:25")))
        assertEquals(AdventureWatch.Verdict.Waiting, w.observe(4_000, panel("剩余 00:00:23")))
        // the streak was broken, so a later long read starts counting again
        assertEquals(AdventureWatch.Verdict.Waiting, w.observe(6_000, panel("剩余 10:00:21")))
    }

    @Test
    fun aPanelThatNeverClearsTimesOut() {
        val w = AdventureWatch(startedAtMs = 0)
        w.observe(2_000, panel("剩余 00:00:10"))
        // 10 s of countdown plus 30 s grace, counted from the read at 2 s
        assertEquals(AdventureWatch.Verdict.Waiting, w.observe(41_000, panel("剩余 00:00:00")))
        assertEquals(AdventureWatch.Verdict.TimedOut, w.observe(43_000, panel("剩余 00:00:00")))
    }

    @Test
    fun aPanelWithoutAnyReadableCountdownStillHasAnUpperBound() {
        val w = AdventureWatch(startedAtMs = 0)
        val bubbleOnly = listOf(line("冒险中", 300, 700, 420, 740))
        assertEquals(AdventureWatch.Verdict.Waiting, w.observe(2_000, bubbleOnly))
        assertEquals(AdventureWatch.Verdict.Waiting, w.observe(300_000, bubbleOnly))
        assertEquals(AdventureWatch.Verdict.TimedOut, w.observe(331_000, bubbleOnly))
    }

    @Test
    fun aSettlementPopupFromAnOlderUiStillCounts() {
        val popup = listOf(
            line("奇遇结算", 200, 400, 520, 450),
            line("本次冒险获得金币+60", 150, 500, 570, 550),
            line("确定", 260, 800, 460, 860),
        )
        val w = AdventureWatch(startedAtMs = 0)
        val v = w.observe(10_000, popup)
        assertTrue(v is AdventureWatch.Verdict.Settled)
        assertEquals(60, (v as AdventureWatch.Verdict.Settled).result.coinAward)
    }

    @Test
    fun rewardWordsInTheLiveLogAreNotASettlement() {
        // "收获" style words can show up in the pet's log while it is still out.
        val w = AdventureWatch(startedAtMs = 0)
        val lines = panel("剩余 00:00:30") + line("03:40 收获满满", 60, 1440, 400, 1480)
        assertEquals(AdventureWatch.Verdict.Waiting, w.observe(2_000, lines))
    }
}
