package com.ynk.virtualdisplay.automation

/** Bounded care allowances for +10 items, including stock-out attempts retained as uncertain. */
object CareAllowance {
    fun stepsFor(gap: Int): Int = (gap.coerceAtLeast(0) + 9) / 10
    fun feedLimit(energy: Int, threshold: Int): Int = stepsFor(threshold - energy) + 1
    fun cleanLimit(cleanliness: Int, threshold: Int): Int = stepsFor(threshold - cleanliness) + 1
    fun cleanRestockLimit(cleanliness: Int, threshold: Int): Int = stepsFor(threshold - cleanliness) + 1

    // Initial recovery plus subsequent lessons' consumption and one uncertain attempt.
    fun cookieBudget(threshold: Int, rounds: Int): Int =
        maxOf(6, stepsFor(threshold) + 2 * rounds + 1)

    // Empty soap stock takes two slots: uncertain scrub, purchase, then confirmed scrub.
    // Cover initial recovery, later rounds and one preparation for the work option's own cost.
    fun soapBudget(threshold: Int, rounds: Int): Int =
        maxOf(4, 2 * (stepsFor(threshold) + rounds + 1))
}
