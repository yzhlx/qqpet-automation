package com.ynk.virtualdisplay.automation

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File

/** Finite main-source controller. No dependency on PetExperimentalSession or test APIs. */
class PetSessionController(context:Context,capture:HeadlessDisplayCapture,input:PetInputController,
    private val evidence:File,guard:()->Unit,
    logger:(String,Double?,String,String,String?)->Unit,
    private val totalRounds:Int,
    private val beforeCourse:()->Unit={},
    private val onRoundCompleted:(Int)->Unit={}) {
    private val ledger=PetRoundLedger()
    private var snapshot=ManagedRuntimeState(sessionId=capture.facts.events.sessionId,totalRounds=totalRounds)
    val flow=PetFlowContext(context,capture,input,evidence,guard,logger,
        onObservation={step,score ->
            snapshot=snapshot.copy(currentStep=step,currentFlow=when(step) {
                "CHECK_STATE","RECOVER_IF_NEEDED","VERIFY_READY","READ_POST_STATE" -> "CARE"
                "INITIAL_NAVIGATION","SELECT_COURSE" -> "NAVIGATION"
                "START_COURSE","WAIT_HIGH_FIVE_WINDOW","HIGH_FIVE","WAIT_RESULT" -> "STUDY"
                "NEXT_ROUND","DONE" -> "CONTROL"
                else -> "OBSERVATION"},lastConfirmedAt=if(score!=null && score>=0.97) System.currentTimeMillis() else snapshot.lastConfirmedAt)
            publish()
        },onRecovery={state,reason,_ ->
            snapshot=snapshot.copy(runState=state,pauseReason=if(state==SessionRunState.PAUSED || state==SessionRunState.RESUMING) reason else null)
            publish()
        },reconcile={cp,page -> cp.phase=="WAIT_RESULT" && cp.trustedPage=="LEARNING" && page=="result" && ledger.canReconcileResult(cp.round)})
    private val navigation=PetNavigation(flow)
    private val care=PetCareFlow(flow,navigation) {health ->
        snapshot=snapshot.copy(energy=health.energy,cleanliness=health.cleanliness,mood=health.mood);publish()
    }
    private val study=PetStudyFlow(flow,navigation,ledger)
    var completed=0;private set
    var stopReason:String?=null;private set
    init {require(totalRounds in 1..3)}
    @Synchronized private fun publish() {
        val value=snapshot.at(System.currentTimeMillis(),SystemClock.elapsedRealtime())
        PetManagedRuntime.publish(value)
        evidence.mkdirs()
        File(evidence,"runtime-state.json").writeText(EventJson.encode(mapOf(
            "sessionId" to value.sessionId,"runState" to value.runState.name,"flow" to value.currentFlow,
            "step" to value.currentStep,"round" to value.round,"totalRounds" to value.totalRounds,
            "completedRounds" to value.completedRounds,"startedAt" to value.startedAt,
            "lastConfirmedAt" to value.lastConfirmedAt,"nextStep" to value.nextStep,
            "pauseReason" to value.pauseReason,"runtimeMs" to value.runtimeMs,
            "energy" to value.energy.fields(),"cleanliness" to value.cleanliness.fields(),"mood" to value.mood.fields())))
    }
    suspend fun run():String=coroutineScope {
        snapshot=snapshot.copy(runState=SessionRunState.RUNNING,startedAt=System.currentTimeMillis(),
            startedElapsedMs=SystemClock.elapsedRealtime(),nextStep="读取健康状态")
        publish()
        val ticker=launch {while(isActive) {delay(1000);publish()}}
        flow.capture.facts.events.append(EventSource.APP_AUTOMATION,EventKind.SESSION_CONFIG,mapOf(
            "runner" to "PetSessionController","rounds" to totalRounds,"moodPolicy" to "OBSERVATION_ONLY",
            "energyThreshold" to 90,"cleanlinessThreshold" to 90))
        try {
            flow.state="INITIAL_NAVIGATION"
            navigation.initialise()
            repeat(totalRounds) {index ->
                flow.round=index+1;flow.state="CHECK_STATE"
                snapshot=snapshot.copy(round=flow.round,currentFlow="CARE",nextStep="护理后确认体力与清洁")
                val before=care.readHealth("before")
                val beforeRecord=flow.healthJson(before)
                care.recover(before)
                flow.state="VERIFY_READY"
                val ready=care.readHealth("ready")
                PetStudyHealthPolicy.requireReady(ready.observations,System.currentTimeMillis())
                val readyRecord=flow.healthJson(ready)
                beforeCourse()
                snapshot=snapshot.copy(nextStep="选择蹦蹦跳跳体能课")
                val course=study.start(ready)
                val started=course.getLong("courseStartElapsedRealtime")
                snapshot=snapshot.copy(currentFlow="STUDY",nextStep="开课约30秒后鼓励10次")
                study.highFiveWindow(started,course)
                snapshot=snapshot.copy(nextStep="等待成绩")
                val result=study.waitResult(started)
                flow.state="READ_POST_STATE"
                snapshot=snapshot.copy(nextStep="处理成绩及升级弹窗")
                navigation.returnHomeFromResult("result")
                val after=care.readHealth("after")
                check(ledger.complete(flow.round)) {"DUPLICATE_ROUND_COMPLETION"}
                completed=ledger.completedCount;onRoundCompleted(flow.round)
                snapshot=snapshot.copy(completedRounds=completed,nextStep=if(completed<totalRounds) "下一节开课前检查" else "批次完成")
                flow.state="NEXT_ROUND"
                val record=JSONObject().put("round",flow.round).put("before",beforeRecord)
                    .put("ready",readyRecord).put("care",care.careStatistics).put("course",course)
                    .put("result",result).put("after",flow.healthJson(after)).put("appEncouragementSent",ledger.encouragementCount(flow.round))
                // Before/ready records are historical observations, never re-used as current authorization.
                File(evidence,"round-${flow.round}.json").writeText(record.toString())
                flow.emit("ROUND_COMPLETE",result=record.toString());publish()
            }
            flow.state="DONE";flow.emit("SESSION_COMPLETE",result="MANAGED_BATCH_PASS $completed/$totalRounds")
            "MANAGED_BATCH_PASS"
        } catch(t:Throwable) {
            stopReason=t.message ?: t.javaClass.simpleName
            snapshot=snapshot.copy(pauseReason=stopReason,nextStep="停止输入")
            flow.emit("SESSION_STOP",result="STOP",reason=stopReason)
            if(t is CancellationException) throw t
            "STOPPED"
        } finally {
            withContext(NonCancellable) {
                ticker.cancelAndJoin()
                flow.recovery.recovery.finish(stopReason ?: "BATCH_COMPLETE")
                snapshot=snapshot.copy(runState=SessionRunState.STOPPED,pauseReason=stopReason);publish()
            }
        }
    }
}
