package com.ynk.virtualdisplay

import android.graphics.Rect
import com.ynk.virtualdisplay.automation.TextLine
import com.ynk.virtualdisplay.automation.WorkOption
import com.ynk.virtualdisplay.automation.WorkPageParser
import org.junit.Assert.*
import org.junit.Test

/**
 * Parser-level tests for the career-town page reading.
 *
 * PROVENANCE. These tests run against the REAL [WorkPageParser] with synthetic OCR lines whose shape
 * is taken from the MEASURED page geometry: the two option cards sit side by side with their own 用时
 * lines at the same height, and the 消耗/工分/奖励 block lives below the fold on the scrolled frame
 * (evidence: .build-tmp/device-acceptance/work-page-layout-20261020.md, frames captured 2026-10-20
 * on display 4). They prove the PARSING rules:
 *
 *  - the reward is read off the scrolled reward block, never off the top-bar 金币余额 line;
 *  - "体力 5（当前 26），清洁 2（当前 6）" reads 5 and 2 as the costs and never the current values;
 *  - the coin icon on the 奖励 row OCRs as "0" and must never be read as the reward;
 *  - the selected option is the card whose OWN 用时 value matches the expected duration;
 *  - a field that cannot be resolved comes back in unreadFields and is never a 0 that looks measured;
 *  - 打工中 is the working marker and 剩余 alone is not, because the study page writes 剩余 too.
 *
 * They prove nothing about the game itself beyond those measured frames.
 */
class WorkPageParserTest {

    /**
     * The JVM android.jar keeps Rect's fields but its constructor ignores the arguments, so a line's
     * position has to be written onto the fields directly. centerY() is also unmocked, and the parser
     * reads it, which is why every box here is built this way.
     */
    private fun line(text: String, left: Int, top: Int, right: Int, bottom: Int): TextLine {
        val box = Rect()
        box.left = left
        box.top = top
        box.right = right
        box.bottom = bottom
        return TextLine(text, box)
    }

    /**
     * The measured page geometry (2026-10-20): cards side by side at y~950 with their own 用时 lines
     * directly below; the reward block on the SCROLLED frame at y~1080-1270; 去打工 and 升星 fixed at
     * the bottom. Top-bar balance sits at y~110.
     */
    private val topBar = listOf(
        line("金币 8", 560, 100, 700, 130),
    )
    private val cards = listOf(
        line("帮武馆跑腿", 60, 936, 263, 963),
        line("陪练基础课", 400, 936, 537, 963),
    )
    private val cardDurations = listOf(
        line("用时：10分钟", 60, 978, 263, 997),
        line("用时：45分钟", 400, 978, 537, 997),
    )
    private val rewardBlock = listOf(
        line("体力 5（当前 26），清洁 2（当前 6）", 60, 1085, 620, 1112),
        line("工分", 60, 1159, 120, 1178),
        line("+10 点", 160, 1159, 260, 1178),
        line("奖励", 60, 1233, 132, 1252),
        line("0 68", 167, 1233, 240, 1252),
    )
    private val fixedBottom = listOf(
        line("去打工", 285, 1366, 461, 1399),
        line("升星条件：获得该阶段工分 20/200", 144, 1467, 577, 1493),
    )

    /** The top frame: header, cards, card durations. */
    private fun topFrame(extra: List<TextLine> = emptyList()) =
        topBar + cards + cardDurations + extra

    /** The scrolled frame: reward block plus the fixed bottom, cards gone. */
    private fun scrolledFrame() = topBar + rewardBlock + fixedBottom

    // ------------------------------------------------------- reward row

    @Test
    fun `theRewardIsReadOffTheScrolledBlockNotTheTopBarBalance`() {
        // THE bug this closes: the balance is 8 and the reward is 68, and the reward block is only
        // on the scrolled frame. The reading must come from the 奖励 row's own value line, never
        // from the top bar.
        val row = WorkPageParser.parseRewardRow(scrolledFrame(), "竹影武馆", "帮武馆跑腿", durationMinutes = 10)
        assertEquals(68, row.pageCoinReward)
        assertEquals(10, row.pageWorkScoreReward)
        assertTrue(row.unreadFields.isEmpty())
    }

