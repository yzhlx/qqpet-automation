package com.ynk.virtualdisplay.automation

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.*
import org.json.JSONArray
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

/**
 * The coins care will plausibly demand before a lesson. Cookie +10 energy for 5 coins, soap +10
 * cleanliness for 2, measured 2026-10-12 (PetExchangeFlow.unitPrice). The estimate only decides
 * whether a work shift must run BEFORE care; each buy itself is verified against the page's own
 * numbers, so an over-estimate costs one extra shift at most and an under-estimate is caught by
 * the in-flow verified buys (a refused pay is impossible: after one +68-coin shift the pet can
 * always afford the rest of the batch's consumables).
 */
object CareFundsEstimate {
    fun careCoins(plan: SessionPlan, energy: Int, cleanliness: Int): Int {
        val energyGap=((plan.energyThreshold-energy).coerceAtLeast(0)+9)/10
        val cleanGap=((plan.cleanlinessThreshold-cleanliness).coerceAtLeast(0)+9)/10
        return energyGap*COOKIE_COINS+cleanGap*SOAP_COINS
    }
    const val COOKIE_COINS = 5
    const val SOAP_COINS = 2
}

/** Interpolated MOVE points per fling; QQ scroll strips ignore two-point drags (device-measured). */
private const val FLING_STEPS = 6

