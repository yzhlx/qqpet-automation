package com.ynk.virtualdisplay.automation

import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import com.ynk.virtualdisplay.data.repository.IDisplayRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject
import com.ynk.virtualdisplay.data.repository.ConnectionStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

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
    val facts = AutomationFacts(repository.automationEventsDirectory, displayId)
    private class InvalidFrame(message: String): IllegalStateException(message)
    private val thread = HandlerThread("pet-headless-capture-$displayId").apply { start() }
    private val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
    private val latestImage = AtomicReference<Image?>(null)
    private val pendingSignal = AtomicReference<CompletableDeferred<Unit>?>(null)
    private val started = AtomicBoolean(false)
    private val receivedFrameCount = AtomicLong(0)
    private val lastFrameAtMs = AtomicLong(0)
    private var renderedSnapshot: Bitmap? = null
    private var renderedAtMs = 0L
    private var lastTelemetry = JSONObject()
    private var imageReaderSuccess = 0
    private var imageReaderTimeout = 0
    private var fallbackCount = 0
    private var fallbackSuccess = 0
    private val fallbackLatencies = mutableListOf<Long>()
    private val frameLock = Any()

    fun telemetry(): JSONObject = JSONObject(lastTelemetry.toString())
    fun statistics(): JSONObject {
        val sorted = fallbackLatencies.sorted()
        fun percentile(p: Double): Any = if (sorted.isEmpty()) JSONObject.NULL
            else sorted[(kotlin.math.ceil(sorted.size * p).toInt() - 1).coerceIn(sorted.indices)]
        return JSONObject().put("imageReaderSuccess", imageReaderSuccess).put("imageReaderTimeout", imageReaderTimeout)
            .put("shizukuFallbackCount", fallbackCount).put("shizukuFallbackSuccess", fallbackSuccess)
            .put("captureLatencyP50Ms", percentile(0.50)).put("captureLatencyP95Ms", percentile(0.95))
    }

    private fun setTelemetry(source: String, readerResult: String, waitMs: Long) {
        lastTelemetry = JSONObject().put("displayId", displayId).put("imageReaderResult", readerResult)
            .put("imageReaderWaitMs", waitMs).put("fallbackAttempted", false).put("fallbackResult", "NOT_ATTEMPTED")
            .put("fallbackDurationMs", 0).put("captureSource", source).put("frameDeliveryMiss", false)
    }

    private fun remember(frame: Bitmap): Bitmap {
        validate(frame)
        renderedSnapshot?.recycle()
        renderedSnapshot = frame.copy(Bitmap.Config.ARGB_8888, false)
        renderedAtMs = SystemClock.elapsedRealtime()
        return frame
    }

    private fun validate(frame: Bitmap) {
        val result=FrameLiveness.inspect(displayId,frame.width,frame.height,width,height,
            repository.connectionStatus.value==ConnectionStatus.CONNECTED && displayId in repository.managedDisplayIds.value,
            pixel=frame::getPixel)
        facts.frame(result,lastTelemetry.optString("captureSource"))
        if(!result.valid || facts.frames.stopped) {frame.recycle();throw InvalidFrame("FRAME_INVALID:${result.status}")}
    }

    /** For an immediate pre-action check of an unchanged page. After an input,
     * callers must use awaitFrame(), which still requires a newly rendered frame. */
    suspend fun latestRenderedFrame(maxAgeMs: Long = 1500L): Bitmap {
        check(started.get())
        latestImage.getAndSet(null)?.let { img ->
            setTelemetry("IMAGEREADER_RECENT", "RECENT", 0)
            try { return remember(img.toBitmap()) }
            catch(t: Exception) {
                if(t !is InvalidFrame) facts.frame(FrameLivenessResult(FrameStatus.INVALID_DECODE,t.toString()),"IMAGEREADER_RECENT")
            } finally { img.close() }
            return awaitFrame()
        }
        val snapshot = renderedSnapshot
        if (snapshot != null && SystemClock.elapsedRealtime() - renderedAtMs <= maxAgeMs) {
            setTelemetry("RECENT_CACHE", "NOT_REQUESTED", 0)
            val frame=snapshot.copy(Bitmap.Config.ARGB_8888, false)
            try { validate(frame);return frame } catch(_: InvalidFrame) { return awaitFrame() }
        }
        return awaitFrame()
    }

    init {
        require(displayId > 0) { "Refuse to capture default display" }
        reader.setOnImageAvailableListener({ r ->
            synchronized(frameLock) {
            val image = try { r.acquireLatestImage() } catch (_: Throwable) { null }
            if (image == null) return@setOnImageAvailableListener
            receivedFrameCount.incrementAndGet()
            lastFrameAtMs.set(SystemClock.elapsedRealtime())
            latestImage.getAndSet(image)?.close()
            pendingSignal.getAndSet(null)?.complete(Unit)
            }
        }, Handler(thread.looper))
    }

    fun diagnostics(): String {
        val last = lastFrameAtMs.get()
        val age = if (last == 0L) "never" else (SystemClock.elapsedRealtime() - last).toString()
        return "displayId=$displayId,receivedFrames=${receivedFrameCount.get()},lastFrameAgeMs=$age,pending=${pendingSignal.get()!=null}"
    }

    suspend fun start() {
        if (started.compareAndSet(false, true)) {
            repository.setDisplaySurface(displayId, reader.surface).getOrThrow()
        }
    }

    private suspend fun takeImage(timeoutMs: Long): Image {
        check(started.get()) { "capture not started" }
        val signal = CompletableDeferred<Unit>()
        synchronized(frameLock) {
            // Post-action verification must not consume a pre-request queued buffer or cached bitmap.
            latestImage.getAndSet(null)?.close()
            runCatching { reader.acquireLatestImage()?.close() }
            check(pendingSignal.compareAndSet(null, signal)) { "another frame request is pending" }
        }

        try {
            withTimeout(timeoutMs) { signal.await() }
        } catch (timeout: kotlinx.coroutines.TimeoutCancellationException) {
            // A frame can land in the reader queue as the timeout wins the signal race.
            // The queue was drained before this wait, so any image here is post-request.
            val lateImage = latestImage.getAndSet(null)
            if (lateImage != null && timeoutMs > 0) return lateImage
            lateImage?.close()
            throw timeout
        } finally {
            pendingSignal.compareAndSet(signal, null)
        }
        return latestImage.getAndSet(null) ?: error("frame signal without image")
    }

    suspend fun awaitFrame(timeoutMs: Long = 350L): Bitmap {
        check(!facts.frames.stopped) { "FRAME_FAILURE_LIMIT" }
        var failure: Throwable?=null
        repeat(3) {
            try { return awaitSingleFrame(timeoutMs) }
            catch(cancelled: CancellationException) {
                facts.frame(FrameLiveness.failure(cancelled.toString()),lastTelemetry.optString("captureSource"))
                throw cancelled
            }
            catch(t: Exception) {
                failure=t
                if(t !is InvalidFrame) facts.frame(FrameLiveness.failure(t.toString()),
                    lastTelemetry.optString("captureSource"))
                if(facts.frames.stopped) throw IllegalStateException("FRAME_FAILURE_LIMIT",t)
                delay(100)
            }
        }
        throw IllegalStateException("FRAME_UNCONFIRMED",failure)
    }

    private suspend fun awaitSingleFrame(timeoutMs: Long): Bitmap {
        check(started.get()) { "capture not started" }
        val began = SystemClock.elapsedRealtime()
        val img = try {
            takeImage(timeoutMs)
        } catch (timeout: kotlinx.coroutines.TimeoutCancellationException) {
            imageReaderTimeout++; fallbackCount++
            setTelemetry("SHIZUKU_FALLBACK", "TIMEOUT", SystemClock.elapsedRealtime() - began)
            lastTelemetry.put("fallbackAttempted", true).put("frameDeliveryMiss", true)
            val fallbackBegan = SystemClock.elapsedRealtime()
            try {
                val captured = repository.captureDisplay(displayId).getOrThrow()
                lastTelemetry.put("fallbackResult", "OK").put("fallbackDurationMs", captured.durationMs)
                    .put("source", captured.source).put("byteCount", captured.byteCount)
                val accepted=remember(captured.bitmap)
                fallbackSuccess++; fallbackLatencies.add(captured.durationMs)
                return accepted
            } catch(t: Throwable) {
                lastTelemetry.put("fallbackResult", "FAILED").put("error", t.toString())
                    .put("fallbackDurationMs", SystemClock.elapsedRealtime() - fallbackBegan)
                if(t is InvalidFrame || t is CancellationException) throw t
                throw IllegalStateException("CAPTURE_UNCONFIRMED:${t.message}", t)
            }
        }
        imageReaderSuccess++
        setTelemetry("IMAGEREADER", "OK", SystemClock.elapsedRealtime() - began)
        return try { remember(img.toBitmap()) } finally { img.close() }
    }

    suspend fun stop() {
        if (!started.compareAndSet(true, false)) return
        pendingSignal.getAndSet(null)?.cancel()
        latestImage.getAndSet(null)?.close()
        renderedSnapshot?.recycle(); renderedSnapshot = null
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