    @Test
    fun `aCostLineReadsTheCostNeverTheCurrentValue`() {
        // "体力 5（当前 26），清洁 2（当前 6）" must read 5 and 2. The old first-number rule could
        // pick up the current values 26 and 6 instead.
        val row = WorkPageParser.parseRewardRow(scrolledFrame(), "竹影武馆", "帮武馆跑腿", durationMinutes = 10)
        assertEquals(5, row.pageEnergyCost)
        assertEquals(2, row.pageCleanlinessCost)
    }

    @Test
    fun `aMerged消耗LineStillReadsTheCosts`() {
        // ML Kit can merge the 消耗 label row into the value row (measured 2026-10-20 s14/s15):
        // "消耗 体力 5（当前 24），清洁 2（当前 3）" must read 5 and 2, never the current values.
        val merged = scrolledFrame().map { l ->
            if (l.text.startsWith("体力")) l.copy(text = "消耗 体力 5（当前 24），清洁 2（当前 3）") else l
        }
        val row = WorkPageParser.parseRewardRow(merged, "竹影武馆", "帮武馆跑腿", durationMinutes = 10)
        assertEquals(5, row.pageEnergyCost)
        assertEquals(2, row.pageCleanlinessCost)
        assertTrue(row.unreadFields.isEmpty())
    }

    @Test
    fun `theS16MergedLineWith请洁ResolvesAllFields`() {
        // The raw line dump from s16 (2026-10-20): ML Kit merged the whole block into one line and
        // read 清洁 as 请洁. With the glyph map the line must resolve every field.
        val line1 = line("消耗体力5(当前24),请洁2(当前3)", 60, 1085, 620, 1112)
        val line2 = line("工分+10点", 60, 1159, 200, 1178)
        val line3 = line("奖励", 60, 1233, 132, 1252)
        val line4 = line("68", 167, 1233, 240, 1252)
        val row = WorkPageParser.parseRewardRow(
            listOf(line1, line2, line3, line4), "竹影武馆", "帮武馆跑腿", durationMinutes = 10)
        assertEquals(5, row.pageEnergyCost)
        assertEquals(2, row.pageCleanlinessCost)
        assertEquals(10, row.pageWorkScoreReward)
        assertEquals(68, row.pageCoinReward)
        assertTrue(row.unreadFields.isEmpty())
    }

    @Test
    fun `aBare消耗LabelLineIsNotTheBlockLine`() {
        // "消耗" as its own label row plus a separate 体力 value row: the value row wins.
        val lines = listOf(
            line("消耗", 81, 1085, 135, 1111),
            line("体力 5（当前 24），清洁 2（当前 3）", 162, 1085, 531, 1114),
        ) + rewardBlock.filterNot { it.text.startsWith("体力") }
        val row = WorkPageParser.parseRewardRow(lines, "竹影武馆", "帮武馆跑腿", durationMinutes = 10)
        assertEquals(5, row.pageEnergyCost)
        assertEquals(2, row.pageCleanlinessCost)
    }

    @Test
    fun `aSplitCleanlinessLineWithLeadingCommaIsRead`() {
        // A split second half can start with the stray "，" ML Kit keeps from the merged line.
        val split = scrolledFrame().flatMap { l ->
            when {
                l.text.startsWith("体力") -> listOf(
                    l.copy(text = "体力 5（当前 24）"),
                    line("，清洁 2（当前 3）", 60, 1113, 300, 1138),
                )
                else -> listOf(l)
            }
        }
        val row = WorkPageParser.parseRewardRow(split, "竹影武馆", "帮武馆跑腿", durationMinutes = 10)
        assertEquals(5, row.pageEnergyCost)
        assertEquals(2, row.pageCleanlinessCost)
    }

    @Test
    fun `theCoinIconReadAsZeroIsNeverTheReward`() {
        // The 奖励 value line reads "0 68": the 0 is the coin icon (measured 2026-10-20). The reward
        // is the LAST number, 68, never the icon's 0.
        val row = WorkPageParser.parseRewardRow(scrolledFrame(), "竹影武馆", "帮武馆跑腿", durationMinutes = 10)
        assertEquals(68, row.pageCoinReward)
    }

