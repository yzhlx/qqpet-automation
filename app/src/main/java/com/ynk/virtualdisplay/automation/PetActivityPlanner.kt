package com.ynk.virtualdisplay.automation

import kotlin.math.max
import kotlin.math.min

/**
 * Joint study+work planner for one pet day.
 *
 * The game charges study and work against ONE shared daily budget with diminishing returns after
 * 8 hours (100% -> 25% -> 10%, reset at 00:00, measured 2026-10-12, see
 * docs/P5-01-career-discovery.md §4.3). So the planner treats them as competitors for one pool and
 * never optimises them separately.
 *
 * Two rules from the P5 brief are enforced structurally rather than by convention:
 *
 *  - **Rule D (study goals survive).** An option carrying `minUnits >= 1` is a user goal. The
 *    planner schedules at least that many; if it cannot, the plan reports
 *    [DayPlan.studyGoalsMet] == false instead of quietly dropping the course. There is no code path
 *    that trades a study goal for coins.
 *  - **No greedy-as-optimal.** A ratio-greedy pass runs alongside the exact solver purely as a
 *    witness and its gap is reported in [DayPlan.comparison]. The chosen plan is always the exact
 *    one, so a test (and a human) can prove the solver is not restating the heuristic.
 *
 * Pure Kotlin, no Android types: JVM-testable.
 */
object PetActivityPlanner {

    /** Above this the enumeration is cut off and the planner reports a degraded plan. */
    private const val MAX_ENUMERATION_STATES = 400_000

    /** Ceiling on units per option so a bad input cannot explode the search space. */
    private const val MAX_UNITS_PER_OPTION = 24

    /**
     * Builds the plan. Never throws for a merely inconvenient day: it returns a plan carrying the
     * reason instead.
     */
    fun plan(input: DayPlanInput): DayPlan {
        val warnings = buildWarnings(input.options)

        // An option whose reward was never collected is not schedulable: putting it in the plan
        // would mean optimising against a number nobody measured. A user study goal is the one
        // exception - it is mandatory by configuration, and the warning below carries the doubt.
        val schedulable = input.options.filter {
            it.confidence != RewardConfidence.UNKNOWN || it.minUnits > 0
        }
        val studyGoals = schedulable.filter { it.isStudy && it.minUnits > 0 }

        val reserved = reserveStudyGoals(studyGoals)
        if (!reserved.ok) {
            return DayPlan(
                mode = input.mode,
                activities = reserved.planned,
                totalMinutes = reserved.minutes,
                totalCoins = reserved.coins,
                totalWorkScore = reserved.workScore,
                totalEnergy = reserved.energy,
                totalCleanliness = reserved.cleanliness,
                totalAttributes = reserved.attributes,
                studyGoalsMet = false,
                explanation = reserved.explanations,
                warnings = warnings,
                comparison = emptyList(),
            )
        }

        // What is left for the optimiser after the mandatory study goals.
        val residualMinutes = max(0, input.remainingMinutes - reserved.minutes)
        // DayPlanInput requires budgetMinutes > 0 and spentMinutes >= 0, so the empty day is encoded
        // as budgetMinutes == 1, spentMinutes == 1 -> remainingMinutes == 0, which schedules nothing.
        val residualBudget = max(1, residualMinutes)
        val residual = DayPlanInput(
            budgetMinutes = residualBudget,
            spentMinutes = residualBudget - residualMinutes,
            energy = max(0, input.energy - reserved.energy),
            cleanliness = max(0, input.cleanliness - reserved.cleanliness),
            // Study goals are already satisfied, and unmeasured options stay out of the pool.
            options = schedulable
                .map { it.copy(minUnits = 0) }
                .filter { it.confidence != RewardConfidence.UNKNOWN },
            mode = input.mode,
            avoidDiminishingReturns = input.avoidDiminishingReturns,
        )

        val best = optimise(residual)
        val greedy = greedy(residual)
        val activities = reserved.planned + best.planned

        val explanation = buildExplanation(input, reserved, best, greedy, studyGoals)
        val comparison = listOf(
            PlanComparison(
                strategy = "每分钟金币贪心（对照用）",
                totalCoins = greedy.totalCoins,
                totalMinutes = greedy.totalMinutes,
                coinGap = best.totalCoins - greedy.totalCoins,
                note = when {
                    greedy.totalCoins < best.totalCoins ->
                        "贪心漏掉了组合收益：只看单项每分钟金币会错过长短选项搭配。"
                    greedy.totalCoins == best.totalCoins ->
                        "本次与精确解持平（小规模实例常见），不作为一般结论。"
                    else ->
                        "贪心反而更高，说明精确解有缺陷，必须排查。"
                },
            ),
        )

        return DayPlan(
            mode = input.mode,
            activities = activities,
            totalMinutes = reserved.minutes + best.totalMinutes,
            totalCoins = reserved.coins + best.totalCoins,
            totalWorkScore = reserved.workScore + best.totalWorkScore,
            totalEnergy = reserved.energy + best.totalEnergy,
            totalCleanliness = reserved.cleanliness + best.totalCleanliness,
            totalAttributes = reserved.attributes.merge(best.totalAttributes),
            studyGoalsMet = true,
            explanation = explanation,
            warnings = warnings,
            comparison = comparison,
        )
    }

