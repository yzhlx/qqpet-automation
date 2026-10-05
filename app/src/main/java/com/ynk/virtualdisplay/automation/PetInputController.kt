package com.ynk.virtualdisplay.automation

import android.os.SystemClock
import android.graphics.PointF
import android.view.InputDevice
import android.view.MotionEvent
import com.ynk.virtualdisplay.data.repository.IDisplayRepository
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

class PetInputController(
    private val repository: IDisplayRepository,
    private val displayId: Int,
    private val width: Int,
    private val height: Int,
) {
    init { require(displayId > 0) { "Refuse to inject into default display" } }

    suspend fun tap(x: Float, y: Float, pressMs: Long = 80L) {
        require(x >= 0f && x < width.toFloat() && y >= 0f && y < height.toFloat()) { "tap outside pet display" }
        val downTime = SystemClock.uptimeMillis()
        var downSent = false
        val down = event(downTime, downTime, MotionEvent.ACTION_DOWN, x, y)
        try {
            check(repository.injectInputWithDisplayId(down, displayId).getOrThrow()) { "ACTION_DOWN rejected" }
            downSent = true
            delay(pressMs)
        } finally {
            down.recycle()
            if (downSent) {
                withContext(NonCancellable) {
                    val up = event(downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x, y)
                    try {
                        check(repository.injectInputWithDisplayId(up, displayId).getOrThrow()) { "ACTION_UP rejected" }
                    } finally { up.recycle() }
                }
            }
        }
    }

    /** One continuous, display-scoped gesture, used only for the measured soap interaction. */
    suspend fun drag(points: List<PointF>, stepMs: Long = 250L) {
        require(points.size >= 2)
        points.forEach { require(it.x in 0f..<width.toFloat() && it.y in 0f..<height.toFloat()) }
        val downTime = SystemClock.uptimeMillis()
        var downSent = false
        var last = points.first()
        suspend fun send(action: Int, point: PointF) {
            val e = event(downTime, SystemClock.uptimeMillis(), action, point.x, point.y)
            try { check(repository.injectInputWithDisplayId(e, displayId).getOrThrow()) { "Gesture event rejected" } }
            finally { e.recycle() }
        }
        try {
            send(MotionEvent.ACTION_DOWN, last)
            downSent = true
            delay(650)
            for (point in points.drop(1)) {
                last = point
                send(MotionEvent.ACTION_MOVE, point)
                delay(stepMs)
            }
        } finally {
            if (downSent) withContext(NonCancellable) { send(MotionEvent.ACTION_UP, last) }
        }
    }

    private fun event(downTime: Long, whenMs: Long, action: Int, x: Float, y: Float): MotionEvent {
        val pointer = MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER }
        val coords = MotionEvent.PointerCoords().apply {
            this.x = x; this.y = y
            pressure = if (action == MotionEvent.ACTION_UP) 0f else 1f
            size = 1f
        }
        return MotionEvent.obtain(downTime, whenMs, action, 1, arrayOf(pointer), arrayOf(coords),
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
    }
}
