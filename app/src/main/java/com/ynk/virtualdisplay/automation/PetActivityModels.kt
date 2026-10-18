package com.ynk.virtualdisplay.automation

import kotlin.math.max
import kotlin.math.min

/**
 * One schedulable activity for the pet's day.
 *
 * A work option and a study course are the SAME kind of thing here on purpose: the game charges
 * them against one shared daily budget (see [DailyBudgetStore.HARD_STOP_MINUTES] and the measured
 * rule in docs/P5-01-career-discovery.md §4.3), so the only honest model is a single pool of
 * activities competing for one allowance.
 *
 * `minUnits`/`maxUnits` bound how many times this activity may be scheduled. A user study goal
 * arrives with `minUnits >= 1` and the planner may NEVER schedule fewer: the learning target is a
 * constraint, not a preference the coin optimiser gets to drop.
 *
 * Pure data, no Android types, so the planner and its tests run on the JVM.
 */
data class ActivityOption(
    /** Stable id, e.g. "study:蹦蹦跳跳体能课" or "work:竹影武馆/帮武馆跑腿". */
    val id: String,
    val kind: ActivityKind,
    /** Human-readable label for logs/UI, e.g. "帮武馆跑腿（10分钟）". */
    val label: String,
    /** Game minutes charged against the shared daily budget. */
    val durationMinutes: Int,
    /** Coins granted on completion. 0 for study (courses cost coins instead). */
    val coinReward: Int = 0,
    /** 工分 granted. Study courses grant none. */
    val workScoreReward: Int = 0,
    /** Attribute points granted, e.g. 力量 +8. Study courses only. */
    val attributeGain: Map<String, Int> = emptyMap(),
    /** Coins the activity CONSUMMS (course tuition). Work costs no coins. */
    val coinCost: Int = 0,
    /** 体力 consumed by one session. */
    val energyCost: Int = 0,
    /** 清洁 consumed by one session. */
    val cleanlinessCost: Int = 0,
    /** How many sessions are still available today (0 = exhausted/unavailable). */
    val sessionsAvailable: Int = Int.MAX_VALUE,
    /** Lower bound on sessions the planner MUST schedule. Used for user study goals. */
    val minUnits: Int = 0,
    /** Upper bound on sessions the planner may schedule. */
    val maxUnits: Int = Int.MAX_VALUE,
    /**
     * Confidence in the numbers behind this option.
     * [RewardConfidence.VERIFIED] = confirmed by a real completed session or a real course result.
     * [RewardConfidence.PAGE_READING] = read off a page but not closed-loop verified.
     * [RewardConfidence.UNKNOWN] = not collected; the reward fields must not be trusted.
     */
    val confidence: RewardConfidence = RewardConfidence.VERIFIED,
    /** Non-fatal note surfaced with the plan, e.g. "45min reward not closed-loop verified". */
    val caveat: String? = null,
) {
    init {
        require(durationMinutes > 0) { "ACTIVITY_DURATION_INVALID:$durationMinutes" }
        require(minUnits >= 0) { "ACTIVITY_MIN_UNITS_NEGATIVE:$minUnits" }
        require(maxUnits >= minUnits) { "ACTIVITY_MAX_BELOW_MIN:$id" }
        require(sessionsAvailable >= 0) { "ACTIVITY_SESSIONS_NEGATIVE:$sessionsAvailable" }
        require(coinReward >= 0 && coinCost >= 0) { "ACTIVITY_COIN_NEGATIVE:$id" }
        require(energyCost >= 0 && cleanlinessCost >= 0) { "ACTIVITY_COST_NEGATIVE:$id" }
    }

    /** Upper bound after availability and the caller's own cap. */
    fun effectiveMaxUnits(): Int =
        if (sessionsAvailable == Int.MAX_VALUE) maxUnits else min(maxUnits, sessionsAvailable)

    /** Net coins per session: reward minus anything the activity itself spends. */
    fun netCoinsPerSession(): Int = coinReward - coinCost

    val isStudy: Boolean get() = kind == ActivityKind.STUDY
    val isWork: Boolean get() = kind == ActivityKind.WORK
}