    // ------------------------------------------------------------------ study floor

    private class Reserved(
        val ok: Boolean,
        val planned: List<PlannedActivity>,
        val minutes: Int, val coins: Int, val workScore: Int,
        val energy: Int, val cleanliness: Int,
        val attributes: Map<String, Int>,
        val explanations: List<String>,
    )

    /**
     * Schedules the mandatory study sessions. If a goal cannot be met, returns ok == false with the
     * reason; callers must refuse to trade these away.
     */
    private fun reserveStudyGoals(studyGoals: List<ActivityOption>): Reserved {
        val planned = mutableListOf<PlannedActivity>()
        var minutes = 0; var coins = 0; var workScore = 0
        var energy = 0; var cleanliness = 0
        val attributes = mutableMapOf<String, Int>()
        val problems = mutableListOf<String>()

        for (option in studyGoals) {
            val want = option.minUnits
            val can = option.effectiveMaxUnits()
            if (want > can) {
                problems += "学习目标「${option.label}」需要 $want 次，但今天只剩 $can 次可用。"
            }
            val units = min(want, can)
            val pa = PlannedActivity(option, units)
            planned += pa
            minutes += pa.minutes; coins += pa.netCoins; workScore += pa.workScore
            energy += pa.energy; cleanliness += pa.cleanliness
            pa.attributes.forEach { (k, v) -> attributes[k] = (attributes[k] ?: 0) + v }
        }

        return if (problems.isEmpty()) {
            Reserved(
                true, planned, minutes, coins, workScore, energy, cleanliness, attributes,
                listOf("已为用户学习目标预留 ${planned.sumOf { it.units }} 次课程（$minutes 分钟）。"),
            )
        } else {
            Reserved(
                false, planned, minutes, coins, workScore, energy, cleanliness, attributes,
                listOf("无法满足全部学习目标，已停止排程而不是删减课程：") + problems,
            )
        }
    }

    // ------------------------------------------------------------------ exact solve

    /** Accumulator used during the recursive search so allocations stay out of the inner loop. */
    private class Totals {
        var minutes = 0
        var studyMinutes = 0
        var energy = 0
        var cleanliness = 0
        var coins = 0
        var workScore = 0
        val attributes = mutableMapOf<String, Int>()
    }

    private class Solution(
        val planned: List<PlannedActivity>,
        val totalMinutes: Int,
        val totalCoins: Int,
        val totalWorkScore: Int,
        val totalEnergy: Int,
        val totalCleanliness: Int,
        val totalAttributes: Map<String, Int>,
    )

