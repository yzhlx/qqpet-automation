package com.ynk.virtualdisplay.automation

import java.time.LocalDate

/** Shared by the legacy adapter, keyed reservations and every file-store instance in this process. */
internal object BudgetLedgerLock { val monitor = Any() }

private fun requireBudgetDay(day: String) {
    val parsed = try { LocalDate.parse(day) } catch (e: java.time.DateTimeException) {
        throw IllegalArgumentException("BUDGET_DAY_INVALID:$day", e)
    }
    require(parsed.toString() == day) { "BUDGET_DAY_INVALID:$day" }
}

/** Imported minutes remain separate from new keyed attempts, so migration never charges them twice. */
data class BudgetLedgerState(
    val day: String,
    val legacyUsedMinutes: Int,
    val attempts: Map<String, BudgetAttempt>,
    val legacyByDay: Map<String, Int> = emptyMap(),
) {
    init {
        requireBudgetDay(day)
        legacyByDay.keys.forEach(::requireBudgetDay)
        require(legacyUsedMinutes >= 0) { "LEGACY_BUDGET_INVALID" }
        require(legacyByDay.values.all { it >= 0 } && day !in legacyByDay) { "LEGACY_BUDGET_DAY_INVALID" }
        require(attempts.all { (id, value) -> id == value.attemptId }) { "BUDGET_ATTEMPT_KEY_MISMATCH" }
    }
}

data class BudgetAttempt(
    val attemptId: String,
    val day: String,
    val minutes: Int,
    val status: ReserveStatus,
    val reason: String? = null,
    val limitMinutes: Int = 480,
) {
    init {
        requireBudgetDay(day)
        require(attemptId.isNotBlank()) { "BUDGET_ATTEMPT_ID_BLANK" }
        require(minutes >= 0 && limitMinutes in 1..480) { "BUDGET_MINUTES_INVALID" }
    }
}

enum class ReserveStatus { RESERVED_BEFORE_INPUT, START_CONFIRMED, COMPLETED, UNCONFIRMED_RETAINED,
    /** Old outcome remains unknown; current baseline re-established without replay or refund. */
    RECONCILED_STOPPED }

/** Write must finish durably or throw before the consuming input is sent. */
interface BudgetLedgerStore {
    fun read(): BudgetLedgerState
    fun write(state: BudgetLedgerState)
}

data class ReserveResult(val attempt: BudgetAttempt, val newlyCharged: Boolean)

