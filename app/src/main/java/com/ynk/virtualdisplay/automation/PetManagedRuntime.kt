package com.ynk.virtualdisplay.automation

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ManagedRuntimeState(
    val sessionId:String?=null,
    val runState:SessionRunState=SessionRunState.IDLE,
    val currentFlow:String="IDLE",
    val currentStep:String="IDLE",
    val round:Int=0,
    val totalRounds:Int=0,
    val completedRounds:Int=0,
    val startedAt:Long?=null,
    val startedElapsedMs:Long?=null,
    val lastConfirmedAt:Long?=null,
    val nextStep:String="等待启动",
    val pauseReason:String?=null,
    val pauseCount:Int=0,
    val runtimeMs:Long=0,
    val energy:ObservedValue<Int> = ObservedValue.Unknown("NOT_OBSERVED",0,"session not started"),
    val cleanliness:ObservedValue<Int> = ObservedValue.Unknown("NOT_OBSERVED",0,"session not started"),
    val mood:ObservedValue<Int> = ObservedValue.Unknown("NOT_OBSERVED",0,"session not started"),
) {
    fun at(wall:Long,elapsed:Long)=copy(energy=energy.at(wall,60000),cleanliness=cleanliness.at(wall,60000),
        mood=mood.at(wall,60000),runtimeMs=startedElapsedMs?.let {(elapsed-it).coerceAtLeast(0)} ?: 0)
}

/** Only the controller publishes. UI receives immutable snapshots and a read-only flow. */
object PetManagedRuntime {
    private val mutable=MutableStateFlow(ManagedRuntimeState())
    val state:StateFlow<ManagedRuntimeState> = mutable.asStateFlow()
    internal fun publish(value:ManagedRuntimeState) {mutable.value=value}
}

/** Same active coroutine resumes, so course start/count/encouragement are never replayed. */
class PetRoundLedger {
    private val attempts=mutableSetOf<Int>()
    private val started=mutableSetOf<Int>()
    private val completed=mutableSetOf<Int>()
    private val encouragement=mutableMapOf<Int,Int>()
    val completedCount get()=completed.size
    val startedCount get()=started.size
    val attemptCount get()=attempts.size
    val encouragementTotal get()=encouragement.values.sum()
    fun startAttempt(round:Int) {check(round>0 && attempts.add(round)) {"DUPLICATE_COURSE_START"}}
    fun started(round:Int) {check(round in attempts && started.add(round)) {"DUPLICATE_START_CONFIRMATION"}}
    fun complete(round:Int):Boolean {check(round in started) {"COURSE_NOT_STARTED"};return completed.add(round)}
    fun encouraged(round:Int) {check(round in started);val n=encouragement[round] ?: 0
        check(n<10) {"ENCOURAGEMENT_LIMIT"};encouragement[round]=n+1}
    fun encouragementCount(round:Int)=encouragement[round] ?: 0
    fun canReconcileResult(round:Int)=round in started && round !in completed
}