    @Test
    fun `theWorkScorePlus10PointsIsReadAs10`() {
        // 工分's value line reads "+10 点": the number is 10, not whatever a sign-first parse would say.
        val row = WorkPageParser.parseRewardRow(scrolledFrame(), "竹影武馆", "帮武馆跑腿", durationMinutes = 10)
        assertEquals(10, row.pageWorkScoreReward)
    }

    @Test
    fun `anUnreadableFieldIsReportedNotZeroed`() {
        // No 奖励 row anywhere: the reading must be an explicit unread marker, because a 0 here
        // would look like a measured "no reward" and could let an unmeasured option run.
        val lines = scrolledFrame().filterNot { it.text.startsWith("奖励") || it.text == "0 68" }
        val row = WorkPageParser.parseRewardRow(lines, "竹影武馆", "帮武馆跑腿", durationMinutes = 10)
        assertTrue(row.unreadFields.contains("金币未读出"))
        assertEquals(0, row.pageCoinReward)
    }

    @Test
    fun `aBalanceLineAloneIsNotAReading`() {
        // Only the top-bar balance is visible - the reward block never rendered. The coin reading
        // must be refused rather than taken from the balance.
        val row = WorkPageParser.parseRewardRow(topBar + cards, "竹影武馆", "帮武馆跑腿")
        assertTrue(row.unreadFields.isNotEmpty())
    }

    @Test
    fun `theDurationIsCarriedFromTheTopFrame`() {
        // On the scrolled frame the option cards are gone, so the caller supplies the duration it
        // read on the top frame. That duration must be kept, not dropped to 0.
        val row = WorkPageParser.parseRewardRow(scrolledFrame(), "竹影武馆", "帮武馆跑腿", durationMinutes = 10)
        assertEquals(10, row.durationMinutes)
    }

    @Test
    fun `withoutACarriedDurationTheScrolledFrameLeavesDurationAtZero`() {
        // No cards on the scrolled frame and no carried duration: the field is 0 and the caller
        // must refuse (0 is never a measured duration). The reward fields stay readable though.
        val row = WorkPageParser.parseRewardRow(scrolledFrame(), "竹影武馆", "帮武馆跑腿")
        assertEquals(0, row.durationMinutes)
        assertEquals(68, row.pageCoinReward)
    }

    @Test
    fun `theDurationComesFromTheSelectedOptionsOwnCardOnTheTopFrame`() {
        // Both cards and their 用时 lines are on screen; the selected option's duration is the one
        // under ITS card, not the other card's.
        val row = WorkPageParser.parseRewardRow(topFrame(), "竹影武馆", "帮武馆跑腿")
        assertEquals(10, row.durationMinutes)
    }

    @Test
    fun `anotherCardsDurationIsNotAttributedToTheSelectedOption`() {
        // The 45-minute line is under 陪练基础课's card: asking for 帮武馆跑腿 must not pick it up.
        val row = WorkPageParser.parseRewardRow(topFrame(), "竹影武馆", "帮武馆跑腿")
        assertEquals(10, row.durationMinutes)
        assertFalse(row.durationMinutes == 45)
    }

    // ------------------------------------------------------- selected option

    @Test
    fun `theSelectedOptionIsTheCardWhoseDurationMatches`() {
        // The two cards sit side by side with their own 用时 lines; the selected card is the one
        // whose duration equals the expected 10 minutes (帮武馆跑腿).
        val selected = WorkPageParser.parseSelectedOptionLabel(topFrame(), expectedMinutes = 10)
        assertNotNull(selected)
        assertEquals("帮武馆跑腿", selected)
    }

    @Test
    fun `theOtherDurationSelectsTheOtherCard`() {
        // Expected 45 minutes: the selected card must be 陪练基础课, the one whose card says 45.
        val selected = WorkPageParser.parseSelectedOptionLabel(topFrame(), expectedMinutes = 45)
        assertEquals("陪练基础课", selected)
    }

    @Test
    fun `sideBySideCardsWithoutAExpectedDurationAreAmbiguous`() {
        // Both cards carry a readable duration, so without a target duration nothing can be
        // positively matched: null, never the first card.
        assertNull(WorkPageParser.parseSelectedOptionLabel(topFrame()))
    }