enum class ActivityKind { STUDY, WORK }

/**
 * How much the numbers behind an option can be trusted. P5.1 collected some rewards only as page
 * readings; a plan that silently mixes them with closed-loop-verified numbers would overstate its
 * own certainty.
 */
enum class RewardConfidence { VERIFIED, PAGE_READING, UNKNOWN }

/** What the optimiser is allowed to trade away. Ordered by how protective it is of study goals. */
enum class PlanMode {
    /** Maximise coins. Study goals are still a hard floor, never traded away. */
    COIN_FIRST,

    /** Meet every study goal, then maximise coins, then 工分. */
    BALANCED,

    /** Meet every study goal first; only spend what is left on work. */
    STUDY_FIRST,
}

/**
 * The state of the day the planner is allowed to reason about. Everything here must already be
 * confirmed; the planner never invents a value a flow failed to read.
 */
data class DayPlanInput(
    /** Shared budget in minutes. Defaults to the game's measured 8h full-reward window. */
    val budgetMinutes: Int = DailyBudgetStore.HARD_STOP_MINUTES,
    /** Minutes already spent today by study AND work (the game counts both). */
    val spentMinutes: Int = 0,
    /** 体力 available right now. */
    val energy: Int,
    /** 清洁 available right now. */
    val cleanliness: Int,
    val options: List<ActivityOption>,
    val mode: PlanMode = PlanMode.BALANCED,
    /**
     * When true, prefer plans that keep the day inside the full-reward window. The 8-12h band pays
     * 25%, so spending it is usually a bad trade; this is a tie-breaker, not a hard cap.
     */
    val avoidDiminishingReturns: Boolean = true,
) {
    init {
        require(budgetMinutes > 0) { "PLAN_BUDGET_INVALID:$budgetMinutes" }
        require(spentMinutes >= 0) { "PLAN_SPENT_NEGATIVE:$spentMinutes" }
        require(spentMinutes <= budgetMinutes) { "PLAN_SPENT_OVER_BUDGET:$spentMinutes>$budgetMinutes" }
        require(energy >= 0 && cleanliness >= 0) { "PLAN_RESOURCE_NEGATIVE" }
        require(options.isNotEmpty()) { "PLAN_NO_OPTIONS" }
    }

    val remainingMinutes: Int get() = maxOf(0, budgetMinutes - spentMinutes)
}

/** One line of the schedule: "do this option this many times". */
data class PlannedActivity(
    val option: ActivityOption,
    val units: Int,
) {
    val minutes: Int get() = option.durationMinutes * units
    val netCoins: Int get() = option.netCoinsPerSession() * units
    val workScore: Int get() = option.workScoreReward * units
    val energy: Int get() = option.energyCost * units
    val cleanliness: Int get() = option.cleanlinessCost * units
    val attributes: Map<String, Int>
        get() = option.attributeGain.mapValues { (_, v) -> v * units }
}

/**
 * The full plan. [explanation] is written for humans: the UI shows it, and a plan nobody can
 * justify is indistinguishable from a guess.
 */
data class DayPlan(
    val mode: PlanMode,
    val activities: List<PlannedActivity>,
    val totalMinutes: Int,
    val totalCoins: Int,
    val totalWorkScore: Int,
    val totalEnergy: Int,
    val totalCleanliness: Int,
    val totalAttributes: Map<String, Int>,
    /** True when every user study goal the planner was given is present in the schedule. */
    val studyGoalsMet: Boolean,
    /** Why the plan looks like this, and what it refused to do. */
    val explanation: List<String>,
    /** Data-quality warnings carried from [ActivityOption.caveat] and confidence. */
    val warnings: List<String>,
    /** Alternatives the planner considered and rejected, with the gap. */
    val comparison: List<PlanComparison>,
) {
    val isEmpty: Boolean get() = activities.isEmpty()
}

/** Recorded so the answer can be checked against something other than itself. */
data class PlanComparison(
    val strategy: String,
    val totalCoins: Int,
    val totalMinutes: Int,
    /** How far ahead of this strategy the chosen plan landed, in coins. */
    val coinGap: Int,
    val note: String,
)
