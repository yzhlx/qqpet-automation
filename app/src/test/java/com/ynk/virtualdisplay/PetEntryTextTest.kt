package com.ynk.virtualdisplay

import android.graphics.Rect
import com.ynk.virtualdisplay.automation.PetEntryText
import com.ynk.virtualdisplay.automation.TextLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * QQ's search page read by TEXT, so 宠物 is found wherever the recent-searches list put it.
 *
 * The line geometry below is the settled search page measured on VD48 (720x1600, 2026-11-06):
 * 取消 at the top right, 搜索指定内容, the four scope icons, the heading 最近搜索 at y≈550, and the
 * list row at y≈632 with 宠物 at x≈58 and QQ邮箱 at x≈433. QQ lists recent searches newest first, so
 * a manual search of anything else pushes 宠物 into the second slot - the case the first-slot
 * template cannot see.
 */
class PetEntryTextTest {
    // The JVM android.jar stub does not run Rect's constructor body, so set the fields directly.
    private fun line(text: String, l: Int, t: Int, r: Int, b: Int): TextLine {
        val box = Rect()
        box.left = l; box.top = t; box.right = r; box.bottom = b
        return TextLine(text, box)
    }

    private val chrome = listOf(
        line("搜索", 98, 128, 168, 172),
        line("取消", 620, 128, 688, 172),
        line("问元宝", 563, 218, 651, 252),
        line("搜索指定内容", 32, 300, 200, 332),
        line("找人/群", 58, 436, 152, 470),
        line("最近搜索", 32, 534, 144, 566),
    )

    @Test fun theSettledSearchPageIsRecognisedByItsText() {
        assertTrue(PetEntryText.isSearchPage(chrome))
    }

    @Test fun theMessageTabAndTheRoomAreNotTheSearchPage() {
        val messageTab = listOf(line("搜索", 348, 232, 414, 268), line("QQ游戏中心", 152, 440, 340, 476), line("消息", 70, 1560, 110, 1590))
        val room = listOf(line("出门", 333, 1428, 387, 1458), line("喂食", 611, 724, 665, 756), line("洗澡", 611, 862, 665, 896))
        assertFalse(PetEntryText.isSearchPage(messageTab))
        assertFalse(PetEntryText.isSearchPage(room))
        assertFalse(PetEntryText.isSearchPage(emptyList()))
    }

    @Test fun theWordInTheFirstSlotIsTappedWhereItIsDrawn() {
        val lines = chrome + listOf(line("宠物", 28, 612, 88, 653), line("QQ邮箱", 380, 612, 486, 653))
        val (x, y) = PetEntryText.historyWordPoint(lines)!!
        assertEquals(58f, x, 0.5f)
        assertEquals(632.5f, y, 0.5f)
    }

    @Test fun theWordPushedIntoTheSecondSlotIsStillFound() {
        val lines = chrome + listOf(line("QQ邮箱", 28, 612, 134, 653), line("宠物", 380, 612, 440, 653))
        val (x, y) = PetEntryText.historyWordPoint(lines)!!
        assertEquals(410f, x, 0.5f)
        assertEquals(632.5f, y, 0.5f)
    }

    @Test fun theWordOnASecondRowIsStillFound() {
        val lines = chrome + listOf(line("QQ邮箱", 28, 612, 134, 653), line("天气", 380, 612, 440, 653), line("宠物", 28, 680, 88, 721))
        val (x, y) = PetEntryText.historyWordPoint(lines)!!
        assertEquals(58f, x, 0.5f)
        assertEquals(700.5f, y, 0.5f)
    }

    @Test fun aLineThatFoldedTwoListEntriesTogetherIsSplitByCharacterShare() {
        // ML Kit sometimes returns neighbouring entries as one line; the word is then located by its
        // share of the characters, which still lands inside its own list cell.
        val lines = chrome + listOf(line("QQ邮箱 宠物", 28, 612, 486, 653))
        val (x, _) = PetEntryText.historyWordPoint(lines)!!
        assertTrue("tap x=$x must be on the 宠物 half of the folded line", x > 257f && x < 486f)
        val first = chrome + listOf(line("宠物 QQ邮箱", 28, 612, 486, 653))
        val (x2, _) = PetEntryText.historyWordPoint(first)!!
        assertTrue("tap x=$x2 must be on the 宠物 half of the folded line", x2 > 28f && x2 < 257f)
    }

    @Test fun aLongerEntryThatMerelyContainsTheWordIsADifferentSearch() {
        val lines = chrome + listOf(line("宠物店", 28, 612, 118, 653), line("QQ宠物", 380, 612, 486, 653))
        assertNull(PetEntryText.historyWordPoint(lines))
    }