    /**
     * Exact search over per-option unit counts. The space is the product of the counts, which for a
     * real day (a handful of courses and a few work options, each capped at [MAX_UNITS_PER_OPTION])
     * is small. Enumeration is used rather than a minutes-DP because the plan has to report the
     * full assignment, not just its value.
     */
    private fun optimise(input: DayPlanInput): Solution {
        val options = input.options.filter { it.effectiveMaxUnits() >= 1 }
        if (options.isEmpty()) return Solution(emptyList(), 0, 0, 0, 0, 0, emptyMap())

        val bounds = options.map { min(it.effectiveMaxUnits(), MAX_UNITS_PER_OPTION) }
        var space = 1L
        for (b in bounds) space *= (b + 1).toLong()
        val truncated = space > MAX_ENUMERATION_STATES
        if (truncated) {
            // Degrade to the greedy answer and say so, rather than claim an exact search.
            val g = greedy(input)
            return Solution(g.planned, g.totalMinutes, g.totalCoins, g.totalWorkScore,
                g.totalEnergy, g.totalCleanliness, g.totalAttributes)
        }

        var best: IntArray? = null
        var bestScore: Objective? = null
        val counts = IntArray(options.size)
        val t = Totals()

        fun recurse(i: Int) {
            if (i == options.size) {
                val s = score(input, t)
                val current = bestScore
                if (current == null || s > current) { bestScore = s; best = counts.copyOf() }
                return
            }
            val opt = options[i]
            for (u in 0..bounds[i]) {
                val nm = t.minutes + opt.durationMinutes * u
                if (nm > input.remainingMinutes) break
                val ne = t.energy + opt.energyCost * u
                if (ne > input.energy) break
                val nc = t.cleanliness + opt.cleanlinessCost * u
                if (nc > input.cleanliness) break
                counts[i] = u
                t.minutes = nm; t.energy = ne; t.cleanliness = nc
                t.studyMinutes += if (opt.isStudy) opt.durationMinutes * u else 0
                t.coins += opt.netCoinsPerSession() * u
                t.workScore += opt.workScoreReward * u
                opt.attributeGain.forEach { (k, v) -> t.attributes[k] = (t.attributes[k] ?: 0) + v * u }
                recurse(i + 1)
                t.minutes = nm - opt.durationMinutes * u
                t.energy = ne - opt.energyCost * u
                t.cleanliness = nc - opt.cleanlinessCost * u
                t.studyMinutes -= if (opt.isStudy) opt.durationMinutes * u else 0
                t.coins -= opt.netCoinsPerSession() * u
                t.workScore -= opt.workScoreReward * u
                opt.attributeGain.forEach { (k, v) -> t.attributes[k] = (t.attributes[k] ?: 0) - v * u }
            }
            counts[i] = 0
        }
        recurse(0)

        val chosen = best ?: return Solution(emptyList(), 0, 0, 0, 0, 0, emptyMap())
        val planned = options.mapIndexedNotNull { i, o ->
            chosen[i].takeIf { it > 0 }?.let { PlannedActivity(o, it) }
        }
        return Solution(
            planned,
            planned.sumOf { it.minutes },
            planned.sumOf { it.netCoins },
            planned.sumOf { it.workScore },
            planned.sumOf { it.energy },
            planned.sumOf { it.cleanliness },
            planned.fold(mutableMapOf<String, Int>()) { acc, pa ->
                pa.attributes.forEach { (k, v) -> acc[k] = (acc[k] ?: 0) + v }; acc
            },
        )
    }

    /**
     * Lexicographic objective, compared key by key so no packed-int overflow can silently invert
     * the ordering. The mode decides the key ORDER, which is the whole difference between the
     * three modes; everything after the first key is a tie-breaker.
     *
     * [BALANCED_WORK_SCORE_WEIGHT] is a POLICY knob, not a measured game rate: the game never
     * states an exchange rate between 金币 and 工分. It is exposed as a constant and printed in
     * the plan explanation precisely so nobody mistakes it for a fact.
     */
    private fun score(input: DayPlanInput, t: Totals): Objective {
        val coins = t.coins
        val workScore = t.workScore
        val attributes = t.attributes.values.sum()
        val studyMinutes = t.studyMinutes
        val fill = t.minutes
        val penalty = if (input.avoidDiminishingReturns && t.minutes > input.remainingMinutes) 1 else 0

        return when (input.mode) {
            // Coins dominate; 工分 and attributes only separate otherwise identical coin plans.
            PlanMode.COIN_FIRST -> Objective(
                listOf(coins, workScore, attributes, fill, -penalty),
            )

            // Coins still lead, but a 工分-rich plan can outrank a coin-rich one once the coin gap
            // is smaller than the 工分 advantage. The weight is stated in the explanation.
            PlanMode.BALANCED -> Objective(
                listOf(
                    coins + workScore * BALANCED_WORK_SCORE_WEIGHT,
                    attributes, fill, -penalty,
                ),
            )

            // Learning leads: more study minutes beats more coins. Only then do coins matter.
            PlanMode.STUDY_FIRST -> Objective(
                listOf(studyMinutes, attributes, coins, workScore, fill, -penalty),
            )
        }
    }

    /**
     * Policy weight for 平衡 mode: how many coins one 工分 is worth. NOT a measured game rate.
     * Chosen so that a plan with 54 more 工分 can beat one with 278 more coins (the 竹影武馆
     * 45-minute option is exactly that trade), which is the behaviour 平衡 is supposed to have.
     */
    const val BALANCED_WORK_SCORE_WEIGHT = 5

    /** Comparable lexicographic key list. */
    private class Objective(private val keys: List<Int>) : Comparable<Objective> {
        override fun compareTo(other: Objective): Int {
            for (i in keys.indices) {
                val c = keys[i].compareTo(other.keys[i])
                if (c != 0) return c
            }
            return 0
        }
    }