/** Finite main-source controller. No dependency on PetExperimentalSession or test APIs. */
class PetSessionController(context:Context,capture:HeadlessDisplayCapture,input:PetInputController,
    private val evidence:File,private val guard:()->Unit,
    logger:(String,Double?,String,String,String?)->Unit,
    private val totalRounds:Int,
    private val mode:SessionMode=SessionMode.FULL,
    private val plan:SessionPlan=SessionPlan(),
    private val beforeCourse:(Int)->Unit={},
    private val onRoundCompleted:(Int)->Unit={},
    private val beforeWork:(Int)->Unit={},
    private val onWorkCompleted:(Int)->Unit={},
    private val catalog:CourseCatalog?=null,
    private val protectedTuitionCoins:Int=0,
    private val onCourseStarted:(Int)->Unit={},
    private val onWorkStarted:(Int)->Unit={},
    private val beforeCare:(Int)->Unit={},
    private val onCareCompleted:(Int)->Unit={},
    private val planV2:PlanV2?=null,
    private val planRequest:RunRequest?=null,
    private val planRunStore:RunStore?=null,
    private val planAttemptBudget:PlanAttemptBudget?=null) {
    private var activePlanContext:StepExecutionContext?=null
    private var planCareConsumed=false
    private var planCourseCost:CourseCostReading?=null
    private var planSkipWork=false
    var planRunSnapshot:RunSnapshot?=null;private set
    private val ledger=PetRoundLedger()
    private val reader=MlKitCourseNameReader()
    private val learningObserver=LearningPanelObserver(reader)
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
        },reconcile={cp,page -> cp.phase=="WAIT_RESULT" && cp.trustedPage=="LEARNING" && page=="result" && ledger.canReconcileResult(cp.round)},
        energyThreshold=plan.energyThreshold,cleanlinessThreshold=plan.cleanlinessThreshold,
        learningEvidence={learningObserver.confirms(it)})
    private val workPageReader=DeviceWorkPageReader(flow,reader)
    private val navigation=PetNavigation(flow,workPageReader,roomExitReader=reader)
    // Consumable/exchange budgets must scale with the batch: a long session eats/drags every
    // round, and a failed consumable gesture retains its allowance as possibly-spent before the
    // restock buys a verified replacement (measured: ~2 budget slots per wasted feed). One cookie
    // ≈one feed round, one soap ≈ one clean round, plus the single-round spares measured on
    // device. Coins: cookie=5, soap=2, plus a drift margin.
    //
    // The counts are now derived from the MEASURED gap to the plan's threshold (CareAllowance)
    // instead of being fixed: the old caps (6 cookies / 4 soaps) were measured on a pet that
    // already sat at 84-91, so a pet that starts tired or dirty could never reach the threshold
    // with them. The soap budget also carries the one soap a work preparation may need before
    // the lesson's own cleaning starts.
    private val cookieBudget=CareAllowance.cookieBudget(plan.energyThreshold,totalRounds)
    private val soapBudget=CareAllowance.soapBudget(plan.cleanlinessThreshold,totalRounds)
    /** Every lesson still unpaid is protected from care payments. */
    private var tuitionReserveCoins=protectedTuitionCoins
    private val care=PetCareFlow(flow,navigation,
        budget=CareConsumableBudget(cookies=cookieBudget,soaps=soapBudget),
        exchangeBudget=ExchangeBudget(maxPurchases=cookieBudget+soapBudget,
            maxCoins=6*cookieBudget+3*soapBudget),
        beforeConsumingInput={
            if(planV2!=null) {
                checkNotNull(activePlanContext).beforeConsumingInput(1,0)
                planCareConsumed=true
            } else beforeCare(flow.round)
        },
        freshPayGuard={item,quantity,unit,total,wallet ->
            requireCarePurchaseAffordable(item,quantity,unit,total,wallet)}) {health ->
        snapshot=snapshot.copy(energy=health.energy,cleanliness=health.cleanliness,mood=health.mood);publish()
    }
    private val study=PetStudyFlow(flow,navigation,ledger,CourseSelector(flow,navigation,reader),learningTextReader=reader)
    private val work by lazy {
        PetWorkFlow(flow.asWorkSink(),
            PetWorkNavigation(flow.asWorkSink(),workPageReader,
                tap={point,settle -> flow.safety();flow.input.tap(point.x,point.y);kotlinx.coroutines.delay(settle)},
                swipe={x1,y1,x2,y2,ms -> flow.safety();
                    // Measured on device (CourseScanFlow doc): QQ's scrollable strips ignore a
                    // two-point drag (DOWN + one MOVE + UP) but respond to an interpolated fling.
                    // Every intermediate point must stay inside the strip's band.
                    val pts=ArrayList<android.graphics.PointF>()
                    for(i in 0..FLING_STEPS) {
                        val t=i.toFloat()/FLING_STEPS
                        pts+=android.graphics.PointF(x1+(x2-x1)*t,y1+(y2-y1)*t)
                    }
                    flow.input.drag(pts,ms/FLING_STEPS)}),
            workResources,
            beforeStart={minutes ->
                if(planV2!=null) checkNotNull(activePlanContext).beforeConsumingInput(1,minutes)
                else beforeWork(minutes)
            })
    }
    private val workResources by lazy {
        DeviceWorkResourceReader(care,workPageReader::snapshotResources)
    }
    var completed=0;private set
    var stopReason:String?=null;private set
    private var cancelled=false
    init {require(totalRounds in 1..10)}

    /** Production gate receives the wallet read from this payment's verified dialog frame. */
    private fun requireCarePurchaseAffordable(item:Consumable,quantity:Int,unit:Int,total:Int,
        observedWallet:ObservedValue<Int>) {
        val wallet=CarePaymentPolicy.requireAffordable(observedWallet,quantity,unit,total,
            tuitionReserveCoins,System.currentTimeMillis())
        flow.emit("care-purchase-affordable",
            result="item=$item qty=$quantity unit=$unit total=$total wallet=$wallet " +
                "tuitionReserve=$tuitionReserveCoins")
    }
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
            } else if(mode==SessionMode.WORK) runWorkOnly()
            else if(planV2!=null) runPlanSteps()
            else runRounds()
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
                    planV2!=null && planRunSnapshot?.state==RunState.COMPLETED -> SessionRunState.COMPLETED
                    planV2!=null && cancelled -> SessionRunState.STOPPED
                    planV2!=null -> SessionRunState.FAILED
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
     * 打工 as its OWN action: exactly one shift of the plan's option, no course and no care.
     *
     * Differences from the funding shift inside 托管, all deliberate:
     *  - no course is selected, no tuition is read, and the course catalog is never consulted, so a
     *    pet whose course list changed can still work;
     *  - no care happens: the job's own 体力/清洁 cost is checked by [PetWorkFlow] against the current
     *    readings, and a pet that cannot pay is stopped with a readable reason instead of being
     *    cleaned first (the user can run 护理 on its own and then retry);
     *  - the shared daily budget is charged for the shift's own minutes only.
     */
    private suspend fun runWorkOnly():String {
        val optionId=plan.workOptionId ?: error("PLAN_WORK_OPTION_MISSING")
        val entry=WorkCatalog().all().firstOrNull {it.id==optionId} ?: error("WORK_OPTION_UNKNOWN:$optionId")
        check(entry.confidence==RewardConfidence.VERIFIED) {"WORK_OPTION_NOT_VERIFIED:$optionId"}
        flow.round=1
        flow.state="CHECK_STATE"
        snapshot=snapshot.copy(round=1,currentFlow="WORK",nextStep="读取体力与清洁")
        // A read-only observation, not a gate: the shift's own cost is what authorises the start, and
        // it is checked inside the flow on the frame that carries the 去打工 button.
        care.readHealth("work-before")
        flow.state="WORK"
        snapshot=snapshot.copy(currentFlow="WORK",nextStep="前往职业小镇：")
        // Room -> outdoor is the bottom 「出门」 world icon, measured at ~(360,1255) on the 720x1600
        // room (2026-10-21 screenshot pre-work2.png). The old (360,1390) landed below the icon and the
        // page stayed on the room, failing PAGE_MISMATCH on the outdoor probe.
        navigation.enterOutdoor("work-only-outdoor")
        val target=WorkTarget(entry.venue,entry.optionLabel,entry.id)
        work.authorise(WorkStartAuthorisation.grant(entry.venue,entry.id,maxStarts=1,
            maxCoinsSpent=0,maxEnergySpent=entry.energyCost,grantedBy="work-shortcut",
            nowMs=System.currentTimeMillis()))
        val started=work.run(target)
        check(started is WorkOutcome.Started) {"WORK_NOT_STARTED:${started::class.simpleName}"}
        onWorkStarted(started.durationMinutes)
        val done=work.waitAndVerify(target)
        check(done is WorkOutcome.Completed) {"WORK_NOT_COMPLETED:${done::class.simpleName}"}
        // The completion bookkeeping mirrors the funding shift in 托管 (see onWorkCompleted there):
        // without it the reserved attempt survives as UNCONFIRMED_RETAINED and the pending gate then
        // refuses EVERY later session (defect D1, proven by device run d5bf86e8 on 2026-10-24).
        onWorkCompleted(started.durationMinutes)
        completed=0
        snapshot=snapshot.copy(completedRounds=0,nextStep="打工完成（未护理，未开课）")
        File(evidence,"work-only.json").writeText(JSONObject()
            .put("option",entry.id).put("minutes",started.durationMinutes)
            .put("energyCost",entry.energyCost).put("cleanlinessCost",entry.cleanlinessCost)
            .put("records",EventJson.encode(done.records.map {it.fields()})).toString())
        flow.emit("WORK_COMPLETE",result=EventJson.encode(mapOf(
            "mode" to "work-only","option" to entry.id,"minutes" to started.durationMinutes,
            "records" to done.records.map {it.fields()})))
        check(workPageReader.dismissCompletion()) {"WORK_COMPLETION_DISMISS_UNCONFIRMED"}
        flow.verifySettled(listOf(flow.outdoor),"work-completion-outdoor")
        // The shift is DONE and its award is confirmed: the work itself succeeded. Returning to the
        // room is only tidying up the pet's location. A back-arrow that lands on the outdoor map and
        // does not reach the room (measured 2026-10-21 21:55: the return tap verified 0.9997 on
        // outdoor, not room, and RETURN_HOME_UNRECOVERED ended the whole session as STOPPED) must NOT
        // turn a completed, paid shift into a failure. Leave it best-effort and report WORK_PASS.
        try {
            navigation.returnHomeFromMap("work")
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            flow.emit("work-return-home-best-effort", result = "SKIP",
                reason = "WORK_SUCCEEDED_LEFT_AT_OUTDOOR:${t.message}")
        }
        return "WORK_PASS"
    }

    /**
     * The plan (combination) run: the selected steps in their fixed WORK -> CARE -> STUDY order.
     *
     * Every step performs the SAME real action as its standalone entry, with the same resource
     * gates. A step that fails, is unconfirmed or is stopped leaves the LATER steps unexecuted,
     * because the plan is a sequence and a broken prefix cannot authorise the rest.
     */
    private suspend fun runPlanSteps():String {
        val selected=checkNotNull(planV2)
        val store=checkNotNull(planRunStore) {"PLAN_RUN_STORE_REQUIRED"}
        val request=checkNotNull(planRequest) {"PLAN_RUN_REQUEST_REQUIRED"}
        val ownerJob=currentCoroutineContext()[Job]
        val authority={ ownerJob?.ensureActive(); guard(); Unit }
        val preflight=PlanPreflight(
            courses=CourseDependencyPort { courseId ->
                val step=selected.steps.filterIsInstance<StudyStep>().single()
                check(step.courseId==courseId)
                val cost=readPlanCourseCost(step)
                planCourseCost=cost
                val timing=CourseTiming.resolve(plan,catalog,System.currentTimeMillis())
                StudyPreflight.Ok(courseId,plan.courseName,timing.durationMinutes,cost.tuitionCoins)
            },
            resources=ResourcePreflightPort {
                val studyStep=selected.steps.filterIsInstance<StudyStep>().singleOrNull()
                tuitionReserveCoins=if(studyStep==null) 0 else CarePaymentPolicy.unpaidTuition(
                    checkNotNull(planCourseCost).tuitionCoins,studyStep.rounds)
                val conditional=selected.steps.filterIsInstance<WorkStep>().singleOrNull()
                    ?.trigger==WorkTrigger.IF_FUNDS_SHORT
                if(conditional) {
                    planSkipWork=checkNotNull(planCourseCost).balanceCoins>=tuitionReserveCoins
                    val careStep=selected.steps.filterIsInstance<CareStep>().singleOrNull()
                    if(careStep!=null) {
                        val health=care.readHealth("plan-funding-health")
                        val job=WorkCatalog().all().single {it.id==selected.steps.filterIsInstance<WorkStep>().single().workOptionId}
                        if(!PlanDevicePreflight.careNeedsNoItemsAfterWork(health.digitEnergy,
                            health.digitCleanliness,careStep,!planSkipWork,job.energyCost,job.cleanlinessCost)) {
                            return@ResourcePreflightPort ResourcePreflight.NeedsObservation(
                                listOf("CARE_SUFFIX_EXACT_INVENTORY_AND_COST_REQUIRED"))
                        }
                    }
                }
                val workStep=selected.steps.filterIsInstance<WorkStep>().singleOrNull()
                if(studyStep!=null && workStep==null && checkNotNull(planCourseCost).balanceCoins<tuitionReserveCoins) {
                    return@ResourcePreflightPort ResourcePreflight.Insufficient("COURSE_SUFFIX_COINS_INSUFFICIENT")
                }
                val workMinutes=if(workStep==null || planSkipWork) 0 else WorkCatalog().all()
                    .single {it.id==workStep.workOptionId}.durationMinutes
                val studyMinutes=if(studyStep==null) 0 else Math.multiplyExact(studyStep.rounds,
                    CourseTiming.resolve(plan,catalog,System.currentTimeMillis()).durationMinutes)
                if(!checkNotNull(planAttemptBudget).canAfford(Math.addExact(workMinutes,studyMinutes),selected.dailyBudgetMinutes)) {
                    return@ResourcePreflightPort ResourcePreflight.Insufficient("DAILY_BUDGET_LIMIT")
                }
                ResourcePreflight.Ready("device-preflight-${System.currentTimeMillis()}",planSkipWork,false)
            })
        val execution=PlanDeviceExecution(store,checkNotNull(planAttemptBudget),preflight,
            work={step,ctx -> activePlanContext=ctx;runPlanWork(step)},
            care={step,ctx -> activePlanContext=ctx;runPlanCare(step)},
            study={step,ctx -> activePlanContext=ctx;runPlanStudy(step)},checkAuthority=authority)
        try {
            val result=execution.run(request,flow.capture.facts.events.sessionId)
            planRunSnapshot=result
            completed=result.counts.studyRoundsCompleted
            stopReason=result.stopReason
            snapshot=snapshot.copy(completedRounds=completed,nextStep=if(result.state==RunState.COMPLETED) "方案完成" else "方案停止")
            File(evidence,"plan-run.json").writeText(PlanRunJsonCodec.encode(StoredRun(result,
                store.attempts(result.runId),emptyMap())))
            return if(result.state==RunState.COMPLETED) "PLAN_PASS" else "STOPPED"
        } finally {
            activePlanContext=null
            planRunSnapshot=store.load(flow.capture.facts.events.sessionId)
            completed=planRunSnapshot?.counts?.studyRoundsCompleted ?: completed
        }
    }

    /**
     * One plan WORK step. ALWAYS runs the shift; IF_FUNDS_SHORT only runs it when the pet cannot
     * pay the tuition of the lessons that follow, and a sufficient balance is a legal skip.
     */
    private suspend fun runPlanWork(step:WorkStep):StepOutcome {
        flow.round=1
        val entry=WorkCatalog().all().firstOrNull {it.id==step.workOptionId} ?: error("WORK_OPTION_UNKNOWN:${step.workOptionId}")
        check(entry.confidence==RewardConfidence.VERIFIED) {"WORK_OPTION_NOT_VERIFIED:${step.workOptionId}"}
        flow.state="CHECK_STATE"
        snapshot=snapshot.copy(currentFlow="WORK",nextStep="打工：${entry.optionLabel}")
        if(step.trigger==WorkTrigger.IF_FUNDS_SHORT && planSkipWork) {
            flow.emit("plan-work-skipped",result="FUNDS_SUFFICIENT:tuitionReserve=$tuitionReserveCoins")
            return StepOutcome.Skipped(SkipReason.FUNDS_SUFFICIENT)
        }
        care.readHealth("work-before")
        flow.state="WORK"
        navigation.enterOutdoor("plan-work-outdoor")
        val target=WorkTarget(entry.venue,entry.optionLabel,entry.id)
        work.authorise(WorkStartAuthorisation.grant(entry.venue,entry.id,maxStarts=1,
            maxCoinsSpent=0,maxEnergySpent=entry.energyCost,grantedBy="plan",
            nowMs=System.currentTimeMillis()))
        val started=work.run(target)
        check(started is WorkOutcome.Started) {"WORK_NOT_STARTED:${started::class.simpleName}"}
        checkNotNull(activePlanContext).startConfirmed(1)
        val done=work.waitAndVerify(target)
        check(done is WorkOutcome.Completed) {"WORK_NOT_COMPLETED:${done::class.simpleName}"}
        checkNotNull(activePlanContext).completed(1,started.durationMinutes)
        flow.emit("WORK_COMPLETE",result=EventJson.encode(mapOf("option" to entry.id,"minutes" to started.durationMinutes,
            "records" to done.records.map {it.fields()})))
        check(workPageReader.dismissCompletion()) {"WORK_COMPLETION_DISMISS_UNCONFIRMED"}
        flow.verifySettled(listOf(flow.outdoor),"work-completion-outdoor")
        navigation.returnHomeFromMap("work")
        return StepOutcome.Completed()
    }

    /**
     * One plan CARE step: read the health, repair it to the step's own thresholds and confirm.
     *
     * The tuition of every lesson that still has to be paid is held back from the care purchases,
     * because a cookie must never eat the money the lessons need.
     */
    private suspend fun runPlanCare(step:CareStep):StepOutcome {
        flow.round=1
        planCareConsumed=false
        flow.state="CHECK_STATE"
        snapshot=snapshot.copy(currentFlow="CARE",nextStep="护理到 ${step.energyThreshold}/${step.cleanlinessThreshold}")
        val before=care.readHealth("plan-care-before")
        if(before.digitEnergy?.let {it>=step.energyThreshold}==true &&
            before.digitCleanliness?.let {it>=step.cleanlinessThreshold}==true) {
            return StepOutcome.Skipped(SkipReason.ALREADY_SATISFIED)
        }
        care.recover(before,step.energyThreshold,step.cleanlinessThreshold)
        flow.state="VERIFY_READY"
        val ready=care.readHealth("plan-care-ready")
        check(ready.digitEnergy?.let {it>=step.energyThreshold}==true &&
            ready.digitCleanliness?.let {it>=step.cleanlinessThreshold}==true) {"CARE_COMPLETION_UNCONFIRMED"}
        if(planCareConsumed) checkNotNull(activePlanContext).completed(1,0)
        File(evidence,"plan-care.json").writeText(JSONObject()
            .put("thresholds",JSONObject().put("energy",step.energyThreshold).put("cleanliness",step.cleanlinessThreshold))
            .put("before",flow.healthJson(before)).put("ready",flow.healthJson(ready))
            .put("care",care.careStatistics).put("tuitionReserve",tuitionReserveCoins).toString())
        return if(planCareConsumed) StepOutcome.Completed() else StepOutcome.Skipped(SkipReason.ALREADY_SATISFIED)
    }

    /**
     * One plan STUDY step: the plan's own course and rounds, each lesson with its own budget
     * reservation and its own completion record. Health is observed, never a gate.
     */
    private suspend fun runPlanStudy(step:StudyStep):StepOutcome {
        val derived=plan.copy(courseId=step.courseId,rounds=step.rounds)
        var lessons=0
        repeat(step.rounds) {index ->
            flow.round=index+1
            flow.state="SELECT_COURSE"
            snapshot=snapshot.copy(currentFlow="STUDY",nextStep="第 ${index+1}/${step.rounds} 节 ${derived.courseName}")
            val timing=CourseTiming.resolve(derived,catalog,System.currentTimeMillis())
            val ctx=checkNotNull(activePlanContext)
            val course=study.start(null,derived,timing,beforeStart={minutes ->
                ctx.beforeConsumingInput(index+1,minutes)
            },ensureTuition={
                val cost=readCourseCost()
                check(cost.tuitionCoins==checkNotNull(planCourseCost).tuitionCoins) {"COURSE_TUITION_CHANGED"}
                check(cost.balanceCoins>=cost.tuitionCoins) {"COURSE_COINS_INSUFFICIENT"}
            })
            ctx.startConfirmed(index+1)
            val started=course.getLong("courseStartElapsedRealtime")
            study.highFiveWindow(started,course)
            val result=study.waitResult(started,course.getInt("durationMinutes"))
            File(evidence,"plan-round-${flow.round}-completion.json").writeText(JSONObject()
                .put("round",flow.round).put("course",course).put("result",result).toString())
            ctx.completed(index+1,course.getInt("durationMinutes"))
            check(ledger.complete(flow.round)) {"DUPLICATE_ROUND_COMPLETION"}
            lessons++
            completed=ledger.completedCount
            flow.emit("ROUND_RESULT_CONFIRMED",result="round=${flow.round} completed=$completed")
            navigation.returnHomeFromResult("result")
        }
        snapshot=snapshot.copy(completedRounds=lessons)
        return StepOutcome.Completed()
    }

    /** The plan's own course cost, read from the course detail page of [step]'s course. */
    private suspend fun readPlanCourseCost(step:StudyStep):CourseCostReading {
        val derived=plan.copy(courseId=step.courseId,rounds=step.rounds)
        navigation.enterOutdoor("plan-course-cost-outdoor")
        val selected=CourseSelector(flow,navigation,reader).select(derived)
        CourseTiming.resolve(derived,catalog,System.currentTimeMillis())
            .confirmDetail(selected.name,selected.durationMinutes ?: error("COURSE_DURATION_UNREAD"))
        val cost=readCourseCost()
        returnHomeFromCourse("plan-course-cost")
        return cost
    }

    /**
     * The 护理/学习 batch loop. Reached by every mode except HEALTH_CHECK and WORK.
     *
     * The stage boundaries are the mode's, not the loop's: a CARE session reads and recovers but
     * never calls beforeCourse()/study, so it never touches the shared daily budget and never
     * requires the ≥90 precondition that only a lesson needs.
     */
    private suspend fun runRounds():String=coroutineScope {
            repeat(totalRounds) {index ->
                flow.round=index+1;flow.state="CHECK_STATE"
                snapshot=snapshot.copy(round=flow.round,currentFlow="CARE",nextStep="读取体力与清洁")
                val timing=if(mode.runsStudy) CourseTiming.resolve(plan,catalog,System.currentTimeMillis()) else null
                val before=if(mode.runsCare) care.readHealth("before") else null
                val beforeRecord=before?.let(flow::healthJson) ?: JSONObject().put("status","NOT_REQUESTED")
                // Work-before-care: care buys consumables, so a pet too poor to restock used to die
                // in EXCHANGE_PAY_UNCONFIRMED before the tuition work shift could ever run (it sat
                // after care, inside study.start). When the plan asks for a work shift, run it
                // BEFORE care whenever the coins cannot cover tuition plus the care restock the
                // thresholds imply. Cookie +10 energy for 5 coins, soap +10 cleanliness for 2,
                // measured 2026-10-12; the estimate only decides whether work is needed, the buys
                // themselves stay fully verified against what the pages actually show.
                var beforeAfterWork: PetCareReading? = null
                // The funding shift belongs to 托管 only. A standalone 学习 session never works: if the
                // pet cannot pay the tuition it stops and says so, because silently spending ten
                // minutes of the daily budget to earn coins is exactly the "not only the chosen
                // action" behaviour the standalone entries promise not to do.
                if(mode==SessionMode.FULL && plan.workBeforeStudy && index==0) {
                    beforeAfterWork = earnTuitionAndCareFundsBeforeCare(checkNotNull(before))
                } else if(mode.runsCare && mode.runsStudy) {
                    beforeAfterWork = observeUnpaidTuitionBeforeCare()
                }
                // A 10-minute shift makes the pre-work health reading stale (PetCareReading reads
                // through the 60s freshness gate), so care must start from the post-work reading.
                val careStart = beforeAfterWork ?: before
                if(mode.runsCare) {
                    care.recover(checkNotNull(careStart),plan.energyThreshold,plan.cleanlinessThreshold)
                    flow.state="VERIFY_READY"
                }
                var ready=if(mode.runsCare) care.readHealth("ready") else null
                if(mode.runsCare) onCareCompleted(flow.round)
                var readyRecord=ready?.let(flow::healthJson) ?: JSONObject().put("status","NOT_REQUESTED")
                // User rule (2026-10-21): a lesson is gated by course identity, the course's real
                // tuition and the daily minutes - NOT by 体力/清洁. Care may lift the pet toward the
                // plan thresholds, but a lesson the pet can pay for must not be refused because the
                // reading sits below a care target, went stale during navigation, or could not be
                // re-read at all. The reading stays recorded for the ledger/evidence only.
                if(mode.runsStudy) {
                    flow.emit("study-health-observation", result=if(ready==null) "NOT_REQUESTED" else
                        "energy=${ready.observations.energy.fields()["status"]} " +
                            "cleanliness=${ready.observations.cleanliness.fields()["status"]}")
                }
                if(!mode.runsStudy) {
                    val careReady=checkNotNull(ready)
                    snapshot=snapshot.copy(energy=careReady.observations.energy,
                        cleanliness=careReady.observations.cleanliness,
                        mood=careReady.observations.mood,completedRounds=0,
                        nextStep="护理完成（未开课）")
                    File(evidence,"care-only.json").writeText(JSONObject()
                        .put("before",beforeRecord).put("ready",readyRecord)
                        .put("care",care.careStatistics).toString())
                    flow.emit("CARE_COMPLETE",result=readyRecord.toString())
                    return@coroutineScope "CARE_PASS"
                }
                snapshot=snapshot.copy(nextStep="选择${plan.courseName}")
                val course=study.start(ready,plan,timing,beforeStart=beforeCourse,
                    ensureTuition={
                        if(earnTuitionIfShort(allowWork=mode==SessionMode.FULL && plan.workBeforeStudy && index==0)) {
                            val afterWork=care.readHealth("after-work")
                            care.recover(afterWork,plan.energyThreshold,plan.cleanlinessThreshold)
                            ready=care.readHealth("ready-after-work")
                            readyRecord=flow.healthJson(ready)
                            navigation.enterOutdoor("after-work-outdoor")
                            val selected=CourseSelector(flow,navigation,reader).select(plan)
                            timing!!.confirmDetail(selected.name,selected.durationMinutes ?: error("COURSE_DURATION_UNREAD"))
                            val cost=readCourseCost()
                            check(cost.balanceCoins>=cost.tuitionCoins) {"WORK_TUITION_STILL_SHORT"}
                        }
                    })
                onCourseStarted(flow.round)
                val started=course.getLong("courseStartElapsedRealtime")
                snapshot=snapshot.copy(currentFlow="STUDY",nextStep="开课约30秒后鼓励10次")
                study.highFiveWindow(started,course)
                snapshot=snapshot.copy(nextStep="等待成绩")
                val result=study.waitResult(started,course.getInt("durationMinutes"))
                flow.state="READ_POST_STATE"
                // Preserve confirmed completion before navigation or any optional health observation.
                File(evidence,"round-${flow.round}-completion.json").writeText(JSONObject()
                    .put("round",flow.round).put("course",course).put("result",result).toString())
                check(ledger.complete(flow.round)) {"DUPLICATE_ROUND_COMPLETION"}
                completed=ledger.completedCount;onRoundCompleted(flow.round)
                snapshot=snapshot.copy(completedRounds=completed,nextStep="处理成绩及升级弹窗")
                publish()
                flow.emit("ROUND_RESULT_CONFIRMED",result="round=${flow.round} completed=$completed")
                navigation.returnHomeFromResult("result")
                val afterRecord:JSONObject = if(!mode.runsCare) JSONObject().put("status","NOT_REQUESTED") else try {
                    flow.healthJson(care.readHealth("after"))
                } catch(t:Throwable) {
                    if(t is CancellationException) throw t
                    flow.emit("after-health-unread-kept-completion",
                        result="round=${flow.round} reason=${t.message}",reason=null)
                    JSONObject().put("status","UNREAD").put("reason",t.message ?: "AFTER_HEALTH_UNREAD")
                }
                snapshot=snapshot.copy(completedRounds=completed,nextStep=if(completed<totalRounds) "下一节开课前检查" else "批次完成")
                flow.state="NEXT_ROUND"
                val record=JSONObject().put("round",flow.round).put("before",beforeRecord)
                    .put("ready",readyRecord).put("care",care.careStatistics).put("course",course)
                    .put("result",result).put("after",afterRecord).put("appEncouragementSent",ledger.encouragementCount(flow.round))
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
    /**
     * The pre-care check: walks to the course list, reads tuition and balance, and if the coins
     * cannot cover both the tuition and the consumables the thresholds will demand, runs the
     * plan's one work shift right there. Returns the health reading that care must start from:
     * a fresh room-panel reading after every return path. [before] is only safe for the care-fund
     * estimate — prework navigation alone can exceed the 60s freshness gate (session
     * 1792513340455), so no-work paths re-read as "prework-fresh" after returnHome(), and a
     * completed shift re-reads as "after-work". Every path returns to the ROOM page, so the
     * caller's care can open the health panel from a known page. Fails closed on every unread
     * page, exactly like [earnTuitionIfShort].
     */
    private suspend fun observeUnpaidTuitionBeforeCare():PetCareReading {
        flow.state="TUITION_PREFLIGHT"
        navigation.enterOutdoor("care-tuition-outdoor")
        val selected=CourseSelector(flow,navigation,reader).select(plan)
        CourseTiming.resolve(plan,catalog,System.currentTimeMillis())
            .confirmDetail(selected.name,selected.durationMinutes ?: error("COURSE_DURATION_UNREAD"))
        val cost=readCourseCost()
        tuitionReserveCoins=CarePaymentPolicy.unpaidTuition(cost.tuitionCoins,totalRounds-flow.round+1)
        check(cost.balanceCoins>=tuitionReserveCoins) { "COURSE_COINS_INSUFFICIENT" }
        returnHomeFromCourse("care-tuition")
        return care.readHealth("care-tuition-fresh")
    }

    private suspend fun earnTuitionAndCareFundsBeforeCare(before:PetCareReading):PetCareReading {
        flow.state="WORK_PRECHECK"
        navigation.enterOutdoor("prework-outdoor")
        val selected=CourseSelector(flow,navigation,reader).select(plan)
        CourseTiming.resolve(plan,catalog,System.currentTimeMillis())
            .confirmDetail(selected.name,selected.durationMinutes ?: error("COURSE_DURATION_UNREAD"))
        val cost=readCourseCost()
        // Hold this lesson's real tuition back from any care purchase: buying a cookie must never eat
        // the money the lesson about to start needs (measured economics: cookie 5, soap 2, tuition 66).
        tuitionReserveCoins=CarePaymentPolicy.unpaidTuition(cost.tuitionCoins,totalRounds-flow.round+1)
        val energy=(before.observations.energy as? ObservedValue.Known)?.value ?: error("PREWORK_ENERGY_UNREAD")
        val cleanliness=(before.observations.cleanliness as? ObservedValue.Known)?.value ?: error("PREWORK_CLEANLINESS_UNREAD")
        val careEstimate=CareFundsEstimate.careCoins(plan,energy,cleanliness)
        flow.emit("prework-tuition",result="tuition=${cost.tuitionCoins} coins=${cost.balanceCoins} careEstimate=$careEstimate")
        val returnHome: suspend () -> Unit = {
            navigation.tap(flow.profile.courseListProbe,android.graphics.PointF(80f,160f),flow.academy,"prework-close-course")
            navigation.tap(flow.academy,android.graphics.PointF(80f,160f),flow.outdoor,"prework-leave-academy")
            navigation.returnHomeFromMap("prework")
        }
        // [before] is only safe for the care-fund ESTIMATE. Selecting the course + reading tuition
        // routinely exceeds the 60s health freshness gate (measured 2026-10-21 session
        // 1792513340455: HEALTH_STALE after ~61s of prework navigation with no work shift). Any
        // path that returns to care must therefore re-observe health on the room page.
        val required=tuitionReserveCoins.toLong()+careEstimate
        if(cost.balanceCoins.toLong()>=required) {
            returnHome()
            return care.readHealth("prework-fresh")
        }
        flow.emit("prework-needed",result="short=${required-cost.balanceCoins}")
        val ran=earnTuitionIfShort(allowWork=true,preloadedCost=cost,requiredCoins=required)
        // The decision to work was made here (tuition + care estimate), and earnTuitionIfShort has
        // already confirmed the tuition-only check against the same preloaded reading. A work shift
        // leaves the health stale, so care must start from a fresh reading. Either way the caller
        // expects the ROOM page: the tuition-covered path skips the shift but still walked to the
        // course list, so it must walk back before returning.
        return if(ran) {
            care.readHealth("after-work")
        } else {
            returnHome()
            care.readHealth("prework-fresh")
        }
    }

    private var fundingShiftStarted=false
    private suspend fun earnTuitionIfShort(allowWork:Boolean,preloadedCost:CourseCostReading?=null,
        requiredCoins:Long?=null):Boolean {
        val cost=preloadedCost ?: readCourseCost()
        tuitionReserveCoins=CarePaymentPolicy.unpaidTuition(cost.tuitionCoins,totalRounds-flow.round+1)
        flow.emit("course-tuition",result="tuition=${cost.tuitionCoins} coins=${cost.balanceCoins} reserve=$tuitionReserveCoins")
        if(cost.balanceCoins.toLong()>=(requiredCoins ?: tuitionReserveCoins.toLong())) return false
        check(allowWork) {"COURSE_COINS_INSUFFICIENT"}
        check(!fundingShiftStarted) {"WORK_TUITION_STILL_SHORT_AFTER_SINGLE_SHIFT"}
        val optionId=plan.workOptionId ?: error("PLAN_WORK_OPTION_MISSING")
        val entry=WorkCatalog().all().firstOrNull {it.id==optionId} ?: error("WORK_OPTION_UNKNOWN:$optionId")
        check(entry.confidence==RewardConfidence.VERIFIED) {"WORK_OPTION_NOT_VERIFIED:$optionId"}
        snapshot=snapshot.copy(currentFlow="WORK",nextStep="打工赚学费：${entry.optionLabel}")
        publish()
        // Only the option's own game cost belongs to this preparation, never the plan thresholds.
        preparePetForWork(entry,cost.balanceCoins)
        navigation.enterOutdoor("work-balance-outdoor")
        val selected=CourseSelector(flow,navigation,reader).select(plan)
        CourseTiming.resolve(plan,catalog,System.currentTimeMillis())
            .confirmDetail(selected.name,selected.durationMinutes ?: error("COURSE_DURATION_UNREAD"))
        // Soap may have spent coins: the payout baseline must be read AFTER that purchase.
        val preparedCost=readCourseCost()
        flow.emit("work-prepared-balance",result="coins=${preparedCost.balanceCoins}")
        workResources.setCoursePageBalance(
            ObservedValue.Known(preparedCost.balanceCoins, System.currentTimeMillis(), 1.0,
                "post-preparation-course-consumption-row", ObservationSource.DIGIT_RECOGNITION))
        returnHomeFromCourse("work-prepared")
        care.readHealth("work-prepared-health",flow.room)
        navigation.enterOutdoor("work-enter-outdoor")
        val target=WorkTarget(entry.venue,entry.optionLabel,entry.id)
        fundingShiftStarted=true
        work.authorise(WorkStartAuthorisation.grant(entry.venue,entry.id,maxStarts=1,
            maxCoinsSpent=0,maxEnergySpent=entry.energyCost,grantedBy="session-plan",nowMs=System.currentTimeMillis()))
        val started=work.run(target)
        check(started is WorkOutcome.Started) {"WORK_NOT_STARTED:${started::class.simpleName}"}
        onWorkStarted(started.durationMinutes)
        val done=work.waitAndVerify(target)
        check(done is WorkOutcome.Completed) {"WORK_NOT_COMPLETED:${done::class.simpleName}"}
        onWorkCompleted(started.durationMinutes)
        flow.emit("WORK_COMPLETE",result=EventJson.encode(mapOf("option" to entry.id,"minutes" to started.durationMinutes,
            "records" to done.records.map {it.fields()})))
        check(workPageReader.dismissCompletion()) {"WORK_COMPLETION_DISMISS_UNCONFIRMED"}
        flow.verifySettled(listOf(flow.outdoor),"work-completion-outdoor")
        navigation.returnHomeFromMap("work")
        return true
    }
    /** Satisfies only the game's shift cost; plan care thresholds belong to lessons. */
    private suspend fun preparePetForWork(entry:WorkCatalog.WorkCatalogEntry,balanceCoins:Int) {
        returnHomeFromCourse("work-prep")
        val reading=care.readHealth("work-prep-health",flow.room)
        val have=(reading.observations.cleanliness as? ObservedValue.Known)?.value
            ?: error("WORK_PREP_CLEANLINESS_UNREAD")
        if(WorkPreparation.isReady(have,entry.cleanlinessCost)) return
        val need=WorkPreparation.requiredCleanliness(entry.cleanlinessCost)
        snapshot=snapshot.copy(currentFlow="WORK",nextStep="为打工备清洁，have $have / need $need")
        publish()
        try {
            care.cleanTo(need,reading)
        } catch(t: Throwable) {
            // A timeout with enough funds stays unconfirmed; it does not prove a refused payment.
            if(t.message.orEmpty().startsWith("EXCHANGE_PAY_UNCONFIRMED") &&
                !WorkPreparation.canAffordClean(balanceCoins)) {
                throw IllegalStateException(WorkPreparation.shortfallReason(balanceCoins,need),t)
            }
            throw t
        }
    }
    private suspend fun returnHomeFromCourse(label:String) {
        navigation.tap(flow.profile.courseListProbe,android.graphics.PointF(80f,160f),flow.academy,"$label-close-course")
        navigation.tap(flow.academy,android.graphics.PointF(80f,160f),flow.outdoor,"$label-leave-academy")
        navigation.returnHomeFromMap(label)
    }
    /**
     * Read tuition + balance off the course detail page.
     *
     * One frame is not enough: ML Kit on this page is flaky (measured 2026-10-20: the same
     * 田径运动课 layout raised COURSE_COST_UNREADABLE at 17:56 and read tuition=22 on an
     * otherwise identical 蹦蹦跳跳体能课 frame at 18:17). Re-read on fresh frames, and when
     * every attempt still fails leave the raw lines in the log so the next parser fix has
     * something to work from — same pattern as DeviceWorkPageReader.readRewardRow.
     */
    private suspend fun readCourseCost():CourseCostReading {
        var lastLines:List<String> = emptyList()
        var attempts=0
        while(attempts<COURSE_COST_READ_ATTEMPTS) {
            if(attempts>0) delay(COURSE_COST_RETRY_DELAY_MS)
            val frame=flow.frame(latest=attempts>0)
            try {
                check(flow.score(frame,listOf(flow.profile.courseListProbe))>=flow.profile.matchThreshold) {"COURSE_COST_PAGE_UNCONFIRMED"}
                val lines=reader.read(frame).sortedBy {it.bounds.top}
                lastLines=lines.map {it.text}
                CourseCostReading.parse(lines.joinToString(" ") {it.text})?.let { return it }
            } finally {frame.recycle()}
            attempts++
        }
        flow.emit("course-cost-read-unresolved", result="FAIL",
            reason="COURSE_COST_UNREADABLE|lines="+lastLines.joinToString(";"))
        error("COURSE_COST_UNREADABLE")
    }

    companion object {
        private const val COURSE_COST_READ_ATTEMPTS = 4
        private const val COURSE_COST_RETRY_DELAY_MS = 800L
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