    @Test
    fun `aKnownLabelFarFromTheRewardBlockIsNotTheSelectedCard`() {
        // The option labels are on screen but their 用时 lines are missing: nothing can be matched.
        val lines = topBar + cards
        assertNull(WorkPageParser.parseSelectedOptionLabel(lines, expectedMinutes = 10))
    }

    @Test
    fun `noRewardBlockMeansNoSelectedLabel`() {
        // Without any card duration there is nothing to match: unknown, never the first card.
        assertNull(WorkPageParser.parseSelectedOptionLabel(topBar + cards))
    }

    @Test
    fun `aDurationThatMatchesNoCardIsAReadFailure`() {
        // Expected 30 minutes matches neither card: null, so the flow stops instead of guessing.
        assertNull(WorkPageParser.parseSelectedOptionLabel(topFrame(), expectedMinutes = 30))
    }

    // ------------------------------------------------------- working page

    @Test
    fun `打工中ConfirmsTheWorkingPage`() {
        val lines = listOf(line("打工中", 0, 100, 200, 140))
        assertTrue(WorkPageParser.parseWorkingPage(lines))
    }

    @Test
    fun `剩余AloneDoesNotConfirmTheWorkingPage`() {
        // THE bug this closes: the study page writes 剩余 too, so a lesson countdown could confirm a
        // work shift. Only 打工中 is the working marker.
        val lines = listOf(line("剩余 00:08:24", 0, 100, 300, 140))
        assertFalse(WorkPageParser.parseWorkingPage(lines))
    }

    @Test
    fun `打工中Plus剩余ConfirmsTheWorkingPage`() {
        // The real outdoor page (P5.1 §5, w3.png) shows both. The marker is enough on its own, and the
        // countdown adds nothing - which is the point.
        val lines = listOf(
            line("打工中", 0, 100, 200, 140),
            line("剩余 00:08:24", 0, 160, 300, 200),
        )
        assertTrue(WorkPageParser.parseWorkingPage(lines))
    }

    @Test fun corruptOrOffscreenBoundsCannotBecomeAnEdgeTap() {
        assertFalse(WorkPageParser.validBounds(822, Int.MAX_VALUE, 513, Int.MAX_VALUE, 720, 1600))
        assertFalse(WorkPageParser.validBounds(-1, 849, 513, 623, 720, 1600))
        assertFalse(WorkPageParser.validBounds(822, 849, 513, 721, 720, 1600))
        assertFalse(WorkPageParser.validBounds(822, 822, 513, 623, 720, 1600))
        assertTrue(WorkPageParser.validBounds(822, 849, 513, 623, 720, 1600))
    }

    // ------------------------------------------------------- payroll surface

    @Test
    fun `aPayrollFrameWithMarkersMinutesAndOptionResolves`() {
        // The measured dialog (P5.1 §5): 工资明细 + 帮武馆跑腿(10分钟) + 基础工资68 + 工分+10.
        val surface = WorkPageParser.parseCompletionSurface(listOf(
            line("工资明细", 100, 300, 300, 340),
            line("帮武馆跑腿(10分钟) +68", 100, 360, 500, 400),
            line("基础工资68", 100, 420, 300, 460),
        ))
        assertNotNull(surface)
        assertEquals(10, surface!!.minutes)
        assertEquals("帮武馆跑腿", surface.optionLabel)
        assertEquals(68, surface.coinAward)
    }

    @Test
    fun `aPayrollCoinPlusLineIsAnAwardNotABalance`() {
        // Measured wage page 2026-10-20: "金币 +68" proves the payout amount, while parseBalanceLine
        // must keep refusing the same text so it cannot fabricate a delta.
        assertEquals(68, WorkPageParser.parsePayrollCoinAward(listOf("金币 +68", "基础工资68")))
        assertEquals(68, WorkPageParser.parsePayrollCoinAward(listOf("基础工资 68")))
        assertNull(WorkPageParser.parseBalanceLine("金币+68"))
        assertNull(WorkPageParser.parseBalanceLine("基础工资 68"))
    }

