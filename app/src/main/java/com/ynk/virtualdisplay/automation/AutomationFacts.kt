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
    var beforeInput: suspend ()->Unit={}
    var markActionPending: ()->Unit={}
    fun inhibitInput() {recoveryBlocked=true}
    fun rearmCapture() {check(recoveryBlocked);frames.rearmForRecovery()}
    fun resumeInput() {frames.requireInputAllowed();recoveryBlocked=false}
    fun frame(result: FrameLivenessResult, source: String) {
        try { events.append(EventSource.GAME_OBSERVATION,if(result.valid) EventKind.FRAME_VALID else EventKind.FRAME_INVALID,
            mapOf("displayId" to displayId,"status" to result.status.name,"detail" to result.detail,
                "captureSource" to source,"consecutiveFailures" to if(result.valid) 0 else frames.consecutiveFailures+1,
                "inputAllowed" to (result.valid && !frames.stopped && !recoveryBlocked))) }
        catch(t: Exception) { frames.observe(FrameLivenessResult(FrameStatus.CAPTURE_FAILED,"EVENT_WRITE_FAILED"));throw t }
        frames.observe(result)
    }
    fun health(snapshot: HealthObservations) {
        val values=listOf(snapshot.energy,snapshot.cleanliness,snapshot.mood)
        events.append(EventSource.GAME_OBSERVATION,if(values.all {it is ObservedValue.Known})
            EventKind.HEALTH_OBSERVED else EventKind.HEALTH_UNKNOWN,snapshot.fields(),listOf(snapshot.evidence))
    }
    fun requireInput() {check(!recoveryBlocked) {"SESSION_INPUT_PAUSED"};frames.requireInputAllowed()}
    fun input(action: String, payload: Map<String,Any?>) {
        events.append(EventSource.APP_AUTOMATION,EventKind.INPUT_SENT,mapOf("displayId" to displayId,"action" to action)+payload)
    }
}
