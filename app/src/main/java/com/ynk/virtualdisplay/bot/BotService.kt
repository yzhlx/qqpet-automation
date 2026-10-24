package com.ynk.virtualdisplay.bot

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.ynk.virtualdisplay.MyApplication
import com.ynk.virtualdisplay.R
import com.ynk.virtualdisplay.automation.HeadlessDisplayCapture
import com.ynk.virtualdisplay.automation.MlKitCourseNameReader
import com.ynk.virtualdisplay.automation.PetInputController
import com.ynk.virtualdisplay.automation.TemplateMatcher
import com.ynk.virtualdisplay.data.repository.ConnectionStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.io.File

/** Runs one [Scheduler] run on the pet's virtual display, in the foreground, until done or stopped. */
class BotService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private lateinit var app: MyApplication

    override fun onCreate() {
        super.onCreate()
        app = application as MyApplication
        app.acquireDisplayRepository("BotService")
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(NotificationChannel(CHANNEL_ID, "QQ宠物托管", NotificationManager.IMPORTANCE_LOW))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> job?.cancel(CancellationException("用户停止"))
            ACTION_START -> if (job?.isActive != true) {
                val displayId = intent.getIntExtra(EXTRA_DISPLAY_ID, -1)
                val config = BotConfigCodec.decode(intent.getStringExtra(EXTRA_CONFIG) ?: "")
                startForeground(NOTIFICATION_ID, notification("运行中"))
                job = scope.launch { run(displayId, config) }
            }
        }
        return START_NOT_STICKY
    }

    private suspend fun run(displayId: Int, config: BotConfig) {
        BotRuntime.started()
        BotRuntime.log("开始：$config")
        var capture: HeadlessDisplayCapture? = null
        try {
            check(displayId > 0) { "没有找到 720x1600 的虚拟屏" }
            val repo = app.displayRepository
            withTimeout(7_000L) {
                repo.connectionStatus.first { it == ConnectionStatus.CONNECTED }
                repo.managedDisplayIds.first { displayId in it }
            }
            acquireWakeLock()
            val cap = HeadlessDisplayCapture(repo, displayId, WIDTH, HEIGHT).also { capture = it }
            val input = PetInputController(repo, displayId, WIDTH, HEIGHT, cap.facts)
            cap.start()
            val matcher = TemplateMatcher(this)
            val ocr = MlKitCourseNameReader()
            val frame = suspend { cap.awaitFrame() }
            val eyes = DeviceEyes(frame, ProbeSet(matcher), townCheck(ocr))
            val hands = DeviceHands(input) { cap.awaitFrame().recycle() }
            val gauges = DeviceGauges(this, frame, matcher, ocr)
            val navigator = Navigator(eyes, hands)
            val scheduler = Scheduler(
                CareAction(eyes, hands, gauges, navigator),
                StudyAction(eyes, hands, gauges, navigator),
                WorkAction(eyes, hands, gauges, navigator),
                navigator,
                FileLedger(File(filesDir, "bot-ledger.txt")),
                log = BotRuntime::log,
            )
            val report = scheduler.run(config)
            BotRuntime.finished(report, "完成：上课${report.lessons}节，打工${report.shifts}班，原因 ${report.stop}")
        } catch (e: CancellationException) {
            BotRuntime.finished(null, e.message ?: "已停止")
        } catch (t: Throwable) {
            BotRuntime.finished(null, "出错：${t.javaClass.simpleName}: ${t.message}")
        } finally {
            runCatching { capture?.stop() }
            wakeLock?.let { if (it.isHeld) it.release() }
            wakeLock = null
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:bot").apply {
            setReferenceCounted(false)
            acquire(MAX_RUN_MS)
        }
    }

    private fun notification(text: String): Notification {
        val stop = PendingIntent.getService(this, 3, stopIntent(this), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("QQ宠物托管")
            .setContentText(text)
            .setOngoing(true)
            .addAction(0, "停止", stop)
            .build()
    }

    override fun onDestroy() {
        job?.cancel()
        scope.cancel()
        app.releaseDisplayRepository("BotService")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val ACTION_START = "com.ynk.virtualdisplay.bot.START"
        private const val ACTION_STOP = "com.ynk.virtualdisplay.bot.STOP"
        private const val EXTRA_DISPLAY_ID = "displayId"
        private const val EXTRA_CONFIG = "config"
        private const val CHANNEL_ID = "qqpet-bot"
        private const val NOTIFICATION_ID = 42
        private const val WIDTH = 720
        private const val HEIGHT = 1600
        /** Ten lessons with a shift each stays well inside this. */
        private const val MAX_RUN_MS = 6 * 60 * 60 * 1000L

        fun startIntent(context: Context, displayId: Int, config: BotConfig) = Intent(context, BotService::class.java)
            .setAction(ACTION_START).putExtra(EXTRA_DISPLAY_ID, displayId).putExtra(EXTRA_CONFIG, BotConfigCodec.encode(config))

        fun stopIntent(context: Context) = Intent(context, BotService::class.java).setAction(ACTION_STOP)
    }
}
