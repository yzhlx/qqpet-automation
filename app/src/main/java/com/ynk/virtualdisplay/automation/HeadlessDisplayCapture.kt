package com.ynk.virtualdisplay.automation

import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import com.ynk.virtualdisplay.data.repository.IDisplayRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Headless capture for one VirtualDisplay. No MediaProjection / Accessibility / network.
 * It temporarily owns the VirtualDisplay output Surface. Do not open DisplayActivity preview
 * while an automation session is active.
 */
class HeadlessDisplayCapture(
    private val repository: IDisplayRepository,
    private val displayId: Int,
    private val width: Int,
    private val height: Int,
) {
    private val thread = HandlerThread("pet-headless-capture-$displayId").apply { start() }
    private val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
    private val latestImage = AtomicReference<Image?>(null)
    private val pendingSignal = AtomicReference<CompletableDeferred<Unit>?>(null)
    private val started = AtomicBoolean(false)

    init {
        require(displayId > 0) { "Refuse to capture default display" }
        reader.setOnImageAvailableListener({ r ->
            val image = try { r.acquireLatestImage() } catch (_: Throwable) { null }
            if (image == null) return@setOnImageAvailableListener
            latestImage.getAndSet(image)?.close()
            pendingSignal.getAndSet(null)?.complete(Unit)
        }, Handler(thread.looper))
    }

    suspend fun start() {
        if (started.compareAndSet(false, true)) {
            repository.setDisplaySurface(displayId, reader.surface).getOrThrow()
        }
    }

    suspend fun awaitFrame(timeoutMs: Long = 4_000L): Bitmap {
        check(started.get()) { "capture not started" }
        latestImage.getAndSet(null)?.let { img ->
            return try { img.toBitmap() } finally { img.close() }
        }

        val signal = CompletableDeferred<Unit>()
        check(pendingSignal.compareAndSet(null, signal)) { "another frame request is pending" }

        // Close the race where a frame arrives between the first lookup and pending registration.
        latestImage.getAndSet(null)?.let { img ->
            pendingSignal.compareAndSet(signal, null)
            signal.complete(Unit)
            return try { img.toBitmap() } finally { img.close() }
        }

        try {
            withTimeout(timeoutMs) { signal.await() }
        } finally {
            pendingSignal.compareAndSet(signal, null)
        }
        val img = latestImage.getAndSet(null) ?: error("frame signal without image")
        return try { img.toBitmap() } finally { img.close() }
    }

    suspend fun stop() {
        if (!started.compareAndSet(true, false)) return
        pendingSignal.getAndSet(null)?.cancel()
        latestImage.getAndSet(null)?.close()
        // The upstream service interprets null as: restore its own fallback ImageReader Surface.
        withContext(NonCancellable) {
            runCatching { repository.setDisplaySurface(displayId, null).getOrThrow() }
        }
        reader.setOnImageAvailableListener(null, null)
        reader.close()
        thread.quitSafely()
    }

    private fun Image.toBitmap(): Bitmap {
        val plane = planes.firstOrNull() ?: error("RGBA plane missing")
        check(plane.pixelStride == 4) { "unexpected pixelStride=${plane.pixelStride}" }
        check(plane.rowStride >= plane.pixelStride * width) { "invalid rowStride" }
        check(plane.rowStride % plane.pixelStride == 0) { "non-integral row stride" }
        val paddedWidth = plane.rowStride / plane.pixelStride
        val padded = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888)
        try {
            plane.buffer.rewind()
            padded.copyPixelsFromBuffer(plane.buffer)
            return Bitmap.createBitmap(padded, 0, 0, width, height)
        } finally {
            padded.recycle()
        }
    }
}
