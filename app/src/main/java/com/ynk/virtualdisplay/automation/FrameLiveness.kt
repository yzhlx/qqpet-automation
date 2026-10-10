package com.ynk.virtualdisplay.automation

enum class FrameStatus { VALID, INVALID_BLACK_FRAME, INVALID_SIZE, INVALID_DECODE, INVALID_PNG,
    DISPLAY_UNAVAILABLE, INVALID_DISPLAY_ID, IDENTITY_MISMATCH, CAPTURE_FAILED }
/**
 * [observedAtMs] is the monotonic instant at which this exact frame was RECEIVED from the
 * capture transport. It is carried with the frame so that age is measured from the frame's own
 * receipt, never from the moment a consumer happens to look at it: a re-validated cache entry or
 * a queued ImageReader buffer therefore keeps its original, older instant. 0 means the receipt
 * instant is unknown, which the freshness gate refuses rather than assumes fresh.
 */
data class FrameLivenessResult(val status: FrameStatus, val detail: String = status.name,
    val observedAtMs: Long = 0L) {
    val valid get() = status == FrameStatus.VALID
}

/** Fixed-device base checks, independent of templates and capture transport. */
object FrameLiveness {
    fun failure(message: String): FrameLivenessResult {
        val status=when {
            "BLACK_FRAME" in message -> FrameStatus.INVALID_BLACK_FRAME
            "DECODE" in message -> FrameStatus.INVALID_DECODE
            "INVALID_PNG" in message -> FrameStatus.INVALID_PNG
            "SIZE" in message -> FrameStatus.INVALID_SIZE
            "IDENTITY" in message -> FrameStatus.IDENTITY_MISMATCH
            "DEFAULT_DISPLAY" in message -> FrameStatus.INVALID_DISPLAY_ID
            "DISPLAY_UNAVAILABLE" in message -> FrameStatus.DISPLAY_UNAVAILABLE
            else -> FrameStatus.CAPTURE_FAILED
        }
        return FrameLivenessResult(status,message)
    }
    fun inspect(displayId: Int, width: Int, height: Int, expectedWidth: Int, expectedHeight: Int,
        available: Boolean = true, decoded: Boolean = true, pixel: (Int,Int)->Int): FrameLivenessResult {
        if(displayId<=0) return FrameLivenessResult(FrameStatus.INVALID_DISPLAY_ID)
        if(!available) return FrameLivenessResult(FrameStatus.DISPLAY_UNAVAILABLE)
        if(!decoded) return FrameLivenessResult(FrameStatus.INVALID_DECODE)
        if(width!=expectedWidth || height!=expectedHeight || width<=0 || height<=0)
            return FrameLivenessResult(FrameStatus.INVALID_SIZE,"$width x $height")
        for(y in 0 until height step 16) for(x in 0 until width step 16)
            if((pixel(x,y) and 0x00ffffff)!=0) return FrameLivenessResult(FrameStatus.VALID)
        return FrameLivenessResult(FrameStatus.INVALID_BLACK_FRAME)
    }
}

/** First miss inhibits input. A successful reacquisition recovers; N consecutive misses latch STOP. */
class FrameTrustGate(private val failureLimit: Int = 3) {
    init {require(failureLimit>0)}
    var consecutiveFailures=0; private set
    var stopped=false; private set
    var inputAllowed=false; private set
    /**
     * Receipt instant carried by the frame that currently authorizes input. It is copied from the
     * frame, never read from the clock here, so observing the same frame twice cannot age it down.
     */
    private var acceptedAtMs=0L
    @Synchronized fun observe(result: FrameLivenessResult) {
        if(stopped) return
        if(result.valid) { consecutiveFailures=0; inputAllowed=true; acceptedAtMs=result.observedAtMs }
        else {
            consecutiveFailures++;inputAllowed=false;acceptedAtMs=0L
            stopped=consecutiveFailures>=failureLimit
        }
    }
    @Synchronized fun requireInputAllowed() {check(inputAllowed && !stopped) {"FRAME_NOT_TRUSTED:$consecutiveFailures"}}
    /** Age of the frame that authorizes input, or null when no frame does or its receipt is unknown. */
    @Synchronized fun trustedFrameAgeMs(nowMs: Long): Long? {
        if(!inputAllowed || stopped || acceptedAtMs<=0L) return null
        return if(nowMs<acceptedAtMs) null else nowMs-acceptedAtMs
    }
    /**
     * Strict start-of-action freshness gate: the frame authorizing a NEW business action must have
     * been RECEIVED no more than [maxAgeMs] ago. It fails closed - no accepted frame, an unknown
     * receipt instant, a rolled-back clock, an invalid frame, or a latched stop all refuse the
     * action rather than assume the frame is fresh. Because the age comes from the frame's own
     * receipt, a re-validated cache entry or a queued buffer is correctly judged old, and the
     * caller cannot renew a frame's authority by looking at it again.
     */
    @Synchronized fun requireFreshInput(nowMs: Long, maxAgeMs: Long = 5000L) {
        require(maxAgeMs>0) {"FRAME_FRESHNESS_BUDGET_INVALID:$maxAgeMs"}
        check(inputAllowed && !stopped) {"FRAME_NOT_TRUSTED:$consecutiveFailures"}
        check(acceptedAtMs>0L) {"FRAME_FRESHNESS_UNKNOWN"}
        check(nowMs>=acceptedAtMs) {"FRAME_FRESHNESS_UNKNOWN:CLOCK_ROLLBACK:$nowMs<$acceptedAtMs"}
        val age=nowMs-acceptedAtMs
        check(age<=maxAgeMs) {"FRAME_TOO_OLD:$age>$maxAgeMs"}
    }
    /** Only an explicit RESUMING attempt may reacquire; the session input barrier remains closed. */
    @Synchronized fun rearmForRecovery() {consecutiveFailures=0;stopped=false;inputAllowed=false;acceptedAtMs=0L}
}