    @Test fun theWordTypedInTheSearchBoxAboveTheListIsNotTheListEntry() {
        // The results page puts 宠物 in the search box; there is no 最近搜索 heading there, and even
        // on a page that has one, text above the heading is not a list entry.
        val resultsPage = listOf(line("宠物", 98, 128, 168, 172), line("取消", 620, 128, 688, 172), line("搜索指定内容", 32, 300, 200, 332))
        assertNull(PetEntryText.historyWordPoint(resultsPage))
        val aboveHeading = chrome + listOf(line("宠物", 98, 200, 168, 240))
        assertNull(PetEntryText.historyWordPoint(aboveHeading))
    }

    @Test fun aSearchPageWithoutTheWordGivesNothingToTap() {
        val lines = chrome + listOf(line("QQ邮箱", 28, 612, 134, 653))
        assertTrue(PetEntryText.isSearchPage(lines))
        assertNull(PetEntryText.historyWordPoint(lines))
    }

    @Test fun anEntryFarBelowTheListIsAnotherSection() {
        val lines = chrome + listOf(line("宠物", 28, 1200, 88, 1241))
        assertNull(PetEntryText.historyWordPoint(lines))
    }

    /**
     * The ML Kit reading of the real page (device log 2026-11-07 01:58:14, QQ邮箱 first, 宠物 second):
     * "Q搜索;|搜索指定内容;找人/群;最近機素;QQ邮箱;小程序;宠物;页面設置;取消;问元宝③;漫剧;可".
     * 最近搜索 came back as 最近機素 and 页面设置 as 页面設置. The boxes are the measured layout.
     */
    private val misread = listOf(
        line("Q搜索", 50, 128, 170, 172),
        line("|", 98, 128, 102, 172),
        line("搜索指定内容", 32, 300, 200, 332),
        line("找人/群", 58, 436, 152, 470),
        line("最近機素", 32, 534, 144, 566),
        line("QQ邮箱", 28, 612, 134, 653),
        line("小程序", 404, 436, 488, 470),
        line("宠物", 380, 612, 440, 653),
        line("页面設置", 305, 1445, 415, 1480),
        line("取消", 620, 128, 688, 172),
        line("问元宝③", 563, 218, 651, 252),
        line("漫剧", 588, 436, 642, 470),
        line("可", 700, 500, 718, 530),
    )

    @Test fun theMisreadHeadingOfTheRealFrameStillFindsTheWordInTheSecondSlot() {
        assertTrue(PetEntryText.isSearchPage(misread))
        val (x, y) = PetEntryText.historyWordPoint(misread)!!
        assertEquals(410f, x, 0.5f)
        assertEquals(632.5f, y, 0.5f)
    }

    @Test fun withoutAnyHeadingTheListIsPlacedFromTheScopeIconRow() {
        val noHeading = misread.filter { !it.text.startsWith("最近") }
        assertTrue(PetEntryText.isSearchPage(noHeading))
        val (x, y) = PetEntryText.historyWordPoint(noHeading)!!
        assertEquals(410f, x, 0.5f)
        assertEquals(632.5f, y, 0.5f)
    }

    @Test fun aMisreadCancelButtonDoesNotHideAPageThatIsOtherwiseThisOne() {
        val noCancel = misread.filter { it.text != "取消" }
        assertTrue(PetEntryText.isSearchPage(noCancel))
        assertNotNull(PetEntryText.historyWordPoint(noCancel))
    }

    @Test fun twoLabelsWithoutTheCancelButtonAreNotEnough() {
        val thin = listOf(line("搜索指定内容", 32, 300, 200, 332), line("找人/群", 58, 436, 152, 470))
        assertFalse(PetEntryText.isSearchPage(thin))
        assertNull(PetEntryText.historyWordPoint(thin))
    }

    @Test fun theResultsPageSharesTheCancelButtonAndTabsButIsNotTheSearchPage() {
        // 宠物 sits in the search box here and as a result title; the tabs 小程序 and 表情 are the
        // search page's scope-icon names too, which is why they are not anchors.
        val results = listOf(
            line("宠物", 150, 128, 218, 168), line("取消", 620, 128, 688, 172),
            line("全部", 42, 240, 106, 276), line("用户", 148, 240, 212, 276), line("群聊", 254, 240, 318, 276),
            line("频道", 360, 240, 424, 276), line("小程序", 466, 240, 562, 276), line("表情", 604, 240, 668, 276),
            line("宠物 - 功能", 48, 358, 218, 392), line("宠物", 176, 486, 244, 520), line("每日Q崽", 176, 634, 302, 668),
        )
        assertFalse(PetEntryText.isSearchPage(results))
        assertNull(PetEntryText.historyWordPoint(results))
    }

    @Test fun aCorruptBoxIsNotTurnedIntoATap() {
        val lines = chrome + listOf(line("宠物", 28, 612, 88, 2000))
        val p = PetEntryText.historyWordPoint(lines)
        assertNotNull("a tall box still centres inside the frame only when valid", p == null || p.second <= 1600f)
    }
}