    @Test
    fun `aPayrollFrameWithMarkersButNoMinutesIsAPollingSignalNotAFailure`() {
        // The dialog is arriving; the markers render before the duration line does. The surface is
        // real but minutes=null, which is why the caller keeps polling instead of failing.
        val surface = WorkPageParser.parseCompletionSurface(listOf(
            line("工资明细", 100, 300, 300, 340),
            line("基础工资68", 100, 420, 300, 460),
        ))
        assertNotNull(surface)
        assertNull(surface!!.minutes)
    }

    @Test
    fun `aFrameWithoutPayrollMarkersIsNotACompletionSurface`() {
        // The working page writes 剩余 00:08:24: a marker-free frame is never a payroll.
        assertNull(WorkPageParser.parseCompletionSurface(listOf(
            line("打工中", 0, 100, 200, 140),
            line("剩余 00:08:24", 0, 160, 300, 200),
        )))
    }

    @Test
    fun `aPayrollNamingTwoOptionsIsAmbiguousNotSingleOptioned`() {
        // Two known labels on one surface cannot be attributed to one shift; singleOrNull -> null
        // and the caller's namesOption check refuses it.
        val surface = WorkPageParser.parseCompletionSurface(listOf(
            line("工资明细", 100, 300, 300, 340),
            line("帮武馆跑腿(10分钟)", 100, 360, 500, 400),
            line("陪练基础课(45分钟)", 100, 420, 500, 460),
        ))
        assertNull(surface!!.optionLabel)
    }

    @Test
    fun `aZeroMinutePayrollLineIsStillUnread`() {
        // "0分钟" is never a measured duration; the surface stays minutes=null.
        val surface = WorkPageParser.parseCompletionSurface(listOf(
            line("工资明细", 100, 300, 300, 340),
            line("帮武馆跑腿(0分钟)", 100, 360, 500, 400),
        ))
        assertNotNull(surface)
        assertNull(surface!!.minutes)
    }

    // ------------------------------------------------------- balance line

    @Test
    fun `aBalanceLineWithLabelResolves`() {
        assertEquals(76, WorkPageParser.parseBalanceLine("金币余额 76"))
        assertEquals(76, WorkPageParser.parseBalanceLine("余额：76"))
        assertEquals(76, WorkPageParser.parseBalanceLine("余额:76"))
    }

    @Test
    fun `aPayrollRewardLineIsNeverABalance`() {
        // "基础工资 68" / "金币+68" are the session's reward. Reading them as the balance and
        // differencing against a pre-shift balance produces a number that means nothing.
        assertNull(WorkPageParser.parseBalanceLine("基础工资 68"))
        assertNull(WorkPageParser.parseBalanceLine("金币+68"))
        assertNull(WorkPageParser.parseBalanceLine("看视频+10 金币+68"))
    }

    @Test
    fun `aBareNumberLineIsNotABalanceWithoutItsLabel`() {
        // The game renders the balance as a bare number next to the coin icon (measured P5.1
        // coin-zoom). Until the line text that accompanies it is proven, a bare number is refused:
        // an unread balance blocks the payout, a guessed one fabricates it.
        assertNull(WorkPageParser.parseBalanceLine("76"))
    }

    // ------------------------------------------------------- joined label run

    @Test
    fun `theJoinedRunBoundsNeverOverflowIntMaxSeed`() {
        // THE bug this closes (measured 2026-10-20 s12): the fold seeded ALL four accumulators with
        // Int.MAX_VALUE, so bottom/right stayed pinned there and every center came out
        // (min + Int.MAX_VALUE) / 2 = about -1073741568, which PetInputController refused as
        // "tap outside pet display". With the correct seeds the bounds must be the run's real edges.
        val lines = listOf(
            line("职业", 513, 822, 570, 849),
            line("小镇", 575, 823, 623, 848),
        )
        val hit = WorkPageParser.locateJoinedRun(lines, "职业小镇")!!
        assertEquals(822, hit[0]) // top
        assertEquals(849, hit[1]) // bottom
        assertEquals(513, hit[2]) // left
        assertEquals(623, hit[3]) // right
    }

