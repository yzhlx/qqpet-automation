package com.ynk.virtualdisplay

import com.ynk.virtualdisplay.automation.*
import com.ynk.virtualdisplay.ui.records.RecordsPresentation
import org.junit.Assert.*
import org.junit.Test

class PlanOrchestrationTest {

    private val workId = "work:bamboo_runner"
    private val work = WorkStep("w", workId)
    private val care = CareStep("c", 80, 80)
    private val study = StudyStep("s", "study:蹦蹦跳跳体能课", 2)
    private val adv = AdventureStep("a", AdventureMode.NEARBY_WALK, 1)

    @Test
    fun planV2AcceptsAllFourStepsInStrictOrder() {
        val plan = PlanV2(
            planId = "plan-all-4",
            name = "完整四步编排方案",
            dailyBudgetMinutes = 180,
            steps = listOf(work, care, study, adv)
        )
        assertEquals(4, plan.steps.size)
        assertEquals(listOf(PlanStepType.WORK, PlanStepType.CARE, PlanStepType.STUDY, PlanStepType.ADVENTURE), plan.steps.map { it.type })
    }

    @Test
    fun planV2RejectsDuplicateStepTypeOrReversedOrder() {
        try {
            PlanV2(
                planId = "p-rev",
                name = "逆序方案",
                dailyBudgetMinutes = 180,
                steps = listOf(adv, study)
            )
            fail("Expected PLAN_STEP_ORDER_INVALID")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("PLAN_STEP_ORDER_INVALID") == true)
        }

        try {
            PlanV2(
                planId = "p-dup",
                name = "重复步骤方案",
                dailyBudgetMinutes = 180,
                steps = listOf(adv, AdventureStep("a2", AdventureMode.POETRY_AND_DISTANT, 1))
            )
            fail("Expected PLAN_STEP_TYPE_DUPLICATE")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("PLAN_STEP_TYPE_DUPLICATE") == true)
        }
    }

    @Test
    fun adventureStepRoundsAreValidated() {
        assertEquals(1, adv.rounds)
        assertEquals(AdventureMode.NEARBY_WALK, adv.mode)

        val longAdv = AdventureStep("a-long", AdventureMode.POETRY_AND_DISTANT, 5)
        assertEquals(5, longAdv.rounds)
        assertEquals(AdventureMode.POETRY_AND_DISTANT, longAdv.mode)

        try {
            AdventureStep("a-0", rounds = 0)
            fail("Expected PLAN_ADVENTURE_ROUNDS_OUT_OF_RANGE")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("PLAN_ADVENTURE_ROUNDS_OUT_OF_RANGE") == true)
        }

        try {
            AdventureStep("a-11", rounds = 11)
            fail("Expected PLAN_ADVENTURE_ROUNDS_OUT_OF_RANGE")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("PLAN_ADVENTURE_ROUNDS_OUT_OF_RANGE") == true)
        }
    }

    @Test
    fun jsonCodecEncodesAndDecodesAdventureStep() {
        val plan = PlanV2(
            planId = "plan-adv",
            name = "冒险专属方案",
            dailyBudgetMinutes = 120,
            steps = listOf(care, adv)
        )
        val json = PlanV2JsonCodec.encode(plan)
        val decoded = PlanV2JsonCodec.decode(json)

        assertEquals(plan.planId, decoded.planId)
        assertEquals(plan.name, decoded.name)
        assertEquals(2, decoded.steps.size)
        val decodedAdv = decoded.steps.filterIsInstance<AdventureStep>().single()
        assertEquals(adv.stepId, decodedAdv.stepId)
        assertEquals(adv.mode, decodedAdv.mode)
        assertEquals(adv.rounds, decodedAdv.rounds)
    }

    @Test
    fun runCountsTrackAllCompletedStepsWithoutMiscount() {
        val counts = RunCounts(
            workShiftsCompleted = 1,
            careCompleted = 1,
            studyRoundsCompleted = 3,
            adventureCompleted = 2
        )
        assertEquals(1, counts.workShiftsCompleted)
        assertEquals(1, counts.careCompleted)
        assertEquals(3, counts.studyRoundsCompleted)
        assertEquals(2, counts.adventureCompleted)

        try {
            RunCounts(adventureCompleted = -1)
            fail("Expected RUN_COUNT_NEGATIVE")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("RUN_COUNT_NEGATIVE") == true)
        }
    }

    @Test
    fun sessionModeRecognizesAdventure() {
        assertEquals(SessionMode.ADVENTURE, SessionMode.fromWire("adventure"))
        assertTrue(SessionMode.ADVENTURE.runsAdventure)
        assertFalse(SessionMode.ADVENTURE.runsStudy)
        assertFalse(SessionMode.ADVENTURE.runsWork)
        assertFalse(SessionMode.ADVENTURE.runsCare)
    }

    @Test
    fun stopReasonTextCoversDistinctFailureCategories() {
        assertEquals("系统权限不足，请在排故页检查 Shizuku 与显示授权", RecordsPresentation.stopReasonText("SHIZUKU_UNAUTHORIZED"))
                assertEquals("界面未识别目标按钮或入口，请确认 QQ 处于宠物页面", RecordsPresentation.stopReasonText("BUTTON_NOT_FOUND"))
        assertEquals("冒险入口未找到或已达今日次数上限", RecordsPresentation.stopReasonText("ADVENTURE_ENTRY_NOT_FOUND"))
        assertEquals("金币余额不足以支付学费或开销", RecordsPresentation.stopReasonText("COURSE_COINS_INSUFFICIENT"))
        assertEquals("宠物当前体力不足，请先喂食护理", RecordsPresentation.stopReasonText("WORK_ENERGY_INSUFFICIENT"))
        assertEquals("背包护理道具不足", RecordsPresentation.stopReasonText("COOKIE_OUT_OF_STOCK"))
        assertEquals("候选打工岗位均未达到开工条件", RecordsPresentation.stopReasonText("WORK_ALL_CANDIDATES_REFUSED"))
        assertEquals("你手动暂停", RecordsPresentation.stopReasonText(ManualPause.REASON))
        assertEquals("今天蟹累了，明天再来", RecordsPresentation.stopReasonText("DAILY_BUDGET_LIMIT"))
    }
}
