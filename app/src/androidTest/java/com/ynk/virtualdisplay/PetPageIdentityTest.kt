package com.ynk.virtualdisplay

import android.graphics.BitmapFactory
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ynk.virtualdisplay.automation.PetPageProbes
import com.ynk.virtualdisplay.automation.TemplateMatcher
import com.ynk.virtualdisplay.data.repository.ConnectionStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Page identity must be a stable SCENE region, never a game element that repaints with state.
 *
 * Regression for the real 3-round acceptance failure at 14:52 (session
 * 59a8b1a4-0f43-44c8-8262-8e3f9f85e46c, run 1791269448236):
 * `PAGE_MISMATCH:RECOVER_IF_NEEDED:clean-after:0.9538353304284677`, cleanliness already 99.
 * The bath page was identified by the soap BUTTON; once cleanliness maxed out the button
 * repainted to its pale "already clean" state and scored 0.9538, far below the 0.97
 * threshold, aborting the run with 0 completed rounds.
 *
 * Frames here are real captures pulled from three independent sessions and are read-only
 * fixtures: no Service, no input, no game navigation.
 */
@RunWith(AndroidJUnit4::class)
class PetPageIdentityTest {
    private val inst get() = InstrumentationRegistry.getInstrumentation()

    // Fixtures live in the androidTest APK, so they must come from the instrumentation's own
    // context (inst.context), not targetContext (the app under test).
    private val fixtureContext get() = inst.context

    // Templates (pet_mvp/*.png, pet_experimental/*.png) ship in the app APK, so the matcher
    // must read through the app's context.
    private val templateContext get() = inst.targetContext

    private fun frame(name: String) =
        BitmapFactory.decodeStream(fixtureContext.assets.open("pet_pages/$name"))

    private fun score(name: String, probeName: String): Double {
        val bitmap = frame(name)
        assertNotNull("Fixture missing: $name", bitmap)
        val matcher = TemplateMatcher(templateContext)
        val probe = PetPageProbes.byName().getValue(probeName)
        return try { matcher.match(bitmap, probe, 0.97).score } finally { bitmap.recycle() }
    }

    /** Every real bath frame matches the bath identity, including the pale-button state. */
    @Test fun bathSceneIdentifiesEveryMeasuredBathFrame() {
        val fixtures = listOf(
            "bath-9343-enter-after.png",
            "bath-9343-clean-after.png",
            "bath-9448-clean-before.png",
            "bath-9448-clean-after-pale.png",   // the frame that aborted the real run
            "bath-2045-leave-before.png",
        )
        for (name in fixtures) {
            val s = score(name, "bath")
            assertTrue("Bath scene identity must hold on $name (score=$s)", s >= 0.97)
        }
    }

    /**
     * The exact defect: the soap button is a legitimate page TARGET but an unreliable page
     * IDENTITY. On the pale "already clean" frame it stays below threshold. If this assertion
     * ever starts passing, the button has changed and identity must be re-measured, not swapped
     * back to the button.
     */
    @Test fun soapButtonAloneIsNotSufficientPageIdentity() {
        val pale = score("bath-9448-clean-after-pale.png", "soap")
        assertTrue(
            "Soap button unexpectedly matched the pale frame ($pale); re-measure the bath identity",
            pale < 0.97,
        )
        // The same frame IS identified by the scene region, which is why the fix works.
        assertTrue(score("bath-9448-clean-after-pale.png", "bath") >= 0.97)
    }

