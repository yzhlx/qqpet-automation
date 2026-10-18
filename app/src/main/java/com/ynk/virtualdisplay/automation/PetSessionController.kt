package com.ynk.virtualdisplay.automation

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File

/**
 * Whether the session must run a work shift before the first lesson.
 *
 * [coins] is the balance read off the current frame, or null when it could not be read. An unread
 * balance is treated as short: skipping the shift on a guess would start a lesson the pet may not
 * be able to pay for, while running one extra verified shift only costs its known minutes.
 */
object TuitionWorkDecision {
    fun needsWork(coins: Int?, tuitionCoins: Int): Boolean = coins == null || coins < tuitionCoins
}

/** Finite main-source controller. No dependency on PetExperimentalSession or test APIs. */
class PetSessionController(context:Context,capture:HeadlessDisplayCapture,input:PetInputController,
    private val evidence:File,guard:()->Unit,
    logger:(String,Double?,String,String,String?)->Unit,
    private val totalRounds:Int,
    private val mode:SessionMode=SessionMode.FULL,
    private val plan:SessionPlan=SessionPlan(),
    private val beforeCourse:()->Unit={},
    private val onRoundCompleted:(Int)->Unit={},
    private val onWorkCompleted:(Int)->Unit={}) {
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
            // Count a pause once per PAUSED episode, not once per transition into it.
            val pausing=state==SessionRunState.PAUSED && snapshot.runState!=SessionRunState.PAUSED
            snapshot=snapshot.copy(runState=state,
                pauseReason=if(state==SessionRunState.PAUSED || state==SessionRunState.RESUMING) reason else null,
                pauseCount=if(pausing) snapshot.pauseCount+1 else snapshot.pauseCount)
            publish()
        },reconcile={cp,page -> cp.phase=="WAIT_RESULT" && cp.trustedPage=="LEARNING" && page=="result" && ledger.canReconcileResult(cp.round)})
    private val navigation=PetNavigation(flow)
    // Consumable/exchange budgets must scale with the batch: a long session eats/drags every
    // round, and a failed consumable gesture retains its allowance as possibly-spent before the
    // restock buys a verified replacement (measured: ~2 budget slots per wasted feed). One cookie
    // ≈ one feed round, one soap ≈ one clean round, plus the single-round spares measured on
    // device (6 cookies / 4 soaps at 1-3 rounds). Coins: cookie=5, soap=2, plus a drift margin.
    private val cookieBudget=maxOf(6,2*totalRounds)
    private val soapBudget=maxOf(4,totalRounds+1)
    private val care=PetCareFlow(flow,navigation,
        budget=CareConsumableBudget(cookies=cookieBudget,soaps=soapBudget),
        exchangeBudget=ExchangeBudget(maxPurchases=cookieBudget+soapBudget,
            maxCoins=6*cookieBudget+3*soapBudget)) {health ->
        snapshot=snapshot.copy(energy=health.energy,cleanliness=health.cleanliness,mood=health.mood);publish()
    }
    private val reader=MlKitCourseNameReader()
    private val study=PetStudyFlow(flow,navigation,ledger,CourseSelector(flow,navigation,reader))
    private val work by lazy {
        val pageReader=DeviceWorkPageReader(flow,reader)
        PetWorkFlow(flow.asWorkSink(),
            PetWorkNavigation(flow.asWorkSink(),pageReader,
                tap={point,settle -> flow.input.tap(point.x,point.y);kotlinx.coroutines.delay(settle)},
                swipe={x1,y1,x2,y2,ms -> flow.input.drag(listOf(android.graphics.PointF(x1.toFloat(),y1.toFloat()),android.graphics.PointF(x2.toFloat(),y2.toFloat())),ms)}),
            DeviceWorkResourceReader(care))
    }
    var completed=0;private set
    var stopReason:String?=null;private set
    /** Tuition of the one verified course, measured on device. A plan for another course cannot use it. */
    private val COURSE_TUITION_COINS=22
    private val COIN_BALANCE=Regex("""金币\s*(\d+)""")
    private var cancelled=false
    init {require(totalRounds in 1..10)}
    @Synchronized private fun publish() {
        val value=snapshot.at(System.currentTimeMillis(),SystemClock.elapsedRealtime())
        PetManagedRuntime.publish(value)
        evidence.mkdirs()
        File(evidence,"runtime-state.json").writeText(EventJson.encode(mapOf(
            "sessionId" to value.sessionId,"runState" to value.runState.name,"flow" to value.currentFlow,
            "step" to value.currentStep,"round" to value.round,"totalRounds" to value.totalRounds,
            "completedRounds" to value.completedRounds,"startedAt" to value.startedAt,
            "lastConfirmedAt" to value.lastConfirmedAt,"nextStep" to value.nextStep,
            "pauseReason" to value.pauseReason,"pauseCount" to value.pauseCount,"runtimeMs" to value.runtimeMs,
            "energy" to value.energy.fields(),"cleanliness" to value.cleanliness.fields(),"mood" to value.mood.fields())))
    }
    suspend fun run():String=coroutineScope {
        snapshot=snapshot.copy(runState=SessionRunState.RUNNING,startedAt=System.currentTimeMillis(),
            startedElapsedMs=SystemClock.elapsedRealtime(),nextStep="读取健康状态")
        publish()
        val ticker=launch {while(isActive) {delay(1000);publish()}}
        flow.capture.facts.events.append(EventSource.APP_AUTOMATION,EventKind.SESSION_CONFIG,mapOf(
            "runner" to "PetSessionController","mode" to mode.wire,"rounds" to totalRounds,
            "runsCare" to mode.runsCare,"runsStudy" to mode.runsStudy,"moodPolicy" to "OBSERVATION_ONLY",
            "courseId" to plan.courseId,"workBeforeStudy" to plan.workBeforeStudy,
            "energyThreshold" to plan.energyThreshold,"cleanlinessThreshold" to plan.cleanlinessThreshold))
        try {
            flow.state="INITIAL_NAVIGATION"
            navigation.initialise()
            // A HEALTH_CHECK session is deliberately not a loop over rounds: it reads the panel
            // once, records it and stops. It never reaches care, study, beforeCourse or the
            // readiness precondition, so it cannot spend a coin or a budget minute.
            if(mode==SessionMode.HEALTH_CHECK) {
                flow.round=1;flow.state="CHECK_STATE"
                snapshot=snapshot.copy(round=1,currentFlow="CARE",nextStep="读取体力与清洁")
                val observed=care.readHealth("health-check")
                val record=flow.healthJson(observed)
                snapshot=snapshot.copy(energy=observed.observations.energy,
                    cleanliness=observed.observations.cleanliness,
                    mood=observed.observations.mood,completedRounds=0,
                    nextStep="读数完成（未护理，未开课）")
                File(evidence,"health-check.json").writeText(record.toString())
                flow.emit("HEALTH_CHECK_COMPLETE",result=record.toString())
                completed=0
                flow.state="DONE"
                "HEALTH_CHECK_PASS"
            } else runRounds()
        } catch(t:Throwable) {
            stopReason=t.message ?: t.javaClass.simpleName
            if(t is CancellationException) cancelled=true
            snapshot=snapshot.copy(pauseReason=stopReason,nextStep="停止输入")
            flow.emit("SESSION_STOP",result="STOP",reason=stopReason)
            if(t is CancellationException) throw t
            "STOPPED"
        } finally {
            withContext(NonCancellable) {
                ticker.cancelAndJoin()
                // finish() publishes STOPPED; the terminal lifecycle state is decided below.
                flow.recovery.recovery.finish(stopReason ?: "BATCH_COMPLETE")
                val terminal=when {
                    // A HEALTH_CHECK or CARE session has no rounds to complete, so its success is
                    // "it reached its own end without a stopReason", never a round count.
                    stopReason==null && !mode.runsStudy -> SessionRunState.COMPLETED
                    stopReason==null && completed==totalRounds -> SessionRunState.COMPLETED
                    cancelled -> SessionRunState.STOPPED
                    else -> SessionRunState.FAILED
                }
                snapshot=snapshot.copy(runState=terminal,pauseReason=stopReason,
                    completedRounds=completed,
                    nextStep=if(terminal==SessionRunState.COMPLETED) "批次完成" else "已安全停止")
                publish()
                writeRunRecord(terminal)
            }
        }
    }
    /**
     * The 护理/学习 batch loop. Reached by every mode except HEALTH_CHECK.
     *
     * The stage boundaries are the mode's, not the loop's: a CARE session reads and recovers but
     * never calls beforeCourse()/study, so it never touches the shared daily budget and never
     * requires the ≥90 precondition that only a lesson needs.
     */
    private suspend fun runRounds():String=coroutineScope {
            repeat(totalRounds) {index ->
                flow.round=index+1;flow.state="CHECK_STATE"
                snapshot=snapshot.copy(round=flow.round,currentFlow="CARE",nextStep="读取体力与清洁")
                val before=care.readHealth("before")
                val beforeRecord=flow.healthJson(before)
                if(mode.runsCare) {
                    care.recover(before)
                    flow.state="VERIFY_READY"
                }
                val ready=if(mode.runsCare) care.readHealth("ready") else before
                val readyRecord=flow.healthJson(ready)
                if(mode.runsStudy) {
                    PetStudyHealthPolicy.requireReady(ready.observations,System.currentTimeMillis(),plan.energyThreshold,plan.cleanlinessThreshold)
                    if(plan.workBeforeStudy && index==0) earnTuitionIfShort()
                    beforeCourse()
                }
                if(!mode.runsStudy) {
                    // CARE mode stops right here: the reading is recorded, nothing else is spent.
                    snapshot=snapshot.copy(energy=ready.observations.energy,
                        cleanliness=ready.observations.cleanliness,
                        mood=ready.observations.mood,completedRounds=0,
                        nextStep="护理完成（未开课）")
                    File(evidence,"care-only.json").writeText(JSONObject()
                        .put("before",beforeRecord).put("ready",readyRecord)
                        .put("care",care.careStatistics).toString())
                    flow.emit("CARE_COMPLETE",result=readyRecord.toString())
                    return@coroutineScope "CARE_PASS"
                }
                snapshot=snapshot.copy(nextStep="选择${plan.courseName}")
                val course=study.start(ready,plan)
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
    }
    /**
     * One work shift before the first lesson, and only when the plan asked for it and the pet
     * cannot pay the tuition. The grant comes from the plan the user saved, never from this
     * service deciding on its own. Anything short of a confirmed completion stops the session:
     * continuing into a lesson with an unknown coin balance is exactly the failure this guards
     * against.
     */
    private suspend fun earnTuitionIfShort() {
        val optionId=plan.workOptionId ?: error("PLAN_WORK_OPTION_MISSING")
        val entry=WorkCatalog().all().firstOrNull {it.id==optionId} ?: error("WORK_OPTION_UNKNOWN:$optionId")
        check(entry.confidence==RewardConfidence.VERIFIED) {"WORK_OPTION_NOT_VERIFIED:$optionId"}
        val coins=readCoins()
        if(!TuitionWorkDecision.needsWork(coins,COURSE_TUITION_COINS)) return
        snapshot=snapshot.copy(currentFlow="WORK",nextStep="打工赚学费：${entry.optionLabel}")
        publish()
        val target=WorkTarget(entry.venue,entry.optionLabel,entry.id)
        work.authorise(WorkStartAuthorisation.grant(entry.venue,entry.id,maxStarts=1,
            maxCoinsSpent=0,maxEnergySpent=entry.energyCost,grantedBy="session-plan",nowMs=System.currentTimeMillis()))
        val started=work.run(target)
        check(started is WorkOutcome.Started) {"WORK_NOT_STARTED:${started::class.simpleName}"}
        val done=work.waitAndVerify(target)
        check(done is WorkOutcome.Completed) {"WORK_NOT_COMPLETED:${done::class.simpleName}"}
        onWorkCompleted(entry.durationMinutes)
        flow.emit("WORK_COMPLETE",result="${entry.optionLabel} 完成")
    }
    /** Coins on the current frame, or null when the balance cannot be read. Null means short. */
    private suspend fun readCoins(): Int? {
        val frame=flow.frame()
        return try {
            reader.read(frame).firstNotNullOfOrNull {line ->
                COIN_BALANCE.find(line.text)?.groupValues?.get(1)?.toIntOrNull()
            }
        } finally {frame.recycle()}
    }
    /** One consolidated run record per session, written once at termination. */
    private fun writeRunRecord(terminal:SessionRunState) {
        val value=snapshot.at(System.currentTimeMillis(),SystemClock.elapsedRealtime())
        evidence.mkdirs()
        val record=mapOf<String,Any?>(
            "sessionId" to value.sessionId,"terminalState" to terminal.name,
            "runner" to "PetSessionController","rounds" to totalRounds,
            "completedRounds" to completed,"pauseCount" to value.pauseCount,
            "startedAt" to value.startedAt,"runtimeMs" to value.runtimeMs,
            "energy" to value.energy.fields(),"cleanliness" to value.cleanliness.fields(),
            "mood" to value.mood.fields(),"stopReason" to stopReason,
            "moodPolicy" to "OBSERVATION_ONLY",
            "care" to mapOf("feed_count" to care.careStatistics.optInt("feed_count"),
                "clean_count" to care.careStatistics.optInt("clean_count"),
                "feed_duration_ms" to care.careStatistics.optLong("feed_duration_ms"),
                "clean_duration_ms" to care.careStatistics.optLong("clean_duration_ms")),
            "encouragementSent" to ledger.encouragementTotal,
            "courseStarts" to ledger.startedCount,
            "requestedRounds" to ledger.attemptCount)
        File(evidence,"run-record.json").writeText(EventJson.encode(record))
    }
}
