package com.ynk.virtualdisplay

import com.ynk.virtualdisplay.automation.ActivityKind
import com.ynk.virtualdisplay.automation.ActivityOption
import com.ynk.virtualdisplay.automation.DayPlanInput
import com.ynk.virtualdisplay.automation.PetActivityPlanner
import com.ynk.virtualdisplay.automation.PlanMode
import com.ynk.virtualdisplay.automation.RewardConfidence
import org.junit.Assert.*
import org.junit.Test

/**
 * The joint study+work optimiser.
 *
 * The numbers below come from docs/P5-01-career-discovery.md (collected 2026-10-12). 竹影武馆's two
 * options are closed-loop/page-read pairs; 风铃旅社's long option is deliberately left OUT because
 * its reward was never collected - a plan must not be built on a number nobody measured.
 */
class PetActivityPlannerTest {

    /** 蹦蹦跳跳体能课: 10 minutes, costs 22 coins, grants 力量 +8. Measured on device. */
    private val tiaotiao = ActivityOption(
        id = "study:蹦蹦跳跳体能课",
        kind = ActivityKind.STUDY,
        label = "蹦蹦跳跳体能课",
        durationMinutes = 10,
        coinReward = 0,
        coinCost = 22,
        attributeGain = mapOf("力量" to 8),
        energyCost = 5,
        cleanlinessCost = 2,
        confidence = RewardConfidence.VERIFIED,
    )

    /** 竹影武馆·帮武馆跑腿: 10 min, 体力5 清洁2, 68 coins, 10 工分. Closed-loop verified. */
    private val paotu = ActivityOption(
        id = "work:竹影武馆/帮武馆跑腿",
        kind = ActivityKind.WORK,
        label = "帮武馆跑腿（10分钟）",
        durationMinutes = 10,
        coinReward = 68,
        workScoreReward = 10,
        energyCost = 5,
        cleanlinessCost = 2,
        confidence = RewardConfidence.VERIFIED,
    )

    /** 竹影武馆·陪练基础课: 45 min, 体力10, 278 coins, 54 工分. Page reading only. */
    private val peilian = ActivityOption(
        id = "work:竹影武馆/陪练基础课",
        kind = ActivityKind.WORK,
        label = "陪练基础课（45分钟）",
        durationMinutes = 45,
        coinReward = 278,
        workScoreReward = 54,
        energyCost = 10,
        cleanlinessCost = 4,
        confidence = RewardConfidence.PAGE_READING,
        caveat = "45 分钟收益尚未用真实打工闭环验证",
    )

    /** 风铃旅社·替摊主看摊: 10 min, 体力5 清洁2, 40 coins, 10 工分. Page reading only. */
    private val kantan = ActivityOption(
        id = "work:风铃旅社/替摊主看摊",
        kind = ActivityKind.WORK,
        label = "替摊主看摊（10分钟）",
        durationMinutes = 10,
        coinReward = 40,
        workScoreReward = 10,
        energyCost = 5,
        cleanlinessCost = 2,
        confidence = RewardConfidence.PAGE_READING,
    )

    private fun input(
        options: List<ActivityOption>,
        mode: PlanMode = PlanMode.BALANCED,
        budgetMinutes: Int = 480,
        spentMinutes: Int = 0,
        energy: Int = 100,
        cleanliness: Int = 100,
    ) = DayPlanInput(
        budgetMinutes = budgetMinutes,
        spentMinutes = spentMinutes,
        energy = energy,
        cleanliness = cleanliness,
        options = options,
        mode = mode,
    )

    /**
     * Total units scheduled for one option id. The planner emits a mandatory study goal as its own
     * [com.ynk.virtualdisplay.automation.PlannedActivity] and then may schedule more of the same
     * option in the optimiser, so the count has to be summed, not read from the first match.
     */
    private fun unitsFor(plan: com.ynk.virtualdisplay.automation.DayPlan, id: String): Int =
        plan.activities.filter { it.option.id == id }.sumOf { it.units }

    // ------------------------------------------------------------------ rule D

    @Test
    fun studyGoalIsNeverDroppedForCoins() {
        val plan = PetActivityPlanner.plan(
            input(
                options = listOf(tiaotiao.copy(minUnits = 3), paotu, peilian),
                mode = PlanMode.COIN_FIRST,
            ),
        )
        assertTrue("a coin-first plan still has to keep the user's study goal", plan.studyGoalsMet)
        assertEquals(3, unitsFor(plan, tiaotiao.id))
        // The course is paid for out of the budget, not skipped: 3 x 10 = 30 minutes reserved.
        assertEquals(30, plan.activities.first { it.option.id == tiaotiao.id }.minutes)
    }

