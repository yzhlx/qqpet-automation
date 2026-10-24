package com.ynk.virtualdisplay.bot

/**
 * The bot's only view of the phone. The device implementation wraps the existing virtual display
 * capture and input; tests use a scripted fake, so all bot logic runs on the plain JVM.
 */
interface Eyes {
    /** Captures a fresh frame and says which page it shows. */
    suspend fun look(): Observation
}

interface Hands {
    suspend fun tap(point: Point)
    suspend fun drag(points: List<Point>, stepMs: Long = 150L)
    /** Waits for the game to react. Fakes skip the wait. */
    suspend fun pause(ms: Long)
}
