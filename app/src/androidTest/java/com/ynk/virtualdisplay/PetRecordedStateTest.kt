package com.ynk.virtualdisplay

import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ynk.virtualdisplay.automation.PetHealthCheck
import com.ynk.virtualdisplay.automation.PetProfile
import com.ynk.virtualdisplay.automation.TemplateMatcher
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Reads previously captured real-device frames only. No input, Service or QQ navigation. */
@RunWith(AndroidJUnit4::class)
class PetRecordedStateTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val fixtures get() = File(context.getExternalFilesDir(null), "pet_mvp_fixtures")

    @Test fun measuredStatesRejectOtherPages() {
        assumeTrue(fixtures.exists())
        val p = PetProfile.calibrated()
        val matcher = TemplateMatcher(context)
        val groups = listOf(listOf(p.resultProbe), listOf(p.courseListProbe),
            listOf(p.courseDetailProbe), p.learningProbes)
        val samples = listOf(
            "result-before-continue" to setOf(0),
            "continue-after" to setOf(1, 2),
            "course-detail-confirmed" to setOf(1, 2),
            "learning-start" to setOf(3),
            "observed-03" to setOf(3),
            "health-values-before" to emptySet(),
        )
        for ((name, expected) in samples) {
            val frame = BitmapFactory.decodeFile(File(fixtures, "$name.png").absolutePath)
            assertNotNull("Fixture missing: $name", frame)
            try {
                groups.forEachIndexed { index, probes ->
                    val best = probes.maxOf { matcher.match(frame, it, p.matchThreshold).score }
                    assertEquals("$name group=$index score=$best", index in expected, best >= p.matchThreshold)
                }
            } finally { frame.recycle() }
        }
    }

    @Test fun measuredHealthBarsRejectLowHealth() {
        assumeTrue(fixtures.exists())
        for ((name, expected) in listOf(
            "health-values-before" to listOf(67, 66, 74),
            "cookie-after-3" to listOf(97, 66, 77),
        )) {
            val frame = BitmapFactory.decodeFile(File(fixtures, "$name.png").absolutePath)
            assertNotNull(frame)
            try {
                val health = PetHealthCheck.read(frame)
                // Rounded end caps produce a conservative 0..2 point underestimate.
                // Never tolerate an overestimate that could authorize low health.
                listOf(health.energy, health.cleanliness, health.mood).zip(expected).forEach { (read, shown) ->
                    assertTrue("Conservative health estimate $read vs displayed $shown", read in (shown - 2)..shown)
                }
                assertFalse("Low health must never authorize a course", health.ready)
            } finally { frame.recycle() }
        }
    }

    @Test fun cachedTemplateCanBeMatchedRepeatedly() {
        assumeTrue(fixtures.exists())
        val p = PetProfile.calibrated()
        val frame = BitmapFactory.decodeFile(File(fixtures, "result-before-continue.png").absolutePath)
        assertNotNull(frame)
        try {
            val matcher = TemplateMatcher(context)
            val scores = List(3) { matcher.match(frame, p.resultProbe, p.matchThreshold).score }
            assertEquals("Repeated matches of the cached template must remain stable", scores[0], scores[2], 0.0)
            assertTrue(scores[0] >= p.matchThreshold)
        } finally { frame.recycle() }
    }
}
