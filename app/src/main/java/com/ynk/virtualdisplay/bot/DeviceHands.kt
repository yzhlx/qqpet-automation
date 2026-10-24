package com.ynk.virtualdisplay.bot

import android.graphics.PointF
import com.ynk.virtualdisplay.automation.PetInputController
import kotlinx.coroutines.delay

/** [Hands] on the real virtual display, through the existing isolated input controller. */
class DeviceHands(private val input: PetInputController) : Hands {
    override suspend fun tap(point: Point) = input.tap(point.x, point.y)

    override suspend fun drag(points: List<Point>, stepMs: Long) =
        input.drag(points.map { PointF(it.x, it.y) }, stepMs)

    override suspend fun pause(ms: Long) = delay(ms)
}
