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
            ACTION_START -> {
                val displayId = intent.getIntExtra(EXTRA_DISPLAY_ID, -1)
                startForeground(NOTIFICATION_ID, notification("正在初始化…"))
                if (job?.isActive == true) return START_NOT_STICKY
                stopReason = "会话结束"
                val newJob = scope.launch { runSession(displayId) }
                job = newJob
                newJob.invokeOnCompletion {
                    scope.launch(Dispatchers.Main) { finalizeStop() }
                }
            }
        }
        return START_NOT_STICKY
    }

    private suspend fun runSession(displayId: Int) {
        val errors = profile.validate()
        if (errors.isNotEmpty()) return stopSession("配置未完成：${errors.joinToString()}")
        if (displayId <= 0) return stopSession("拒绝默认/无效 displayId")

        try {
            withTimeout(7_000L) {
                app.displayRepository.connectionStatus.first { it == ConnectionStatus.CONNECTED }
            }
            verifyDisplay(displayId)
            acquireWakeLock()

            val budget = DailyBudgetStore(this)
            val capture = HeadlessDisplayCapture(app.displayRepository, displayId, profile.displayWidth, profile.displayHeight)
            val input = PetInputController(app.displayRepository, displayId, profile.displayWidth, profile.displayHeight)
            val matcher = TemplateMatcher(this)
            val fsm = PetStateMachine(capture, input, matcher, profile, ::logLine)

            capture.start()
            try {
                var cyclesStarted = 0
                PetRuntime.update { it.copy(running = true, displayId = displayId, status = "检查空闲页并启动第一节短课") }
                logLine("SESSION_START display=$displayId used=${budget.usedMinutes()}min")

                // User pressing Start authorizes this bounded MVP session. The first action still
                // requires the deterministic FINISHED probe; otherwise the FSM fails closed.
                val first = fsm.runOneCycle()
                if (!first.ok) {
                    stopSession("无法从课程结束/空闲页启动：${first.message}")
                    return
                }
                cyclesStarted += 1
                PetRuntime.update { it.copy(cycles = cyclesStarted, status = "第1节短课已启动") }
                logLine("CYCLE_START count=$cyclesStarted used=${budget.usedMinutes()}min")

                while (currentCoroutineContext().isActive) {
                    if (cyclesStarted >= MAX_CYCLES_PER_SESSION) {
                        stopSession("达到MVP单会话 $MAX_CYCLES_PER_SESSION 轮安全上限")
                        return
                    }

                    val usedBefore = budget.usedMinutes()
                    if (usedBefore >= DailyBudgetStore.HARD_STOP_MINUTES) {
                        stopSession("达到8小时MVP保护线")
                        return
                    }

                    val next = System.currentTimeMillis() + profile.courseDurationMs + profile.courseFinishGraceMs
                    PetRuntime.update { it.copy(nextCheckAtMs = next, status = "学习中，等待到点") }
                    updateNotification("学习中 · 本App今日已记录 ${usedBefore} 分钟")
                    delay(profile.courseDurationMs + profile.courseFinishGraceMs)

                    // Only completed automated courses are booked into the local fatigue ledger.
                    budget.addCourse(10)
                    val usedNow = budget.usedMinutes()
                    if (usedNow >= DailyBudgetStore.HARD_STOP_MINUTES) {
                        stopSession("完成本轮后达到8小时MVP保护线")
                        return
                    }

                    PetRuntime.update { it.copy(status = "到点检查并续下一节") }
                    val result = fsm.runOneCycle()
                    if (!result.ok) {
                        stopSession("识别/操作失败，已Fail Closed：${result.message}")
                        return
                    }
                    cyclesStarted += 1
                    PetRuntime.update { it.copy(cycles = cyclesStarted, status = "第 $cyclesStarted 节短课已启动") }
                    logLine("CYCLE_START count=$cyclesStarted used=${budget.usedMinutes()}min")
                }
            } finally {
                capture.stop()
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
        PetRuntime.update { it.copy(running = false, status = reason, nextCheckAtMs = null) }
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
        val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
        runCatching { File(filesDir, "pet_mvp.log").appendText("$ts $message\n") }
        Log.i(TAG, message)
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        stopSession("应用任务被移除，MVP按Fail Closed停止")
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        job?.cancel(); job = null
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        PetRuntime.update { it.copy(running = false, nextCheckAtMs = null) }
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
        private const val ACTION_START = "com.ynk.virtualdisplay.pet.START"
        private const val ACTION_STOP = "com.ynk.virtualdisplay.pet.STOP"
        private const val MAX_WAKELOCK_MS = 8L * 60L * 60L * 1000L
        private const val MAX_CYCLES_PER_SESSION = 20

        fun startIntent(context: Context, displayId: Int) = Intent(context, PetAutomationService::class.java)
            .setAction(ACTION_START).putExtra(EXTRA_DISPLAY_ID, displayId)
        fun stopIntent(context: Context) = Intent(context, PetAutomationService::class.java).setAction(ACTION_STOP)
    }
}