    @Test
    fun unmetStudyGoalStopsThePlanInsteadOfTrimmingTheCourse() {
        val plan = PetActivityPlanner.plan(
            input(
                options = listOf(tiaotiao.copy(minUnits = 3, sessionsAvailable = 1), paotu),
                mode = PlanMode.COIN_FIRST,
            ),
        )
        assertFalse(plan.studyGoalsMet)
        assertTrue(
            "the reason must name the course",
            plan.explanation.any { it.contains("蹦蹦跳跳体能课") && it.contains("需要 3 次") },
        )
    }

    @Test
    fun studyMinutesComeOffTheSharedBudgetBeforeWorkIsPlanned() {
        val plan = PetActivityPlanner.plan(
            input(
                options = listOf(tiaotiao.copy(minUnits = 4), paotu),
                // 4 courses = 40 minutes, leaving 440 for work.
                budgetMinutes = 480,
            ),
        )
        assertEquals(4, unitsFor(plan, tiaotiao.id))
        val workMinutes = plan.activities.filter { it.option.isWork }.sumOf { it.minutes }
        assertTrue("work may not consume the courses' minutes", workMinutes <= 440)
        assertEquals(plan.totalMinutes, workMinutes + 40)
    }

    // ------------------------------------------------------------------ exactness

    /**
     * The whole point of the brief: a per-minute greedy must not be able to claim optimality.
     * 帮武馆跑腿 is 6.8 coins/min and 陪练基础课 is 6.18 coins/min, so greedy takes the short one
     * five times (340 coins, 50 min) and then has 5 minutes it cannot use. The exact solver sees
     * that 45 + 10 = 55 fits exactly and pays 278 + 68 = 346. Greedy loses by 6 coins.
     */
    @Test
    fun exactSolverBeatsPerMinuteGreedy() {
        val plan = PetActivityPlanner.plan(
            input(
                options = listOf(paotu, peilian),
                budgetMinutes = 55,
                energy = 100,
                cleanliness = 100,
                mode = PlanMode.COIN_FIRST,
            ),
        )
        assertEquals(346, plan.totalCoins)
        assertEquals(1, unitsFor(plan, peilian.id))
        assertEquals(1, unitsFor(plan, paotu.id))

        val comparison = plan.comparison.single()
        assertEquals(340, comparison.totalCoins)
        assertEquals(6, comparison.coinGap)
        assertTrue(comparison.note.contains("贪心"))
    }

    @Test
    fun greedyMatchesExactOnASmallInstanceWithoutClaimingAGeneralRule() {
        // 20 minutes: only the 10-minute option fits, so both answers are 2 x 68.
        val plan = PetActivityPlanner.plan(
            input(options = listOf(paotu), budgetMinutes = 20, mode = PlanMode.COIN_FIRST),
        )
        assertEquals(136, plan.totalCoins)
        val comparison = plan.comparison.single()
        assertEquals(136, comparison.totalCoins)
        assertEquals(0, comparison.coinGap)
        assertTrue(comparison.note.contains("持平"))
    }

    // ------------------------------------------------------------------ shared budget

    @Test
    fun studyAndWorkShareOneBudget() {
        val plan = PetActivityPlanner.plan(
            input(
                options = listOf(tiaotiao, paotu, peilian),
                budgetMinutes = 480,
                spentMinutes = 440,
                mode = PlanMode.COIN_FIRST,
            ),
        )
        // Only 40 minutes are left; the 45-minute work cannot fit, so the 10-minute option wins 4 times.
        assertTrue(plan.totalMinutes <= 40)
        assertEquals(0, unitsFor(plan, peilian.id))
        assertEquals(272, plan.totalCoins)
        assertEquals(4, unitsFor(plan, paotu.id))
    }

    @Test
    fun alreadySpentMinutesShrinkTheDay() {
        val plan = PetActivityPlanner.plan(
            input(options = listOf(paotu), budgetMinutes = 480, spentMinutes = 470),
        )
        assertEquals(1, unitsFor(plan, paotu.id))
        assertEquals(68, plan.totalCoins)
    }

    @Test
    fun exhaustedBudgetProducesAnEmptyPlanWithAReason() {
        val plan = PetActivityPlanner.plan(
            input(options = listOf(paotu, peilian), budgetMinutes = 480, spentMinutes = 480),
        )
        assertTrue(plan.isEmpty)
        assertTrue(plan.explanation.any { it.contains("装不下") || it.contains("没有可执行") })
    }

