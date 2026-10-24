package com.ynk.virtualdisplay.automation

import android.content.Context
import java.time.LocalDate

/** Tracks this app's reserved learning/work minutes; manual activities remain outside this ledger. */
object ActivityBudgetPolicy {
    fun canAfford(used: Int, minutes: Int, limit: Int): Boolean {
        require(used >= 0 && minutes > 0 && limit in 1..DailyBudgetStore.HARD_STOP_MINUTES)
        return used.toLong() + minutes <= limit
    }
}

/**
 * The daily minute ledger, decoupled from SharedPreferences so its rollover and reservation rules
 * are JVM-testable without a device.
 *
 * THE LOCK IS PROCESS-WIDE AND SHARED BY EVERY INSTANCE. The app builds a store per screen and per
 * session, so an instance-level monitor would let one caller's read-modify-write interleave with
 * another's. The concrete failure this closes: a session commits day=today + minutes=10, the UI's
 * usedMinutes() then runs its rollover off a day stamp read BEFORE that commit, and its reset write
 * lands afterwards with minutes=0 - the just-booked reservation vanishes and the next start is
 * allowed to overspend the day. Every method below runs inside one shared monitor, so the
 * rollover's read-then-write can never race a reservation.
 *
 * Writes are synchronous: a reservation must be on disk before the start tap it authorises goes out.
 */
internal class DailyBudgetLedger(
    private val read: () -> BudgetState,
    private val write: (BudgetState) -> Boolean,
    private val today: () -> String = { LocalDate.now().toString() },
) {
    fun usedMinutes(): Int = lock { rolled().minutes }

    fun addCourse(minutes: Int) {
        require(minutes > 0)
        lock {
            val updated = Math.addExact(rolled().minutes, minutes)
            check(write(BudgetState(today(), updated))) { "DAILY_BUDGET_WRITE_FAILED" }
        }
    }

    /**
     * True when another activity of [minutes] would stay inside the allowance. The game's own
     * [DailyBudgetStore.HARD_STOP_MINUTES] always applies; [limitMinutes] can only tighten it, which is
     * how a [SessionPlan] asks the app to stop earlier than the game would.
     */
    fun canAfford(minutes: Int, limitMinutes: Int = DailyBudgetStore.HARD_STOP_MINUTES): Boolean {
        require(minutes > 0)
        return lock {
            ActivityBudgetPolicy.canAfford(rolled().minutes, minutes, minOf(limitMinutes, DailyBudgetStore.HARD_STOP_MINUTES))
        }
    }

    /** Persist before the start tap. An unconfirmed start retains its minutes. */
    fun reserveActivity(minutes: Int, limitMinutes: Int) {
        lock {
            check(canAfford(minutes, limitMinutes)) { "DAILY_BUDGET_LIMIT" }
            addCourse(minutes)
        }
    }

    fun resetToday() {
        lock { check(write(BudgetState(today(), 0))) { "DAILY_BUDGET_WRITE_FAILED" } }
    }

    /** Applies the day rollover and returns the current state. Must be called with the lock held. */
    private fun rolled(): BudgetState {
        val current = read()
        val now = today()
        if (current.day != now) {
            check(write(BudgetState(now, 0))) { "DAILY_BUDGET_WRITE_FAILED" }
            return BudgetState(now, 0)
        }
        return current
    }

    companion object {
        private val LOCK = BudgetLedgerLock.monitor

        private inline fun <T> lock(body: () -> T): T = synchronized(LOCK) { body() }
    }
}

/** One day's ledger exactly as it is persisted: the day stamp and the minutes booked against it. */
internal data class BudgetState(val day: String?, val minutes: Int)

/**
 * Production budget adapter. The versioned file is the only authority once it exists; original
 * SharedPreferences day/minutes are a one-time import source and are left intact. Old and keyed
 * APIs therefore read the same total instead of adding a reservation to two independent counters.
 */
class DailyBudgetStore private constructor(private val ledger: PlanBudgetLedger) {
    constructor(context: Context) : this(PlanBudgetLedger(PersistentBudgetStore(
        java.io.File(context.filesDir, PersistentBudgetStore.FILE_NAME),
        legacy = {
            val prefs = context.getSharedPreferences("pet_mvp_budget", Context.MODE_PRIVATE)
            LegacyBudgetUsage(prefs.getString(KEY_DAY, null), prefs.getInt(KEY_MINUTES, 0))
        },
    )))

    /** Same file adapter in integration tests; no Android/JSON stub is used for persistence. */
    internal constructor(store: BudgetLedgerStore, today: () -> String) : this(PlanBudgetLedger(store, today))

    fun usedMinutes(): Int = ledger.usedMinutes()

    fun addCourse(minutes: Int) = ledger.addLegacyMinutes(minutes, HARD_STOP_MINUTES)

    fun canAfford(minutes: Int, limitMinutes: Int = HARD_STOP_MINUTES): Boolean =
        ledger.canAfford(minutes, minOf(limitMinutes, HARD_STOP_MINUTES))

    /** Legacy unkeyed hook. A new keyed consuming attempt must use reserveAttempt instead, not both. */
    fun reserveActivity(minutes: Int, limitMinutes: Int) =
        ledger.addLegacyMinutes(minutes, minOf(limitMinutes, HARD_STOP_MINUTES))

    fun reserveAttempt(attemptId: String, minutes: Int, limitMinutes: Int = HARD_STOP_MINUTES): ReserveResult =
        ledger.reserve(attemptId, minutes, limitMinutes)

    fun startConfirmed(attemptId: String) = ledger.startConfirmed(attemptId)
    fun completed(attemptId: String) = ledger.completed(attemptId)
    fun unconfirmed(attemptId: String, reason: String) = ledger.unconfirmed(attemptId, reason)
    fun attempt(attemptId: String): BudgetAttempt? = ledger.attempt(attemptId)
    fun reconcileStopped(proofs:List<LegacyCareStoppedProof>,baseline:CareRoomBaseline,
        noV2Pending:Boolean,now:Long) = ledger.reconcileStopped(proofs,baseline,noV2Pending,now)

    /** Evidence-only completion write-back for the retained WORK attempt of defect D1. */
    fun completedWithAudit(attemptId:String,note:String) = ledger.completedWithAudit(attemptId,note)

    /** Compatibility reset only clears anonymous/imported minutes; keyed attempts are never refunded. */
    fun resetToday() = ledger.resetLegacyToday()

    companion object {
        private const val KEY_DAY = "day"
        private const val KEY_MINUTES = "minutes"
        const val HARD_STOP_MINUTES = 8 * 60
    }
}