    /** Identity must not leak: bath, food and room pages must each reject the others. */
    @Test fun pageIdentitiesRejectForeignPages() {
        val expectBath = listOf(
            "bath-9343-enter-after.png", "bath-9343-clean-after.png", "bath-9448-clean-before.png",
            "bath-9448-clean-after-pale.png", "bath-2045-leave-before.png",
        )
        val expectFood = listOf("food-2033-before.png", "food-9448-enter.png")
        val expectRoom = listOf(
            "room-9448-home.png", "room-9343-before.png", "room-2045-after.png", "room-9448-18-before.png",
        )
        val all = expectBath + expectFood + expectRoom
        for (name in all) {
            for ((probe, owners) in listOf("bath" to expectBath, "cookie" to expectFood, "room" to expectRoom)) {
                val s = score(name, probe)
                val shouldMatch = name in owners
                if (shouldMatch) {
                    assertTrue("$probe must identify its own page $name (score=$s)", s >= 0.97)
                } else {
                    assertFalse("$probe must reject foreign page $name (score=$s)", s >= 0.97)
                }
            }
        }
    }

    // ---------------------------------------------------------------------------------------
    // FOOD identity. `cookie` (the plate/quantity item) is a legitimate page TARGET and legacy
    // measured item, but it is NOT the FOOD page identity: its plate moves with the food state.
    // `foodScene` (floor/cushion scene region) is the identity. The two tests below are the
    // mirror image of the soap/bath pair above, and pin the exact defect that blocked the
    // three-round run.
    // ---------------------------------------------------------------------------------------

    private companion object {
        /** Every recorded FOOD-page state. The two `food-scene-current-badge1-*` frames are the
         *  real display4 captures that exposed the defect. */
        val expectFoodScene = listOf(
            "food-9448-enter.png",
            "food-2033-before.png",
            "food-scene-current-badge1-a.png",
            "food-scene-current-badge1-b.png",
        )

        /** Every available non-FOOD control page. */
        val expectForeign = listOf(
            "bath-9343-enter-after.png", "bath-9343-clean-after.png", "bath-9448-clean-before.png",
            "bath-9448-clean-after-pale.png", "bath-2045-leave-before.png",
            "room-9448-home.png", "room-9343-before.png", "room-2045-after.png", "room-9448-18-before.png",
            "academy-label-source.png", "academy-label-original-failure.png",
            "academy-label-focused-heldout.png",
        )
    }

    /** The FOOD scene identity holds across every measured FOOD state, old and new. */
    @Test fun foodSceneIdentifiesEveryMeasuredFoodState() {
        for (name in expectFoodScene) {
            val s = score(name, "food_scene")
            println("FOOD_SCENE_POSITIVE fixture=$name food_scene=$s")
            assertTrue("Food scene identity must hold on $name (score=$s)", s >= 0.97)
        }
    }

    /** ...and it rejects every available foreign page: no ROOM/BATH/ACADEMY leak. */
    @Test fun foodSceneRejectsEveryAvailableForeignPage() {
        for (name in expectForeign) {
            val s = score(name, "food_scene")
            println("FOOD_SCENE_NEGATIVE fixture=$name food_scene=$s")
            assertFalse("food_scene must reject foreign page $name (score=$s)", s >= 0.97)
        }
    }

    /**
     * The exact defect that blocked the three-round run. The recorded FOOD fixtures are the only
     * states the old identity was ever measured on, and both of their cookie ROIs are identical:
     * the real badge-1 state draws the plate 10 px lower, so `cookie` misses a page that is
     * visually correct. `cookie` MUST stay below threshold on that state - if this ever starts
     * passing, the plate rendering changed and identity must be re-measured, not swapped back.
     */
    @Test fun cookieItemAloneIsNotSufficientFoodIdentity() {
        for (name in listOf("food-scene-current-badge1-a.png", "food-scene-current-badge1-b.png")) {
            val cookieScore = score(name, "cookie")
            val sceneScore = score(name, "food_scene")
            println("FOOD_LEGACY_VS_SCENE fixture=$name cookie=$cookieScore food_scene=$sceneScore threshold=0.97")
            assertTrue("Cookie item unexpectedly matched $name ($cookieScore); re-measure", cookieScore < 0.97)
            // The same frame IS identified by the stable scene region, which is why the fix works.
            assertTrue("Food scene must identify $name (score=$sceneScore)", sceneScore >= 0.97)
        }
    }

