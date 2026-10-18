package com.ynk.virtualdisplay.automation

/**
 * What one user-started session is allowed to DO. v0.1 shipped a single bundled "托管" entry that
 * always ran 健康检查 → 护理 → 学习 as one indivisible batch, so a user who only wanted to read the
 * pet's current 体力/清洁 had to also spend coins on care and burn the shared 480-minute budget on a
 * lesson. Every capability is therefore selected explicitly here, and the flows only run the stages
 * their mode names.
 *
 * The three modes are strictly nested in effect but not in code:
 *  - [HEALTH_CHECK] reads and records only. It never navigates past the health panel and never
 *    spends a consumable, a coin or a budget minute, so it is always safe to run.
 *  - [CARE] adds 护理: it may buy cookies/soap, so it is the first mode with a coin cost.
 *  - [FULL] adds 学习 on top of care, so it is the historical v0.1 behaviour.
 *
 * `STUDY` alone is deliberately absent: starting a lesson requires a verified ≥90 体力/清洁
 * reading, and producing that reading is 健康检查. Bundling the check into every study session
 * keeps that precondition satisfied without asking the user to run two sessions to get one lesson.
 */
enum class SessionMode {
    /** Read 体力/清洁/心情 and record them. No care, no lesson, no consumable, no coin, no budget. */
    HEALTH_CHECK,

    /** 健康检查 + 护理 to the 90/90 threshold. May spend coins on cookies/soap. No lesson. */
    CARE,

    /** 健康检查 + 护理 + 学习. The historical v0.1 托管 behaviour. */
    FULL,
    ;

    /** True when this mode may enter the care stage and spend consumables/coins. */
    val runsCare: Boolean get() = this != HEALTH_CHECK

    /** True when this mode runs lessons and consumes the shared daily budget. */
    val runsStudy: Boolean get() = this == FULL

    /** Stable wire name, used in intents and event payloads. */
    val wire: String get() = name.lowercase()

    companion object {
        /** Parses a wire name; an unknown value fails closed rather than defaulting to a costlier mode. */
        fun fromWire(value: String?): SessionMode = when (value?.lowercase()) {
            "health_check", "healthcheck", "check" -> HEALTH_CHECK
            "care" -> CARE
            "full", null -> FULL
            else -> error("UNKNOWN_SESSION_MODE:$value")
        }
    }
}