    // ------------------------------------------------------------------ resources

    @Test
    fun energyCapBlocksWorkThePetCannotAfford() {
        val plan = PetActivityPlanner.plan(
            input(options = listOf(paotu, peilian), budgetMinutes = 480, energy = 3),
        )
        assertTrue("体力3 cannot pay for a 5-cost session", plan.isEmpty)
    }

    @Test
    fun cleanlinessCapIsRespected() {
        val plan = PetActivityPlanner.plan(
            input(options = listOf(paotu), budgetMinutes = 480, cleanliness = 5),
        )
        // Two sessions cost 4 cleanliness; a third would cost 6.
        assertEquals(2, unitsFor(plan, paotu.id))
        assertEquals(4, plan.totalCleanliness)
    }

    @Test
    fun availabilityCapsTheSessionCount() {
        val plan = PetActivityPlanner.plan(
            input(options = listOf(paotu.copy(sessionsAvailable = 2)), budgetMinutes = 480),
        )
        assertEquals(2, unitsFor(plan, paotu.id))
    }

    // ------------------------------------------------------------------ modes

    @Test
    fun studyFirstCoversTheGoalAndThenKeepsChoosingStudy() {
        val plan = PetActivityPlanner.plan(
            input(
                options = listOf(tiaotiao.copy(minUnits = 2), paotu, peilian),
                mode = PlanMode.STUDY_FIRST,
            ),
        )
        assertTrue(plan.studyGoalsMet)
        // STUDY_FIRST ranks studyMinutes first, so it fills the day with the course rather than
        // stopping at the mandatory 2. The goal is a floor, never a ceiling.
        assertTrue("study-first should buy more than the bare goal", unitsFor(plan, tiaotiao.id) >= 2)
        // Every scheduled session is the course: no work at all until there is nothing left to learn.
        val workMinutes = plan.activities.filter { it.option.isWork }.sumOf { it.minutes }
        assertEquals(0, workMinutes)
    }

    @Test
    fun balancedModeStillMeetsEveryStudyGoal() {
        val plan = PetActivityPlanner.plan(
            input(
                options = listOf(tiaotiao.copy(minUnits = 2), paotu, peilian),
                mode = PlanMode.BALANCED,
            ),
        )
        assertTrue(plan.studyGoalsMet)
        assertEquals(2, unitsFor(plan, tiaotiao.id))
    }

    /**
     * The three modes must produce different answers on the same day, otherwise "three modes" is
     * just one mode with three names.
     *
     * Day: 100 minutes total, one 10-minute course goal, so 90 residual minutes; 体力/清洁 ample.
     *   COIN_FIRST  -> 1 × 蹦蹦跳跳 + 9 × 帮武馆跑腿 = 590 coins (612 - 22 tuition), 90 工分
     *   BALANCED    -> 1 × 蹦蹦跳跳 + 2 × 陪练基础课 = 534 coins but 108 工分
     *   STUDY_FIRST -> 10 × 蹦蹦跳跳 = 90 study minutes, -220 coins, 0 工分
     * (Values confirmed by running the suite; see docs/P5-02-profit-optimization.md §5.3.)
     */
    @Test
    fun theThreeModesDivergeOnTheSameDay() {
        val options = listOf(tiaotiao.copy(minUnits = 1), paotu, peilian)

        val coinFirst = PetActivityPlanner.plan(input(options = options, budgetMinutes = 100, mode = PlanMode.COIN_FIRST))
        val balanced = PetActivityPlanner.plan(input(options = options, budgetMinutes = 100, mode = PlanMode.BALANCED))
        val studyFirst = PetActivityPlanner.plan(input(options = options, budgetMinutes = 100, mode = PlanMode.STUDY_FIRST))

        // All three keep the mandatory course.
        assertTrue(coinFirst.studyGoalsMet && balanced.studyGoalsMet && studyFirst.studyGoalsMet)

        // COIN_FIRST: pure coin maximisation over the residual 90 minutes.
        assertEquals(1, unitsFor(coinFirst, tiaotiao.id))
        assertEquals(9, unitsFor(coinFirst, paotu.id))
        assertEquals(9 * 68 - 22, coinFirst.totalCoins)

        // BALANCED: gives up coins for 工分.
        assertEquals(1, unitsFor(balanced, tiaotiao.id))
        assertEquals(2, unitsFor(balanced, peilian.id))
        assertEquals(2 * 278 - 22, balanced.totalCoins)
        assertTrue("balanced should carry more 工分 than coin-first", balanced.totalWorkScore > coinFirst.totalWorkScore)
        assertTrue(balanced.totalCoins < coinFirst.totalCoins)

        // STUDY_FIRST: keeps buying the course even though it loses coins.
        assertEquals(10, unitsFor(studyFirst, tiaotiao.id))
        assertTrue("study-first spends more minutes learning", studyFirst.totalMinutes >= coinFirst.totalMinutes)
        assertTrue(studyFirst.totalCoins < coinFirst.totalCoins)
    }

