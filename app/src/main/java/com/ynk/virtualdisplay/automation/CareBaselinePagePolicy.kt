package com.ynk.virtualdisplay.automation

/** Room-only baseline. Plain-background similarity is not positive evidence of a learning panel. */
object CareBaselinePagePolicy {
    fun requireRoom(roomScore:Double,modalScores:List<Double>,learningScores:List<Double>,
        roomExitPresent:Boolean,dialogTextVisible:Boolean,learningThreshold:Double=0.95) {
        check(modalScores.size==4 && learningScores.size in 2..3) { "BASELINE_PROBES_INCOMPLETE" }
        check(roomScore.isFinite() && roomScore>=0.97) { "BASELINE_ROOM_NOT_CONFIRMED" }
        check(roomExitPresent) { "BASELINE_ROOM_EXIT_UNCONFIRMED" }
        check(!dialogTextVisible) { "BASELINE_DIALOG_TEXT_VISIBLE" }
        // Keep the strict existing rejection for actual health/result/exchange modal regions.
        check(modalScores.all { it.isFinite() && it<0.80 }) { "BASELINE_OVERLAY_OR_AMBIGUITY" }
        // Running-class probes are scene classifiers, with the same threshold as ordinary learning.
        // Real room negative scores were 0.80558 / 0.82154 with no learning text and a valid exit.
        check(learningThreshold in 0.95..0.99 && learningScores.all {
            it.isFinite() && it<learningThreshold
        }) { "BASELINE_LEARNING_PANEL_VISIBLE" }
    }
}
