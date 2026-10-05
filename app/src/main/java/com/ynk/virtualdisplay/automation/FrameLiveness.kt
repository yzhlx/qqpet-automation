package com.ynk.virtualdisplay.automation

enum class FrameStatus { VALID, INVALID_BLACK_FRAME, INVALID_SIZE, INVALID_DECODE, INVALID_PNG,
    DISPLAY_UNAVAILABLE, INVALID_DISPLAY_ID, IDENTITY_MISMATCH, CAPTURE_FAILED }
data class FrameLivenessResult(val status: FrameStatus, val detail: String = status.name) {
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
    @Synchronized fun observe(result: FrameLivenessResult) {
        if(stopped) return
        if(result.valid) { consecutiveFailures=0; inputAllowed=true }
        else { consecutiveFailures++;inputAllowed=false;stopped=consecutiveFailures>=failureLimit }
    }
    @Synchronized fun requireInputAllowed() {check(inputAllowed && !stopped) {"FRAME_NOT_TRUSTED:$consecutiveFailures"}}
}