    /**
     * PASSIVE end-to-end check on the REAL device: capture the current display-4 content with the
     * repository's own acquire/release lease and the public matcher, and require that it is the
     * FOOD page. Sentinel characters are valid identifiers and test-only.
     *
     * This sends NO input at all - no tap, no swipe, no course, no care action - so it cannot
     * manufacture a pass by navigating. If the device is not sitting on the FOOD page the test
     * must FAIL and report the score; it must never tap its way to a green result.
     *
     * OPT-IN, exactly like [PetCalibrationGateTest]: it needs a live display 4 that is actually
     * showing the game. Without one the repository's own liveness gate refuses the capture
     * (`CAPTURE_INVALID_BLACK_FRAME`) and the assertion has nothing to measure. Skipping in that
     * case is the honest outcome - the alternative, quietly skipping on ANY failure, is exactly
     * how a passive test rots into a green tick that proves nothing. So the ONLY accepted reason
     * to skip is "display 4 currently has no content to capture"; every other failure still fails.
     */
    @Test fun currentDisplay4FrameIsIdentifiedAsFoodSceneWithoutAnyInput() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("enable with -e food_scene_live=yes", args.getString("food_scene_live") == "yes")
        val inst = InstrumentationRegistry.getInstrumentation()
        val app = inst.targetContext.applicationContext as MyApplication
        val repo = app.displayRepository

        // The measured display identity. A different serial/display means the measurement does
        // not apply; report it instead of pretending the assertion passed.
        assertEquals("unexpected device model", "FOA-AL00", Build.MODEL)
        val displayId = 4

        val matcher = TemplateMatcher(app)
        val foodScene = PetPageProbes.byName().getValue("food_scene")
        val room = PetPageProbes.byName().getValue("room")
        val bath = PetPageProbes.byName().getValue("bath")

        withContext(Dispatchers.Main) { app.acquireDisplayRepository("FoodScenePassiveCapture") }
        try {
            withTimeout(15000) { repo.connectionStatus.first { it == ConnectionStatus.CONNECTED } }
            val result = withTimeout(15000) { repo.captureDisplay(displayId) }
            // An EMPTY display is a precondition failure, not a measurement: record it and skip.
            // Anything else - permission, identity, size, transport - is a real failure.
            val empty = result.exceptionOrNull()?.message?.contains("CAPTURE_INVALID_BLACK_FRAME") == true
            if (empty) {
                println("FOOD_SCENE_LIVE_SKIPPED display $displayId has no rendered content; no frame measured")
            }
            assumeTrue("display $displayId has no rendered content to capture", !empty)
            val captured = result.getOrElse { fail("display $displayId capture failed: ${it.message}"); return@runBlocking }
            try {
                assertEquals(720, captured.bitmap.width)
                assertEquals(1600, captured.bitmap.height)
                val scene = matcher.match(captured.bitmap, foodScene, 0.97)
                val roomScore = matcher.match(captured.bitmap, room, 0.97).score
                val bathScore = matcher.match(captured.bitmap, bath, 0.97).score
                println(
                    "FOOD_SCENE_LIVE display=$displayId source=${captured.source} uid=${captured.uid} " +
                        "food_scene=${scene.score} room=$roomScore bath=$bathScore",
                )
                assertTrue(
                    "current display4 frame is not the FOOD page " +
                        "(food_scene=${scene.score}, room=$roomScore, bath=$bathScore); no input was sent",
                    scene.matched,
                )
                assertTrue("room identity must not also match: $roomScore", roomScore < 0.97)
                assertTrue("bath identity must not also match: $bathScore", bathScore < 0.97)
            } finally {
                captured.bitmap.recycle()
            }
        } finally {
            withContext(Dispatchers.Main) { app.releaseDisplayRepository("FoodScenePassiveCapture") }
        }
    }
}