/** One authoritative total = imported/anonymous minutes + all keyed reservations for that day. */
class PlanBudgetLedger(
    private val store: BudgetLedgerStore,
    private val today: () -> String = { LocalDate.now().toString() },
) {
    fun usedMinutes(day: String = today()): Int = synchronized(BudgetLedgerLock.monitor) {
        used(store.read(), day)
    }

    fun reserve(attemptId: String, minutes: Int, limitMinutes: Int = 480): ReserveResult =
        synchronized(BudgetLedgerLock.monitor) {
            require(attemptId.isNotBlank()) { "BUDGET_ATTEMPT_ID_BLANK" }
            require(minutes >= 0 && limitMinutes in 1..480) { "BUDGET_MINUTES_INVALID" }
            val state = store.read()
            val existing = state.attempts[attemptId]
            if (existing != null) {
                check(existing.minutes == minutes && existing.limitMinutes == limitMinutes) {
                    "BUDGET_ATTEMPT_CONFLICT:$attemptId"
                }
                return@synchronized ReserveResult(existing, false)
            }
            val day = today()
            check(minutes == 0 || used(state, day).toLong() + minutes <= limitMinutes) { "DAILY_BUDGET_LIMIT" }
            val attempt = BudgetAttempt(attemptId, day, minutes, ReserveStatus.RESERVED_BEFORE_INPUT,
                limitMinutes = limitMinutes)
            store.write(state.copy(attempts = state.attempts + (attemptId to attempt)))
            ReserveResult(attempt, true)
        }

    fun canAfford(minutes: Int, limitMinutes: Int = 480): Boolean = synchronized(BudgetLedgerLock.monitor) {
        require(minutes >= 0 && limitMinutes in 1..480) { "BUDGET_MINUTES_INVALID" }
        minutes == 0 || used(store.read(), today()).toLong() + minutes <= limitMinutes
    }

    /** Compatibility for the old unkeyed start hooks; never call this as well as reserve for one input. */
    fun addLegacyMinutes(minutes: Int, limitMinutes: Int = 480) {
        synchronized(BudgetLedgerLock.monitor) {
            require(minutes > 0 && limitMinutes in 1..480) { "BUDGET_MINUTES_INVALID" }
            val state = store.read()
            val day = today()
            check(used(state, day).toLong() + minutes <= limitMinutes) { "DAILY_BUDGET_LIMIT" }
            val next = if (day == state.day) {
                state.copy(legacyUsedMinutes = Math.addExact(state.legacyUsedMinutes, minutes))
            } else {
                state.copy(legacyByDay = state.legacyByDay + (day to Math.addExact(state.legacyByDay[day] ?: 0, minutes)))
            }
            store.write(next)
        }
    }

    /** Preserve every keyed reservation, including uncertain input, when the old UI resets its counter. */
    fun resetLegacyToday() {
        synchronized(BudgetLedgerLock.monitor) {
            val state = store.read()
            val day = today()
            store.write(if (day == state.day) state.copy(legacyUsedMinutes = 0)
                else state.copy(legacyByDay = state.legacyByDay - day))
        }
    }

    fun startConfirmed(attemptId: String) = mark(attemptId, ReserveStatus.START_CONFIRMED)
    fun completed(attemptId: String) = mark(attemptId, ReserveStatus.COMPLETED)
    fun unconfirmed(attemptId: String, reason: String) = mark(attemptId, ReserveStatus.UNCONFIRMED_RETAINED, reason)
    fun attempt(attemptId: String): BudgetAttempt? = synchronized(BudgetLedgerLock.monitor) {
        store.read().attempts[attemptId]
    }

    fun reconcileStopped(proofs:List<LegacyCareStoppedProof>, baseline:CareRoomBaseline,
        noV2Pending:Boolean, now:Long) = synchronized(BudgetLedgerLock.monitor) {
        val state=store.read()
        val reasons=CarePendingReconciliation.validate(state.attempts.values,proofs,baseline,noV2Pending,now)
        val updated=state.attempts.mapValues { (id,attempt) ->
            reasons[id]?.let {attempt.copy(status=ReserveStatus.RECONCILED_STOPPED,reason=it)} ?: attempt
        }
        // Exactly one atomic commit for the entire batch. No day/minute/id can change.
        store.write(state.copy(attempts=updated))
    }

    /**
     * Evidence-only completion write-back (defect D1): marks exactly one retained WORK attempt
     * COMPLETED and appends an audit note to its reason. Minutes, day, limit and every other attempt
     * are untouched; a COMPLETED or RECONCILED attempt is never rewritten; no refund, no replay.
     */
    fun completedWithAudit(attemptId: String, note: String) = synchronized(BudgetLedgerLock.monitor) {
        val state = store.read()
        val previous = state.attempts[attemptId] ?: error("BUDGET_ATTEMPT_UNKNOWN:$attemptId")
        val (sessionId, stepId, iteration) = WorkCompletionWritebackPolicy.splitAttemptId(attemptId)
        check(sessionId == WorkCompletionWritebackPolicy.TARGET_SESSION_ID && stepId == "work" && iteration == 1) {
            "BUDGET_WRITEBACK_ATTEMPT_NOT_ALLOWLISTED:$attemptId"
        }
        check(previous.status == ReserveStatus.UNCONFIRMED_RETAINED &&
            previous.reason == WorkCompletionWritebackPolicy.SWEEP_REASON &&
            previous.minutes == WorkCompletionWritebackPolicy.TARGET_WORK_MINUTES) {
            "BUDGET_WRITEBACK_NOT_RETAINED_WORK_SHAPE:$attemptId"
        }
        val updated = previous.copy(status = ReserveStatus.COMPLETED,
            reason = (previous.reason ?: "") + "\n" + note)
        store.write(state.copy(attempts = state.attempts + (attemptId to updated)))
    }

    private fun mark(attemptId: String, status: ReserveStatus, reason: String? = null) {
        synchronized(BudgetLedgerLock.monitor) {
            val state = store.read()
            val previous = state.attempts[attemptId] ?: error("BUDGET_ATTEMPT_UNKNOWN:$attemptId")
            if (previous.status == ReserveStatus.COMPLETED || previous.status == ReserveStatus.RECONCILED_STOPPED) return
            if (previous.status == status && previous.reason == reason) return
            store.write(state.copy(attempts = state.attempts + (attemptId to previous.copy(status = status, reason = reason))))
        }
    }

    private fun used(state: BudgetLedgerState, day: String): Int {
        requireBudgetDay(day)
        val legacy = if (day == state.day) state.legacyUsedMinutes else state.legacyByDay[day] ?: 0
        return state.attempts.values.filter { it.day == day }.fold(legacy) { total, attempt ->
            Math.addExact(total, attempt.minutes)
        }
    }
}
