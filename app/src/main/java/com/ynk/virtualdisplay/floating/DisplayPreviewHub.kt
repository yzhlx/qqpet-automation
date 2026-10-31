package com.ynk.virtualdisplay.floating

import android.graphics.Bitmap
import android.os.SystemClock
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Read-only shared preview of the automation VirtualDisplay.
 *
 * The automation [com.ynk.virtualdisplay.automation.HeadlessDisplayCapture] keeps exclusive ownership
 * of [android.hardware.display.VirtualDisplay] output Surface. This hub only receives scaled copies
 * of already-captured frames so in-app PiP and the system capsule never call setDisplaySurface().
 */
object DisplayPreviewHub {
    data class Frame(
        val bitmap: Bitmap,
        val observedAtMs: Long,
        val generation: Long,
        val sourceWidth: Int,
        val sourceHeight: Int,
    )

    private const val MIN_INTERVAL_MS = 200L // ~5 FPS
    private const val PREVIEW_MAX_WIDTH = 180

    private val subscribers = AtomicInteger(0)
    private val lastPublishAtMs = AtomicLong(0)
    private val generation = AtomicLong(0)

    private val _frame = MutableStateFlow<Frame?>(null)
    val frame: StateFlow<Frame?> = _frame.asStateFlow()

    val hasSubscribers: Boolean get() = subscribers.get() > 0

    /** Keep preview production alive while any UI is watching. */
    fun retain(): AutoCloseable {
        subscribers.incrementAndGet()
        return AutoCloseable { subscribers.decrementAndGet() }
    }

    fun publishFrom(source: Bitmap, observedAtMs: Long) {
        if (subscribers.get() <= 0) return
        val now = SystemClock.elapsedRealtime()
        val last = lastPublishAtMs.get()
        if (last != 0L && now - last < MIN_INTERVAL_MS) return
        if (!lastPublishAtMs.compareAndSet(last, now)) return

        val scaled = scale(source) ?: return
        val next = Frame(
            bitmap = scaled,
            observedAtMs = observedAtMs,
            generation = generation.incrementAndGet(),
            sourceWidth = source.width,
            sourceHeight = source.height,
        )
        // The previous frame is simply dropped: Compose may still be drawing it, and recycling a
        // bitmap under a live ImageBitmap crashes the UI. These are ~180x400 bitmaps, so the
        // garbage collector reclaims them cheaply.
        _frame.value = next
    }

    fun clear() {
        _frame.value = null
        lastPublishAtMs.set(0L)
    }

    private fun scale(source: Bitmap): Bitmap? {
        if (source.isRecycled || source.width <= 0 || source.height <= 0) return null
        val targetW = PREVIEW_MAX_WIDTH.coerceAtMost(source.width)
        if (targetW == source.width) {
            return source.copy(Bitmap.Config.ARGB_8888, false)
        }
        val targetH = (source.height.toLong() * targetW / source.width).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(source, targetW, targetH, true)
    }
}