    @Test
    fun `aSplitTownLabelIsJoinedAcrossConsecutiveLines`() {
        // ML Kit can split 职业小镇 across two lines; the run whose joined text contains the label
        // must match, and its bounds cover both lines.
        val lines = listOf(
            line("宠物学园", 144, 621, 253, 648),
            line("职业", 513, 822, 570, 849),
            line("小镇", 575, 823, 623, 848),
            line("冒险", 289, 1170, 342, 1197),
        )
        val hit = WorkPageParser.locateJoinedRun(lines, "职业小镇")!!
        assertEquals(822, hit[0])
        assertEquals(849, hit[1])
        assertEquals(513, hit[2])
        assertEquals(623, hit[3])
    }

    @Test
    fun `linesFurtherThanTheGapAreNotJoinedIntoOneRun`() {
        // 宠物学园 (bottom 648) and 职业小镇 (top 822) are 174px apart: they must not fold into
        // one run, or the tap center would land between the two labels.
        val lines = listOf(
            line("宠物学园", 144, 621, 253, 648),
            line("职业小镇", 513, 822, 623, 849),
        )
        val hit = WorkPageParser.locateJoinedRun(lines, "职业小镇")!!
        assertEquals(822, hit[0])
        assertEquals(849, hit[1])
    }

    @Test
    fun `anAbsentLabelHasNoJoinedRun`() {
        assertNull(WorkPageParser.locateJoinedRun(listOf(line("冒险", 289, 1170, 342, 1197)), "职业小镇"))
    }

    // ------------------------------------------------------- label-value rules

    @Test
    fun `aLabelInsideAnotherLineIsNotAReading`() {
        // "看视频+10 金币+68" is a payroll summary line, not the reward row: the label does not START
        // the line, so it must not be read as 金币 = 10 or any other number on that line.
        val lines = listOf(line("看视频+10 金币+68", 60, 860, 500, 890))
        val row = WorkPageParser.parseRewardRow(lines, "竹影武馆", "帮武馆跑腿")
        assertTrue(row.unreadFields.contains("金币未读出"))
    }

    @Test
    fun `aWorkScoreProgressLineIsNotAPlainWorkScoreReading`() {
        // The fixed bottom shows "升星条件：获得该阶段工分 20/200" as a stage counter. It must not
        // stand in for the reward block's 工分 value: the 升星 row terminates the value band.
        val progress = line("升星条件：获得该阶段工分 20/200", 144, 1467, 577, 1493)
        val row = WorkPageParser.parseRewardRow(scrolledFrame() + progress, "竹影武馆", "帮武馆跑腿", durationMinutes = 10)
        assertEquals(10, row.pageWorkScoreReward)
    }

    @Test
    fun `theParsedRowMatchesTheMeasuredZhuYingWuGuanRow`() {
        // The measured row for 帮武馆跑腿 (2026-10-20 frames): 10 minutes, 68 coins, 10 工分,
        // 体力 5, 清洁 2. A regression here means the catalog comparison would start blocking a page
        // that has not changed, so the whole flow would stop before any work.
        val row = WorkPageParser.parseRewardRow(scrolledFrame(), "竹影武馆", "帮武馆跑腿", durationMinutes = 10)
        val expected = WorkOption(
            id = "work:竹影武馆/帮武馆跑腿",
            venue = "竹影武馆",
            optionLabel = "帮武馆跑腿",
            durationMinutes = 10,
            pageCoinReward = 68,
            pageWorkScoreReward = 10,
            pageEnergyCost = 5,
            pageCleanlinessCost = 2,
        )
        assertEquals(expected, row)
    }
    // ---------------------------------------------------------------------------------------------
    // Career-town PAGE IDENTITY.
    //
    // PROVENANCE. The lines below are the MEASURED ML Kit reading of the live venue detail page,
    // verbatim from the device log (2026-10-20 06:37:56, "work-reward-read-unresolved"), plus the
    // measured card/block geometry of the same page. The BOUNDS are synthetic on purpose: these
    // tests pin the TEXT rules, not the page's geometry.
    //
    // They matter because a session can be left standing on the town - the work flow opens it, and a
    // refused start stops the flow there - so `PetNavigation.initialise` has to be able to recognise
    // and leave it. Measured twice on 2026-10-20 (r16/r17): every managed session stopped at
    // INITIAL_PAGE_UNKNOWN with zero consumption while the virtual display sat on that page.
    // ---------------------------------------------------------------------------------------------

