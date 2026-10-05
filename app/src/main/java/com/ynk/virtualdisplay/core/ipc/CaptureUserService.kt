package com.ynk.virtualdisplay.core.ipc

import android.content.Context
import android.hardware.display.DisplayManager
import android.graphics.Point
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.annotation.Keep
import com.ynk.virtualdisplay.ICaptureService
import java.io.ByteArrayOutputStream
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/** Same Shizuku authorization; no display ownership, input, storage or PC dependency. */
class CaptureUserService @Keep constructor(private val context: Context) : ICaptureService.Stub() {
    override fun captureDisplay(displayId: Int, result: Bundle): ParcelFileDescriptor? {
        val began = SystemClock.elapsedRealtime()
        result.apply {
            putBoolean("ok", false); putInt("displayId", displayId)
            putString("source", "SHIZUKU_SCREENCAP")
            putInt("uid", android.os.Process.myUid())
        }
        var process: Process? = null
        var readFd: ParcelFileDescriptor? = null
        try {
            require(displayId > 0) { "DEFAULT_DISPLAY_REFUSED" }
            check(android.os.Process.myUid() == 2000) { "NOT_SHELL_IDENTITY" }
            val display = (context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager)
                .getDisplay(displayId) ?: error("DISPLAY_DISAPPEARED")
            val size = Point(); display.getRealSize(size)
            result.putInt("width", size.x); result.putInt("height", size.y)
            result.putString("displayName", display.name)
            val p = ProcessBuilder("/system/bin/screencap", "-d", displayId.toString(), "-p").start()
            process = p
            val stdout = FutureTask {
                p.inputStream.use { stream ->
                    val out = ByteArrayOutputStream(); val buffer = ByteArray(32768)
                    while (true) {
                        val n = stream.read(buffer); if (n < 0) break
                        check(out.size() + n <= 8 * 1024 * 1024) { "PNG_TOO_LARGE" }
                        out.write(buffer, 0, n)
                    }
                    out.toByteArray()
                }
            }
            val stderr = FutureTask { p.errorStream.use { it.readBytes().take(2048).toByteArray().toString(Charsets.UTF_8) } }
            Thread(stdout, "shizuku-capture-png").start()
            Thread(stderr, "shizuku-capture-error").start()
            check(p.waitFor(4, TimeUnit.SECONDS)) { "SCREENCAP_TIMEOUT" }
            check(p.exitValue() == 0) { "SCREENCAP_EXIT_${p.exitValue()}:${stderr.get(1, TimeUnit.SECONDS)}" }
            val bytes = stdout.get(1, TimeUnit.SECONDS)
            check(bytes.size >= 24 && bytes.copyOfRange(0, 8).contentEquals(
                byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 13, 10, 26, 10))) { "INVALID_PNG" }
            val pipe = ParcelFileDescriptor.createPipe()
            readFd = pipe[0]
            result.putInt("byteCount", bytes.size)
            result.putBoolean("ok", true)
            Thread({
                runCatching { ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { it.write(bytes) } }
            }, "shizuku-capture-fd").start()
        } catch (t: Throwable) {
            result.putString("error", "${t.javaClass.simpleName}:${t.message}")
        } finally {
            process?.destroy()
            result.putLong("captureDurationMs", SystemClock.elapsedRealtime() - began)
        }
        return readFd
    }

    override fun destroy() { kotlin.system.exitProcess(0) }
}
