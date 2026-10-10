package com.ynk.virtualdisplay.automation

import android.os.SystemClock
import android.graphics.PointF
import android.view.InputDevice
import android.view.MotionEvent
import com.ynk.virtualdisplay.data.repository.IDisplayRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Bookkeeping for the injection calls one action started.
 *
 * Pure Kotlin on purpose: the rule that an UNACKNOWLEDGED call is not evidence of a non-delivery
 * decides whether a consumable allowance may be returned and whether a pressed display must be
 * released, so JVM tests must be able to execute it with a fake transport that applies an event and
 * only then fails. A MotionEvent-dependent path could not be exercised that way.
 */
internal class InjectAttemptLedger {
    /** Inject calls that were STARTED, acknowledged or not. Monotonic: it never regresses. */
    var attempts = 0; private set

    /** Inject calls answered with true. The absence of this proves nothing about delivery. */
    var confirmed = 0; private set

    /** True once a call that could have pressed the target display was STARTED. */
    var downAttempted = false; private set

    private var confirmedFloor = 0

    /**
     * Opens the per-action scope. The monotonic [attempts] total is deliberately NOT reset: a caller
     * reads it before a consuming action and can then prove whether that action ever started an
     * inject call at all.
     */
    fun beginScope() { confirmedFloor = confirmed; downAttempted = false }

    /** Inject calls the current action got an acknowledgement for. */
    fun confirmedInScope() = confirmed - confirmedFloor

    /**
     * Runs one injection call for `isDown`-style presses. The attempt is recorded BEFORE [inject]
     * runs, because a transport may apply the event and only then fail, return false or be
     * cancelled: an acknowledgement failure is never evidence that nothing reached the display.
     * Only a call that never started can release a consumable allowance or skip its release.
     */
    suspend fun attempt(isDown: Boolean, rejected: String, inject: suspend () -> Boolean) {
        attempts++
        if(isDown) downAttempted = true
        check(inject()) { rejected }
        confirmed++
    }
}

/**
 * Attempts the release of a DOWN that may have reached [displayId], regardless of how that DOWN call
 * was acknowledged, and reports a failed release through [recordFailure] instead of claiming the
 * display was released.
 *
 * A release that is not confirmed FAILS this call: the transport answered false, threw, was
 * cancelled, or the UP event could not even be constructed. It is recorded, [halt] is invoked so the
 * session sends no further DOWN, and the failure is propagated - returning normally after an
 * unconfirmed UP would let the caller journal the action as sent while the game stays pressed.
 *
 * Driven by [downAttempted] alone: the caller passes the DOWN ATTEMPT flag, never its
 * acknowledgement. [release] receives the same [displayId], so a mismatched display can not be
 * released by accident. Android-free on purpose, so JVM tests can prove the same-display release
 * really happens after a failed DOWN acknowledgement.
 */
internal suspend fun releaseAttemptedDown(downAttempted: Boolean, displayId: Int,
    recordFailure: (Int, String, Throwable) -> Unit, halt: (String) -> Unit = {},
    release: suspend (Int) -> Unit) {
    // Only a DOWN that never started an inject call may skip its release. Refusals raised by the
    // freshness/pause/display guards or by event construction leave the flag false.
    if(!downAttempted) return
    try {
        withContext(NonCancellable) { release(displayId) }
    }
    catch(t: Throwable) {
        val reason = if(t is CancellationException) "ACTION_UP_CLEANUP_CANCELLED" else "ACTION_UP_CLEANUP_FAILED"
        // Neither the record nor the halt may mask the release failure they describe.
        runCatching { recordFailure(displayId, reason, t) }
        runCatching { halt(reason) }
        throw t
    }
}

