package com.ynk.virtualdisplay.automation

import android.os.SystemClock
import java.io.File
import java.util.UUID

class AndroidEventClock : EventClock {
    override fun wallTimeMs()=System.currentTimeMillis()
    override fun monotonicMs()=SystemClock.elapsedRealtime()
    override val bootId: ObservedValue<String> by lazy {
        try { ObservedValue.Known(File("/proc/sys/kernel/random/boot_id").readText().trim(),wallTimeMs(),1.0,
            "/proc/sys/kernel/random/boot_id",ObservationSource.SYSTEM) }
        catch(t: Exception) {ObservedValue.Unknown("BOOT_ID_UNREADABLE:${t.javaClass.simpleName}",wallTimeMs(),"proc boot id")}
    }
}

class AutomationFacts(directory: File, val displayId: Int, val clock: EventClock = AndroidEventClock()) {
    val events=SessionEventStore(directory,UUID.randomUUID().toString(),clock)
    val frames=FrameTrustGate(3)
    @Volatile private var recoveryBlocked=false
    /**
     * Latched fail-stop for this session, set when an ACTION_UP could not be confirmed. It is never
     * cleared: not by [rearmCapture], [resumeInput] or [beginAction], and not by a fresh frame. An
     * unconfirmed release may have left the target display pressed, so this session must send no
     * further DOWN. Only a new session (a new [AutomationFacts]) starts without it.
     */
    @Volatile private var inputHaltReason: String? = null
    /**
     * Receipt age of the frame that authorised the CURRENT action, or null. It is cleared when an
     * action begins, cleared again whenever the gate refuses or the action aborts, and consumed by
     * the action's own INPUT_SENT record, so an unfinished gesture cannot authorise a later one.
     */
    @Volatile private var authorizingFrameAgeMs: Long? = null
    var beforeInput: suspend ()->Unit={}
    var markActionPending: ()->Unit={}
    fun inhibitInput() {recoveryBlocked=true;authorizingFrameAgeMs=null}
    /**
     * The capture is deliberately NOT re-armed while an unconfirmed UP is latched: recovering would
     * otherwise look like a clean bill of health for a session that must send no further DOWN.
     */
    fun rearmCapture() {
        requireNotHalted()
        check(recoveryBlocked)
        authorizingFrameAgeMs=null
        frames.rearmForRecovery()
    }
    fun resumeInput() {
        requireNotHalted()
        frames.requireInputAllowed()
        authorizingFrameAgeMs=null
        recoveryBlocked=false
    }
    /** Opens a new business action: no action may inherit the previous action's attestation. */
    fun beginAction() {authorizingFrameAgeMs=null}
    /**
     * Closes an action opened by [beginAction]. [delivered]=false means the action never reached its
     * INPUT_SENT record - it was refused, aborted mid-gesture, cancelled or failed - so its
     * attestation is withdrawn rather than left available to whatever input runs next.
     */
    fun endAction(delivered: Boolean) {if(!delivered) authorizingFrameAgeMs=null}
    fun frame(result: FrameLivenessResult, source: String) {
        // A frame that arrives WITHOUT a receipt instant keeps observedAtMs=0 all the way to the
        // gate; it is journalled as UNKNOWN and never stamped with the observing clock. Stamping it
        // here would make a queued ImageReader buffer or a re-validated cache entry look brand new,
        // which is exactly the gap this guard exists to close: an untimed frame is TRUSTED for
        // liveness but cannot authorise a new action (the freshness gate refuses it).
        val receipt = when {
            result.observedAtMs>0L -> "TRANSPORT"
            !result.valid -> "NOT_APPLICABLE"
            else -> "UNKNOWN"
        }
        try { events.append(EventSource.GAME_OBSERVATION,if(result.valid) EventKind.FRAME_VALID else EventKind.FRAME_INVALID,
            mapOf("displayId" to displayId,"status" to result.status.name,"detail" to result.detail,
                "captureSource" to source,"consecutiveFailures" to if(result.valid) 0 else frames.consecutiveFailures+1,
                "inputAllowed" to (result.valid && !frames.stopped && !recoveryBlocked),
                "frameObservedAtMs" to result.observedAtMs,"receiptSource" to receipt)) }
        catch(t: Exception) { frames.observe(FrameLivenessResult(FrameStatus.CAPTURE_FAILED,"EVENT_WRITE_FAILED"));throw t }
        frames.observe(result)
    }
    fun health(snapshot: HealthObservations) {
        val values=listOf(snapshot.energy,snapshot.cleanliness,snapshot.mood)
        events.append(EventSource.GAME_OBSERVATION,if(values.all {it is ObservedValue.Known})
            EventKind.HEALTH_OBSERVED else EventKind.HEALTH_UNKNOWN,snapshot.fields(),listOf(snapshot.evidence))
    }
    /**
     * Latches the session fail-stop. Called when an ACTION_UP could not be confirmed: the display may
     * still be pressed, so no further DOWN may be sent in this session. Nothing clears it.
     */
    fun haltInput(reason: String) {inputHaltReason=reason;authorizingFrameAgeMs=null}
    /** The latched fail-stop reason, or null. Exposed so a caller can name why input stopped. */
    val inputHalted: String? get() = inputHaltReason
    private fun requireNotHalted() {
        inputHaltReason?.let { error("SESSION_INPUT_HALTED_BY_UNCONFIRMED_UP:$it") }
    }
    fun requireInput() {
        requireNotHalted()
        check(!recoveryBlocked) {"SESSION_INPUT_PAUSED"}
        frames.requireInputAllowed()
    }
    /**
     * Strict freshness gate for the START of a new business action only. A continuous gesture keeps
     * the pause / invalid-frame / display guards it already had, so a long gesture is not aborted
     * mid-way by the age of the frame that was fresh when the gesture began.
     */
    fun requireFreshInput(maxAgeMs: Long = MAX_INPUT_FRAME_AGE_MS) {
        // Any new-action gate evaluation withdraws the previous action's attestation first, so a
        // refusal can never leave it available for a later INPUT_SENT to inherit.
        authorizingFrameAgeMs=null
        requireNotHalted()
        check(!recoveryBlocked) {"SESSION_INPUT_PAUSED"}
        try {
            val now=clock.monotonicMs()
            frames.requireFreshInput(now,maxAgeMs)
            // Remember the attested age so the resulting INPUT_SENT can carry the frame association
            // that justified it, instead of leaving the link to be reconstructed from event order.
            authorizingFrameAgeMs=frames.trustedFrameAgeMs(now)
        }
        catch(t: IllegalStateException) {
            authorizingFrameAgeMs=null
            // Record the refusal, never mask it: a failure to journal must not release the gate.
            runCatching {
                events.append(EventSource.APP_AUTOMATION,EventKind.ERROR,mapOf("displayId" to displayId,
                    "gate" to "INPUT_FRESHNESS","maxAgeMs" to maxAgeMs,"reason" to (t.message ?: "REFUSED")))
            }
            throw t
        }
    }
    fun input(action: String, payload: Map<String,Any?>) {
        // Consumed once: an action that was not itself preceded by a passing freshness check records
        // null rather than inheriting an earlier action's attestation.
        val attested=authorizingFrameAgeMs
        authorizingFrameAgeMs=null
        events.append(EventSource.APP_AUTOMATION,EventKind.INPUT_SENT,mapOf("displayId" to displayId,"action" to action,
            // null means the action was not authorised by a freshness-attested frame; callers that
            // bypass the gate must not be able to claim a fresh authorising frame.
            "authorizingFrameAgeMs" to attested,
            "freshnessBudgetMs" to MAX_INPUT_FRAME_AGE_MS)+payload)
    }
    companion object {
        /** Measured 5 s budget between the receipt of the authorizing frame and a new action start. */
        const val MAX_INPUT_FRAME_AGE_MS=5000L
    }
}