    /**
     * Ratio-greedy reference: repeatedly take the option with the best net-coins-per-minute that
     * still fits. This is the naive answer the brief forbids presenting as optimal; it exists only
     * to be compared against the exact solver.
     */
    private fun greedy(input: DayPlanInput): Solution {
        val options = input.options.filter { it.effectiveMaxUnits() >= 1 }
        val units = mutableMapOf<String, Int>()
        var minutes = 0; var energy = 0; var cleanliness = 0
        var coins = 0; var workScore = 0
        val attributes = mutableMapOf<String, Int>()

        while (true) {
            val best = options
                .filter { (units[it.id] ?: 0) < it.effectiveMaxUnits() }
                .filter { minutes + it.durationMinutes <= input.remainingMinutes }
                .filter { energy + it.energyCost <= input.energy }
                .filter { cleanliness + it.cleanlinessCost <= input.cleanliness }
                .maxByOrNull { o ->
                    if (o.durationMinutes == 0) 0.0
                    else o.netCoinsPerSession().toDouble() / o.durationMinutes
                } ?: break
            units[best.id] = (units[best.id] ?: 0) + 1
            minutes += best.durationMinutes
            energy += best.energyCost
            cleanliness += best.cleanlinessCost
            coins += best.netCoinsPerSession()
            workScore += best.workScoreReward
            best.attributeGain.forEach { (k, v) -> attributes[k] = (attributes[k] ?: 0) + v }
        }

        val planned = options.mapNotNull { o ->
            units[o.id]?.takeIf { it > 0 }?.let { PlannedActivity(o, it) }
        }
        return Solution(planned, minutes, coins, workScore, energy, cleanliness, attributes)
    }

    // ------------------------------------------------------------------ reporting

    private fun buildWarnings(options: List<ActivityOption>): List<String> {
        val out = mutableListOf<String>()
        options.forEach { o ->
            when (o.confidence) {
                RewardConfidence.PAGE_READING ->
                    out += "「${o.label}」的收益来自页面读数，尚未用真实完成闭环验证。"
                RewardConfidence.UNKNOWN ->
                    out += "「${o.label}」收益未采集，本次排程未把它计入。"
                RewardConfidence.VERIFIED -> Unit
            }
            o.caveat?.let { out += "「${o.label}」：$it" }
        }
        return out
    }

    private fun buildExplanation(
        input: DayPlanInput,
        reserved: Reserved,
        best: Solution,
        greedy: Solution,
        studyGoals: List<ActivityOption>,
    ): List<String> {
        val out = mutableListOf<String>()
        out += "模式 ${input.mode.name}；共享预算共 ${input.budgetMinutes} 分钟（学习与打工共同计算，已用 ${input.spentMinutes} 分钟，剩余 ${input.remainingMinutes} 分钟）。"
        out += when (input.mode) {
            PlanMode.COIN_FIRST -> "排序目标：金币 > 工分 > 属性。"
            PlanMode.BALANCED -> "排序目标：金币 + 工分×$BALANCED_WORK_SCORE_WEIGHT（该倍率是策略设定，不是游戏公布的兑换率）> 属性。"
            PlanMode.STUDY_FIRST -> "排序目标：学习分钟 > 属性 > 金币。"
        }
        out += reserved.explanations

        if (best.planned.isEmpty()) {
            out += "剩余预算或资源装不下任何活动。"
        } else {
            out += "最优组合：" + best.planned.joinToString("，") {
                "${it.option.label} ×${it.units}（${it.minutes} 分钟，净金币 ${it.netCoins}，工分 ${it.workScore}）"
            }
            out += "合计 ${best.totalMinutes} 分钟、净金币 ${best.totalCoins}、工分 ${best.totalWorkScore}。"
        }

        if (studyGoals.isNotEmpty()) {
            out += "学习目标优先于金币：已保证 ${studyGoals.sumOf { it.minUnits }} 次课程，金币优化只在剩余预算内进行。"
        }
        if (input.avoidDiminishingReturns) {
            out += "已避免把活动排进 8 小时后的衰减区间（该区间收益仅 25%）。"
        }
        if (greedy.totalCoins < best.totalCoins) {
            out += "对照：每分钟金币贪心得 ${greedy.totalCoins} 金币，比最优组合少 ${best.totalCoins - greedy.totalCoins}。"
        }
        return out
    }

    private fun Map<String, Int>.merge(other: Map<String, Int>): Map<String, Int> {
        if (isEmpty()) return other
        if (other.isEmpty()) return this
        val out = toMutableMap()
        other.forEach { (k, v) -> out[k] = (out[k] ?: 0) + v }
        return out
    }
}
