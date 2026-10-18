package com.ynk.virtualdisplay.ui.data

import com.ynk.virtualdisplay.automation.SessionMode

/**
 * The one place that decides "how many 节 does this particular start request ask for".
 *
 * The user picks a batch size once (1 / 3 / 10). Only 托管 ([SessionMode.FULL]) runs lessons, so only
 * 托管 may carry that choice. 健康检查 reads the pet a single time and 护理 repairs to the 90/90
 * threshold: neither is a batch of lessons, so both must stay on exactly one pass no matter what the
 * selector shows. Routing every entry through this object keeps the three buttons from each
 * re-inventing that rule — and, because it is plain Kotlin, it is the only part of the start path a
 * JVM unit test can observe.
 *
 * Keep [ROUND_OPTIONS] in sync with `PetSessionController`'s accepted range: `require(totalRounds in
 * 1..10)` and `PetAutomationService.runSession`'s `rounds !in 1..10` guard both accept every value
 * offered here, which is why choosing 10 needs no service-side change.
 */
object SessionStartRequest {
    /** Batch sizes offered in the UI. 1 is the default so a careless tap never multiplies the cost. */
    val ROUND_OPTIONS: List<Int> = listOf(1, 3, 10)

    /** 一节: what a session asks for unless the user explicitly chose a longer batch. */
    const val DEFAULT_ROUNDS: Int = 1

    /**
     * The rounds to send for [mode] when the selector currently shows [selected].
     *
     * Returns [DEFAULT_ROUNDS] for every mode that does not run lessons, so selecting 10 and then
     * tapping 健康检查 or 护理 can never turn a read/repair pass into a ten-lesson batch.
     */
    fun plannedRounds(mode: SessionMode, selected: Int): Int =
        if (mode.runsStudy) selected else DEFAULT_ROUNDS

    /** Clamps arbitrary UI state onto an offered option, so the request never carries a stray value. */
    fun sanitize(selected: Int): Int = if (selected in ROUND_OPTIONS) selected else DEFAULT_ROUNDS
}
