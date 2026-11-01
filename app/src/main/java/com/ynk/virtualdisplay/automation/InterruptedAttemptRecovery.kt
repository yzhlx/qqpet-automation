package com.ynk.virtualdisplay.automation

import java.io.File

/**
 * Releases the pending gate after an ordinary interruption (screen lock, crash, stop during a shift).
 * Without this, one interrupted attempt refuses EVERY later session forever, because the older
 * recovery paths only accept allowlisted historic sessions (device run 9013e972, 2026-11-01).
 *
 * The gate exists so the app never acts while a paid action may still be running in the game, so the
 * only question here is whether that time has passed. The outcome stays unknown and is recorded as
 * such: no input, no replay, and the minutes stay charged to their day.
 */
class InterruptedAttemptRecovery(private val filesDir: File, private val store: BudgetLedgerStore) {
    sealed interface Outcome {
        /** Not an interruption this class handles; the caller falls through to the older paths. */
        data object NotApplicable : Outcome
        data class StillRunning(val remainingMs: Long) : Outcome
        data class Reconciled(val attemptIds: List<String>) : Outcome
    }

    fun run(nowMs: Long): Outcome {
        val pending = store.read().attempts.values.filter(CarePendingReconciliation::unresolved)
        if (pending.isEmpty()) return Outcome.NotApplicable
        val lastSeen = pending.map { lastObservedAt(it) ?: return Outcome.NotApplicable }
        val remaining = pending.zip(lastSeen).maxOf { (attempt, at) -> remainingMs(attempt, at, nowMs) }
        if (remaining > 0) return Outcome.StillRunning(remaining)
        val ledger = PlanBudgetLedger(store)
        pending.forEach { ledger.reconcileInterrupted(it.attemptId, nowMs) }
        return Outcome.Reconciled(pending.map { it.attemptId })
    }

    private fun lastObservedAt(attempt: BudgetAttempt): Long? {
        val sessionId = runCatching { WorkCompletionWritebackPolicy.splitAttemptId(attempt.attemptId).first }
            .getOrNull() ?: return null
        if (sessionId == WorkCompletionWritebackPolicy.TARGET_SESSION_ID ||
            sessionId in CarePendingReconciliation.legacySessionIds) return null
        if (!sessionId.matches(Regex("[A-Za-z0-9_-]{1,128}"))) return null
        val file = File(filesDir, "pet-checkpoints/$sessionId.json")
        if (!file.isFile || file.length() > 1_000_000) return null
        return runCatching {
            val checkpoint = LegacySettlementJson.objectAt(file.readText(Charsets.UTF_8))
            check(checkpoint.string("sessionId") == sessionId)
            checkpoint.long("observedAt")
        }.getOrNull()
    }

    companion object {
        /** The checkpoint can predate the start tap by a few frames; a minute covers that safely. */
        const val START_MARGIN_MS = 60_000L

        /** A clock that reads earlier than the checkpoint proves nothing, so it never counts as elapsed. */
        fun remainingMs(attempt: BudgetAttempt, lastObservedAt: Long, nowMs: Long): Long {
            val total = attempt.minutes * 60_000L + START_MARGIN_MS
            if (nowMs < lastObservedAt) return total
            return (lastObservedAt + total - nowMs).coerceAtLeast(0L)
        }
    }
}
