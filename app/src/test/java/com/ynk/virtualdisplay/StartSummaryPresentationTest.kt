package com.ynk.virtualdisplay

import com.ynk.virtualdisplay.automation.AdventureStep
import com.ynk.virtualdisplay.automation.CareStep
import com.ynk.virtualdisplay.automation.CourseCatalog
import com.ynk.virtualdisplay.automation.CourseEntry
import com.ynk.virtualdisplay.automation.LuckyBagStep
import com.ynk.virtualdisplay.automation.PlanV2
import com.ynk.virtualdisplay.automation.SignInStep
import com.ynk.virtualdisplay.automation.StudyRotation
import com.ynk.virtualdisplay.automation.StudyStep
import com.ynk.virtualdisplay.automation.WorkStep
import com.ynk.virtualdisplay.automation.WorkTrigger
import com.ynk.virtualdisplay.ui.screens.StartSummaryPresentation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 引导清单第八节：开始前只报能确定的事，缺数据就说缺，不按默认值猜。 */
class StartSummaryPresentationTest {

    private fun plan(vararg steps: com.ynk.virtualdisplay.automation.PlanStep, budget: Int = 120) =
        PlanV2(planId = "p1", name = "测试方案", dailyBudgetMinutes = budget, steps = steps.toList())

    private fun catalog(vararg courses: Triple<String, String, Int?>) = CourseCatalog(
        scannedAtMs = 1_700_000_000_000,
        courses = courses.mapIndexed { index, (id, name, minutes) ->
            CourseEntry(id, name, minutes, unlocked = true, rowTop = index * 10, rowBottom = index * 10 + 9)
        },
        complete = true,
    )

    private fun valueOf(lines: List<com.ynk.virtualdisplay.ui.screens.StartSummaryLine>, label: String) =
        lines.first { it.label == label }.value

    @Test fun unresolvedCourseDurationIsReportedAsUnreadInsteadOfAGuess() {
        val plan = plan(StudyStep("s1", "study:剑术基础", rounds = 2))
        val lines = StartSummaryPresentation.lines(plan, catalog = null, budgetMinutes = 120)
        val duration = valueOf(lines, "预计占用")
        assertTrue(duration.contains("未读取"))
        assertTrue(duration.contains("以课程页为准"))
        assertFalse(duration.contains("20 分钟"))
        assertFalse(duration.contains("10 分钟"))
    }

    @Test fun rotationMinutesFollowTheRealLessonOrder() {
        val plan = plan(StudyStep(
            "s1", "study:剑术", rounds = 4,
            rotation = listOf(
                StudyRotation("力量", "study:剑术"),
                StudyRotation("智力", "study:棋艺"),
                StudyRotation("魅力", "study:礼仪"),
            ),
        ))
        val lines = StartSummaryPresentation.lines(
            plan,
            catalog(
                Triple("study:剑术", "剑术", 10),
                Triple("study:棋艺", "棋艺", 20),
                Triple("study:礼仪", "礼仪", 30),
            ),
            budgetMinutes = 120,
        )
        assertTrue(valueOf(lines, "预计占用").contains("约 70 分钟"))
    }

    @Test fun conditionalWorkIsNotCountedAsFixedTime() {
        val plan = plan(
            WorkStep("w1", "work:竹影武馆/帮武馆跑腿", WorkTrigger.IF_FUNDS_SHORT),
            StudyStep("s1", "study:剑术", rounds = 1),
        )
        val lines = StartSummaryPresentation.lines(
            plan, catalog(Triple("study:剑术", "剑术", 10)), budgetMinutes = 120,
        )
        val duration = valueOf(lines, "预计占用")
        assertTrue(duration.contains("约 10 分钟"))
        assertTrue(duration.contains("缺钱时的打工未计入"))
    }

    @Test fun knownCostsComeFromThePlanAndTheWorkCatalog() {
        val plan = plan(
            WorkStep("w1", "work:竹影武馆/帮武馆跑腿"),
            CareStep("c1", energyThreshold = 70, cleanlinessThreshold = 65),
        )
        val lines = StartSummaryPresentation.lines(plan, catalog = null, budgetMinutes = 90)
        val resources = valueOf(lines, "已知消耗")
        assertTrue(resources.contains("体力≥70"))
        assertTrue(resources.contains("清洁≥65"))
        assertTrue(resources.contains("耗体力 5"))
        assertTrue(resources.contains("回报 +68 金币"))
        assertTrue(resources.contains("每日预算 ≤ 90 分钟"))
        assertTrue(resources.contains("学费"))
        assertTrue(resources.contains("不显示数字"))
    }

    @Test fun orderLineNamesEveryStepAndTheLuckyBagCap() {
        val plan = plan(
            SignInStep("i1"),
            LuckyBagStep("l1", maxDailyCount = 3),
            AdventureStep("a1", rounds = 2),
        )
        val order = valueOf(StartSummaryPresentation.lines(plan, catalog = null, budgetMinutes = 60), "执行顺序")
        assertTrue(order.contains("签到"))
        assertTrue(order.contains("福袋（最多 3 次）"))
        assertTrue(order.contains("冒险 2 轮"))
    }
}
