package com.ynk.virtualdisplay.automation

/**
 * Persistent record of one run: an immutable snapshot plus the attempts that cross the input boundary.
 *
 * The story the store must be able to tell after a crash or restart is exactly two facts:
 *  1. which attempt is INPUT_PENDING (its input may already have reached the game - never replay it);
 *  2. which attempts are COMPLETED (the only source of the per-kind completion counters).
 *
 * [FileRunStore] supplies an atomic JSON file adapter; tests may still supply in-memory adapters.
 */
interface RunStore {
    /** Persist (or replace) the current snapshot last. Must be atomic. */
    fun save(snapshot: RunSnapshot)
    fun load(runId: String): RunSnapshot?

    /** Record the attempt BEFORE the consuming input. Throws on write failure so no input is sent. */
    fun persistIntent(attempt: ActivityAttempt)
    /** Record dispatch only after the input call; intent must already be durable. Never sends input.
     * Default keeps existing adapters source-compatible and fails explicitly if not implemented. */
    fun persistDispatched(attemptId: AttemptId, atMs: Long) {
        throw UnsupportedOperationException("DISPATCH_PERSISTENCE_NOT_IMPLEMENTED")
    }
    /** The attempt's start was positively confirmed. */
    fun persistStartConfirmed(attemptId: AttemptId, atMs: Long)
    /** The attempt's completion proof landed; this is what the counters are derived from. */
    fun persistCompleted(attemptId: AttemptId, atMs: Long)
    /** The outcome is unknown; the attempt stays pending and is never replayed. */
    fun persistUnconfirmed(attemptId: AttemptId, reason: String)

    fun attempt(attemptId: AttemptId): ActivityAttempt?
    fun attempts(runId: String): List<ActivityAttempt>

    /** True when a stored attempt for the run is still INPUT_PENDING (a live input may be in flight). */
    fun hasPendingInput(runId: String): Boolean = attempts(runId).any { it.inputPending }

    /** Completion counters derived ONLY from persisted COMPLETED attempts, never from step states. */
    fun countsOf(runId: String, plan: PlanV2): RunCounts {
        val perKind = attempts(runId).filter { it.state == AttemptState.COMPLETED }
            .groupingBy { it.attemptId.stepId }.eachCount()
        val work = plan.steps.filterIsInstance<WorkStep>()
            .sumOf { step -> (perKind[step.stepId] ?: 0).coerceAtMost(step.shifts) }
        val study = plan.steps.filterIsInstance<StudyStep>()
            .sumOf { step -> (perKind[step.stepId] ?: 0).coerceAtMost(step.rounds) }
        val care = plan.steps.filterIsInstance<CareStep>()
            .count { step -> (perKind[step.stepId] ?: 0) > 0 }
        return RunCounts(workShiftsCompleted = work, careCompleted = care, studyRoundsCompleted = study)
    }
}
