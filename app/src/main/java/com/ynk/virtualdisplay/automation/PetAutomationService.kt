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
                if (job?.isActive == true) return START_NOT_STICKY
                val newJob = scope.launch { scanCourses(displayId) }
                job = newJob
                newJob.invokeOnCompletion { scope.launch(Dispatchers.Main) { finalizeStop() } }
            }
            ACTION_START -> {
                val displayId = intent.getIntExtra(EXTRA_DISPLAY_ID, -1)
                startForeground(NOTIFICATION_ID, notification("正在初始化…"))
                if (job?.isActive == true) return START_NOT_STICKY
                stopReason = "会话结束"
                val rounds = intent.getIntExtra(EXTRA_ROUNDS, 1)
                // An unknown mode name fails closed here rather than silently running a costlier
                // stage than the user selected.
                val mode = SessionMode.fromWire(intent.getStringExtra(EXTRA_MODE))
                val newJob = scope.launch { runSession(displayId, rounds, mode) }
                job = newJob
                newJob.invokeOnCompletion {
                    scope.launch(Dispatchers.Main) { finalizeStop() }
                }
            }
        }
        return START_NOT_STICKY
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
            val capture = HeadlessDisplayCapture(app.displayRepository, displayId, profile.displayWidth, profile.displayHeight)
            val input = PetInputController(app.displayRepository, displayId, profile.displayWidth, profile.displayHeight, capture.facts)
            val evidence = File(getExternalFilesDir(null), "pet_mvp_session/${System.currentTimeMillis()}")
            capture.start()
            try {
                val flow = PetFlowContext(this, capture, input, evidence, { verifyDisplay(displayId) }, ::logEvent)
                val navigation = PetNavigation(flow)
                flow.state = "INITIAL_NAVIGATION"
                navigation.initialise()
                navigation.tap(flow.room, android.graphics.PointF(360f, 1390f), flow.outdoor, "outdoor")
                val catalog = CourseScanFlow(flow, navigation, MlKitCourseNameReader()).scan()
                CourseCatalogStore(this).save(catalog)
                stopReason = "课程读取完成 ${catalog.courses.size} 门"
            } finally { capture.stop() }
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            Log.e(TAG, "course scan failed", t)
            stopSession("读取课程失败：${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private suspend fun runSession(displayId: Int, rounds: Int, mode: SessionMode) {
        val errors = profile.validate(healthGuardInstalled=true)
        if (errors.isNotEmpty()) return stopSession("配置未完成：${errors.joinToString()}")
        if (displayId <= 0) return stopSession("拒绝默认/无效 displayId")
        // A HEALTH_CHECK session has no rounds to run, so the 1..10 batch rule does not apply to it.
        if (mode.runsStudy && rounds !in 1..10) return stopSession("Only finite 1..10 round batches are supported")

        try {
            withTimeout(7_000L) {
                app.displayRepository.connectionStatus.first { it == ConnectionStatus.CONNECTED }
                // CONNECTED is published before the asynchronous ownership snapshot.
                app.displayRepository.managedDisplayIds.first { displayId in it }
            }
            verifyDisplay(displayId)
            acquireWakeLock()

            val budget = DailyBudgetStore(this)
            val plan = SessionPlanStore(this).load()
            val capture = HeadlessDisplayCapture(app.displayRepository, displayId, profile.displayWidth, profile.displayHeight)
            val input = PetInputController(app.displayRepository, displayId, profile.displayWidth, profile.displayHeight, capture.facts)
            val matcher = TemplateMatcher(this)
            val evidence = File(getExternalFilesDir(null), "pet_mvp_session/${System.currentTimeMillis()}")
            val controller=PetSessionController(this,capture,input,evidence,{verifyDisplay(displayId)},::logEvent,rounds,mode,plan,
                beforeCourse={check(budget.canAfford(plan.courseDurationMinutes(),plan.dailyBudgetMinutes)) {"DAILY_BUDGET_LIMIT"}},
                onRoundCompleted={round ->budget.addCourse(plan.courseDurationMinutes());PetRuntime.update {it.copy(cycles=round)}},
                onWorkCompleted={minutes ->budget.addCourse(minutes)})
            capture.start()
            try {
                PetRuntime.update {it.copy(running=true,runState=SessionRunState.RUNNING,displayId=displayId,cycles=0,status=modeLabel(mode))}
                val verdict=controller.run()
                stopReason=if(verdict=="MANAGED_BATCH_PASS") "BATCH_PASS ${controller.completed}/$rounds" else controller.stopReason ?: "STOPPED"
                File(evidence,"verdict.json").writeText(JSONObject().put("verdict",verdict)
                    .put("mode",mode.wire)
                    .put("completed",controller.completed).put("requested",rounds)
                    .put("stopReason",controller.stopReason ?: JSONObject.NULL)
                    .put("captureStatistics",capture.statistics()).toString())
            } finally {capture.stop()}
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
        stopReason = reason
        logLine("SESSION_STOP_REQUEST $reason")
        PetRuntime.update { it.copy(status = reason, nextCheckAtMs = null) }
        job?.cancel(CancellationException(reason))
        if (job == null) scope.launch(Dispatchers.Main) { finalizeStop() }
    }

    private fun finalizeStop() {
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
        private const val ACTION_START = "com.ynk.virtualdisplay.pet.START"
        private const val ACTION_STOP = "com.ynk.virtualdisplay.pet.STOP"
        private const val ACTION_SCAN_COURSES = "com.ynk.virtualdisplay.pet.SCAN_COURSES"
        private const val MAX_WAKELOCK_MS = 8L * 60L * 60L * 1000L

        /** What the foreground notification shows while a session runs, per mode. */
        private fun modeLabel(mode: SessionMode) = when (mode) {
            SessionMode.HEALTH_CHECK -> "健康检查"
            SessionMode.CARE -> "护理中"
            SessionMode.FULL -> "托管中"
        }

        fun startIntent(context: Context, displayId: Int, rounds: Int = 1, mode: SessionMode = SessionMode.FULL) =
            Intent(context, PetAutomationService::class.java)
                .setAction(ACTION_START).putExtra(EXTRA_DISPLAY_ID, displayId)
                .putExtra(EXTRA_ROUNDS, rounds).putExtra(EXTRA_MODE, mode.wire)
        fun stopIntent(context: Context) = Intent(context, PetAutomationService::class.java).setAction(ACTION_STOP)

        /** A read-only pass over the academy course list. It spends nothing and stops itself. */
        fun scanCoursesIntent(context: Context, displayId: Int) =
            Intent(context, PetAutomationService::class.java)
                .setAction(ACTION_SCAN_COURSES).putExtra(EXTRA_DISPLAY_ID, displayId)
    }
}
