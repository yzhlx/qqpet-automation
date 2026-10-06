package com.ynk.virtualdisplay.automation

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

enum class SessionRunState { RUNNING, PAUSED, RESUMING, STOPPED }
data class SessionCheckpoint(val sessionId: String,val round: Int,val phase: String,val trustedPage: String,
    val health: HealthObservations?,val observedAt: Long,val healthRequired: Boolean=false,
    val actionPending: Boolean=false)
interface CheckpointStore {
    fun save(checkpoint: SessionCheckpoint)
    fun load(): SessionCheckpoint?
}
data class RecoveryObservation(val displayValid: Boolean,val frameValid: Boolean,val page: String?,
    val health: HealthObservations?=null)

/** Restore observations at a safe boundary. Never replays an input or guesses a page. */
class SessionRecovery(val sessionId: String,private val store: CheckpointStore,
    private val wall: ()->Long,private val mono: ()->Long,
    private val transition: (SessionRunState,String,Int)->Unit,
    private val blockInput: ()->Unit,private val rearmCapture: ()->Unit,private val allowInput: ()->Unit,
    private val maxAttempts: Int=3,private val windowMs: Long=120000,
    private val wait: suspend (Long)->Unit={delay(it)}) {
    init {require(maxAttempts in 1..3 && windowMs in 1..120000)}
    var state=SessionRunState.RUNNING;private set
    var checkpoint: SessionCheckpoint?=null;private set
    private fun change(next: SessionRunState,reason: String,attempt: Int=0) {
        state=next;transition(next,reason,attempt)
    }
    fun confirm(round: Int,phase: String,page: String,health: HealthObservations?=checkpoint?.health,
        healthRequired: Boolean=false) {
        check(state==SessionRunState.RUNNING)
        require(page.isNotBlank() && page!="UNKNOWN" && round>=0)
        val value=SessionCheckpoint(sessionId,round,phase,page,health,wall(),healthRequired)
        store.save(value);checkpoint=value
    }
    fun boundary(round: Int,phase: String,healthRequired: Boolean=false) {
        checkpoint?.let { val value=it.copy(round=round,phase=phase,healthRequired=healthRequired)
            store.save(value);checkpoint=value }
    }
    fun actionPending() {
        checkpoint?.let {val value=it.copy(actionPending=true);store.save(value);checkpoint=value}
    }
    fun finish(reason: String) {blockInput();change(SessionRunState.STOPPED,reason)}
    fun stop(reason: String): Nothing {finish(reason);error(reason)}
    suspend fun recover(reason: String,locked: ()->Boolean,observe: suspend (SessionCheckpoint)->RecoveryObservation) {
        check(state!=SessionRunState.STOPPED) {"RECOVERY_ALREADY_STOPPED"}
        blockInput();change(SessionRunState.PAUSED,reason)
        val cp=try {store.load()} catch(t: Exception) {stop("CHECKPOINT_INVALID:${t.message}")}
            ?: stop("CHECKPOINT_MISSING")
        if(cp.sessionId!=sessionId || cp!=checkpoint) stop("CHECKPOINT_IDENTITY_MISMATCH")
        // A delivered DOWN must never be resent merely because capture failed afterwards.
        if(cp.actionPending) stop("CHECKPOINT_ACTION_UNCONFIRMED")
        val began=mono();val deadline=began+windowMs
        var attempt=0
        while(mono()<deadline) {
            if(mono()<began) stop("RECOVERY_CLOCK_REVERSED")
            if(locked()) {wait(minOf(500,deadline-mono()));continue}
            if(attempt>=maxAttempts) stop("RECOVERY_ATTEMPTS_EXHAUSTED")
            attempt++;change(SessionRunState.RESUMING,reason,attempt)
            try {
                rearmCapture()
                val observation=withTimeout(minOf(30000,deadline-mono()).coerceAtLeast(1)) {observe(cp)}
                if(!observation.displayValid || !observation.frameValid) throw IllegalStateException("RECOVERY_ENVIRONMENT_INVALID")
                if(locked()) {blockInput();change(SessionRunState.PAUSED,"PHONE_RELOCKED",attempt);continue}
                if(observation.page==null) stop("RECOVERY_PAGE_UNKNOWN")
                if(observation.page!=cp.trustedPage) stop("RECOVERY_PAGE_MISMATCH:${cp.trustedPage}:${observation.page}")
                if(cp.healthRequired) {
                    val health=observation.health ?: stop("RECOVERY_HEALTH_REOBSERVATION_REQUIRED")
                    try {health.current(wall())} catch(t: Exception) {stop("RECOVERY_HEALTH_UNCONFIRMED:${t.message}")}
                }
                if(mono()>=deadline) stop("RECOVERY_WINDOW_EXHAUSTED")
                val rebuilt=cp.copy(health=observation.health ?: cp.health,observedAt=wall())
                store.save(rebuilt);checkpoint=rebuilt
                change(SessionRunState.RUNNING,"RESUME_VERIFIED",attempt);allowInput();return
            } catch(cancelled: kotlinx.coroutines.TimeoutCancellationException) {
                currentCoroutineContext().ensureActive()
                blockInput();change(SessionRunState.PAUSED,"RECOVERY_PROBE_TIMEOUT",attempt)
            } catch(cancelled: CancellationException) {
                blockInput();change(SessionRunState.STOPPED,"CANCELLED",attempt);throw cancelled
            } catch(t: Exception) {
                if(state==SessionRunState.STOPPED) throw t
                blockInput();change(SessionRunState.PAUSED,t.message ?: "RECOVERY_PROBE_FAILED",attempt)
            }
            if(attempt<maxAttempts && mono()<deadline) wait(minOf(1000,deadline-mono()))
        }
        stop("RECOVERY_WINDOW_EXHAUSTED")
    }
}
