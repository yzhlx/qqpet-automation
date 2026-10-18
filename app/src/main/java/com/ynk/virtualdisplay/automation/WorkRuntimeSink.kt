package com.ynk.virtualdisplay.automation

/**
 * The only thing PetWorkFlow needs from the runtime: a state label, a journal append, and two
 * boundary queries.
 *
 * Deliberately minimal so the flow's decision logic is JVM-testable without a capture session, an
 * Android context or a device. [PetFlowContext] satisfies it through [asWorkSink].
 */
interface WorkRuntimeSink {
    var state: String

    fun emit(action: String, result: String, reason: String?)

    /**
     * True when the runtime's recovery boundary says input is not possible right now (an App
     * restart is in flight, the display is gone, the session is halted). The work flow asks before
     * every tap and stops instead of sending input into a recovering session.
     *
     * The default is `false` so a plain sink keeps working; the live bridge answers from the real
     * boundary.
     */
    val inputHalted: Boolean get() = false

    /**
     * True when a human asked the runtime to pause or stop. A work flow that is WAITING must notice
     * this and stop observing rather than holding the session open.
     */
    val pauseRequested: Boolean get() = false
}

/**
 * Bridges the live flow context into the narrow sink.
 *
 * `emit(action, score, result, reason)` is kept for the work flow's own calls: the score has no
 * meaning for a work action, and a reason is what turns the append into an ERROR event, so a
 * successful work step must pass null.
 */
fun PetFlowContext.asWorkSink(): WorkRuntimeSink = object : WorkRuntimeSink {
    override var state: String
        get() = this@asWorkSink.state
        set(value) { this@asWorkSink.state = value }

    override fun emit(action: String, result: String, reason: String?) {
        this@asWorkSink.emit(action, null, result, reason)
    }

    /**
     * The work flow asks this before every tap. Both halves are fail-closed:
     *  - the session's latched input halt (an unconfirmed ACTION_UP) means no further DOWN at all;
     *  - a recovery boundary that is PAUSED or RESUMING means the runtime is still reconstructing
     *    the page, so nothing can positively identify what a tap would hit.
     * Either way, sending a real 去打工 tap now could land on a surface nobody identified.
     */
    override val inputHalted: Boolean
        get() = capture.facts.inputHalted != null ||
            recovery.recovery.state != SessionRunState.RUNNING

    /**
     * A human pause is not a failure and must not be reported as one: the work flow stops observing
     * and hands the session back without booking anything it did not prove.
     */
    override val pauseRequested: Boolean
        get() = recovery.recovery.state == SessionRunState.PAUSED
}
