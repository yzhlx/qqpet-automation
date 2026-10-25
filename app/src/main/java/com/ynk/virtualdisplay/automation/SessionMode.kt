package com.ynk.virtualdisplay.automation

/**
 * What one user-started session is allowed to DO.
 *
 * The three actions have DIFFERENT resource rules, which is why they are separate modes rather than
 * one bundling "托管":
 *  - 打工 spends the job's own 体力 and 清洁 over a fixed duration and pays coins. It does not need a
 *    course, and it does not care about the plan's care thresholds.
 *  - 学习 spends tuition coins. User rule (2026-10-21): it does NOT require 体力/清洁 at any value, so
 *    it is startable on its own without first running care.
 *  - 护理 spends coins only when the pet is OUT OF STOCK, and lifts 体力/清洁 toward the thresholds.
 *
 * [FULL] is the historical 托管: care + lesson, with an optional first-round work shift when the
 * plan asks for one. [WORK] and [STUDY] are the standalone entries; each runs exactly one action.
 */
enum class SessionMode {
    /** Read 体力/清洁/心情 and record them. No care, no lesson, no consumable, no coin, no budget. */
    HEALTH_CHECK,

    /** 护理 to the plan thresholds. May spend coins on cookies/soap. No lesson, no work. */
    CARE,

    /** 打工 exactly one shift of the plan's option. No course, no care, no lesson. */
    WORK,

    /** 学习 the plan's course for the selected rounds. No work, no care. */
    STUDY,

    /** 护理 + 学习, plus the plan's optional first-round work shift. The historical 托管 behaviour. */
    FULL,

    /**
     * A saved plan (方案): the selected WORK/CARE/STUDY steps in their fixed order.
     *
     * The plan itself decides what runs, so the mode's own flags are all false: the steps are
     * read from [com.ynk.virtualdisplay.automation.PlanV2], not from the mode.
     */
    PLAN,
    ;

    /** True when this mode runs the care stage and may spend consumables/coins. */
    val runsCare: Boolean get() = this == CARE || this == FULL

    /** True when this mode runs lessons and consumes the shared daily budget. */
    val runsStudy: Boolean get() = this == STUDY || this == FULL

    /** True when this mode runs a work shift as its own action rather than as a funding step. */
    val runsWork: Boolean get() = this == WORK

    /** Stable wire name, used in intents and event payloads. */
    val wire: String get() = name.lowercase()

    companion object {
        /** Parses a wire name; an unknown value fails closed rather than defaulting to a costlier mode. */
        fun fromWire(value: String?): SessionMode = when (value?.lowercase()) {
            "health_check", "healthcheck", "check" -> HEALTH_CHECK
            "care" -> CARE
            "work" -> WORK
            "study" -> STUDY
            "plan" -> PLAN
            "full", null -> FULL
            else -> error("UNKNOWN_SESSION_MODE:$value")
        }
    }
}
