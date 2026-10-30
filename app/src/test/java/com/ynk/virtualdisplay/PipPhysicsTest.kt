package com.ynk.virtualdisplay

import com.ynk.virtualdisplay.floating.PipPhysics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PipPhysicsTest {
    @Test
    fun snapCornerPrefersProjectedVelocityTarget() {
        val bounds = PipPhysics.Bounds(
            width = 1000f,
            height = 2000f,
            pipWidth = 200f,
            pipHeight = 400f,
            margin = 20f,
            top = 40f,
            bottom = 100f,
        )
        val corner = PipPhysics.snapCorner(
            x = 100f,
            y = 100f,
            vx = 4000f,
            vy = -100f,
            bounds = bounds,
        )
        assertEquals(PipPhysics.Corner.TopEnd, corner)
    }

    @Test
    fun springSettlesNearTarget() {
        val spring = PipPhysics.Spring(response = 0.2f, dampingRatio = 1f)
        var motion = PipPhysics.Motion(position = 0f, velocity = 0f)
        repeat(120) {
            motion = spring.step(motion, target = 100f, dt = 1f / 60f)
        }
        assertTrue(spring.settled(motion, 100f))
    }
}