    /** The sticky header alone: the title and the venue name, with NO detail-page marker. */
    private val stickyHeader = listOf(
        line("彩虹阿专", 348, 77, 567, 138),
        line("宠物职业小镇", 259, 143, 427, 180),
    )
    private val venueName = line("竹影武馆", 250, 402, 409, 441)

    /** The bottom of the detail page: the work button and the star condition, both measured. */
    private val detailFooter = listOf(
        line("去打工", 310, 1367, 410, 1399),
        line("升星条件:获得该阶段工分 20/200", 144, 1467, 577, 1493),
    )

    @Test
    fun `theMeasuredVenueDetailFrameIsATownDetailPage`() {
        val frame = stickyHeader + listOf(venueName) + cards + cardDurations + detailFooter
        assertEquals(WorkPageParser.TownPageKind.DETAIL, WorkPageParser.parseTownPage(frame))
        assertEquals("竹影武馆", WorkPageParser.parseTownVenue(frame))
    }

    @Test
    fun `theLiveVenueDetailFrameIsRecognisedByItsOwnReading`() {
        // The MEASURED live frame of 2026-10-20, ML Kit reading verbatim. It carries every
        // detail-page marker (去打工, 升星条件), the venue name, and the game's own confusions
        // (彩虹阿专, 规規则说明>, 智カ1, 请洁) - the parser has to see through those.
        val live = listOf(
            line("力量213", 97, 236, 207, 263),
            line("同伴", 82, 839, 135, 865),
            line("习武小童合☆", 82, 680, 287, 717),
            line("工分+10点", 81, 1159, 236, 1186),
            line("彩虹阿专", 348, 77, 567, 138),
            line("宠物职业小镇", 259, 143, 427, 180),
            line("奖励", 81, 1233, 134, 1259),
            line("智カ1", 340, 235, 415, 263),
            line("68", 162, 1233, 240, 1259),
            line("竹影武馆", 250, 402, 409, 441),
            line("筒介 堤着比自己还重的沙袋一路飞奔,虽然", 162, 945, 636, 974),
            line("消耗体力5(当前24),请洁2(当前3)", 162, 1085, 531, 1114),
            line("魅力1", 555, 231, 655, 265),
            line("规規则说明>", 512, 688, 634, 710),
            line("去打工", 310, 1367, 410, 1399),
            line("雇佣有額外加成", 444, 840, 638, 866),
            line("升星条件:获得该阶段工分 20/200", 144, 1467, 577, 1493),
        )
        assertEquals(WorkPageParser.TownPageKind.DETAIL, WorkPageParser.parseTownPage(live))
        assertEquals("竹影武馆", WorkPageParser.parseTownVenue(live))
    }

    @Test
    fun `theRewardScrolledVenueFrameIsStillATownDetailPage`() {
        // Measured 2026-10-20 s17: on the scrolled frame the option cards and their 用时 lines are
        // gone, and that is exactly why the confirm step used to fail WORK_PAGE_IDENTITY_UNCONFIRMED.
        // The sticky title and 升星条件 are what survive, so the page must still be a DETAIL page.
        val frame = stickyHeader + listOf(venueName) + rewardBlock + detailFooter
        assertEquals(WorkPageParser.TownPageKind.DETAIL, WorkPageParser.parseTownPage(frame))
    }

    @Test
    fun `theStickyTitleAloneIsNeverATownPage`() {
        // The header repeats on every town page, so a header-only frame - or a header plus ONE venue,
        // which is what a scrolled list row looks like - must resolve to NONE rather than be called
        // the town list on the strength of a title.
        assertEquals(WorkPageParser.TownPageKind.NONE, WorkPageParser.parseTownPage(stickyHeader))
        assertEquals(WorkPageParser.TownPageKind.NONE, WorkPageParser.parseTownPage(
            stickyHeader + listOf(venueName)))
    }

