package com.ynk.virtualdisplay.automation

import android.app.KeyguardManager
import android.content.Context
import android.graphics.Bitmap
import java.io.File

/** Shared by the existing Session/FSM and the live fault gate. Recovery performs no input. */
class PetRecoveryBoundary(private val context: Context,private val capture: HeadlessDisplayCapture,
    private val displayCheck: ()->Unit,private val pages: Map<String,List<PetProfile.Probe>>,
    private val frameTimeoutMs: Long=350L,
    private val reconcile: (SessionCheckpoint,String)->Boolean={_,_->false},
    private val validateHealth: (HealthObservations,Long)->Unit={health,at ->health.current(at);Unit},
    private val onState: (SessionRunState,String,Int)->Unit={_,_,_->}) {
    private val facts=capture.facts
    private val matcher=TemplateMatcher(context)
    private val healthProbe=PetProfile.Probe("health",android.graphics.Rect(169,228,250,415),"pet_health/health.png")
    val file=File(context.filesDir,"pet-checkpoints/${facts.events.sessionId}.json")
    val recovery=SessionRecovery(facts.events.sessionId,FileCheckpointStore(file),facts.clock::wallTimeMs,facts.clock::monotonicMs,
        {state,reason,attempt ->
            PetRuntime.update {it.copy(runState=state,status=state.name)}
            facts.events.append(EventSource.APP_AUTOMATION,EventKind.SESSION_STATE,
                mapOf("state" to state.name,"reason" to reason,"attempt" to attempt,"checkpoint" to file.absolutePath))
            onState(state,reason,attempt)
        },facts::inhibitInput,facts::rearmCapture,facts::resumeInput,
        reconcile=reconcile,validateHealth=validateHealth)
    init {
        PetRuntime.update {it.copy(runState=SessionRunState.RUNNING)}
        facts.events.append(EventSource.APP_AUTOMATION,EventKind.SESSION_STATE,
            mapOf("state" to "RUNNING","reason" to "SESSION_STARTED","attempt" to 0))
        facts.beforeInput={beforeBoundary()}
        facts.markActionPending={recovery.actionPending()}
    }
    fun locked()=(context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isKeyguardLocked
    fun confirm(round: Int,phase: String,page: String,healthRequired: Boolean=false) {
        recovery.confirm(round,phase,page,healthRequired=healthRequired)
        facts.events.append(EventSource.GAME_OBSERVATION,EventKind.CHECKPOINT,
            mapOf("round" to round,"phase" to phase,"trustedPage" to page,"healthRequired" to healthRequired),listOf(file.absolutePath))
    }
    fun health(value: HealthObservations) {
        recovery.checkpoint?.let {recovery.confirm(it.round,it.phase,it.trustedPage,value,it.healthRequired)}
    }
    fun boundary(round: Int,phase: String,healthRequired: Boolean=false)=recovery.boundary(round,phase,healthRequired)
    suspend fun beforeBoundary() {
        if(recovery.state==SessionRunState.STOPPED) error("SESSION_STOPPED")
        if(locked()) recover("PHONE_LOCKED")
        try {displayCheck()} catch(t: Exception) {recover("DISPLAY_INVALID:${t.message}")}
    }
    suspend fun current(latest: Boolean=false): Bitmap {
        beforeBoundary()
        return try {if(latest) capture.latestRenderedFrame() else capture.awaitFrame(frameTimeoutMs)}
        catch(t: kotlinx.coroutines.CancellationException) {throw t}
        catch(t: Exception) {
            recover("CAPTURE_INVALID:${t.message}")
            // Re-observe after recovery, rather than releasing a pre-pause bitmap.
            try {capture.awaitFrame(frameTimeoutMs)} catch(failed: Exception) {recovery.stop("CAPTURE_FAILED_AFTER_RESUME:${failed.message}")}
        }
    }
    suspend fun recover(reason: String) = recovery.recover(reason,::locked) {cp ->
        displayCheck()
        val frame=capture.awaitFrame(frameTimeoutMs)
        try {
            displayCheck()
            val healthVisible=matcher.match(frame,healthProbe,0.97).matched
            val probes=pages[cp.trustedPage] ?: recovery.stop("CHECKPOINT_PAGE_UNSUPPORTED")
            val matches=probes.any {matcher.match(frame,it,0.97).matched}
            // Home/room remain visible behind the health overlay. That is a different page.
            val overlayMismatch=healthVisible && cp.trustedPage!="health" && cp.trustedPage!="HEALTH"
            val ambiguousCourse=cp.trustedPage in setOf("course_list","course_detail","COURSE_LIST","COURSE_DETAIL") &&
                matcher.match(frame,PetProfile.calibrated().courseListProbe,0.97).matched &&
                matcher.match(frame,PetProfile.calibrated().courseDetailProbe,0.97).matched
            val resultVisible=matcher.match(frame,PetProfile.calibrated().resultProbe,0.97).matched
            val page=if(overlayMismatch) "HEALTH" else if(ambiguousCourse) null else if(matches) cp.trustedPage
                else if(resultVisible) "result" else null
            val health=if(cp.healthRequired && healthVisible) PetHealthCheck.observe(context,frame,
                facts.clock.wallTimeMs(),"recovery:${facts.events.sessionId}") else null
            if(health!=null) facts.health(health)
            RecoveryObservation(true,true,page,health)
        } finally {frame.recycle()}
    }
}
