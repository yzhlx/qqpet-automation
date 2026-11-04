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
    /** STR-09：均衡方案要逐门课读学费，这里按课程 id 存每一门读到的学费、时长与当时的余额。 */
    private val planCourseCosts=mutableMapOf<String,CourseCostReading>()
    private val planCourseMinutes=mutableMapOf<String,Int>()
    private var planCourseBalance:Int?=null
    /** STR-09：本次会话每个属性的学习事实（上了几节、页面上写明加多少）。 */
    private val studyAttributeFacts=mutableMapOf<String,StudyAttributeFact>()
    private var planSkipWork=false
    /** STR-08：本次方案今天还能上几节；预检算好后学习步骤按它开课。 */
    private var planStudyAllowedRounds=0
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
                // Plan steps name their own flow; an unmapped observation inside one must not erase it.
                else -> if(snapshot.currentFlow=="IDLE") "OBSERVATION" else snapshot.currentFlow},lastConfirmedAt=if(score!=null && score>=0.97) System.currentTimeMillis() else snapshot.lastConfirmedAt)
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
    private var sessionRoomCoinsBefore: Int? = null
    private var sessionRoomCoinsAfter: Int? = null
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
    /**
     * The manual pause boundary. Called only where nothing is in flight: no gesture, and no attempt
     * reserved but not yet booked. QQ may have moved while the session was held, so on continue the
     * page is re-identified from a fresh frame before any further input.
     */
    private suspend fun pausePoint(nextStep:String) {
        snapshot=snapshot.copy(pauseBoundariesLeft=(snapshot.pauseBoundariesLeft-1).coerceAtLeast(0))
        val held=ManualPause.hold {
            snapshot=snapshot.copy(runState=SessionRunState.PAUSED,pauseReason=ManualPause.REASON,
                pauseCount=snapshot.pauseCount+1,nextStep="点「继续」后：$nextStep")
            flow.emit("USER_PAUSE_HELD",result=nextStep)
            publish()
        }
        if(!held) return
        snapshot=snapshot.copy(runState=SessionRunState.RUNNING,pauseReason=null,nextStep=nextStep)
        flow.emit("USER_PAUSE_RELEASED",result=nextStep)
        publish()
        navigation.initialise()
    }
    suspend fun run():String=coroutineScope {
        // One boundary before each plan step, and before each lesson of a study step or a round.
        val boundaries=when {
            mode==SessionMode.HEALTH_CHECK || mode==SessionMode.WORK || mode==SessionMode.ADVENTURE -> 0
            planV2!=null -> planV2.steps.sumOf {
                when (it) {
                    is StudyStep -> it.rounds
                    is AdventureStep -> it.rounds
                    is WorkStep -> it.shifts
                    else -> 1
                }
            }
            else -> totalRounds
        }
        snapshot=snapshot.copy(runState=SessionRunState.RUNNING,startedAt=System.currentTimeMillis(),
            startedElapsedMs=SystemClock.elapsedRealtime(),nextStep="读取健康状态",pauseBoundariesLeft=boundaries)
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
            val initialCoins = readRoomCoins("session-initial-coins")
            if (initialCoins is ObservedValue.Known && sessionRoomCoinsBefore == null) {
                sessionRoomCoinsBefore = initialCoins.value
            }
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
            else if(mode==SessionMode.ADVENTURE) runAdventureOnly()
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
                    completedRounds=completed,pauseBoundariesLeft=0,
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
        val candidates = WorkCatalog().candidatesFor(entry.id)
        var startedOutcome: Pair<WorkCatalog.WorkCatalogEntry, WorkOutcome.Started>? = null
        val refusedReasons = mutableListOf<String>()

        for (candidate in candidates) {
            val candidateTarget = WorkTarget(candidate.venue, candidate.optionLabel, candidate.id)
            work.authorise(WorkStartAuthorisation.grant(candidate.venue, candidate.id, maxStarts=1,
                maxCoinsSpent=0, maxEnergySpent=candidate.energyCost, grantedBy="work-shortcut",
                nowMs=System.currentTimeMillis()))
            val outcome = work.run(candidateTarget)
            if (outcome is WorkOutcome.Started) {
                startedOutcome = candidate to outcome
                break
            } else {
                val reason = if (outcome is WorkOutcome.Blocked) outcome.reason else outcome::class.simpleName ?: "REFUSED"
                refusedReasons += "${candidate.optionLabel} ($reason)"
                flow.emit("work-candidate-refused", result = "candidate=${candidate.id} reason=$reason")
                leaveTownIfWorkRefused(outcome)
            }
        }
        val (activeEntry, started) = startedOutcome ?: error("WORK_ALL_CANDIDATES_REFUSED:${refusedReasons.joinToString("; ")}")
        val target = WorkTarget(activeEntry.venue, activeEntry.optionLabel, activeEntry.id)
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
                check(step.courseIds.contains(courseId)) {"PLAN_COURSE_NOT_IN_STEP:$courseId"}
                // STR-09：均衡方案要把每一门轮换的课都读一遍学费与时长，闸门才按真实顺序算。
                planCourseCosts.clear();planCourseMinutes.clear();planCourseBalance=null
                for(id in step.courseIds) {
                    val cost=readPlanCourseCost(id)
                    planCourseCosts[id]=cost
                    planCourseBalance=cost.balanceCoins
                    planCourseMinutes[id]=CourseTiming.resolve(plan.copy(courseId=id),catalog,
                        System.currentTimeMillis()).durationMinutes
                }
                val cost=checkNotNull(planCourseCosts[courseId])
                StudyPreflight.Ok(courseId,plan.copy(courseId=courseId).courseName,
                    checkNotNull(planCourseMinutes[courseId]),cost.tuitionCoins)
            },
            resources=ResourcePreflightPort {
                val studyStep=selected.steps.filterIsInstance<StudyStep>().singleOrNull()
                // STR-08 今日停止线：先算出今天还能上几节，学费预留、币值闸门和分钟预算都用这个数。
                planStudyAllowedRounds=if(studyStep==null) 0 else StudyDailyCapPolicy.allowedRounds(
                    confirmedToday=computeConfirmedStudyRoundsToday(),
                    cap=selected.dailyStudyRoundCap,
                    plannedRounds=studyStep.rounds)
                // STR-09：按真实顺序排出本轮的课；学费与时长都按每节自己那门课算。
                val lessons=if(studyStep==null) emptyList() else StudyRotationPolicy.schedule(studyStep,planStudyAllowedRounds)
                tuitionReserveCoins=if(studyStep==null) 0 else StudyRotationPolicy.tuitionTotal(lessons,planCourseCosts)
                val conditional=selected.steps.filterIsInstance<WorkStep>().singleOrNull()
                    ?.trigger==WorkTrigger.IF_FUNDS_SHORT
                if(conditional) {
                    // 今天已经不再学习时照常打工：学费预留为 0，不能因此把打工也跳掉。
                    planSkipWork=planStudyAllowedRounds>0 &&
                        checkNotNull(planCourseBalance)>=tuitionReserveCoins
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
                if(studyStep!=null && planStudyAllowedRounds>0 && workStep==null &&
                    checkNotNull(planCourseBalance)<tuitionReserveCoins) {
                    return@ResourcePreflightPort ResourcePreflight.Insufficient("COURSE_SUFFIX_COINS_INSUFFICIENT")
                }
                if(studyStep!=null && planStudyAllowedRounds>0) {
                    val spendToday=computeConfirmedNetSpendToday()
                    // STR-09：轮换方案的学费按真实顺序合计，不拿最贵那门乘节数。
                    val decision=CoinSpendPolicy.evaluatePlanned(
                        balanceCoins=checkNotNull(planCourseBalance),
                        plannedSpendCoins=tuitionReserveCoins.toLong(),
                        minCoinFloor=selected.minCoinFloor,
                        maxDailyNetSpend=selected.maxDailyNetSpend,
                        alreadySpentToday=spendToday)
                    if(decision is CoinSpendPolicy.Decision.Rejected) {
                        flow.emit("plan-coin-gate-rejected", result = decision.reason)
                        return@ResourcePreflightPort ResourcePreflight.Insufficient(decision.reason)
                    }
                }
                val workMinutes=if(workStep==null || planSkipWork) 0 else WorkCatalog().all()
                    .single {it.id==workStep.workOptionId}.durationMinutes
                val studyMinutes=if(studyStep==null) 0 else StudyRotationPolicy.minutesTotal(lessons,planCourseMinutes)
                val adventureStep=selected.steps.filterIsInstance<AdventureStep>().singleOrNull()
                val adventureMinutes=if(adventureStep==null) 0 else Math.multiplyExact(adventureStep.rounds, adventureStep.mode.durationMinutes)
                if(!checkNotNull(planAttemptBudget).canAfford(Math.addExact(workMinutes,Math.addExact(studyMinutes,adventureMinutes)),selected.dailyBudgetMinutes)) {
                    return@ResourcePreflightPort ResourcePreflight.Insufficient("DAILY_BUDGET_LIMIT")
                }
                ResourcePreflight.Ready("device-preflight-${System.currentTimeMillis()}",planSkipWork,false)
            })
        val execution=PlanDeviceExecution(store,checkNotNull(planAttemptBudget),preflight,
            signIn={step,ctx -> pausePoint("每日签到");activePlanContext=ctx;runPlanSignIn(step)},
            mailbox={step,ctx -> pausePoint("信箱待领");activePlanContext=ctx;runPlanMailbox(step)},
            luckyBag={step,ctx -> pausePoint("福袋任务");activePlanContext=ctx;runPlanLuckyBag(step)},
            work={step,ctx -> pausePoint("打工");activePlanContext=ctx;runPlanWork(step)},
            care={step,ctx -> pausePoint("护理");activePlanContext=ctx;runPlanCare(step)},
            study={step,ctx -> activePlanContext=ctx;runPlanStudy(step)},
            adventure={step,ctx -> pausePoint("冒险: " + step.mode.label);activePlanContext=ctx;runPlanAdventure(step)},
            checkAuthority=authority)
        try {
            val result=execution.run(request,flow.capture.facts.events.sessionId)
            planRunSnapshot=result
            completed=result.counts.studyRoundsCompleted + result.counts.adventureCompleted
            stopReason=result.stopReason
            snapshot=snapshot.copy(completedRounds=completed,nextStep=if(result.state==RunState.COMPLETED) "方案完成" else "方案停止",
                strategyActions=StrategyExplanation.actionsOf(selected,result))
            File(evidence,"plan-run.json").writeText(PlanRunJsonCodec.encode(StoredRun(result,
                store.attempts(result.runId),emptyMap())))
            return if(result.state==RunState.COMPLETED) "PLAN_PASS" else "STOPPED"
        } finally {
            activePlanContext=null
            planRunSnapshot=store.load(flow.capture.facts.events.sessionId)
            val c = planRunSnapshot?.counts
            completed = if (c != null) c.studyRoundsCompleted + c.adventureCompleted else completed
            // STR-16: every action gets its real outcome, on the normal path and on the stopped path.
            // A store that cannot be read back must not erase what the run already proved.
            planRunSnapshot?.let { snapshot=snapshot.copy(strategyActions=StrategyExplanation.actionsOf(selected,it)) }
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
        // The work pages never show the balance; the room's top bar does. Read it here and again
        // after the shift so the payout is a real balance delta, not only the payroll's own award.
        val roomCoinsBefore=readRoomCoins("work-room-coins-before")
        if (roomCoinsBefore is ObservedValue.Known && sessionRoomCoinsBefore == null) {
            sessionRoomCoinsBefore = roomCoinsBefore.value
        }
        workResources.setCoursePageBalance(roomCoinsBefore as? ObservedValue.Known)
        flow.state="WORK"
        navigation.enterOutdoor("plan-work-outdoor")
        val candidates = WorkCatalog().candidatesFor(entry.id)
        var startedOutcome: Pair<WorkCatalog.WorkCatalogEntry, WorkOutcome.Started>? = null
        val refusedReasons = mutableListOf<String>()

        for (candidate in candidates) {
            val candidateTarget = WorkTarget(candidate.venue, candidate.optionLabel, candidate.id)
            work.authorise(WorkStartAuthorisation.grant(candidate.venue, candidate.id, maxStarts=1,
                maxCoinsSpent=0, maxEnergySpent=candidate.energyCost, grantedBy="plan",
                nowMs=System.currentTimeMillis()))
            val outcome = work.run(candidateTarget)
            if (outcome is WorkOutcome.Started) {
                startedOutcome = candidate to outcome
                break
            } else {
                val reason = if (outcome is WorkOutcome.Blocked) outcome.reason else outcome::class.simpleName ?: "REFUSED"
                refusedReasons += "${candidate.optionLabel} ($reason)"
                flow.emit("work-candidate-refused", result = "candidate=${candidate.id} reason=$reason")
                leaveTownIfWorkRefused(outcome)
            }
        }
        val (activeEntry, started) = startedOutcome ?: error("WORK_ALL_CANDIDATES_REFUSED:${refusedReasons.joinToString("; ")}")
        val target = WorkTarget(activeEntry.venue, activeEntry.optionLabel, activeEntry.id)
        checkNotNull(activePlanContext).startConfirmed(1)
        val done=work.waitAndVerify(target)
        check(done is WorkOutcome.Completed) {"WORK_NOT_COMPLETED:${done::class.simpleName}"}
        checkNotNull(activePlanContext).completed(1,started.durationMinutes)
        flow.emit("WORK_COMPLETE",result=EventJson.encode(mapOf("option" to entry.id,"minutes" to started.durationMinutes,
            "records" to done.records.map {it.fields()})))
        check(workPageReader.dismissCompletion()) {"WORK_COMPLETION_DISMISS_UNCONFIRMED"}
        flow.verifySettled(listOf(flow.outdoor),"work-completion-outdoor")
        navigation.returnHomeFromMap("work")
        val roomCoinsAfter=readRoomCoins("work-room-coins-after")
        if (roomCoinsAfter is ObservedValue.Known) {
            sessionRoomCoinsAfter = roomCoinsAfter.value
        }
        if(roomCoinsBefore is ObservedValue.Known && roomCoinsAfter is ObservedValue.Known) {
            val delta=roomCoinsAfter.value-roomCoinsBefore.value
            flow.emit("work-room-balance-delta",result=if(delta>0) "PASS" else "OBSERVED",
                reason="before=${roomCoinsBefore.value}|after=${roomCoinsAfter.value}|delta=$delta")
        }
        return StepOutcome.Completed()
    }

    /**
     * Coins off the room's top bar, or Unknown (never 0). Evidence only: a failed read never stops the
     * plan, it only leaves the payout proved by the payroll award alone.
     */
    private suspend fun readRoomCoins(tag:String):ObservedValue<Int> {
        val now=System.currentTimeMillis()
        val value=try {
            val f=flow.frame(latest=true)
            try {
                if(flow.score(f,listOf(flow.room))<flow.profile.matchThreshold) ObservedValue.Unknown("ROOM_UNCONFIRMED",now,tag)
                else DeviceWalletReader(reader).read(f,now,"room-top-bar:$tag")
            } finally {f.recycle()}
        } catch(t:Exception) {
            if(t is kotlinx.coroutines.CancellationException) throw t
            ObservedValue.Unknown("ROOM_COINS_READ_FAILED:${t.javaClass.simpleName}",now,tag)
        }
        flow.emit(tag,result=if(value is ObservedValue.Known) "PASS" else "UNREAD",
            reason=if(value is ObservedValue.Known) "coins=${value.value}" else (value as ObservedValue.Unknown).reason)
        return value
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
     *
     * STR-09：开了均衡的方案按轮换顺序上不同的课——第 1 节力量、第 2 节智力、第 3 节魅力，
     * 第 4 节回到力量；单属性方案每一节仍是同一门课。
     */
    private suspend fun runPlanStudy(step:StudyStep):StepOutcome {
        // STR-08：今天还能上几节已经在预检里算好，这里只按允许的节数开课；一节都不剩就跳过。
        val rounds=planStudyAllowedRounds
        if(rounds<=0) {
            flow.emit("plan-study-skipped",result="DAILY_LIMIT_REACHED")
            return StepOutcome.Skipped(SkipReason.DAILY_LIMIT_REACHED)
        }
        val schedule=StudyRotationPolicy.schedule(step,rounds)
        var lessons=0
        schedule.forEachIndexed { index, lesson ->
            val derived=plan.copy(courseId=lesson.courseId,rounds=rounds)
            pausePoint("第 ${index+1}/${rounds} 节 ${derived.courseName}")
            flow.round=index+1
            flow.state="SELECT_COURSE"
            snapshot=snapshot.copy(currentFlow="STUDY",nextStep="第 ${index+1}/${rounds} 节 ${derived.courseName}")
            val timing=CourseTiming.resolve(derived,catalog,System.currentTimeMillis())
            val ctx=checkNotNull(activePlanContext)
            // STR-09：这一节页面上写明的属性收益，读不到就是未知，不猜。
            var observedGain:CourseAttributeGain?=null
            val course=study.start(null,derived,timing,beforeStart={minutes ->
                ctx.beforeConsumingInput(index+1,minutes)
            },ensureTuition={
                val page=readCoursePage()
                val expected=checkNotNull(planCourseCosts[lesson.courseId])
                check(page.cost.tuitionCoins==expected.tuitionCoins) {"COURSE_TUITION_CHANGED"}
                check(page.cost.balanceCoins>=page.cost.tuitionCoins) {"COURSE_COINS_INSUFFICIENT"}
                observedGain=page.attributeGain
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
            recordStudyAttribute(lesson.attribute,observedGain)
            completed=ledger.completedCount
            snapshot=snapshot.copy(completedRounds=completed)
            flow.emit("ROUND_RESULT_CONFIRMED",result="round=${flow.round} completed=$completed"+
                (lesson.attribute?.let {" attribute=$it"} ?: ""))
            if(result.getBoolean("result_page")) navigation.returnHomeFromResult("result") else navigation.initialise()
        }
        snapshot=snapshot.copy(completedRounds=lessons)
        return StepOutcome.Completed()
    }

    /**
     * STR-09：记录这个属性这次上了几节、页面上写明一共加多少。
     *
     * 页面上写的收益和这一节练的属性对不上时，宁可记成未知，也不把别的属性的数字算进来。
     */
    private fun recordStudyAttribute(attribute:String?,gain:CourseAttributeGain?) {
        if(attribute.isNullOrBlank()) return
        val fact=studyAttributeFacts[attribute] ?: StudyAttributeFact(attribute,0,null,0)
        val matched=gain?.takeIf { it.attribute==attribute }
        studyAttributeFacts[attribute]=fact.copy(
            rounds=fact.rounds+1,
            gain=(fact.gain ?: 0)+(matched?.amount ?: 0),
            lessonsWithGain=fact.lessonsWithGain+(if(matched==null) 0 else 1))
    }

    /** The plan's own course cost, read from the course detail page of [courseId]'s course. */
    private suspend fun readPlanCourseCost(courseId:String):CourseCostReading {
        val derived=plan.copy(courseId=courseId)
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
     * requires the threshold precondition that only a lesson needs.
     */

    private suspend fun runPlanSignIn(step: SignInStep): StepOutcome {
        flow.round = 1
        flow.state = "SIGN_IN"
        snapshot = snapshot.copy(currentFlow = "SIGN_IN", nextStep = "每日签到")
        val ctx = checkNotNull(activePlanContext)

        val signInFlow = PetSignInFlow(
            flow = flow,
            lineReader = {
                val f = flow.frame(latest = true)
                try {
                    reader.read(f)
                } finally {
                    f.recycle()
                }
            },
            tap = { point, settleMs ->
                flow.safety()
                flow.input.tap(point.x, point.y)
                kotlinx.coroutines.delay(settleMs)
            },
            onBeforeClaim = {
                ctx.beforeConsumingInput(1, 0)
            }
        )

        return when (val outcome = signInFlow.run()) {
            is SignInOutcome.Completed -> {
                ctx.completed(1, 0)
                flow.emit("plan-sign-in-complete", result = "coins=" + (outcome.reward.coinAward ?: 0))
                StepOutcome.Completed()
            }
            is SignInOutcome.AlreadyDone -> {
                flow.emit("plan-sign-in-already-done")
                StepOutcome.Skipped(SkipReason.ALREADY_SATISFIED)
            }
            is SignInOutcome.Skipped -> {
                flow.emit("plan-sign-in-skipped", result = outcome.reason)
                StepOutcome.Skipped(SkipReason.ALREADY_SATISFIED)
            }
            is SignInOutcome.Blocked -> {
                ctx.unconfirmed(1, outcome.reason)
                flow.emit("plan-sign-in-blocked", result = outcome.reason)
                StepOutcome.Blocked(outcome.reason)
            }
            is SignInOutcome.Failed -> {
                ctx.unconfirmed(1, outcome.reason)
                flow.emit("plan-sign-in-failed", result = outcome.reason)
                StepOutcome.Failed(outcome.reason)
            }
        }
    }

    private suspend fun runPlanMailbox(step: MailboxStep): StepOutcome {
        flow.round = 1
        flow.state = "MAILBOX"
        snapshot = snapshot.copy(currentFlow = "MAILBOX", nextStep = "信箱待领")
        val ctx = checkNotNull(activePlanContext)

        val mailboxFlow = PetMailboxFlow(
            flow = flow,
            lineReader = {
                val f = flow.frame(latest = true)
                try {
                    reader.read(f)
                } finally {
                    f.recycle()
                }
            },
            tap = { point, settleMs ->
                flow.safety()
                flow.input.tap(point.x, point.y)
                kotlinx.coroutines.delay(settleMs)
            },
            onBeforeClaim = {
                ctx.beforeConsumingInput(1, 0)
            }
        )

        return when (val outcome = mailboxFlow.run()) {
            is MailboxOutcome.Completed -> {
                ctx.completed(1, 0)
                flow.emit("plan-mailbox-complete", result = "coins=" + (outcome.reward.coinAward ?: 0))
                StepOutcome.Completed()
            }
            is MailboxOutcome.AlreadyDone -> {
                flow.emit("plan-mailbox-already-done")
                StepOutcome.Skipped(SkipReason.ALREADY_SATISFIED)
            }
            is MailboxOutcome.Skipped -> {
                flow.emit("plan-mailbox-skipped", result = outcome.reason)
                StepOutcome.Skipped(SkipReason.ALREADY_SATISFIED)
            }
            is MailboxOutcome.Blocked -> {
                ctx.unconfirmed(1, outcome.reason)
                flow.emit("plan-mailbox-blocked", result = outcome.reason)
                StepOutcome.Blocked(outcome.reason)
            }
            is MailboxOutcome.Failed -> {
                ctx.unconfirmed(1, outcome.reason)
                flow.emit("plan-mailbox-failed", result = outcome.reason)
                StepOutcome.Failed(outcome.reason)
            }
        }
    }

    private var lastLuckyBagCheckMs: Long = 0L

    /**
     * 今日已确认的学习节数（STR-08）：遍历本会话外的当日会话目录，累加 run-record.json 里
     * 已确认完成的学习节数。只认写进记录的数字；没有这个字段的旧记录贡献 0（未知不当作完成）。
     */
    private fun computeConfirmedStudyRoundsToday(): Int {
        val root = evidence.parentFile ?: return 0
        val records = mutableListOf<StudyDailyCapPolicy.ConfirmedRecord>()
        val dirs = root.listFiles { f -> f.isDirectory } ?: return 0
        for (dir in dirs) {
            if (dir == evidence) continue
            val recordFile = java.io.File(dir, "run-record.json")
            if (!recordFile.isFile) continue
            val parsed = runCatching {
                com.ynk.virtualdisplay.ui.data.SessionRunRecord.parse(recordFile.readText())
            }.getOrNull() ?: continue
            val started = parsed.startedAtMs ?: continue
            val rounds = parsed.studyRoundsCompleted ?: continue
            records += StudyDailyCapPolicy.ConfirmedRecord(started, rounds, parsed.targetAttribute)
        }
        return StudyDailyCapPolicy.sumConfirmedToday(records, StudyDailyCapPolicy.dayKey(System.currentTimeMillis()))
    }

    /**
     * 今日已确认学习净支出（金币）：遍历本会话外的当日会话目录，对既有 run-record.json 求和。
     * 只统计 terminalState 存在且 roomCoinsBefore/After 都已确认的记录；未知记录贡献 0，
     * 避免把未知当作已花费（产品约束：未知不当作成功）。
     */
    private fun computeConfirmedNetSpendToday(): Int {
        val root = evidence.parentFile ?: return 0
        val todayKey = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
            .format(java.util.Date(System.currentTimeMillis()))
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
        var total = 0
        val dirs = root.listFiles { f -> f.isDirectory } ?: return 0
        for (dir in dirs) {
            if (dir == evidence) continue
            val recordFile = java.io.File(dir, "run-record.json")
            if (!recordFile.isFile) continue
            val parsed = runCatching {
                com.ynk.virtualdisplay.ui.data.SessionRunRecord.parse(recordFile.readText())
            }.getOrNull() ?: continue
            val started = parsed.startedAtMs ?: continue
            if (fmt.format(java.util.Date(started)) != todayKey) continue
            val before = parsed.roomCoinsBefore ?: continue
            val after = parsed.roomCoinsAfter ?: continue
            val delta = before - after
            if (delta > 0) total += delta
        }
        return total
    }

    private suspend fun runPlanLuckyBag(step: LuckyBagStep): StepOutcome {
        flow.round = 1
        flow.state = "LUCKY_BAG"
        snapshot = snapshot.copy(currentFlow = "LUCKY_BAG", nextStep = "福袋任务")
        val ctx = checkNotNull(activePlanContext)

        val now = System.currentTimeMillis()
        val intervalMs = step.scanIntervalMinutes * 60_000L
        if (lastLuckyBagCheckMs > 0 && (now - lastLuckyBagCheckMs) < intervalMs) {
            val remainMin = ((intervalMs - (now - lastLuckyBagCheckMs)) / 60_000L).coerceAtLeast(1)
            flow.emit("plan-lucky-bag-cooldown-skipped", result = "remainingMinutes=$remainMin")
            return StepOutcome.Skipped(SkipReason.ALREADY_SATISFIED)
        }

        val luckyBagFlow = PetLuckyBagFlow(
            flow = flow,
            lineReader = {
                val f = flow.frame(latest = true)
                try {
                    reader.read(f)
                } finally {
                    f.recycle()
                }
            },
            tap = { point, settleMs ->
                flow.safety()
                flow.input.tap(point.x, point.y)
                kotlinx.coroutines.delay(settleMs)
            },
            onBeforeClaim = {
                ctx.beforeConsumingInput(1, 0)
            }
        )

        return when (val outcome = luckyBagFlow.run()) {
            is LuckyBagOutcome.Completed -> {
                lastLuckyBagCheckMs = System.currentTimeMillis()
                ctx.completed(1, 0)
                flow.emit("plan-lucky-bag-complete", result = "coins=" + (outcome.reward.coinAward ?: 0))
                StepOutcome.Completed()
            }
            is LuckyBagOutcome.AlreadyDone -> {
                lastLuckyBagCheckMs = System.currentTimeMillis()
                flow.emit("plan-lucky-bag-limit-reached")
                StepOutcome.Skipped(SkipReason.DAILY_LIMIT_REACHED)
            }
            is LuckyBagOutcome.Cooldown -> {
                lastLuckyBagCheckMs = System.currentTimeMillis()
                flow.emit("plan-lucky-bag-cooldown", result = "sec=" + outcome.secondsRemaining)
                StepOutcome.Skipped(SkipReason.ALREADY_SATISFIED)
            }
            is LuckyBagOutcome.Skipped -> {
                flow.emit("plan-lucky-bag-skipped", result = outcome.reason)
                StepOutcome.Skipped(SkipReason.ALREADY_SATISFIED)
            }
            is LuckyBagOutcome.Blocked -> {
                flow.emit("plan-lucky-bag-blocked-nonfatal", result = outcome.reason)
                StepOutcome.Skipped(SkipReason.UNAVAILABLE)
            }
            is LuckyBagOutcome.Failed -> {
                flow.emit("plan-lucky-bag-failed-nonfatal", result = outcome.reason)
                StepOutcome.Skipped(SkipReason.UNAVAILABLE)
            }
        }
    }

    private suspend fun runPlanAdventure(step: AdventureStep): StepOutcome {
        val adventureFlow = PetAdventureFlow(
            flow = flow,
            navigation = navigation,
            lineReader = {
                val f = flow.frame(latest = true)
                try {
                    reader.read(f)
                } finally {
                    f.recycle()
                }
            },
            tap = { point, settleMs ->
                flow.safety()
                flow.input.tap(point.x, point.y)
                kotlinx.coroutines.delay(settleMs)
            }
        )
        val maxRounds = if (step.maxDailyRounds != null) minOf(step.rounds, step.maxDailyRounds) else step.rounds
        if (maxRounds <= 0) {
            flow.emit("plan-adventure-skipped", result = "DAILY_LIMIT_REACHED")
            return StepOutcome.Skipped(SkipReason.DAILY_LIMIT_REACHED)
        }
        repeat(maxRounds) { index ->
            pausePoint("第 " + (index + 1) + "/" + maxRounds + " 轮冒险: " + step.mode.label)
            flow.round = index + 1
            flow.state = "ADVENTURE"
            snapshot = snapshot.copy(currentFlow = "ADVENTURE", nextStep = "第 " + (index + 1) + "/" + maxRounds + " 轮冒险: " + step.mode.label)
            val ctx = checkNotNull(activePlanContext)
            val durationMinutes = step.mode.durationMinutes
            var inputDispatched = false
            val outcome = adventureFlow.run(
                mode = step.mode,
                onBeforeStart = {
                    ctx.beforeConsumingInput(index + 1, durationMinutes)
                    inputDispatched = true
                }
            )
            when (outcome) {
                is AdventureOutcome.Completed -> {
                    ctx.completed(index + 1, durationMinutes)
                    flow.emit("ADVENTURE_COMPLETE", result = "round=" + (index + 1) + " coins=" + outcome.coinAward)
                }
                is AdventureOutcome.Blocked -> {
                    if (inputDispatched) {
                        ctx.unconfirmed(index + 1, outcome.reason)
                    }
                    flow.emit("ADVENTURE_BLOCKED", result = outcome.reason)
                    if (outcome.reason.startsWith("ADVENTURE_LIMITED")) {
                        return if (index == 0) StepOutcome.Skipped(SkipReason.DAILY_LIMIT_REACHED) else StepOutcome.Completed()
                    }
                    return StepOutcome.Blocked(outcome.reason)
                }
                is AdventureOutcome.Failed -> {
                    if (inputDispatched) {
                        ctx.unconfirmed(index + 1, outcome.reason)
                    }
                    flow.emit("ADVENTURE_FAILED", result = outcome.reason)
                    return StepOutcome.Failed(outcome.reason)
                }
                is AdventureOutcome.Interrupted -> {
                    if (inputDispatched) {
                        ctx.unconfirmed(index + 1, "INTERRUPTED")
                    }
                    return StepOutcome.Blocked("ADVENTURE_INTERRUPTED")
                }
                else -> {
                    if (inputDispatched) {
                        ctx.unconfirmed(index + 1, "UNCONFIRMED")
                    }
                    return StepOutcome.Unconfirmed("ADVENTURE_UNCONFIRMED")
                }
            }
        }
        return StepOutcome.Completed()
    }

    private suspend fun runAdventureOnly(): String {
        flow.round = 1
        flow.state = "ADVENTURE"
        snapshot = snapshot.copy(round = 1, currentFlow = "ADVENTURE", nextStep = "冒险: 附近走走")
        val adventureFlow = PetAdventureFlow(
            flow = flow,
            navigation = navigation,
            lineReader = {
                val f = flow.frame(latest = true)
                try {
                    reader.read(f)
                } finally {
                    f.recycle()
                }
            },
            tap = { point, settleMs ->
                flow.safety()
                flow.input.tap(point.x, point.y)
                kotlinx.coroutines.delay(settleMs)
            }
        )
        val outcome = adventureFlow.run(mode = AdventureMode.NEARBY_WALK)
        return when (outcome) {
            is AdventureOutcome.Completed -> {
                completed = 1
                snapshot = snapshot.copy(completedRounds = 1, nextStep = "冒险完成")
                flow.emit("ADVENTURE_COMPLETE", result = "coins=" + outcome.coinAward)
                "ADVENTURE_PASS"
            }
            is AdventureOutcome.Blocked -> {
                stopReason = outcome.reason
                "STOPPED"
            }
            is AdventureOutcome.Failed -> {
                stopReason = outcome.reason
                "FAILED"
            }
            else -> "STOPPED"
        }
    }

    private suspend fun runRounds():String=coroutineScope {
            repeat(totalRounds) {index ->
                pausePoint("第 ${index+1}/$totalRounds 节")
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
                if(result.getBoolean("result_page")) navigation.returnHomeFromResult("result") else navigation.initialise()
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
        leaveTownIfWorkRefused(started)
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
    /** Tidying only: the refusal stays the session's stop reason even if the way home fails. */
    private suspend fun leaveTownIfWorkRefused(outcome:WorkOutcome) {
        if(outcome !is WorkOutcome.Blocked) return
        try {
            navigation.leaveTownAfterRefusedWork()
        } catch(t: Throwable) {
            if(t is kotlinx.coroutines.CancellationException) throw t
            flow.emit("work-refused-return-best-effort",result="SKIP",reason="LEFT_ON_TOWN:${t.message}")
        }
    }
    /** Satisfies only the game's shift cost; plan care thresholds belong to lessons. */
    private suspend fun preparePetForWork(entry:WorkCatalog.WorkCatalogEntry,balanceCoins:Int) {
        returnHomeFromCourse("work-prep")
        val reading=care.readHealth("work-prep-health",flow.room)
        val have=(reading.observations.cleanliness as? ObservedValue.Known)?.value
            ?: error("WORK_PREP_CLEANLINESS_UNREAD")
        if(WorkPreparation.isReady(have,entry.cleanlinessCost)) return
        val need=WorkPreparation.requiredCleanliness(entry.cleanlinessCost)
        snapshot=snapshot.copy(currentFlow="WORK",nextStep="为打工备清洁：现有 $have，需要 $need")
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
    private suspend fun readCourseCost():CourseCostReading = readCoursePage().cost

    /**
     * 课程详情页的一次读取：学费与余额，以及页面上写明的属性收益。
     *
     * 学费和属性收益写在同一页上，所以一次读页就够，不必再读一帧；
     * 属性收益读不到就是 null（未知），不影响学费的判定。
     */
    private suspend fun readCoursePage():CoursePageReading = readCoursePageReading(flow,reader)

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
            "requestedRounds" to ledger.attemptCount,
            "workCompleted" to (planRunSnapshot?.counts?.workShiftsCompleted ?: if (mode == SessionMode.WORK && terminal == SessionRunState.COMPLETED) 1 else 0),
            "adventureCompleted" to (planRunSnapshot?.counts?.adventureCompleted ?: if (mode == SessionMode.ADVENTURE && terminal == SessionRunState.COMPLETED) 1 else 0),
            "signInCompleted" to (planRunSnapshot?.counts?.signInCompleted ?: 0),
            "mailboxCompleted" to (planRunSnapshot?.counts?.mailboxCompleted ?: 0),
            "luckyBagCompleted" to (planRunSnapshot?.counts?.luckyBagCompleted ?: 0),
            "roomCoinsBefore" to sessionRoomCoinsBefore,
            "roomCoinsAfter" to sessionRoomCoinsAfter,
            // STR-08：今日停止线靠这两个字段统计「今天已经确认学了几节」。
            "studyRoundsCompleted" to (planRunSnapshot?.counts?.studyRoundsCompleted ?: ledger.completedCount),
            "targetAttribute" to (planV2?.steps?.filterIsInstance<StudyStep>()?.singleOrNull()?.targetAttribute
                ?: plan.targetAttribute),
            // STR-09：每个属性这次上了几节、页面上写明一共加多少（读不到就留 null，不猜）。
            "studyAttributes" to studyAttributeFacts.mapValues { (_, fact) ->
                mapOf<String, Any?>("rounds" to fact.rounds, "gain" to fact.gain,
                    "lessonsWithGain" to fact.lessonsWithGain)
            },
            "strategyActions" to value.strategyActions.map {
                mapOf("label" to it.label,"outcome" to it.outcome.name,"reason" to it.reason)})
        File(evidence,"run-record.json").writeText(EventJson.encode(record))
    }
}