    @Test
    fun `aTownListWithItsVenuesIsATownListAndNotADetailPage`() {
        val frame = stickyHeader + listOf(
            venueName,
            line("风铃旅社", 276, 1138, 475, 1170),
            line("彩虹画室", 150, 431, 435, 461),
        )
        assertEquals(WorkPageParser.TownPageKind.LIST, WorkPageParser.parseTownPage(frame))
    }

    @Test
    fun `theRoomFrameIsNotATownPage`() {
        // The recovery path in PetNavigation taps the back affordance on whatever this returns, so a
        // page that is simply not the town has to come back NONE - the room included.
        val room = listOf(line("体力", 40, 900, 140, 940), line("清洁", 40, 960, 140, 1000))
        assertEquals(WorkPageParser.TownPageKind.NONE, WorkPageParser.parseTownPage(room))
        assertNull(WorkPageParser.parseTownVenue(room))
    }

    @Test
    fun `aDetailFrameNamingNoKnownVenueResolvesToNoVenue`() {
        // The reader only CONFIRMS a detail page when a venue name is on it: the flow selects a
        // venue by name, so an unnamed page cannot prove which one was opened.
        val frame = stickyHeader + listOf(
            line("金币 8", 560, 100, 700, 130),
        ) + detailFooter
        assertEquals(WorkPageParser.TownPageKind.DETAIL, WorkPageParser.parseTownPage(frame))
        assertNull(WorkPageParser.parseTownVenue(frame))
    }

    @Test
    fun `aWorkingOrPayrollOverlayNeverAuthorisesLeavingTown`() {
        // A shift in progress and the payroll dialog are overlays ON the town page, not the town
        // page itself: leaving the town while one of them is up would tap through the overlay.
        val detail = stickyHeader + listOf(venueName) + cards + cardDurations + detailFooter
        for (marker in listOf("打工中") + WorkPageParser.PAYROLL_MARKERS) {
            val overlay = line(marker, 100, 600, 600, 640)
            assertEquals(marker, WorkPageParser.TownPageKind.NONE,
                WorkPageParser.parseTownPage(detail + overlay))
        }
    }

    @Test
    fun `partialDetailMarkersCannotFallBackToTheTownList`() {
        // A frame that shows ONE partial detail marker is a detail page whose reading is incomplete.
        // It must be NONE, not the town list: the recovery taps the page's back affordance, and a
        // half-read detail page is not a page the flow may walk. 升星条件 is deliberately NOT in this
        // list - it names the venue detail page and no other (the course page writes 升X年级条件), so
        // it is a full marker, not a partial one.
        val town = stickyHeader + listOf(
            venueName,
            line("风铃旅社", 276, 1138, 475, 1170),
            line("彩虹画室", 150, 431, 435, 461),
        )
        for (marker in listOf("去打工", "用时：10分钟", "规则说明")) {
            assertEquals(marker, WorkPageParser.TownPageKind.NONE,
                WorkPageParser.parseTownPage(town + line(marker, 100, 1000, 400, 1040)))
        }
    }

    @Test
    fun `aVenueDetailPageWhoseStartButtonIsDisabledByLowCleanlinessIsStillADetailPage`() {
        // Measured 2026-10-20 (r21): with 清洁 0 against the work's own 清洁 2 cost, the game replaces
        // 去打工 with a "你的宠物清洁值不足，请回家洗澡" banner and greys the button out. 升星条件 is
        // the one footer that survives and the one that names the venue detail page uniquely, so the
        // page must still resolve to DETAIL - making 去打工 a required identity marker turned this
        // frame into CAREER_TOWN_PAGE_UNCONFIRMED and stopped the shift before it could meet the cost.
        val disabled = stickyHeader + listOf(venueName) + cards + cardDurations + listOf(
            line("你的宠物清洁值不足,请回家洗澡", 105, 1366, 614, 1402),
            line("升星条件:获得该阶段工分 20/200", 144, 1467, 577, 1493),
        )
        assertEquals(WorkPageParser.TownPageKind.DETAIL, WorkPageParser.parseTownPage(disabled))
        assertEquals("竹影武馆", WorkPageParser.parseTownVenue(disabled))
    }
}
