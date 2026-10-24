package com.ynk.virtualdisplay.automation

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.os.IBinder
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.ynk.virtualdisplay.MyApplication
import com.ynk.virtualdisplay.R
import com.ynk.virtualdisplay.data.repository.ConnectionStatus
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.json.JSONObject

/**
 * User-started, bounded, local MVP automation session.
 * It never touches the physical/default display. Any recognition error ends the session.
 */
class PetAutomationService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    @Volatile private var stopReason: String = "已停止"
    private lateinit var app: MyApplication
    private val profile = PetProfile.calibrated()
    @Volatile private var ownerRunId:String?=null
    @Volatile private var stopEpoch:Long=0
    private val coordinator by lazy {
        PlanRunCoordinator("pet-local-device",PlanRunCoordinator.PendingGate {
            // This forces one-time import from the existing preferences before reading the journal.
            DailyBudgetStore(this).usedMinutes()
            PersistentBudgetStore(File(filesDir,PersistentBudgetStore.FILE_NAME)).read()
                .attempts.values.firstOrNull(CarePendingReconciliation::unresolved)?.attemptId
                ?: PlanRunPendingGate.blockingRunId(File(filesDir,"plan-runs"))
        })
    }

    override fun onCreate() {
        super.onCreate()
        app = application as MyApplication
        app.acquireDisplayRepository("PetAutomationService")
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopSession("用户停止")
            ACTION_SCAN_COURSES -> {
                val displayId = intent.getIntExtra(EXTRA_DISPLAY_ID, -1)
                startForeground(NOTIFICATION_ID, notification("读取课程中…"))
                launchOwned(intent) { scanCourses(displayId) }
            }
            ACTION_START -> {
                val displayId = intent.getIntExtra(EXTRA_DISPLAY_ID, -1)
                startForeground(NOTIFICATION_ID, notification("正在初始化…"))
                val rounds = intent.getIntExtra(EXTRA_ROUNDS, 1)
                val planJson = intent.getStringExtra(EXTRA_PLAN_JSON)
                launchOwned(intent) {
                    if (planJson != null) {
                        val selected = PlanV2JsonCodec.decode(planJson)
                        runSession(displayId, rounds, SessionMode.PLAN, selected,
                            checkNotNull(intent.getStringExtra(EXTRA_REQUEST_ID)))
                    } else {
                        val mode=SessionMode.fromWire(intent.getStringExtra(EXTRA_MODE))
                        check(mode!=SessionMode.PLAN) {"PLAN_JSON_REQUIRED"}
                        runSession(displayId,rounds,mode)
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun launchOwned(intent:Intent,allowReconciliation:Boolean=true,block:suspend ()->Unit) {
        val requestId=intent.getStringExtra(EXTRA_REQUEST_ID) ?: java.util.UUID.randomUUID().toString()
        intent.putExtra(EXTRA_REQUEST_ID,requestId)
        if(intent.hasExtra(EXTRA_PLAN_JSON)) {
            try {
                val receipt=PlanRunPendingGate.findRequest(File(filesDir,"plan-runs"),requestId)
                if(receipt!=null) {
                    check(receipt.request.planSnapshot==PlanV2JsonCodec.decode(checkNotNull(intent.getStringExtra(EXTRA_PLAN_JSON))) &&
                        receipt.request.displayId==intent.getIntExtra(EXTRA_DISPLAY_ID,-1)) {"RUN_REQUEST_CHANGED"}
                    if(allowReconciliation && receipt.state==RunState.UNCONFIRMED && job?.isActive != true) {
                        launchCommittedPlanFinalization(intent,receipt.runId)
                        return
                    }
                    logLine("SESSION_REQUEST_RECEIPT ${receipt.runId} ${receipt.state}")
                    if(job?.isActive != true) stopSession("请求已处理：${receipt.runId}")
                    return
                }
            } catch(t:Throwable) {
                logLine("SESSION_START_REJECTED RUN_STORE_UNREADABLE")
                if(job?.isActive != true) stopSession("RUN_STORE_UNREADABLE:${t.message}")
                return
            }
        }
        val runId=java.util.UUID.randomUUID().toString()
        when(val admission=coordinator.admit(requestId,runId)) {
            is PlanRunCoordinator.Admission.Rejected -> {
                logLine("SESSION_START_REJECTED ${admission.reason}")
                if(allowReconciliation && admission.reason.startsWith("PENDING_RUN_UNRESOLVED:") && job?.isActive != true) {
                    try {
                        val blockingId=admission.reason.removePrefix("PENDING_RUN_UNRESOLVED:")
                        val writebackRunner=WorkCompletionWritebackRunner(filesDir,checkNotNull(getExternalFilesDir(null)),
                            app.displayRepository.automationEventsDirectory,
                            PersistentBudgetStore(File(filesDir,PersistentBudgetStore.FILE_NAME)))
                        val v2=PlanRunPendingGate.blockingRunId(File(filesDir,"plan-runs"))
                        when {
                            v2!=null -> launchCommittedPlanFinalization(intent,v2)
                            writebackRunner.isRetainedWorkAttempt(blockingId) ->
                                launchRetainedWorkWriteback(intent,blockingId)
                            else -> launchLegacyCareObservation(intent,block)
                        }
                    } catch(t:Throwable) {
                        stopSession("PENDING_RECOVERY_REFUSED:${t.message}")
                    }
                    return
                }
                if(job?.isActive != true) stopSession(admission.reason)
                return
            }
            is PlanRunCoordinator.Admission.Accepted -> if(!admission.shouldExecute) {
                logLine("SESSION_REQUEST_RECEIPT ${admission.runId}")
                if(job?.isActive != true) stopSession("请求已处理：${admission.runId}")
                return
            }
        }
        ownerRunId=runId
        stopReason="会话结束"
        val newJob=scope.launch(start=CoroutineStart.LAZY) {
            try {block()}
            catch(ce:CancellationException) {throw ce}
            catch(t:Throwable) {
                stopReason="会话异常：${t.javaClass.simpleName}: ${t.message}"
                logLine("SESSION_START_FAILED ${t.javaClass.simpleName}")
            }
        }
        job=newJob
        newJob.invokeOnCompletion {
            coordinator.finish(runId)
            scope.launch(Dispatchers.Main) {finalizeStop(runId)}
        }
        newJob.start()
    }

    private fun launchCommittedPlanFinalization(intent:Intent,targetRunId:String) {
        val observationId=java.util.UUID.randomUUID().toString()
        val requestId=checkNotNull(intent.getStringExtra(EXTRA_REQUEST_ID))
        val grant=coordinator.admitObservation("$requestId:$targetRunId",observationId,
            PlanRunCoordinator.ObservationTask.COMMITTED_NON_STUDY_FINALIZATION)
        if(grant !is PlanRunCoordinator.Admission.Accepted || !grant.shouldExecute) {
            stopSession("PLAN_FINALIZATION_OBSERVATION_NOT_ADMITTED")
            return
        }
        ownerRunId=observationId
        val epoch=stopEpoch
        val observationJob=scope.launch(start=CoroutineStart.LAZY) {
            try {
                withTimeout(20_000L) {
                    val root=File(filesDir,"plan-runs")
                    val finalizer=CommittedPlanFinalizer(root)
                    fun checkpoint():FinalizationCheckpoint {
                        val file=File(filesDir,"pet-checkpoints/$targetRunId.json")
                        check(file.isFile && file.length()<=1_000_000) {"FINALIZATION_CHECKPOINT_MISSING"}
                        val value=JSONObject(file.readText(Charsets.UTF_8))
                        return FinalizationCheckpoint(value.getString("sessionId"),value.getBoolean("actionPending"),file.absolutePath)
                    }
                    fun noOtherPending() {
                        check(PlanRunPendingGate.pendingRunIds(root)==listOf(targetRunId)) {"FINALIZATION_OTHER_RUN_PENDING"}
                        check(PersistentBudgetStore(File(filesDir,PersistentBudgetStore.FILE_NAME)).read().attempts.values
                            .none(CarePendingReconciliation::unresolved)) {"FINALIZATION_BUDGET_PENDING"}
                    }
                    noOtherPending()
                    val originalCheckpoint=checkpoint()
                    val run=finalizer.requireEligible(targetRunId,originalCheckpoint)
                    val displayId=intent.getIntExtra(EXTRA_DISPLAY_ID,-1)
                    check(displayId==run.request.displayId && displayId>0) {"FINALIZATION_DISPLAY_MISMATCH"}
                    app.displayRepository.connectionStatus.first {it==ConnectionStatus.CONNECTED}
                    app.displayRepository.managedDisplayIds.first {displayId in it}
                    fun authority() {
                        check(coordinator.isActive(observationId) && !coordinator.isStopRequested(observationId) && stopEpoch==epoch) {"FINALIZATION_OWNER_REVOKED"}
                        verifyDisplay(displayId)
                    }
                    authority()
                    val capture=HeadlessDisplayCapture(app.displayRepository,displayId,profile.displayWidth,profile.displayHeight)
                    val evidence=File(getExternalFilesDir(null),"pet_finalization/$targetRunId/$observationId")
                    try {
                        capture.start()
                        val baseline=CarePendingBaselineObserver(this@PetAutomationService,capture,displayId,evidence,::authority).observe()
                        currentCoroutineContext().ensureActive();authority();noOtherPending()
                        check(checkpoint()==originalCheckpoint) {"FINALIZATION_CHECKPOINT_CHANGED"}
                        val result=finalizer.finish(targetRunId,originalCheckpoint,baseline,System.currentTimeMillis()) {
                            authority();noOtherPending()
                        }
                        stopReason="PLAN_FINALIZED_NO_REPLAY run=$targetRunId work=${result.counts.workShiftsCompleted} care=${result.counts.careCompleted} study=0"
                        logLine(stopReason)
                    } finally {capture.stop()}
                }
            } catch(t:Throwable) {
                stopReason="PLAN_FINALIZATION_REFUSED:${t.message ?: t.javaClass.simpleName}"
                logLine(stopReason)
                if(t is CancellationException) throw t
            }
        }
        job=observationJob
        observationJob.invokeOnCompletion {
            coordinator.finish(observationId)
            scope.launch(Dispatchers.Main) {if(ownerRunId==observationId) finalizeStop(observationId)}
        }
        // Deliberately no continuation into a new requested run: this operation sends zero input.
        observationJob.start()
    }

    /**
     * Evidence-only write-back of a retained standalone-WORK attempt (defect D1). Sends zero input and
     * never continues into the requested run; the user starts that again explicitly afterwards.
     */
    private fun launchRetainedWorkWriteback(intent:Intent,attemptId:String) {
        val requestId=checkNotNull(intent.getStringExtra(EXTRA_REQUEST_ID))
        val observationId=java.util.UUID.randomUUID().toString()
        val admission=coordinator.admitObservation(requestId,observationId,
            PlanRunCoordinator.ObservationTask.RETAINED_WORK_COMPLETION_WRITEBACK)
        if(admission !is PlanRunCoordinator.Admission.Accepted || !admission.shouldExecute) {
            stopSession("WORK_WRITEBACK_OBSERVATION_NOT_ADMITTED")
            return
        }
        ownerRunId=observationId
        val epoch=stopEpoch
        val runner=WorkCompletionWritebackRunner(filesDir,checkNotNull(getExternalFilesDir(null)),
            app.displayRepository.automationEventsDirectory,
            PersistentBudgetStore(File(filesDir,PersistentBudgetStore.FILE_NAME)))
        val observationJob=scope.launch(start=CoroutineStart.LAZY) {
            try {
                withTimeout(20_000L) {
                    fun authority() {
                        check(coordinator.isActive(observationId) && !coordinator.isStopRequested(observationId) &&
                            stopEpoch==epoch) {"WORK_WRITEBACK_OWNER_REVOKED"}
                    }
                    val updated=runner.run(attemptId,System.currentTimeMillis(),::authority)
                    val sessionId=WorkCompletionWritebackPolicy.splitAttemptId(attemptId).first
                    stopReason="WORK_COMPLETION_WRITTEN_BACK attempt=$attemptId minutes=${updated.minutes} " +
                        "day=${updated.day} audit=${runner.auditFile(sessionId).absolutePath}"
                    logLine(stopReason)
                }
            } catch(t:Throwable) {
                stopReason="WORK_WRITEBACK_REFUSED:${t.message ?: t.javaClass.simpleName}"
                logLine(stopReason)
                if(t is CancellationException) throw t
            }
        }
        job=observationJob
        observationJob.invokeOnCompletion {
            coordinator.finish(observationId)
            scope.launch(Dispatchers.Main) { if(ownerRunId==observationId) finalizeStop(observationId) }
        }
        observationJob.start()
    }

    private fun launchLegacyCareObservation(intent:Intent,block:suspend ()->Unit) {
        val requestId=checkNotNull(intent.getStringExtra(EXTRA_REQUEST_ID))
        val observationId=java.util.UUID.randomUUID().toString()
        val admission=coordinator.admitObservation(requestId,observationId,
            PlanRunCoordinator.ObservationTask.LEGACY_ZERO_MINUTE_CARE_RECONCILIATION)
        if(admission !is PlanRunCoordinator.Admission.Accepted || !admission.shouldExecute) {
            stopSession("LEGACY_CARE_OBSERVATION_NOT_ADMITTED")
            return
        }
        ownerRunId=observationId
        val epoch=stopEpoch
        var reconciled=false
        val observerJob=scope.launch(start=CoroutineStart.LAZY) {
            try {
                withTimeout(20_000L) {
                    val displayId=intent.getIntExtra(EXTRA_DISPLAY_ID,-1)
                    val budget=DailyBudgetStore(this@PetAutomationService)
                    budget.usedMinutes()
                    val attempts=PersistentBudgetStore(File(filesDir,PersistentBudgetStore.FILE_NAME)).read().attempts.values
                    check(PlanRunPendingGate.blockingRunId(File(filesDir,"plan-runs"))==null) {"V2_PENDING_PREVENTS_RECONCILIATION"}
                    val proofs=LegacyCareStoppedEvidenceReader(filesDir,
                        checkNotNull(getExternalFilesDir(null)),app.displayRepository.automationEventsDirectory,
                        LegacyPaySettlementReader(AndroidLegacyPayFrameReader(this@PetAutomationService))).read(attempts)
                    check(proofs.isNotEmpty()) {"NO_LEGACY_CARE_PENDING"}
                    app.displayRepository.connectionStatus.first {it==ConnectionStatus.CONNECTED}
                    app.displayRepository.managedDisplayIds.first {displayId in it}
                    fun authority() {
                        check(coordinator.isActive(observationId) && !coordinator.isStopRequested(observationId) && stopEpoch==epoch) {"OBSERVATION_OWNER_REVOKED"}
                        verifyDisplay(displayId)
                    }
                    authority()
                    val capture=HeadlessDisplayCapture(app.displayRepository,displayId,profile.displayWidth,profile.displayHeight)
                    val evidence=File(getExternalFilesDir(null),"pet_reconciliation/$observationId")
                    try {
                        capture.start()
                        val baseline=CarePendingBaselineObserver(this@PetAutomationService,capture,displayId,evidence,::authority).observe()
                        currentCoroutineContext().ensureActive();authority()
                        budget.reconcileStopped(proofs,baseline,
                            PlanRunPendingGate.blockingRunId(File(filesDir,"plan-runs"))==null,System.currentTimeMillis())
                        reconciled=true
                        logLine("LEGACY_CARE_RECONCILED_STOPPED count=${proofs.size} evidence=${evidence.absolutePath}")
                    } finally {capture.stop()}
                }
            } catch(t:Throwable) {
                reconciled=false
                stopReason="LEGACY_CARE_RECONCILIATION_REFUSED:${t.message ?: t.javaClass.simpleName}"
                logLine(stopReason)
                if(t is CancellationException) throw t
            }
        }
        job=observerJob
        observerJob.invokeOnCompletion {cause ->
            coordinator.finish(observationId)
            scope.launch(Dispatchers.Main) {
                // An older observation callback never stops or replaces a newer owner.
                if(ownerRunId!=observationId) return@launch
                job=null
                if(reconciled && cause==null && stopEpoch==epoch) {
                    ownerRunId=null
                    // The observation grant is gone. A normal durable admission must now succeed.
                    launchOwned(intent,allowReconciliation=false,block=block)
                } else finalizeStop(observationId)
            }
        }
        observerJob.start()
    }

    /**
     * Reads the academy course list and stores it. Read only: the scan scrolls the list but never
     * taps a course or a button that spends anything, then returns home and stops.
     */
    private suspend fun scanCourses(displayId: Int) {
        if (displayId <= 0) return stopSession("拒绝默认/无效 displayId")
        try {
            withTimeout(7_000L) {
                app.displayRepository.connectionStatus.first { it == ConnectionStatus.CONNECTED }
                app.displayRepository.managedDisplayIds.first { displayId in it }
            }
            verifyDisplay(displayId)
            acquireWakeLock()
            PetRuntime.update { it.copy(running = true, runState = SessionRunState.RUNNING,
                displayId = displayId, status = "读取课程中…") }
            val capture = HeadlessDisplayCapture(app.displayRepository, displayId, profile.displayWidth, profile.displayHeight)
            val input = PetInputController(app.displayRepository, displayId, profile.displayWidth, profile.displayHeight, capture.facts)
            val evidence = File(getExternalFilesDir(null), "pet_mvp_session/${System.currentTimeMillis()}")
            capture.start()
            try {
                val flow = PetFlowContext(this, capture, input, evidence, { verifyDisplay(displayId) }, ::logEvent)
                // The scan starts from wherever the pet was left, and the career-town pages carry no
                // template probe, so the navigation needs the town reader to be able to come home.
                val reader = MlKitCourseNameReader()
                val navigation = PetNavigation(flow, DeviceWorkPageReader(flow, reader))
                flow.state = "INITIAL_NAVIGATION"
                navigation.initialise()
                navigation.enterOutdoor("outdoor")
                val catalog = CourseScanFlow(flow, navigation, reader).scan()
                CourseCatalogStore(this).save(catalog)
                navigation.tap(profile.courseListProbe, android.graphics.PointF(80f, 160f), flow.academy, "scan-close-panel")
                navigation.tap(flow.academy, android.graphics.PointF(80f, 160f), flow.outdoor, "scan-leave-academy")
                navigation.returnHomeFromMap("scan")
                stopReason = "课程读取完成 ${catalog.courses.size} 门（可选 ${catalog.unlocked().size} 门）"
            } finally { capture.stop() }
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            Log.e(TAG, "course scan failed", t)
            stopSession("读取课程失败：${t.javaClass.simpleName}: ${t.message}")
        }
    }

    /**
     * The effective legacy-shaped plan for a saved plan (方案).
     *
     * The plan's own steps decide the course, the lesson count, the care thresholds and the work
     * option; the stored 托管 settings only supply the values the plan does not mention. The
     * implicit funding shift is OFF: a plan's WORK step is explicit, so the app never adds a
     * shift the user did not select.
     */
    private fun effectivePlan(selected: PlanV2): SessionPlan {
        return SelectedPlanConfiguration.toSessionPlan(selected)
    }

    private suspend fun runSession(displayId: Int, rounds: Int, mode: SessionMode, selected: PlanV2?=null,
        requestId:String?=null) {
        val errors = profile.validate(healthGuardInstalled=true)
        if (errors.isNotEmpty()) return stopSession("配置未完成：${errors.joinToString()}")
        if (displayId <= 0) return stopSession("拒绝默认/无效 displayId")
        // A HEALTH_CHECK session has no rounds to run, so the 1..10 batch rule does not apply to it.
        if (mode.runsStudy && rounds !in 1..10) return stopSession("Only finite 1..10 round batches are supported")
        // A plan runs the lessons its own STUDY step asks for, so its round count is the plan's.
        val planRunsStudy=selected?.steps?.any {it is StudyStep} == true
        if (selected!=null && planRunsStudy) {
            val lessonRounds=selected.steps.filterIsInstance<StudyStep>().first().rounds
            if (lessonRounds !in 1..10) return stopSession("方案学习节数必须在1..10")
        }

        try {
            withTimeout(7_000L) {
                app.displayRepository.connectionStatus.first { it == ConnectionStatus.CONNECTED }
                // CONNECTED is published before the asynchronous ownership snapshot.
                app.displayRepository.managedDisplayIds.first { displayId in it }
            }
            verifyDisplay(displayId)
            acquireWakeLock()

            val budget = DailyBudgetStore(this)
            val plan = if (selected!=null) effectivePlan(selected) else SessionPlanStore(this).load()
            val requestedRounds=selected?.let(SelectedPlanConfiguration::studyRounds) ?: rounds
            val controllerRounds=if(selected==null) rounds else maxOf(1,requestedRounds)
            selected?.let { snapshot ->
                val verified=WorkCatalog().all().filter { it.confidence==RewardConfidence.VERIFIED }.map {it.id}.toSet()
                PlanValidator.validate(snapshot,verified)
            }
            val capture = HeadlessDisplayCapture(app.displayRepository, displayId, profile.displayWidth, profile.displayHeight)
            val input = PetInputController(app.displayRepository, displayId, profile.displayWidth, profile.displayHeight, capture.facts)
            val catalog = if (mode.runsStudy || planRunsStudy) CourseCatalogStore(this).load() else null
            if (mode.runsStudy || planRunsStudy) check(catalog?.isUsable(System.currentTimeMillis()) == true) { "COURSE_CATALOG_RESCAN_REQUIRED" }
            val evidence = File(getExternalFilesDir(null), "pet_mvp_session/${System.currentTimeMillis()}")
            val selectedRunStore=if(selected!=null) FileRunStore(File(filesDir,"plan-runs")) else null
            val selectedRequest=selected?.let { RunRequest(checkNotNull(requestId),it,"pet-local-device",
                displayId,RunOrigin.PLAN,System.currentTimeMillis()) }
            val selectedBudget=object:PlanAttemptBudget {
                override fun canAfford(minutes:Int,limit:Int)=minutes==0 || budget.canAfford(minutes,limit)
                override fun reserve(id:String,minutes:Int,limit:Int)=budget.reserveAttempt(id,minutes,limit).newlyCharged
                override fun started(id:String) {budget.startConfirmed(id)}
                override fun completed(id:String) {budget.completed(id)}
                override fun unconfirmed(id:String,reason:String) {budget.unconfirmed(id,reason)}
            }
            val reservedAttempts=linkedSetOf<String>()
            val activeCareRounds=mutableSetOf<Int>()
            fun studyAttempt(round:Int)=AttemptId(capture.facts.events.sessionId,"study",round).value
            fun careAttempt(round:Int)=AttemptId(capture.facts.events.sessionId,"care",round).value
            val workAttempt=AttemptId(capture.facts.events.sessionId,"work",1).value
            fun reserve(attemptId:String,minutes:Int) {
                val receipt=budget.reserveAttempt(attemptId,minutes,plan.dailyBudgetMinutes)
                check(receipt.newlyCharged) { "BUDGET_ATTEMPT_ALREADY_RESERVED_NO_REPLAY:$attemptId" }
                reservedAttempts+=attemptId
            }
            lateinit var controller:PetSessionController
            controller=PetSessionController(this,capture,input,evidence,{verifyDisplay(displayId)},::logEvent,controllerRounds,mode,plan,
                beforeCourse={minutes -> reserve(studyAttempt(controller.flow.round),minutes)},
                onRoundCompleted={round ->
                    budget.completed(studyAttempt(round))
                    PetRuntime.update {it.copy(cycles=round)}
                },
                beforeWork={minutes ->
                    if (mode != SessionMode.WORK) {
                        // The suffix the shift must leave room for is the plan's OWN study step.
                        // A plan without a study step has no lesson to protect, so only the daily
                        // cap applies - asking the catalog for a course that is not in the plan
                        // stopped a WORK+CARE plan before the shift even started.
                        val suffixMinutes=if (selected!=null) {
                            PlanBudgetPolicy.suffixLessonMinutes(selected) { courseId ->
                                catalog?.find(courseId)?.durationMinutes
                            }
                        } else {
                            catalog?.find(plan.courseId)?.durationMinutes
                                ?: error("PLAN_COURSE_DURATION_UNKNOWN:${plan.courseId}")
                        }
                        check(budget.canAfford(minutes + suffixMinutes,plan.dailyBudgetMinutes)) {"DAILY_BUDGET_LIMIT"}
                    }
                    reserve(workAttempt,minutes)
                },
                onWorkStarted={budget.startConfirmed(workAttempt)},
                onWorkCompleted={budget.completed(workAttempt)},
                onCourseStarted={round -> budget.startConfirmed(studyAttempt(round))},
                beforeCare={round ->
                    check(round !in activeCareRounds || budget.attempt(careAttempt(round))?.status == ReserveStatus.RESERVED_BEFORE_INPUT) {
                        "CARE_ATTEMPT_NOT_ACTIVE_NO_REPLAY"
                    }
                    if(round !in activeCareRounds) {
                        reserve(careAttempt(round),0)
                        activeCareRounds+=round
                    }
                },
                onCareCompleted={round ->
                    if(round in activeCareRounds && budget.attempt(careAttempt(round))?.status == ReserveStatus.RESERVED_BEFORE_INPUT) {
                        budget.completed(careAttempt(round))
                    }
                },
                catalog=catalog,
                planV2=selected,
                planRequest=selectedRequest,
                planRunStore=selectedRunStore,
                planAttemptBudget=selectedBudget)
            capture.start()
            try {
                PetRuntime.update {it.copy(running=true,runState=SessionRunState.RUNNING,displayId=displayId,cycles=0,status=modeLabel(mode))}
                val verdict=controller.run()
                // Each independent entry has its OWN success verdict; only 托管 counts rounds. A
                // successful WORK_PASS/CARE_PASS/HEALTH_CHECK_PASS must not be reported as STOPPED
                // while the controller itself marked the run COMPLETED (stopReason null).
                stopReason=when(verdict) {
                    "PLAN_PASS" -> "PLAN_PASS work=${controller.planRunSnapshot?.counts?.workShiftsCompleted ?: 0} care=${controller.planRunSnapshot?.counts?.careCompleted ?: 0} study=${controller.completed}/$requestedRounds"
                    "MANAGED_BATCH_PASS" -> "BATCH_PASS ${controller.completed}/$requestedRounds"
                    "WORK_PASS" -> "WORK_PASS"
                    "CARE_PASS" -> "CARE_PASS"
                    "HEALTH_CHECK_PASS" -> "HEALTH_CHECK_PASS"
                    else -> controller.stopReason ?: "STOPPED"
                }
                File(evidence,"verdict.json").writeText(JSONObject().put("verdict",verdict)
                    .put("mode",mode.wire)
                    .put("completed",controller.completed).put("requested",requestedRounds)
                    .put("workCompleted",controller.planRunSnapshot?.counts?.workShiftsCompleted ?: JSONObject.NULL)
                    .put("careCompleted",controller.planRunSnapshot?.counts?.careCompleted ?: JSONObject.NULL)
                    .put("planRunState",controller.planRunSnapshot?.state?.name ?: JSONObject.NULL)
                    .put("stopReason",controller.stopReason ?: JSONObject.NULL)
                    .put("captureStatistics",capture.statistics()).toString())
            } finally {
                try {
                    for(attemptId in reservedAttempts) {
                        if(budget.attempt(attemptId)?.status != ReserveStatus.COMPLETED) {
                            budget.unconfirmed(attemptId,controller.stopReason ?: "SESSION_ENDED_WITHOUT_COMPLETION_PROOF")
                        }
                    }
                } finally {capture.stop()}
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            Log.e(TAG, "session failed", t)
            stopSession("会话异常：${t.javaClass.simpleName}: ${t.message}")
        }
    }

    @Suppress("DEPRECATION")
    private fun verifyDisplay(displayId: Int) {
        val dm = getSystemService(DISPLAY_SERVICE) as DisplayManager
        val d = dm.getDisplay(displayId) ?: error("virtual display disappeared")
        val p = Point(); d.getRealSize(p)
        val metrics = android.util.DisplayMetrics(); d.getRealMetrics(metrics)
        check(metrics.densityDpi == 320) { "display density must be 320dpi" }
        check(Build.MODEL == "FOA-AL00") { "Only the calibrated FOA-AL00 device is supported" }
        val qq = packageManager.getPackageInfo("com.tencent.mobileqq", 0)
        check(qq.versionName == "9.3.70" && qq.longVersionCode == 16410L) { "QQ version differs from calibration" }
        check(p.x == profile.displayWidth && p.y == profile.displayHeight) {
            "display must be ${profile.displayWidth}x${profile.displayHeight}, actual=${p.x}x${p.y}"
        }
        check(app.displayRepository.managedDisplayIds.value.contains(displayId)) {
            "display is not managed by current Shizuku service"
        }
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:pet-mvp").apply {
            setReferenceCounted(false)
            acquire(MAX_WAKELOCK_MS)
        }
    }

    private fun stopSession(reason: String) {
        stopEpoch++
        ownerRunId?.let(coordinator::stop)
        stopReason = reason
        logLine("SESSION_STOP_REQUEST $reason")
        PetRuntime.update { it.copy(status = reason, nextCheckAtMs = null) }
        job?.cancel(CancellationException(reason))
        if (job == null) scope.launch(Dispatchers.Main) { finalizeStop() }
    }

    private fun finalizeStop(owner:String?=ownerRunId) {
        if(owner!=null && ownerRunId!=null && owner!=ownerRunId) return
        ownerRunId=null
        val reason = stopReason
        logLine("SESSION_STOP $reason")
        PetRuntime.update { it.copy(running = false, runState = SessionRunState.STOPPED, status = reason, nextCheckAtMs = null) }
        runCatching { updateNotification(reason) }
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        job = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun notification(text: String): Notification {
        val stop = PendingIntent.getService(
            this, 2,
            Intent(this, PetAutomationService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("QQ宠物短课 MVP")
            .setContentText(text)
            .setOngoing(true)
            .addAction(0, "停止", stop)
            .build()
    }

    private fun updateNotification(text: String) {
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFICATION_ID, notification(text))
    }

    private fun ensureChannel() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "QQ宠物MVP", NotificationManager.IMPORTANCE_LOW))
    }

    private fun logLine(message: String) {
        logEvent("SESSION", null, "none", message, if (message.startsWith("SESSION_STOP")) stopReason else null)
    }

    private fun logEvent(state: String, score: Double?, action: String, result: String, reason: String?) {
        val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
        val line = JSONObject().put("timestamp", ts).put("state", state)
            .put("matchScore", score ?: JSONObject.NULL).put("action", action)
            .put("result", result).put("stopReason", reason ?: JSONObject.NULL).toString()
        File(filesDir, "pet_mvp.log").appendText("$line\n")
        Log.i(TAG, line)
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        stopSession("应用任务被移除，MVP按Fail Closed停止")
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        job?.cancel(); job = null
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        PetRuntime.update { it.copy(running = false, runState = SessionRunState.STOPPED, nextCheckAtMs = null) }
        app.releaseDisplayRepository("PetAutomationService")
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "PetAutomationService"
        private const val CHANNEL_ID = "pet_mvp"
        private const val NOTIFICATION_ID = 10901
        private const val EXTRA_DISPLAY_ID = "display_id"
        private const val EXTRA_ROUNDS = "rounds"
        private const val EXTRA_MODE = "session_mode"
        private const val EXTRA_REQUEST_ID = "request_id"
        private const val EXTRA_PLAN_JSON = "plan_json"
        private const val ACTION_START = "com.ynk.virtualdisplay.pet.START"
        private const val ACTION_STOP = "com.ynk.virtualdisplay.pet.STOP"
        private const val ACTION_SCAN_COURSES = "com.ynk.virtualdisplay.pet.SCAN_COURSES"
        private const val MAX_WAKELOCK_MS = 8L * 60L * 60L * 1000L

        /** What the foreground notification shows while a session runs, per mode. */
        private fun modeLabel(mode: SessionMode) = when (mode) {
            SessionMode.HEALTH_CHECK -> "健康检查"
            SessionMode.CARE -> "护理"
            SessionMode.WORK -> "打工"
            SessionMode.STUDY -> "学习"
            SessionMode.FULL -> "托管"
            SessionMode.PLAN -> "方案"
        }

        fun startIntent(context: Context, displayId: Int, rounds: Int = 1, mode: SessionMode = SessionMode.FULL,
            requestId: String = java.util.UUID.randomUUID().toString()) =
            Intent(context, PetAutomationService::class.java)
                .setAction(ACTION_START).putExtra(EXTRA_DISPLAY_ID, displayId)
                .putExtra(EXTRA_ROUNDS, rounds).putExtra(EXTRA_MODE, mode.wire)
                .putExtra(EXTRA_REQUEST_ID, requestId)
        fun stopIntent(context: Context) = Intent(context, PetAutomationService::class.java).setAction(ACTION_STOP)

        /** A read-only pass over the academy course list. It spends nothing and stops itself. */
        fun scanCoursesIntent(context: Context, displayId: Int) =
            Intent(context, PetAutomationService::class.java)
                .setAction(ACTION_SCAN_COURSES).putExtra(EXTRA_DISPLAY_ID, displayId)
    }
}
