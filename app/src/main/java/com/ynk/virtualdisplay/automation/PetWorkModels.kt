package com.ynk.virtualdisplay.automation

/**
 * One work option as it exists on the venue detail page, plus the catalog entry it must be
 * checked against.
 *
 * The three levels are deliberately kept apart (P5.1 §3.1): career -> venue-stage -> single work
 * option. Rewards hang on the OPTION, not the venue, so two options in one venue are two
 * independent objects and are never merged into one.
 */
data class WorkOption(
    /** `work:<venue>/<option>`, matching the P5.2 ActivityOption id so a plan line maps 1:1. */
    val id: String,
    val venue: String,
    val optionLabel: String,
    val durationMinutes: Int,
    /** What the page showed for this option at the moment it was selected. */
    val pageCoinReward: Int,
    val pageWorkScoreReward: Int,
    val pageEnergyCost: Int,
    val pageCleanlinessCost: Int = 0,
    /** 0 when the row was readable, non-zero when the reading is known to be incomplete. */
    val unreadFields: List<String> = emptyList(),
)

/** Where a number came from. Never let a lower rung claim to be a higher one. */
enum class RewardEvidence {
    /** Read from the detail page after selecting the option card. */
    PAGE_READING,

    /** Measured as an actual resource delta across a completed real work session. */
    OBSERVED_DELTA,

    /** PAGE_READING reconciled against OBSERVED_DELTA within tolerance. */
    CONFIRMED_REWARD,
}

/**
 * The four reward rungs the brief demands stay separate.
 *
 * EXPECTED_REWARD and PAGE_READING are both *predictions*; OBSERVED_DELTA is *fact*;
 * CONFIRMED_REWARD is a prediction that survived contact with fact. Only CONFIRMED_REWARD may be
 * used to promote a PAGE_READING catalog entry (e.g. 陪练基础课 45min) to verified.
 */
enum class RewardRecordKind {
    EXPECTED_REWARD,
    PAGE_READING,
    OBSERVED_DELTA,
    CONFIRMED_REWARD,
}

data class ResourceSnapshot(
    val coins: ObservedValue<Int>,
    val workScore: ObservedValue<Int>,
    val energy: ObservedValue<Int>,
    val cleanliness: ObservedValue<Int>? = null,
) {
    fun fields(): Map<String, Any?> = mapOf(
        "coins" to coins.fields(),
        "workScore" to workScore.fields(),
        "energy" to energy.fields(),
        "cleanliness" to cleanliness?.fields(),
    )
}

/**
 * One resource reading taken at a named point in the flow.
 *
 * [kind] records what the reading IS, so the ledger can never silently promote an expectation into
 * an observation. [evidence] carries the frame path or reason.
 */
data class RewardRecord(
    val kind: RewardRecordKind,
    val at: String,
    val coins: ObservedValue<Int>,
    val workScore: ObservedValue<Int>,
    val energy: ObservedValue<Int>,
    val cleanliness: ObservedValue<Int>? = null,
    val evidence: String,
) {
    fun fields(): Map<String, Any?> = mapOf(
        "kind" to kind.name, "at" to at, "coins" to coins.fields(),
        "workScore" to workScore.fields(), "energy" to energy.fields(),
        "cleanliness" to cleanliness?.fields(), "evidence" to evidence,
    )
}
