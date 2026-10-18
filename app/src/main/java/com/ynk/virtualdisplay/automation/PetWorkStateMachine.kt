package com.ynk.virtualdisplay.automation

/**
 * PetWorkFlow phase model.
 *
 * Every transition carries a reason; nothing advances on a timer alone. The five events the brief
 * demands stay separate are:
 *
 *  - START_REQUESTED        the tap was sent (or was refused)
 *  - WORKING_CONFIRMED      positive page proof the work is running
 *  - EXPECTED_COMPLETION    the countdown reached zero (a PREDICTION, never a fact)
 *  - COMPLETION_OBSERVED    the game showed a completion/payroll surface
 *  - REWARD_CONFIRMED       resource deltas read and reconciled
 *
 * Collapsing any two of these would let the flow claim a reward it never verified.
 */
enum class WorkPhase {
    IDLE,
    NAVIGATING,
    WORK_SELECTED,
    PREVIEW_VERIFIED,
    AUTHORIZED,
    START_REQUESTED,
    WORKING_CONFIRMED,
    WAITING,
    COMPLETION_CHECK,
    REWARD_VERIFIED,
    FINISHED,
    BLOCKED,
    FAILED,
    INTERRUPTED,
    RECOVERY_REQUIRED,
}

/** Outcome of the whole flow, reported to the caller and written to the run record. */
sealed interface WorkOutcome {
    /** Preview only: no real work was started. The brief's Gate 3A/3B terminal state. */
    data class Preview(
        val venue: String,
        val optionId: String,
        val pageReward: WorkOption,
        val catalogMatched: Boolean,
        val catalogDifferences: List<String>,
        val blockers: List<String>,
    ) : WorkOutcome

    /** A real work session ran to completion and its reward was verified. */
    data class Completed(
        val venue: String,
        val optionId: String,
        val actualMinutes: Int,
        val records: List<RewardRecord>,
    ) : WorkOutcome

    /** The start tap was delivered and the WORKING page was positively confirmed. */
    data class Started(
        val venue: String,
        val optionId: String,
        val durationMinutes: Int,
        val expectedEndAtMs: Long,
        val before: RewardRecord,
    ) : WorkOutcome

    /**
     * The start tap was delivered but the work could not be confirmed. No reward is claimed and
     * nothing is recorded as a success; the caller must re-check the pet's state by hand.
     */
    data class UnconfirmedStart(
        val venue: String,
        val optionId: String,
        val reason: String,
    ) : WorkOutcome

    /** Stopped before any input that could start work: authorisation, navigation or data missing. */
    data class Blocked(val reason: String) : WorkOutcome

    data class Failed(val reason: String) : WorkOutcome
    data class Interrupted(val reason: String) : WorkOutcome
}

/**
 * Pure state machine for PetWorkFlow.
 *
 * Android-free on purpose: the transition table, the start-once latch and the
 * preview/authorisation separation are all testable on the JVM without a device. The flow drives
 * it; this object only decides which moves are legal.
 */
class WorkStateMachine {
    var phase: WorkPhase = WorkPhase.IDLE; private set
    private val transitions = mutableListOf<WorkTransition>()

    data class WorkTransition(
        val from: WorkPhase,
        val to: WorkPhase,
        val reason: String,
        val atMs: Long,
    )

    val history: List<WorkTransition> get() = transitions

    /** Terminal phases: nothing may move out of them except an explicit new run. */
    private fun isTerminal(p: WorkPhase) =
        p == WorkPhase.FINISHED || p == WorkPhase.FAILED || p == WorkPhase.INTERRUPTED