    @Test
    fun balancedModeTradesCoinsForWorkScoreWithinItsWeight() {
        // 55 minutes, no study goal. COIN_FIRST takes 45+10 = 346 coins / 64 工分.
        // BALANCED scores coins + 工分*5, so 陪练基础课 (278 + 54*5 = 548) beats
        // 帮武馆跑腿 x5 (340 + 50*5 = 590)? No: 340+250 = 590 > 548, so balanced still picks
        // the short one. Use a budget where only the long option fits at scale instead.
        val plan = PetActivityPlanner.plan(
            input(options = listOf(peilian), budgetMinutes = 180, mode = PlanMode.BALANCED),
        )
        assertEquals(4, unitsFor(plan, peilian.id))
        assertEquals(1112, plan.totalCoins)
        assertEquals(216, plan.totalWorkScore)
        assertTrue(plan.explanation.any { it.contains("工分×5") && it.contains("不是游戏公布的兑换率") })
    }

    @Test
    fun explanationDisclosesTheWorkScoreWeightAsAPolicyChoice() {
        val plan = PetActivityPlanner.plan(
            input(options = listOf(paotu), mode = PlanMode.BALANCED),
        )
        assertTrue(
            "the 工分 weight is a policy knob, not a measured rate",
            plan.explanation.any { it.contains("策略设定") && it.contains("不是游戏公布的兑换率") },
        )
    }

    // ------------------------------------------------------------------ honesty

    @Test
    fun pageReadingRewardsAreFlaggedRatherThanPresentedAsVerified() {
        val plan = PetActivityPlanner.plan(input(options = listOf(peilian)))
        assertTrue(
            plan.warnings.any { it.contains("陪练基础课") && it.contains("页面读数") },
        )
    }

    @Test
    fun unknownRewardsAreExcludedAndSaySo() {
        val mystery = ActivityOption(
            id = "work:风铃旅社/街巷跑腿",
            kind = ActivityKind.WORK,
            label = "街巷跑腿（45分钟）",
            durationMinutes = 45,
            coinReward = 0,
            workScoreReward = 0,
            confidence = RewardConfidence.UNKNOWN,
            caveat = "收益未采集（QQ 限流阻塞）",
        )
        val plan = PetActivityPlanner.plan(input(options = listOf(paotu, mystery)))
        assertEquals(0, unitsFor(plan, mystery.id))
        assertTrue(plan.warnings.any { it.contains("街巷跑腿") && it.contains("未采集") })
    }

    @Test
    fun netCoinsSubtractCourseTuition() {
        val plan = PetActivityPlanner.plan(input(options = listOf(tiaotiao.copy(minUnits = 1))))
        // One 22-coin course: net -22 coins, 力量 +8.
        assertEquals(-22, plan.totalCoins)
        assertEquals(8, plan.totalAttributes["力量"])
    }

    @Test
    fun explanationNamesTheModeAndTheSharedBudget() {
        val plan = PetActivityPlanner.plan(input(options = listOf(paotu), spentMinutes = 120))
        assertTrue(plan.explanation.any { it.contains("BALANCED") })
        assertTrue(plan.explanation.any { it.contains("480") && it.contains("已用 120") })
    }

    @Test
    fun invalidOptionsAreRejected() {
        listOf(
            { ActivityOption("x", ActivityKind.WORK, "x", 0) },
            { ActivityOption("x", ActivityKind.WORK, "x", 10, minUnits = 2, maxUnits = 1) },
            { ActivityOption("x", ActivityKind.WORK, "x", 10, minUnits = -1) },
            { ActivityOption("x", ActivityKind.WORK, "x", 10, coinReward = -5) },
            { ActivityOption("x", ActivityKind.WORK, "x", 10, sessionsAvailable = -1) },
        ).forEachIndexed { i, block ->
            val ex = runCatching(block).exceptionOrNull()
            assertTrue("case $i should be rejected", ex is IllegalArgumentException)
        }
    }

    @Test
    fun emptyOptionListIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            DayPlanInput(energy = 10, cleanliness = 10, options = emptyList())
        }
    }
}
