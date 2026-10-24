package com.ynk.virtualdisplay.bot

import android.graphics.PointF
import com.ynk.virtualdisplay.automation.PetInputController
import kotlinx.coroutines.delay

/**
 * [Hands] on the real virtual display, through the existing isolated input controller.
 *
 * The controller refuses input unless a frame younger than 5 s was seen (AutomationFacts), so each
 * gesture first takes a fresh frame through [freshen].
 */
class DeviceHands(private val input: PetInputController, private val freshen: suspend () -> Unit) : Hands {
    override suspend fun tap(point: Point) {
        freshen()
        input.tap(point.x, point.y)
    }

    override suspend fun drag(points: List<Point>, stepMs: Long) {
        freshen()
        input.drag(points.map { PointF(it.x, it.y) }, stepMs)
    }

    override suspend fun pause(ms: Long) = delay(ms)
}
