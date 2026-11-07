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
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import com.ynk.virtualdisplay.MyApplication
import com.ynk.virtualdisplay.ui.main.MainActivity
import com.ynk.virtualdisplay.floating.PetCapsuleOverlay
import com.ynk.virtualdisplay.R
import com.ynk.virtualdisplay.data.repository.ConnectionStatus
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import java.io.File
import java.text.SimpleDateFormat
import java.time.LocalTime
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
        scope.launch {
            combine(PetRuntime.state, PetManagedRuntime.state, ManualPause.state) { runtime, managed, pause ->
                Triple(runtime.running, runtime.status, ManualPause.actionLabel(runtime.running, managed.pauseBoundariesLeft, pause))
            }.distinctUntilChanged().collect { (running, status, _) ->
                if (running) updateNotification(status)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopSession("用户停止")
            ACTION_PAUSE -> {
                if (ManualPause.actionLabel(PetRuntime.state.value.running,
                        PetManagedRuntime.state.value.pauseBoundariesLeft, ManualPause.state.value) != null) {
                    ManualPause.toggle()
                } else if (job == null) stopSelf()
            }
            ACTION_SCAN_COURSES -> {
                val displayId = intent.getIntExtra(EXTRA_DISPLAY_ID, -1)
                startForeground(NOTIFICATION_ID, notification("读取课程中…"))
                launchOwned(intent) { scanCourses(displayId) }
            }
            ACTION_READ_COURSE_GAINS -> {
                val displayId = intent.getIntExtra(EXTRA_DISPLAY_ID, -1)
                startForeground(NOTIFICATION_ID, notification("读取课程收益…"))
                launchOwned(intent) { readCourseGains(displayId) }
            }
            ACTION_READ_WORK_GAINS -> {
                val displayId = intent.getIntExtra(EXTRA_DISPLAY_ID, -1)
                startForeground(NOTIFICATION_ID, notification("读取岗位收益…"))
                launchOwned(intent) { readWorkGains(displayId) }
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
                        val runRoot=File(filesDir,"plan-runs")
                        val v2=PlanRunPendingGate.blockingRunId(runRoot)
                        when {
                            v2!=null && InterruptedRunClosure.finalizable(checkNotNull(FileRunStore(runRoot).load(v2))) ->
                                launchCommittedPlanFinalization(intent,v2)
                            v2==null && writebackRunner.isRetainedWorkAttempt(blockingId) ->
                                launchRetainedWorkWriteback(intent,blockingId)
                            else -> when(val recovery=InterruptedAttemptRecovery(filesDir,
                                PersistentBudgetStore(File(filesDir,PersistentBudgetStore.FILE_NAME))).runAndCloseRuns(runRoot,System.currentTimeMillis())) {
                                is InterruptedAttemptRecovery.Outcome.Reconciled -> {
                                    logLine("INTERRUPTED_ATTEMPT_RECONCILED ${recovery.attemptIds}")
                                    launchOwned(intent,allowReconciliation=false,block=block)
                                }
                                is InterruptedAttemptRecovery.Outcome.StillRunning -> {
                                    val message="上一个任务被中断，游戏里可能还在进行，约 ${recovery.remainingMs/60_000+1} 分钟后再试"
                                    // Without this the rejected tap looks like a dead button: the home screen only says 手动停止.
                                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                                        android.widget.Toast.makeText(applicationContext,message,android.widget.Toast.LENGTH_LONG).show()
                                    }
                                    stopSession(message)
                                }
                                InterruptedAttemptRecovery.Outcome.NotApplicable -> launchLegacyCareObservation(intent,block)
                            }
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
                val store = CourseCatalogStore(this)
                val existing = store.load()
                check(existing == null || existing.mayBeReplacedBy(catalog, System.currentTimeMillis())) {
                    "COURSE_SCAN_INCOMPLETE:READ_${catalog.courses.size}_OF_${existing?.courses?.size}"
                }
                store.save(catalog)
                navigation.tap(profile.courseListProbe, android.graphics.PointF(80f, 160f), flow.academy, "scan-close-panel")
                navigation.tap(flow.academy, android.graphics.PointF(80f, 160f), flow.outdoor, "scan-leave-academy")
                navigation.returnHomeFromMap("scan")
                stopReason = "课程读取完成 ${catalog.courses.size} 门（可选 ${catalog.unlocked().size} 门）"
            } finally { capture.stop() }
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            Log.e(TAG, "course scan failed", t)
            stopSession(courseScanFailureText(t))
        }
    }

    /** The reason a user can act on; the raw exception name is kept only for unknown failures. */
    private fun courseScanFailureText(t: Throwable): String {
        val m = t.message.orEmpty()
        return when {
            m.contains("PHONE_LOCKED") -> "读取课程失败：手机处于锁屏状态。解锁后重新读取，并把自动锁屏时间调长"
            m.contains("not managed") || m.contains("disappeared") ->
                "读取课程失败：后台画面不可用，请到「设置 → 后台画面」重新创建"
            t is TimeoutCancellationException -> "读取课程失败：等待后台画面超时，请确认 Shizuku 已启动"
            m.contains("INITIAL_PAGE_UNKNOWN") -> "读取课程失败：QQ 没有停在宠物房间。请在后台画面里手动进入宠物房间，再点「读取我的课程」"
            m.contains("COURSE_SCAN_INCOMPLETE") -> "读取课程不完整（$m），请重新读取"
            else -> "读取课程失败：${t.javaClass.simpleName}: $m"
        }
    }

    /**
     * STR-10：逐门打开课程详情页，读学费、时长和页面上写明的属性收益，存成候选课程比较数据。
     *
     * 只读：只点课程卡片和返回，不点开课/购买按钮，不花金币。每读完一门就落盘，
     * 中途失败时已读到的课程仍然保留，用户可以直接重试。课表缺失或过期时先补一次读取。
     */
    private suspend fun readCourseGains(displayId: Int) {
        if (displayId <= 0) return stopSession("拒绝默认/无效 displayId")
        try {
            withTimeout(7_000L) {
                app.displayRepository.connectionStatus.first { it == ConnectionStatus.CONNECTED }
                app.displayRepository.managedDisplayIds.first { displayId in it }
            }
            verifyDisplay(displayId)
            acquireWakeLock()
            PetRuntime.update { it.copy(running = true, runState = SessionRunState.RUNNING,
                displayId = displayId, status = "读取课程收益…") }
            val capture = HeadlessDisplayCapture(app.displayRepository, displayId, profile.displayWidth, profile.displayHeight)
            val input = PetInputController(app.displayRepository, displayId, profile.displayWidth, profile.displayHeight, capture.facts)
            val evidence = File(getExternalFilesDir(null), "pet_mvp_session/${System.currentTimeMillis()}")
            capture.start()
            try {
                val flow = PetFlowContext(this, capture, input, evidence, { verifyDisplay(displayId) }, ::logEvent)
                val reader = MlKitCourseNameReader()
                val navigation = PetNavigation(flow, DeviceWorkPageReader(flow, reader))
                flow.state = "INITIAL_NAVIGATION"
                navigation.initialise()
                val catalogStore = CourseCatalogStore(this)
                val existing = catalogStore.load()
                val catalog = if (existing != null && existing.isUsable(System.currentTimeMillis())) existing
                    else {
                        navigation.enterOutdoor("course-gain-scan-outdoor")
                        val scanned = CourseScanFlow(flow, navigation, reader).scan()
                        check(existing == null || existing.mayBeReplacedBy(scanned, System.currentTimeMillis())) {
                            "COURSE_SCAN_INCOMPLETE:READ_${scanned.courses.size}_OF_${existing?.courses?.size}"
                        }
                        catalogStore.save(scanned)
                        navigation.tap(profile.courseListProbe, android.graphics.PointF(80f, 160f), flow.academy, "course-gain-scan-close-panel")
                        navigation.tap(flow.academy, android.graphics.PointF(80f, 160f), flow.outdoor, "course-gain-scan-leave-academy")
                        navigation.returnHomeFromMap("course-gain-scan")
                        scanned
                    }
                val targets = catalog.unlocked()
                check(targets.isNotEmpty()) { "COURSE_SCAN_EMPTY" }
                val store = CourseReadingStore(this)
                val readings = runCatching { store.load() }.getOrDefault(emptyMap()).toMutableMap()
                for ((index, course) in targets.withIndex()) {
                    PetRuntime.update { it.copy(status = "读取课程收益 ${index + 1}/${targets.size}：${course.name}") }
                    val plan = SessionPlan(courseId = course.id)
                    navigation.enterOutdoor("course-gain-outdoor")
                    val selected = CourseSelector(flow, navigation, reader).select(plan)
                    val timing = CourseTiming.resolve(plan, catalog, System.currentTimeMillis())
                    timing.confirmDetail(selected.name, selected.durationMinutes ?: error("COURSE_DURATION_UNREAD"))
                    val page = readCoursePageReading(flow, reader)
                    readings[course.id] = CourseReading(
                        courseId = course.id,
                        courseName = course.name,
                        durationMinutes = timing.durationMinutes,
                        tuitionCoins = page.cost.tuitionCoins,
                        attributeGain = page.attributeGain,
                        readAtMs = System.currentTimeMillis(),
                    )
                    store.save(readings.values)
                    navigation.tap(profile.courseListProbe, android.graphics.PointF(80f, 160f), flow.academy, "course-gain-close-course")
                    navigation.tap(flow.academy, android.graphics.PointF(80f, 160f), flow.outdoor, "course-gain-leave-academy")
                    navigation.returnHomeFromMap("course-gain")
                }
                stopReason = "课程收益读取完成 ${targets.size} 门"
            } finally { capture.stop() }
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            Log.e(TAG, "course gains failed", t)
            stopSession(courseGainFailureText(t))
        }
    }

    /** 用户能照着做的失败原因；原始异常名只留在日志里。 */
    private fun courseGainFailureText(t: Throwable): String {
        val m = t.message.orEmpty()
        return when {
            m.contains("PHONE_LOCKED") -> "读取课程收益失败：手机处于锁屏状态。解锁后重新读取，并把自动锁屏时间调长"
            m.contains("not managed") || m.contains("disappeared") ->
                "读取课程收益失败：后台画面不可用，请到「设置 → 后台画面」重新创建"
            m.contains("COURSE_COST_UNREADABLE") ->
                "读取课程收益失败：课程详情页的学费读不出来。请确认页面显示正常后重试；已读到的课程会保留"
            m.contains("COURSE_NOT_FOUND") || m.contains("COURSE_MISMATCH") ->
                "读取课程收益失败：课程页和课表对不上。请先重新读取课表再试"
            t is TimeoutCancellationException -> "读取课程收益失败：等待后台画面超时，请确认 Shizuku 已启动"
            else -> "读取课程收益失败：${m.ifEmpty { t.javaClass.simpleName }}。已读到的课程会保留，可重试"
        }
    }

    /**
     * STR-11：逐家打开目录里已知的职业，选中每个时长档但绝不开工，现场读金币/工分/消耗。
     *
     * 只读：不点「去打工」，不花金币、不消耗体力。每读完一个岗位就落盘；
     * 某一家职业没开放或没找到时记下原因继续下一家，读完一家都会安全走回房间。
     * 一个岗位都没读到时以失败结束，界面仍保留原来的安全默认岗位。
     */
    private suspend fun readWorkGains(displayId: Int) {
        if (displayId <= 0) return stopSession("拒绝默认/无效 displayId")
        try {
            withTimeout(7_000L) {
                app.displayRepository.connectionStatus.first { it == ConnectionStatus.CONNECTED }
                app.displayRepository.managedDisplayIds.first { displayId in it }
            }
            verifyDisplay(displayId)
            acquireWakeLock()
            PetRuntime.update { it.copy(running = true, runState = SessionRunState.RUNNING,
                displayId = displayId, status = "读取岗位收益…") }
            val capture = HeadlessDisplayCapture(app.displayRepository, displayId, profile.displayWidth, profile.displayHeight)
            val input = PetInputController(app.displayRepository, displayId, profile.displayWidth, profile.displayHeight, capture.facts)
            val evidence = File(getExternalFilesDir(null), "pet_mvp_session/${System.currentTimeMillis()}")
            capture.start()
            try {
                val flow = PetFlowContext(this, capture, input, evidence, { verifyDisplay(displayId) }, ::logEvent)
                val reader = MlKitCourseNameReader()
                val workPageReader = DeviceWorkPageReader(flow, reader)
                val navigation = PetNavigation(flow, workPageReader, roomExitReader = reader)
                val workNavigation = PetWorkNavigation(flow.asWorkSink(), workPageReader,
                    tap = { point, settle ->
                        flow.safety()
                        flow.input.tap(point.x, point.y)
                        kotlinx.coroutines.delay(settle)
                    },
                    swipe = { x1, y1, x2, y2, ms ->
                        flow.safety()
                        val points = ArrayList<android.graphics.PointF>()
                        for (i in 0..CourseScanFlow.FLING_STEPS) {
                            val t = i.toFloat() / CourseScanFlow.FLING_STEPS
                            points += android.graphics.PointF(x1 + (x2 - x1) * t, y1 + (y2 - y1) * t)
                        }
                        flow.input.drag(points, ms / CourseScanFlow.FLING_STEPS)
                    })
                flow.state = "INITIAL_NAVIGATION"
                navigation.initialise()
                val catalog = WorkCatalog()
                val venues = catalog.all()
                    .filter { it.confidence != RewardConfidence.UNKNOWN }
                    .groupBy { it.venue }
                check(venues.isNotEmpty()) { "WORK_CATALOG_EMPTY" }
                val store = WorkReadingStore(this)
                val readings = runCatching { store.load() }.getOrDefault(emptyMap()).toMutableMap()
                val failures = mutableListOf<String>()
                var readNow = 0
                for ((venue, entries) in venues) {
                    PetRuntime.update { it.copy(status = "读取岗位收益：$venue") }
                    navigation.enterOutdoor("work-gain-outdoor")
                    workNavigation.enterCareerTown()
                    try {
                        workNavigation.navigateToVenue(venue)
                        for (entry in entries) {
                            PetRuntime.update { it.copy(status = "读取岗位收益：$venue · ${entry.optionLabel}") }
                            val selected = workNavigation.selectWorkOption(venue, entry.optionLabel, entry.durationMinutes)
                            val row = selected.rewardRow
                            if (row.unreadFields.isNotEmpty()) {
                                failures += "${entry.optionLabel}（${row.unreadFields.joinToString("、")}）"
                                continue
                            }
                            readings[entry.id] = WorkReading(
                                optionId = entry.id,
                                venue = venue,
                                optionLabel = entry.optionLabel,
                                durationMinutes = row.durationMinutes,
                                coinReward = row.pageCoinReward,
                                workScoreReward = row.pageWorkScoreReward,
                                energyCost = row.pageEnergyCost,
                                cleanlinessCost = row.pageCleanlinessCost,
                                readAtMs = System.currentTimeMillis(),
                            )
                            store.save(readings.values)
                            readNow++
                        }
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (t: Throwable) {
                        failures += "$venue：${workGainVenueReason(t)}"
                        flow.emit("work-gain-venue-failed", result = "FAIL", reason = "$venue:${t.message}")
                    } finally {
                        // A failed walk-back must not throw away the venues already read; record
                        // it and let the next venue try (it reports its own reason if stuck).
                        try {
                            navigation.leaveTownAfterRefusedWork()
                        } catch (ce: CancellationException) {
                            throw ce
                        } catch (t: Throwable) {
                            failures += "$venue：页面没能安全回到房间（${workGainVenueReason(t)}）"
                        }
                    }
                }
                if (readNow == 0) {
                    stopSession("读取岗位收益失败：没有读到任何岗位（${failures.joinToString("；")}）。可重试")
                } else {
                    stopReason = "岗位收益读取完成 $readNow 个" +
                        if (failures.isEmpty()) "" else "；未读到：${failures.joinToString("；")}"
                }
            } finally { capture.stop() }
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            Log.e(TAG, "work gains failed", t)
            stopSession(workGainFailureText(t))
        }
    }

    private fun workGainVenueReason(t: Throwable): String {
        val m = t.message.orEmpty()
        return when {
            m.contains("CAREER_VENUE_NAVIGATION_FAILED") -> "没找到这家职业（可能未开放）"
            m.contains("WORK_OPTION_CARD_NOT_FOUND") -> "没找到岗位卡片"
            m.contains("WORK_OPTION_NOT_SELECTED") -> "岗位卡片没有选中"
            m.contains("WORK_DETAIL_PAGE_UNCONFIRMED") -> "岗位详情页未确认"
            m.contains("WORK_REWARD_ROW_UNREAD") -> "奖励行没读全"
            else -> m.ifEmpty { t.javaClass.simpleName }.take(80)
        }
    }

    /** 用户能照着做的失败原因；原始异常名只留在日志里。 */
    private fun workGainFailureText(t: Throwable): String {
        val m = t.message.orEmpty()
        return when {
            m.contains("PHONE_LOCKED") -> "读取岗位收益失败：手机处于锁屏状态。解锁后重新读取，并把自动锁屏时间调长"
            m.contains("not managed") || m.contains("disappeared") ->
                "读取岗位收益失败：后台画面不可用，请到「设置 → 后台画面」重新创建"
            m.contains("CAREER_TOWN_ENTRY_NOT_FOUND") || m.contains("CAREER_TOWN_PAGE_UNCONFIRMED") ->
                "读取岗位收益失败：没找到职业小镇入口。请确认 QQ 已停在宠物房间后重试"
            t is TimeoutCancellationException -> "读取岗位收益失败：等待后台画面超时，请确认 Shizuku 已启动"
            else -> "读取岗位收益失败：${m.ifEmpty { t.javaClass.simpleName }}。已读到的岗位会保留，可重试"
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

    /**
     * 返回仍在冷却期的剩余分钟数；null 表示不在冷却期（允许启动）。
     * 只读取已结束的 run 记录；没有 endedAtMs 的记录不参与判定。
     */
    private fun planFailureCooldown(plan: PlanV2): Long? {
        val root = File(filesDir, "plan-runs")
        val store = FileRunStore(root)
        val history = (root.listFiles { f -> f.isFile && f.name.endsWith(".json") } ?: return null)
            .mapNotNull { file ->
                val runId = file.name.removeSuffix(".json")
                if (!Regex("[A-Za-z0-9_-]{1,128}").matches(runId)) return@mapNotNull null
                val snapshot = store.load(runId) ?: return@mapNotNull null
                FailureCooldownPolicy.Snapshot(snapshot.request.planSnapshot.planId, snapshot.state, snapshot.endedAtMs)
            }
        val decision = FailureCooldownPolicy.evaluate(history, plan.planId, plan.failureCooldownMinutes, System.currentTimeMillis())
        return (decision as? FailureCooldownPolicy.Decision.Cooling)?.remainingMinutes
    }

    /** 这次会话会做哪些动作；只有会扣费或会领奖的动作才需要中断确认。 */
    private fun requestedStepTypes(mode: SessionMode, selected: PlanV2?): Set<PlanStepType> = when {
        selected != null -> selected.steps.map { it.type }.toSet()
        mode.runsStudy -> setOf(PlanStepType.STUDY)
        else -> emptySet()
    }

    /** 给用户看的动作名字，不出现英文代号。 */
    private fun pendingActionLabel(type: PlanStepType): String = when (type) {
        PlanStepType.STUDY -> "学习"
        PlanStepType.SIGN_IN -> "签到"
        PlanStepType.MAILBOX -> "信箱"
        PlanStepType.LUCKY_BAG -> "福袋"
        else -> type.name
    }

    private suspend fun runSession(displayId: Int, rounds: Int, mode: SessionMode, selected: PlanV2?=null,
        requestId:String?=null) {
        val errors = profile.validate(healthGuardInstalled=true)
        if (errors.isNotEmpty()) return stopSession("配置未完成：${errors.joinToString()}")
        if (displayId <= 0) return stopSession("拒绝默认/无效 displayId")
        // STR-17 夜间暂停窗口：窗口内不开始新的托管。
        val quietWindow=QuietWindowStore(this).load()
        val quietDecision=SessionStartGate.evaluate(quietWindow,QuietWindowPolicy.nowMinute(LocalTime.now()),mode)
        if(quietDecision is SessionStartGate.Decision.Refused) {
            logLine("SESSION_START_REFUSED QUIET_WINDOW_ACTIVE")
            val message=quietDecision.message
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                android.widget.Toast.makeText(applicationContext,message,android.widget.Toast.LENGTH_LONG).show()
            }
            return stopSession(message)
        }
        // 中断后先确认：上一次的动作结果未知时，不直接再来一次（可能重复扣费或重复领取）。
        val pending=PendingActionGuard.pendingActions(DailyBudgetStore(this).attempts())
        val unconfirmed=requestedStepTypes(mode,selected).flatMap { type ->
            val decision=PendingActionGuard.decide(pending,type)
            if (decision is PendingActionDecision.NeedsConfirmation) decision.actions else emptyList()
        }
        if (unconfirmed.isNotEmpty()) {
            val names=unconfirmed.map { pendingActionLabel(it.stepType) }.distinct().joinToString("、")
            logLine("SESSION_START_REFUSED PENDING_ACTION_UNCONFIRMED:" +
                unconfirmed.map { it.stepType.name }.distinct().joinToString(","))
            val message="上一次"+names+"被中断，是否已扣费或已领取无法确认。为避免重复执行，请先在「运行方案」页确认。"
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                android.widget.Toast.makeText(applicationContext,message,android.widget.Toast.LENGTH_LONG).show()
            }
            return stopSession(message)
        }
        // A HEALTH_CHECK session has no rounds to run, so the 1..10 batch rule does not apply to it.
        if (mode.runsStudy && rounds !in 1..10) return stopSession("Only finite 1..10 round batches are supported")
        // STR-15: 失败后冷却。同方案最近一次失败/未确认结束后仍在冷却期内，拒绝重开以免高频空转。
        if (selected != null && selected.failureCooldownMinutes != null) {
            val cooldown = planFailureCooldown(selected)
            if (cooldown != null) {
                return stopSession("上一次任务失败后仍在冷却期，请等待 " + cooldown + " 分钟再试")
            }
        }
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
            ensureQqOnDisplay(displayId)
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
                override fun pendingActions(): List<PendingAction> =
                    PendingActionGuard.pendingActions(budget.attempts())
                override fun confirmedDoneCount(day: String, type: PlanStepType): Int =
                    PendingActionGuard.confirmedDoneCount(budget.attempts(), day, type)
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
                PetCapsuleOverlay.show(this)
                ManualPause.release()
                PetRuntime.update {it.copy(running=true,runState=SessionRunState.RUNNING,displayId=displayId,cycles=0,status=modeLabel(mode))}
                val verdict=controller.run()
                // Each independent entry has its OWN success verdict; only 托管 counts rounds. A
                // successful WORK_PASS/CARE_PASS/HEALTH_CHECK_PASS must not be reported as STOPPED
                // while the controller itself marked the run COMPLETED (stopReason null).
                stopReason=when(verdict) {
                    // controller.completed adds study and adventure together, which read as "study=2/1" for
                    // one lesson plus one adventure; each action reports its own count instead.
                    "PLAN_PASS" -> controller.planRunSnapshot?.counts.let { c ->
                        "PLAN_PASS work=${c?.workShiftsCompleted ?: 0} care=${c?.careCompleted ?: 0} " +
                            "study=${c?.studyRoundsCompleted ?: 0} adventure=${c?.adventureCompleted ?: 0}"
                    }
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

    /**
     * QQ gone from every screen (closed, killed, or lost with a restarted screen service) is the one
     * start state the user cannot see coming: the pet screen is simply black. The app already knows
     * how to put QQ on it (the same launch 一键创建 does), so it does that instead of failing on a
     * capture error 20 s later. Never when QQ is on any screen: see [PetDisplayPicker.shouldRelaunchQq].
     * Best effort: a failure here only logs, and the normal entry chain then reports what it sees.
     */
    private suspend fun ensureQqOnDisplay(displayId: Int) {
        val repo = app.displayRepository
        val hosting = repo.displaysHosting(PetDisplayPicker.QQ_PACKAGE).getOrNull() ?: return
        if (!PetDisplayPicker.shouldRelaunchQq(hosting)) return
        logLine("QQ_NOT_ON_ANY_DISPLAY display=$displayId, launching it on the pet screen")
        val launched = repo.launchApp(PetDisplayPicker.QQ_PACKAGE, displayId)
        if (launched.isFailure) {
            logLine("QQ_RELAUNCH_FAILED ${launched.exceptionOrNull()?.message}")
            return
        }
        // QQ takes a few seconds to draw; the entry chain's own settle logic does the rest.
        val deadline = SystemClock.elapsedRealtime() + QQ_RELAUNCH_WAIT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            delay(QQ_RELAUNCH_POLL_MS)
            val now = repo.displaysHosting(PetDisplayPicker.QQ_PACKAGE).getOrNull().orEmpty()
            if (displayId in now) {
                logLine("QQ_RELAUNCHED display=$displayId")
                delay(QQ_RELAUNCH_DRAW_MS)
                return
            }
        }
        logLine("QQ_RELAUNCH_NOT_CONFIRMED display=$displayId")
    }

    @Suppress("DEPRECATION")
    private fun verifyDisplay(displayId: Int) {
        val dm = getSystemService(DISPLAY_SERVICE) as DisplayManager
        val d = dm.getDisplay(displayId) ?: error("virtual display disappeared")
        val p = Point(); d.getRealSize(p)
        val metrics = android.util.DisplayMetrics(); d.getRealMetrics(metrics)
        check(metrics.densityDpi == 320) { "display density must be 320dpi" }
        check(p.x == profile.displayWidth && p.y == profile.displayHeight) {
            "display must be ${profile.displayWidth}x${profile.displayHeight}, actual=${p.x}x${p.y}"
        }
        check(app.displayRepository.managedDisplayIds.value.contains(displayId)) {
            "display is not managed by current Shizuku service"
        }
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        // Keep the screen on, not just the CPU: a lock during a 10-minute shift or course ends the
        // run with CHECKPOINT_ACTION_UNCONFIRMED, and the default screen timeout is exactly that long.
        @Suppress("DEPRECATION")
        wakeLock = pm.newWakeLock(PowerManager.SCREEN_DIM_WAKE_LOCK, "$packageName:pet-mvp").apply {
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
        PetCapsuleOverlay.hide()
        val reason = stopReason
        logLine("SESSION_STOP $reason")
        ManualPause.release()
        PetRuntime.update { it.copy(running = false, runState = SessionRunState.STOPPED, status = reason, nextCheckAtMs = null) }
        runCatching { updateNotification(reason) }
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        job = null
        pruneEvidence()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** Off the main thread: the first pass after an update can delete thousands of old frames. */
    private fun pruneEvidence() {
        val sessions=getExternalFilesDir(null)?.let {File(it,"pet_mvp_session")} ?: return
        Thread {
            runCatching {
                val pending=PersistentBudgetStore(File(filesDir,PersistentBudgetStore.FILE_NAME)).read().attempts.values
                    .any(CarePendingReconciliation::unresolved) || PlanRunPendingGate.blockingRunId(File(filesDir,"plan-runs"))!=null
                if(!pending) {
                    EvidenceRetention.prune(sessions) {job==null}
                    EvidenceRetention.trimLog(File(filesDir,"pet_mvp.log"))
                }
            }
        }.start()
    }

    private fun notification(text: String): Notification {
        // 点通知回到 App：停止后的失败提示不能只是一句话，得有一个能走下去的入口。
        val open = PendingIntent.getActivity(
            this, 1,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 2,
            Intent(this, PetAutomationService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_crab)
            .setContentTitle("蟹啦")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(open)
            .apply {
                ManualPause.actionLabel(PetRuntime.state.value.running,
                    PetManagedRuntime.state.value.pauseBoundariesLeft, ManualPause.state.value)?.let { label ->
                    val pause = PendingIntent.getService(this@PetAutomationService, 3,
                        Intent(this@PetAutomationService, PetAutomationService::class.java).setAction(ACTION_PAUSE),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                    addAction(0, label, pause)
                }
            }
            .addAction(0, "停止", stop)
            .build()
    }

    private fun updateNotification(text: String) {
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFICATION_ID, notification(text))
    }

    private fun ensureChannel() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "托管进度", NotificationManager.IMPORTANCE_LOW))
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
        PetCapsuleOverlay.hide()
        job?.cancel(); job = null
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        ManualPause.release()
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
        private const val ACTION_PAUSE = "com.ynk.virtualdisplay.pet.PAUSE"
        private const val ACTION_SCAN_COURSES = "com.ynk.virtualdisplay.pet.SCAN_COURSES"
        private const val ACTION_READ_COURSE_GAINS = "com.ynk.virtualdisplay.pet.READ_COURSE_GAINS"
        private const val ACTION_READ_WORK_GAINS = "com.ynk.virtualdisplay.pet.READ_WORK_GAINS"
        private const val MAX_WAKELOCK_MS = 8L * 60L * 60L * 1000L

        /** Relaunching QQ onto a pet screen it has left: how long to wait for it to appear, and to draw. */
        private const val QQ_RELAUNCH_WAIT_MS = 10_000L
        private const val QQ_RELAUNCH_POLL_MS = 500L
        private const val QQ_RELAUNCH_DRAW_MS = 2_500L

        /** What the foreground notification shows while a session runs, per mode. */
        private fun modeLabel(mode: SessionMode) = when (mode) {
            SessionMode.HEALTH_CHECK -> "健康检查"
            SessionMode.CARE -> "护理"
            SessionMode.WORK -> "打工"
            SessionMode.STUDY -> "学习"
            SessionMode.ADVENTURE -> "冒险"
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

        /** A read-only pass over every unlocked course's detail page. It spends nothing. */
        fun readCourseGainsIntent(context: Context, displayId: Int) =
            Intent(context, PetAutomationService::class.java)
                .setAction(ACTION_READ_COURSE_GAINS).putExtra(EXTRA_DISPLAY_ID, displayId)

        /** A read-only pass over the known jobs' reward rows. It never starts work. */
        fun readWorkGainsIntent(context: Context, displayId: Int) =
            Intent(context, PetAutomationService::class.java)
                .setAction(ACTION_READ_WORK_GAINS).putExtra(EXTRA_DISPLAY_ID, displayId)
    }
}
