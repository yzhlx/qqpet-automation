package com.ynk.virtualdisplay.automation

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first

enum class ManualPauseState { NONE, REQUESTED, HELD }

/**
 * The user's own pause. It only takes effect at a safe boundary - before a plan step, a lesson or a
 * round starts - so a gesture, a lesson or a shift already under way always runs to its own end and
 * nothing is left reserved but unbooked. Separate from [SessionRecovery], whose PAUSED is automatic
 * and bounded to its recovery window; a manual pause holds until the user continues or stops.
 */
object ManualPause {
    const val REASON = "USER_PAUSED"
    private val mutable = MutableStateFlow(ManualPauseState.NONE)
    val state: StateFlow<ManualPauseState> = mutable.asStateFlow()

    fun request() { mutable.compareAndSet(ManualPauseState.NONE, ManualPauseState.REQUESTED) }

    /** Continues a held session, or withdraws a request that has not reached a boundary yet. */
    fun release() { mutable.value = ManualPauseState.NONE }

    /**
     * Called at a boundary. Suspends while a pause is requested and returns true when it actually
     * held, so the caller knows the page must be re-identified before any further input. Stopping
     * the session cancels the wait.
     */
    suspend fun hold(onHeld: () -> Unit): Boolean {
        if (!mutable.compareAndSet(ManualPauseState.REQUESTED, ManualPauseState.HELD)) return false
        onHeld()
        mutable.first { it == ManualPauseState.NONE }
        return true
    }
}
