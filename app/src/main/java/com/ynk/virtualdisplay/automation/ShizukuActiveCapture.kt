package com.ynk.virtualdisplay.automation

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.IBinder
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import com.ynk.virtualdisplay.BuildConfig
import com.ynk.virtualdisplay.ICaptureService
import com.ynk.virtualdisplay.core.ipc.CaptureUserService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import rikka.shizuku.Shizuku

data class ActiveCapturedFrame(val bitmap: Bitmap, val displayId: Int, val byteCount: Int,
    val captureDurationMs: Long, val durationMs: Long, val source: String, val uid: Int)

/** Binary FD transport. The existing display-owning UserService is never restarted. */
class ShizukuActiveCapture(private val context: Context) {
    private val events by lazy { SessionEventStore(java.io.File(context.filesDir,"pet-events"),
        "capture-"+java.util.UUID.randomUUID(),AndroidEventClock()) }
    private var service: ICaptureService? = null
    private var ready = CompletableDeferred<ICaptureService>()
    private var bound = false
    private val args = Shizuku.UserServiceArgs(ComponentName(context.packageName, CaptureUserService::class.java.name))
        .daemon(false).processNameSuffix("capture_service").debuggable(BuildConfig.DEBUG).version(1)
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val connected = ICaptureService.Stub.asInterface(binder)
            service = connected; ready.complete(connected)
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            service = null; ready = CompletableDeferred(); bound = false
        }
    }

    suspend fun captureDisplay(displayId: Int): ActiveCapturedFrame {
        try { return captureChecked(displayId) }
        catch(t: Exception) {
            if(t is kotlinx.coroutines.CancellationException) throw t
            events.append(EventSource.GAME_OBSERVATION,EventKind.FRAME_INVALID,
                mapOf("displayId" to displayId,"captureSource" to "SHIZUKU_SCREENCAP",
                    "status" to FrameLiveness.failure(t.toString()).status.name,"error" to t.toString()))
            throw t
        }
    }

    private suspend fun captureChecked(displayId: Int): ActiveCapturedFrame {
        require(displayId > 0) { "DEFAULT_DISPLAY_REFUSED" }
        check(Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) { "SHIZUKU_PERMISSION_DENIED" }
        val began = SystemClock.elapsedRealtime()
        if (!bound) {
            bound = true
            Shizuku.bindUserService(args, connection)
        }
        val svc = service ?: withTimeout(15000) { ready.await() }
        return withContext(Dispatchers.IO) {
            val r = Bundle()
            val fd = svc.captureDisplay(displayId, r)
            if (!r.getBoolean("ok")) { fd?.close(); error("SHIZUKU_CAPTURE_FAILED:${r.getString("error")}") }
            check(r.getBoolean("ok")) { "SHIZUKU_CAPTURE_FAILED:${r.getString("error")}" }
            check(r.getInt("displayId") == displayId && r.getInt("uid") == 2000) { "CAPTURE_IDENTITY_MISMATCH" }
            val bytes = ParcelFileDescriptor.AutoCloseInputStream(fd ?: error("CAPTURE_FD_MISSING")).use { it.readBytes() }
            check(bytes.size == r.getInt("byteCount") && bytes.size <= 8 * 1024 * 1024) { "CAPTURE_LENGTH_MISMATCH" }
            check(bytes.size >= 24 && bytes.copyOfRange(0, 8).contentEquals(
                byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 13, 10, 26, 10))) { "CAPTURE_INVALID_PNG" }
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: error("CAPTURE_DECODE_FAILED")
            try {
                check(bitmap.width == r.getInt("width") && bitmap.height == r.getInt("height")) { "CAPTURE_SIZE_MISMATCH" }
                val live=FrameLiveness.inspect(displayId,bitmap.width,bitmap.height,r.getInt("width"),r.getInt("height"),
                    pixel=bitmap::getPixel)
                check(live.valid) { "CAPTURE_${live.status}" }
                events.append(EventSource.GAME_OBSERVATION,EventKind.FRAME_VALID,
                    mapOf("displayId" to displayId,"status" to live.status.name,"captureSource" to "SHIZUKU_SCREENCAP",
                        "width" to bitmap.width,"height" to bitmap.height,"byteCount" to bytes.size))
                ActiveCapturedFrame(bitmap, displayId, bytes.size, r.getLong("captureDurationMs"),
                    SystemClock.elapsedRealtime() - began, r.getString("source")!!, r.getInt("uid"))
            } catch (t: Throwable) { bitmap.recycle(); throw t }
        }
    }

    fun close() {
        if (bound) runCatching { Shizuku.unbindUserService(args, connection, true) }
        bound = false; service = null; ready = CompletableDeferred()
    }
}
