package com.ynk.virtualdisplay

import com.ynk.virtualdisplay.automation.CourseCostReading
import org.junit.Assert.*
import org.junit.Test

class CourseCostReadingTest {
    @Test fun readsTheRealOcrConsumptionRowWithoutConfusingEnergyForCoins() {
        assertEquals(CourseCostReading(22, 1), CourseCostReading.parse("|消耗@22(当前@1),体力5(当前84),清洁2(当前82)"))
        assertEquals(CourseCostReading(44, 100), CourseCostReading.parse("消耗 44（当前100），体力5（当前92）"))
    }

    @Test fun aDifferentCourseUsesItsOwnTuitionAndAZeroBalanceIsReadable() {
        assertEquals(CourseCostReading(66, 0), CourseCostReading.parse("消耗66(当前0),体力10(当前80)"))
    }

    @Test fun incompleteOrEnergyOnlyReadingsCannotSupplyCoinFacts() {
        assertNull(CourseCostReading.parse("消耗体力5(当前80),清洁2(当前88)"))
        assertNull(CourseCostReading.parse("消耗22 当前1"))
        assertNull(CourseCostReading.parse("金币不足"))
    }

    /**
     * Measured 2026-10-20 17:56 on 田径运动课: ML Kit digitised the coin icon as a leading "0"
     * sitting between 消耗 and the real tuition, so the strict "消耗…digits（当前" pattern missed.
     * The first positive （当前 pair before 体力 is still the coin column.
     */
    @Test fun aDigitisedCoinIconBeforeTheTuitionIsSkipped() {
        assertEquals(
            CourseCostReading(66, 1),
            CourseCostReading.parse("消 耗 0 66 （ 当 前 01 ） ， 体 力 15 （ 当 前 14 ） ， 清 洁 6 （ 当 前 0 ）"))
        assertEquals(
            CourseCostReading(22, 1),
            CourseCostReading.parse("消耗 0 22（当前 01），体力 5（当前 14），清洁 2（当前 0）"))
    }

    @Test fun aZeroTuitionGhostPairIsSkippedForTheRealCoinColumn() {
        // If the icon is itself emitted as "0（当前 N）", that pair is non-positive and must not
        // be taken as tuition; the next positive pair is the real coin column.
        assertEquals(
            CourseCostReading(66, 1),
            CourseCostReading.parse("消耗 0（当前 99）66（当前 01），体力15（当前14）"))
    }

    @Test fun theFallbackStillRequiresTheConsumptionLabel() {
        assertNull(CourseCostReading.parse("66（当前 01），体力15（当前14）"))
    }

    /**
     * Measured 2026-10-20 19:39 session 1792496334314: the 田径运动课 intro says
     * "提升体力和反应" BEFORE the 消耗 row. Cutting at the first 体力 on the page used to
     * discard the coin column even though ML Kit had already emitted
     * "|消耗66(当前1),体力15(当前13),". The window must start at 消耗.
     */
    @Test fun anIntroThatMentionsEnergyBeforeTheCostRowStillYieldsTuition() {
        val page = listOf(
            "宠物学园", "魅力1", "智力1", "力量213", "初级学园",
            "但初级学园 4年级", "视则说明》", "闪亮自我表这课", "课", "田径运动课",
            "用时: 10分钟", "用时: 30分钟", "中",
            "简介 练基础跑跳,提升体力和反应,力量", "越来越强。",
            "|消耗66(当前1),体力15(当前13),", "清洁6(当前0)", "学!",
            "|你的金币不足,请打工补充金币",
            "升5年级件:获得该阶段学分60/240",
        ).joinToString(" ")
        assertEquals(CourseCostReading(66, 1), CourseCostReading.parse(page))
    }
}