    /**
     * Applies one transition. Throws [IllegalStateException] (not IllegalArgumentException) because
     * a bad transition is a state violation by the CALLER, not a bad argument: the machine was in a
     * phase from which the requested move is illegal.
     */
    fun move(to: WorkPhase, reason: String, atMs: Long) {
        check(!isTerminal(phase)) { "WORK_PHASE_TERMINAL:$phase" }
        // Idempotent re-entry is allowed: a preview run that already verified its page may be asked to
        // verify it again (e.g. the reward row was re-read) without that being a state change.
        if (phase == to) return
        check(isAllowed(phase, to)) { "WORK_PHASE_ILLEGAL:$phase->$to:$reason" }
        transitions += WorkTransition(phase, to, reason, atMs)
        phase = to
    }

    private fun isAllowed(from: WorkPhase, to: WorkPhase): Boolean = when (from) {
        WorkPhase.IDLE -> to == WorkPhase.NAVIGATING || to == WorkPhase.BLOCKED || to == WorkPhase.FAILED
        WorkPhase.NAVIGATING ->
            to == WorkPhase.WORK_SELECTED || to == WorkPhase.BLOCKED || to == WorkPhase.FAILED ||
                to == WorkPhase.RECOVERY_REQUIRED || to == WorkPhase.INTERRUPTED
        WorkPhase.WORK_SELECTED ->
            to == WorkPhase.PREVIEW_VERIFIED || to == WorkPhase.BLOCKED || to == WorkPhase.FAILED ||
                to == WorkPhase.RECOVERY_REQUIRED || to == WorkPhase.INTERRUPTED
        WorkPhase.PREVIEW_VERIFIED ->
            to == WorkPhase.AUTHORIZED || to == WorkPhase.FINISHED || to == WorkPhase.BLOCKED ||
                to == WorkPhase.FAILED || to == WorkPhase.INTERRUPTED
        WorkPhase.AUTHORIZED ->
            to == WorkPhase.START_REQUESTED || to == WorkPhase.BLOCKED || to == WorkPhase.FAILED ||
                to == WorkPhase.INTERRUPTED
        WorkPhase.START_REQUESTED ->
            to == WorkPhase.WORKING_CONFIRMED || to == WorkPhase.BLOCKED || to == WorkPhase.FAILED ||
                to == WorkPhase.RECOVERY_REQUIRED || to == WorkPhase.INTERRUPTED
        WorkPhase.WORKING_CONFIRMED ->
            to == WorkPhase.WAITING || to == WorkPhase.FAILED || to == WorkPhase.INTERRUPTED ||
                to == WorkPhase.BLOCKED
        WorkPhase.WAITING ->
            to == WorkPhase.COMPLETION_CHECK || to == WorkPhase.FAILED || to == WorkPhase.INTERRUPTED
        WorkPhase.COMPLETION_CHECK ->
            to == WorkPhase.REWARD_VERIFIED || to == WorkPhase.WAITING || to == WorkPhase.FAILED ||
                to == WorkPhase.RECOVERY_REQUIRED || to == WorkPhase.INTERRUPTED
        WorkPhase.REWARD_VERIFIED ->
            to == WorkPhase.FINISHED || to == WorkPhase.FAILED || to == WorkPhase.INTERRUPTED ||
                to == WorkPhase.BLOCKED
        WorkPhase.BLOCKED, WorkPhase.FAILED, WorkPhase.INTERRUPTED -> false
        WorkPhase.RECOVERY_REQUIRED ->
            to == WorkPhase.NAVIGATING || to == WorkPhase.WORK_SELECTED || to == WorkPhase.PREVIEW_VERIFIED ||
                to == WorkPhase.START_REQUESTED || to == WorkPhase.WAITING || to == WorkPhase.COMPLETION_CHECK ||
                to == WorkPhase.FAILED || to == WorkPhase.INTERRUPTED
        WorkPhase.FINISHED -> false
    }

    /**
     * True once the flow is past the point where a start tap could be sent. Used by tests and by
     * the UI to show that the preview phase is over.
     */
    val pastPreview: Boolean
        get() = phase.ordinal >= WorkPhase.AUTHORIZED.ordinal && phase != WorkPhase.BLOCKED
}