class PetInputController(
    private val repository: IDisplayRepository,
    private val displayId: Int,
    private val width: Int,
    private val height: Int,
    private val facts: AutomationFacts,
) {
    init { require(displayId > 0 && facts.displayId == displayId) { "Refuse default or mismatched display" } }

    private val ledger = InjectAttemptLedger()

    /**
     * How many inject calls this action got an acknowledgement for. It is NOT the evidence that
     * decides whether a consumable allowance may be returned: a transport can apply an event and
     * refuse to acknowledge it, so the release decision is made on [injectionAttempts] instead.
     */
    val deliveredEvents: Int get() = ledger.confirmedInScope()

    /**
     * Inject calls this controller has STARTED, acknowledged or not, across the whole session. Never
     * regresses, so a caller that reads it before a consuming action can prove afterwards whether
     * that action ever started an inject call - a guard refusal or a construction failure leaves it
     * unchanged, which is the only evidence that lets a consumable allowance be returned.
     */
    val injectionAttempts: Int get() = ledger.attempts

    /** Starts observing delivery for one action; call it before that action is attempted. */
    fun beginDeliveryObservation() = ledger.beginScope()

    /** Delivers one owned event and counts it; the caller keeps ownership and recycles it. */
    private suspend fun deliver(event: MotionEvent, rejected: String = "Input event rejected") {
        ledger.attempt(event.action == MotionEvent.ACTION_DOWN, rejected) {
            repository.injectInputWithDisplayId(event, displayId).getOrThrow()
        }
    }

    /**
     * Records a failed release on this display. It is never reported as a completed cleanup, and a
     * journaling failure here must not replace the failure that caused the release.
     */
    private fun journalCleanupFailure(action: String, displayId: Int, reason: String, cause: Throwable) {
        runCatching {
            facts.events.append(EventSource.APP_AUTOMATION, EventKind.ERROR,
                mapOf("displayId" to displayId, "action" to action, "reason" to reason,
                    "cause" to (cause.message ?: cause.javaClass.simpleName)))
        }
    }

    /** The release sink for this controller: one display, and every failure kept on record. */
    private fun recordCleanupFailure(action: String): (Int, String, Throwable) -> Unit =
        { id, reason, cause -> journalCleanupFailure(action, id, reason, cause) }

    /**
     * Releases a possibly-pressed display after the body of an action. [primary] is the failure the
     * action itself already hit, if any: an unconfirmed release is then attached to it as a
     * suppressed exception rather than replacing it, so the original cause of the action is never
     * masked by its cleanup. With no primary failure the release failure propagates on its own.
     *
     * Either way the session is halted and the caller's INPUT_SENT record is never reached.
     */
    private suspend fun releaseOrFail(action: String, primary: Throwable?, release: suspend (Int) -> Unit) {
        try {
            releaseAttemptedDown(ledger.downAttempted, displayId, recordCleanupFailure(action),
                facts::haltInput, release)
        }
        catch(upFailure: Throwable) {
            if(primary == null || primary === upFailure) throw upFailure
            primary.addSuppressed(upFailure)
            throw primary
        }
    }

    private fun requireTrustedFrame() {
        facts.requireInput()
        if(displayId !in repository.managedDisplayIds.value ||
            repository.connectionStatus.value != com.ynk.virtualdisplay.data.repository.ConnectionStatus.CONNECTED) {
            facts.frame(FrameLivenessResult(FrameStatus.DISPLAY_UNAVAILABLE),"INPUT_GUARD")
            error("DISPLAY_UNAVAILABLE")
        }
    }

    /**
     * Start-of-action freshness barrier. Evaluated ONCE per new business action, before any event
     * of that action is delivered, so the frame that justified the action is the frame whose age is
     * judged. It is deliberately NOT part of [requireTrustedFrame]: the continuous gesture below
     * re-checks only the trust barrier between its own events, and applying an age budget there
     * would abort a measured ~25 s soap gesture part-way through.
     */
    private fun requireFreshActionFrame() = facts.requireFreshInput()

    suspend fun tap(x: Float, y: Float, pressMs: Long = 80L) {
        require(x >= 0f && x < width.toFloat() && y >= 0f && y < height.toFloat()) { "tap outside pet display" }
        // A new action owns its attestation exclusively: whatever the previous action left behind,
        // this action may only be authorised by a frame this action actually checked.
        facts.beginAction()
        // Scoped to this action: the count observed afterwards, including on failure, describes only
        // the events this action delivered.
        beginDeliveryObservation()
        var recorded = false
        try {
            facts.beforeInput()
            requireTrustedFrame()
            requireFreshActionFrame()
            facts.markActionPending()
            val downTime = SystemClock.uptimeMillis()
            val down = event(downTime, downTime, MotionEvent.ACTION_DOWN, x, y)
            var primary: Throwable? = null
            try {
                deliver(down, "ACTION_DOWN rejected")
                delay(pressMs)
            } catch(t: Throwable) { primary = t; throw t } finally {
                down.recycle()
                // The release is driven by the DOWN ATTEMPT, not by its acknowledgement: a call that
                // returned false, threw or was cancelled may still have pressed the game, and leaving
                // it pressed is worse than a release whose failure is on record. An UNCONFIRMED
                // release is itself a failure: the action is failed and this session is halted here,
                // before its INPUT_SENT record, so a pressed display is never reported as sent.
                releaseOrFail("TAP", primary) { _ ->
                    val up = event(downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x, y)
                    try { deliver(up, "ACTION_UP rejected") } finally { up.recycle() }
                }
            }
            facts.input("TAP",mapOf("x" to x,"y" to y,"pressMs" to pressMs))
            recorded = true
        } finally {
            // Refused, aborted, cancelled or failed: the action never produced its own INPUT_SENT
            // record, so its attestation is withdrawn instead of being inherited by the next input.
            facts.endAction(recorded)
        }
    }

    /** One continuous, display-scoped gesture, used only for the measured soap interaction. */
    suspend fun drag(points: List<PointF>, stepMs: Long = 250L) {
        require(points.size >= 2)
        points.forEach { require(it.x in 0f..<width.toFloat() && it.y in 0f..<height.toFloat()) }
        facts.beginAction()
        // One continuous gesture, so one delivery scope: the count afterwards describes every event
        // of this gesture, which is exactly what decides whether the soap may already be spent.
        beginDeliveryObservation()
        var recorded = false
        try {
            facts.beforeInput()
            requireTrustedFrame()
            requireFreshActionFrame()
            facts.markActionPending()
            val downTime = SystemClock.uptimeMillis()
            var last = points.first()
            suspend fun send(action: Int, point: PointF) {
                // Preserve the continuous gesture, but inhibit further MOVE after a concurrent invalid capture.
                // UP remains mandatory cleanup for a DOWN already delivered to this same virtual display.
                if(action != MotionEvent.ACTION_UP) requireTrustedFrame()
                val e = event(downTime, SystemClock.uptimeMillis(), action, point.x, point.y)
                try { deliver(e, "Gesture event rejected") } finally { e.recycle() }
            }
            var primary: Throwable? = null
            try {
                send(MotionEvent.ACTION_DOWN, last)
                delay(650)
                for (point in points.drop(1)) {
                    last = point
                    send(MotionEvent.ACTION_MOVE, point)
                    delay(stepMs)
                }
            } catch(t: Throwable) { primary = t; throw t } finally {
                // Same rule as the tap: the DOWN attempt, not its acknowledgement, decides whether the
                // release is owed to this display - and an unconfirmed release halts the session
                // before the gesture can claim an INPUT_SENT of its own.
                releaseOrFail("CONTINUOUS_DRAG", primary) { _ ->
                    send(MotionEvent.ACTION_UP, last)
                }
            }
            facts.input("CONTINUOUS_DRAG",mapOf("points" to points.size,"stepMs" to stepMs))
            recorded = true
        } finally {
            // A gesture stopped mid-way by a refused MOVE delivered no INPUT_SENT of its own: it must
            // not leave an attestation behind for a later tap or gesture to claim.
            facts.endAction(recorded)
        }
    }

    private fun event(downTime: Long, whenMs: Long, action: Int, x: Float, y: Float): MotionEvent {
        val pointer = MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER }
        val coords = MotionEvent.PointerCoords().apply {
            this.x = x; this.y = y
            pressure = if (action == MotionEvent.ACTION_UP) 0f else 1f
            size = 1f
        }
        return MotionEvent.obtain(downTime, whenMs, action, 1, arrayOf(pointer), arrayOf(coords),
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
    }
}
