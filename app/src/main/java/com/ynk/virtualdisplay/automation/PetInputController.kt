package com.ynk.virtualdisplay.automation

import android.os.SystemClock
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

    private fun event(downTime: Long, whenMs: Long, action: Int, x: Float, y: Float): MotionEvent =
        MotionEvent.obtain(downTime, whenMs, action, x, y, 0).apply {
            source = InputDevice.SOURCE_TOUCHSCREEN
        }
}
