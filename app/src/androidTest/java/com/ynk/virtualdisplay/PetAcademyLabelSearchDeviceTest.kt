package com.ynk.virtualdisplay

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ynk.virtualdisplay.automation.PetNavigation
import com.ynk.virtualdisplay.automation.PetProfile
import com.ynk.virtualdisplay.automation.TemplateMatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The REAL Android metric and the REAL shipping locator, on real device frames.
 *
 * The JVM test (`PetAcademyLabelSearchTest`) feeds `searchLabel` a score table recorded on the
 * HOST with Pillow's bilinear resize standing in for Android's `createScaledBitmap(filter=true)`.
 * That is a structural approximation, so it pins the search GEOMETRY and the fail-closed control
 * flow, but it cannot prove what the shipping code computes on a device.
 *
 * This file closes that gap. The SHIPPING acceptance path is [PetNavigation.LabelLocator]: it
 * proposes candidates from a frame's own pixels, scores each one in a bounded local neighbourhood
 * through the public `TemplateMatcher.match`, and refuses anything but exactly one distinct
 * qualifying label. The tests below call THAT object - the same code production runs - and the
 * only substitution is the `TemplateMatcher` instance, because a `PetFlowContext` needs a live
 * Service that a passive test must not start. The matcher is the same public class, so the metric,
 * the crop/rescale behaviour and the 0.97 comparison are identical to production.
 *
 * [PetNavigation.searchLabel] and [PetNavigation.LabelScratch] are the RETAINED GROUND TRUTH: the
 * exhaustive fixed-size sweep that established the recorded qualifying windows, and the fast
 * custom-Canvas form whose equivalence to the authoritative metric is measured below. They are
 * reference paths, not the shipping locator, and their ~18-33 us/window must never be quoted as
 * the shipping cost - the shipping path pays the public matcher's ~1.0 ms/window (measured here,
 * with an earlier isolated bench at ~632 us; the two numbers are a run-to-run range, not a
 * contradiction).
 *
 * PASSIVE BY CONSTRUCTION: this test only reads asset bitmaps and calls pure functions. It
 * starts no Service, captures nothing, and sends NO input of any kind - no taps, no swipes, no
 * course batch. It cannot disturb the QQ/display precondition on the device.
 */
@RunWith(AndroidJUnit4::class)
class PetAcademyLabelSearchDeviceTest {
    private val inst get() = InstrumentationRegistry.getInstrumentation()
    private val fixtureContext get() = inst.context
    private val templateContext get() = inst.targetContext

    private companion object {
        const val THR = 0.97
        /**
         * The size of the SYNTHETIC blank frame only.
         *
         * The real fixtures are decoded and their own dimensions are used, so the locator is never
         * told what size the display is - it reads each frame. These two constants exist solely to
         * allocate the featureless control frame, and deliberately match the fixture frames so the
         * blank case is compared on the same geometry as the real ones.
         */
        const val FIXTURE_W = 720
        const val FIXTURE_H = 1600
        /** Enough headroom to be a real frame without being so large the test times out. */
        const val DEADLINE_MS = 300_000L
        /** The production total localisation gate that the two-frame stability path must fit. */
        const val TWO_FRAME_GATE_MS = 8_000.0
    }

    private fun frame(name: String): Bitmap {
        val bitmap = BitmapFactory.decodeStream(fixtureContext.assets.open("pet_pages/$name"))
        assertNotNull("Fixture missing: $name", bitmap)
        return bitmap!!
    }

    private fun template(): PetNavigation.LabelTemplate {
        val asset = BitmapFactory.decodeStream(
            templateContext.assets.open("pet_mvp/academy_primary_label.png")
        )
        assertNotNull("the shipped academy label template must exist in the app APK", asset)
        return PetNavigation.LabelTemplate.of(asset!!, PetNavigation.ACADEMY_LABEL_ASSET)
    }

    /** The production template set, built from the shipped assets exactly as production does. */
    private fun shippingTemplates(): List<PetNavigation.LabelTemplate> =
        PetNavigation.ACADEMY_LABEL_ASSETS.map { asset ->
            val decoded = BitmapFactory.decodeStream(templateContext.assets.open(asset))
            assertNotNull("the shipped template $asset must exist in the app APK", decoded)
            PetNavigation.LabelTemplate.of(decoded!!, asset)
        }

    /**
     * Runs the SHIPPING locator over [bitmap] and reports its outcome.
     *
     * The geometry is read the same way production reads it: from the frame's own dimensions and
     * the live display metrics, so this test exercises the runtime geometry rather than a constant.
     * The threshold is the unchanged `PetProfile.calibrated().matchThreshold`.
     */
    private suspend fun locate(
        bitmap: Bitmap,
        counter: PetNavigation.LabelLocator.WindowCounter? = null,
    ): PetNavigation.LabelLocate? {
        val profile = PetProfile.calibrated()
        val metrics = templateContext.resources.displayMetrics
        // The pure half of production's `geometryOf`, fed the frame's own size and the live density.
        val geometry = PetNavigation.geometryFor(
            bitmap.width, bitmap.height, metrics.densityDpi,
        )
        return PetNavigation.LabelLocator.locate(
            frame = bitmap,
            templates = shippingTemplates(),
            bounds = PetNavigation.primaryAcademyTarget.plateBounds(geometry),
            matcher = TemplateMatcher(templateContext),
            matchThreshold = profile.matchThreshold,
            deadlineMs = android.os.SystemClock.elapsedRealtime() + DEADLINE_MS,
            counter = counter,
        )
    }

    /**
     * The SHIPPING candidate locator resolves every real frame in every observed camera state.
     *
     * This drives the production path end to end - [PetNavigation.LabelLocator.locate], the same
     * function `PetNavigation.locateAcademyLabel` calls - rather than the fixed-size sweep the
     * reference tests below pin. It is the functional evidence that the locator recognises BOTH
     * observed camera states, which the previous fixed-size locator could not:
     *
     *  - the panoramic frame the overview template was cropped from;
     *  - the focused frame that caused the original failure, and
     *  - an INDEPENDENT focused frame from another session (SHA256 EB1324F4B628E1FB, differing
     *    from the crop source BFF2F4FBD94AD294), so this is generalisation and not self-match.
     *
     * Each frame's own dimensions supply the geometry, and the acceptance is the unchanged 0.97
     * from the public matcher, so no coordinate here is hardcoded and no threshold is relaxed.
     */
    @Test fun theShippingLocatorResolvesEveryRealFrameInBothCameraStates() = runBlocking {
        val expected = mapOf(
            "academy-label-source.png" to "panoramic",
            "academy-label-original-failure.png" to "focused",
            "academy-label-focused-heldout.png" to "focused-heldout",
        )
        for ((file, state) in expected) {
            val bitmap = frame(file)
            val hit = locate(bitmap)
            assertNotNull("the shipping locator must run on $file ($state)", hit)
            assertTrue(
                "the shipping locator must resolve $file ($state); it proposed " +
                    "${hit!!.candidates} candidates and accepted ${hit.accepted}",
                hit.clusters == 1 && hit.score >= THR,
            )
            println(
                "ACADEMY_SHIPPING_LOCATE frame=$file state=$state " +
                    "centre=(${hit.centreX},${hit.centreY}) score=${hit.score} " +
                    "clusters=${hit.clusters} candidates=${hit.candidates} accepted=${hit.accepted}"
            )
            bitmap.recycle()
        }
    }

    /**
     * The locator's two-frame LOCALIZATION cost on this device - the dominant term in the gate.
     *
     * MEASURED, not projected. The expert's per-frame figure was a HOST prototype estimate and is
     * not used as evidence here; this runs the SHIPPING candidate locator on the device and records
     * what it actually costs. The window count comes from the shipping code's own counter, so the
     * per-window cost printed is real public-matcher work rather than a formula.
     *
     * WHAT THIS DOES NOT MEASURE, stated plainly so the number is not over-read: this is the
     * LOCALIZATION half of the gate only. It is a passive test, so it starts no Service and captures
     * nothing - the per-frame screen capture cost of the production two-frame path is NOT included
     * and must be recorded during the live acceptance run (t7/t3), where a Service exists. The
     * production `stableAcademyLabel` loop also runs `score(current, listOf(profile.courseListProbe))`
     * on each frame, which this test does not include either. So the figure below is a FLOOR on the
     * real two-frame cost, not the whole budget: it says the ~8 s gate is not consumed by
     * localization, and it does not by itself claim the full capture+localize path fits.
     *
     * The ~1.0 ms/window measured for the public matcher is a DIFFERENT implementation from the old
     * custom-Canvas ~17.6 us/window path and must never be quoted interchangeably - that is a ~57x
     * difference, and the smaller figure belongs to the reference path.
     */
    @Test fun theShippingTwoFrameGateFitsItsBudgetOnThisDevice() = runBlocking {
        val bitmap = frame("academy-label-original-failure.png")
        // Warm the template cache so asset decoding is not counted as per-frame cost.
        locate(bitmap)
        var windows = 0
        val start = android.os.SystemClock.elapsedRealtime()
        var best = 0.0
        repeat(2) {
            val counter = PetNavigation.LabelLocator.WindowCounter()
            val t0 = android.os.SystemClock.elapsedRealtime()
            val hit = locate(bitmap, counter)
            val ms = android.os.SystemClock.elapsedRealtime() - t0
            windows += counter.count
            assertNotNull("each of the two fresh frames must resolve", hit)
            assertTrue(
                "each fresh frame must yield exactly one qualifying label, got " +
                    "${hit!!.clusters} clusters",
                hit.clusters == 1 && hit.score >= THR,
            )
            if (hit.score > best) best = hit.score
            println(
                "ACADEMY_FRAME_COST ms=$ms score=${hit.score} windows=${counter.count} " +
                    "candidates=${hit.candidates}"
            )
        }
        val totalMs = (android.os.SystemClock.elapsedRealtime() - start).toDouble()
        val perWindowUs = totalMs * 1000.0 / windows
        println(
            "ACADEMY_TWO_FRAME_GATE totalMs=$totalMs perFrameMs=${totalMs / 2.0} " +
                "windows=$windows perWindowUs=$perWindowUs budgetMs=$TWO_FRAME_GATE_MS " +
                "fits=${totalMs <= TWO_FRAME_GATE_MS} bestScore=$best"
        )
        assertTrue(
            "the production two-frame stability gate cost ${totalMs} ms over $windows windows, " +
                "which does not fit the unchanged ${TWO_FRAME_GATE_MS.toInt()} ms budget",
            totalMs <= TWO_FRAME_GATE_MS,
        )
        bitmap.recycle()
    }

    /**
     * A SECOND qualifying label must never be missed and silently reported as unique.
     *
     * The duplicate is a genuine second copy of the real plate pasted into a real frame, and its
     * >=0.97 score is CONFIRMED with the public matcher before the locator is run - so this cannot
     * pass by having the generator produce something that was never a valid target. If the
     * candidate signature failed to propose the pasted plate, the locator would see one label and
     * wrongly call it unique, and this test would catch exactly that.
     */
    @Test fun aSecondRealPlateIsNeverMissedAndSilentlyCalledUnique() = runBlocking {
        val decoded = frame("academy-label-source.png")
        val bitmap = decoded.copy(decoded.config ?: Bitmap.Config.ARGB_8888, true)
        decoded.recycle()
        val plate = Bitmap.createBitmap(bitmap, 105, 389, 177, 66)
        // Place the copy well away from the original so the two are distinct labels, not a
        // neighbouring window of one plate.
        Canvas(bitmap).drawBitmap(plate, 60f, 1100f, null)
        plate.recycle()

        // First confirm the pasted copy really is a >=0.97 target using the PUBLIC matcher.
        val matcher = TemplateMatcher(templateContext)
        val pasted = matcher.match(
            bitmap, PetProfile.Probe("p", Rect(60, 1100, 60 + 177, 1100 + 66), PetNavigation.ACADEMY_LABEL_ASSET),
            THR,
        ).score
        println("ACADEMY_DUPLICATE_CONFIRM pastedScore=$pasted")
        assertTrue(
            "the synthetic duplicate must be a real >=0.97 target, or this test proves nothing; " +
                "measured $pasted",
            pasted >= THR,
        )

        val hit = locate(bitmap)
        assertNotNull("both plates must be found so the ambiguity is visible", hit)
        assertTrue(
            "two distinct qualifying plates must be reported as ambiguous, got ${hit!!.clusters}",
            hit.clusters > 1,
        )
        println("ACADEMY_DUPLICATE clusters=${hit.clusters} candidates=${hit.candidates}")
        bitmap.recycle()
    }

    /**
     * A frame with no label must propose nothing and authorise nothing.
     *
     * This is the fail-closed direction: a signature that is too strict costs "no label", which is
     * safe, and must never become a tap at an assumed position.
     */
    @Test fun aFrameWithNoPlateProposesNothingSoNoInputIsSent() = runBlocking {
        // Solid mid-grey: no light plate, no glyphs, nothing resembling the signature.
        val bitmap = Bitmap.createBitmap(FIXTURE_W, FIXTURE_H, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).drawColor(Color.rgb(96, 96, 96))
        val bounds = PetNavigation.primaryAcademyTarget.plateBounds(
            PetNavigation.geometryFor(
                bitmap.width, bitmap.height, templateContext.resources.displayMetrics.densityDpi,
            )
        )
        val candidates = PetNavigation.LabelCandidates.propose(bitmap, bounds)
        println("ACADEMY_NO_PLATE candidates=${candidates?.size}")
        assertTrue(
            "a blank frame must propose nothing, proposed ${candidates?.size}",
            candidates.isNullOrEmpty(),
        )
        val hit = locate(bitmap)
        assertNotNull("the locator must still report an outcome", hit)
        assertTrue(
            "a blank frame must resolve to NO label, got ${hit!!.clusters} clusters",
            hit.clusters != 1,
        )
        bitmap.recycle()
    }

    @Test fun theFastWindowScoreAgreesWithTheAuthoritativeMatcher() = runBlocking {
        val bitmap = frame("academy-label-source.png")
        val template = template()
        val scratch = PetNavigation.LabelScratch()
        scratch.beginFrame(bitmap)
        val matcher = TemplateMatcher(templateContext)
        val cropRect = Rect(0, 0, template.width, template.height)

        val fast = mutableListOf<Double>()
        val reference = mutableListOf<Double>()
        // A spread of real windows: the true peak and the whole qualifying plateau around it, the
        // siblings just outside it, and plain background far from the label, so a decision
        // difference or an inexact score anywhere near the threshold would be caught.
        for (y in listOf(0, 200, 385, 388, 389, 390, 395, 500, 800, 1200, 1500)) {
            for (x in listOf(0, 50, 100, 103, 104, 105, 106, 107, 110, 300, 500, 543)) {
                cropRect.set(x, y, x + template.width, y + template.height)
                // The authoritative public entry point: it crops, rescales and scores exactly as
                // every other probe in the app does.
                val probe = PetProfile.Probe(
                    "academy-window", Rect(cropRect), PetNavigation.ACADEMY_LABEL_ASSET,
                )
                reference += matcher.match(bitmap, probe, THR).score
                fast += scratch.scoreWindow(bitmap, x, y, template)!!
            }
        }
        assertEquals("compared window count", reference.size, fast.size)
        var worst = 0.0
        var worstBelowBand = 0.0
        var exactAboveBand = 0
        var countedAboveBand = 0
        var decisionDisagreements = 0
        for (i in reference.indices) {
            val r = reference[i]
            val f = fast[i]
            val d = kotlin.math.abs(r - f)
            if (d > worst) worst = d
            if ((r >= THR) != (f >= THR)) decisionDisagreements++
            // The band just under the threshold. Above it the approximation is EXACT, which is
            // what keeps the 0.97 decision boundary honest; below it the filter's border
            // clipping is allowed to show, but never by enough to reach the threshold.
            if (r >= 0.96) {
                countedAboveBand++
                if (d == 0.0) exactAboveBand++
            } else if (d > worstBelowBand) worstBelowBand = d
        }
        println(
            "ACADEMY_EQUIV worstAnywhere=$worst worstBelowBand=$worstBelowBand " +
                "exactAboveBand=$exactAboveBand/$countedAboveBand " +
                "decisionDisagreements=$decisionDisagreements"
        )
        assertEquals(
            "the fast path must make the same 0.97 decision as the authoritative matcher at every " +
                "sampled window (worst numeric delta $worst)",
            0, decisionDisagreements,
        )
        // The decision boundary must be reproduced EXACTLY, not approximately. If any window in
        // the band that brackets 0.97 were off by even one unit of the summed absolute
        // difference, a tap could be aimed from a score the real metric would not have given.
        assertEquals(
            "every window at or above 0.96 must score exactly as the authoritative matcher does, " +
                "but only $exactAboveBand of $countedAboveBand did",
            countedAboveBand, exactAboveBand,
        )
        // Below the band the divergence is real but bounded and irrelevant to any decision: it
        // must stay orders of magnitude away from the 0.97 threshold, which is 0.03 above 0.94.
        assertTrue(
            "the low-scoring divergence $worstBelowBand is large enough to threaten a decision",
            worstBelowBand < 0.01,
        )
        bitmap.recycle()
    }

    /**
     * CRITERION-1 FEASIBILITY, measured on the device with the REAL public `TemplateMatcher.match`.
     *
     * Three real frames in the two observed camera states, and the two shipped templates:
     *
     *  - `academy-label-source.png` - panoramic. Resolves with the OVERVIEW template, which is
     *    the template that already existed and is preserved unchanged.
     *  - `academy-label-original-failure.png` - the focused frame that caused the original bug,
     *    and the frame the focused template was cropped from.
     *  - `academy-label-focused-heldout.png` - an INDEPENDENT focused frame from session
     *    1791201870302 (SHA256 EB1324F4B628E1FB, differing from the crop source
     *    BFF2F4FBD94AD294), so matching it is generalisation rather than a template recognising
     *    itself.
     *
     * The question answered here is whether the AUTHORITATIVE metric reaches 0.97 on a correctly
     * positioned window. If it cannot, no locator may claim the label is found, and the threshold
     * is not available as an escape.
     *
     * WHY THIS SWEEPS SIZE AND OFFSET rather than pinning one window size. `TemplateMatcher.match`
     * rescales the TEMPLATE to the live window, so the window size is part of what is being asked,
     * not a constant: the measured optima are 177x66 (component box 177x69), 216x83 (box 216x83)
     * and 220x84 (box 221x89). A fixed-size window on a +-2 px grid therefore cannot represent the
     * answer - an earlier revision of this test capped at y=388 while the measured optimum is
     * y=389, and a 216x83 window cannot express the held-out frame's 220x84 optimum. The
     * neighbourhood below is derived from each frame's MEASURED component box (its own width +-4,
     * its own centre +-6) so it can reach every measured optimum without a hand-tuned constant.
     *
     * This sweep deliberately does NOT use `LabelCandidates`/`LabelLocator`: it is the independent
     * feasibility oracle for the shipping path, so it must not share the shipping path's geometry.
     */
    @Test fun everyRealFrameResolvesAtItsOwnPlateSizeWithAMatchingTemplate() = runBlocking {
        val matcher = TemplateMatcher(templateContext)
        data class Case(
            val file: String, val state: String,
            val plateX: Int, val plateY: Int, val plateW: Int, val plateH: Int,
        )
        // Plate geometry MEASURED on device by connected-component analysis, not assumed.
        val cases = listOf(
            Case("academy-label-source.png", "panoramic", 105, 386, 177, 69),
            Case("academy-label-original-failure.png", "focused-crop-source", 185, 352, 216, 83),
            Case("academy-label-focused-heldout.png", "focused-heldout", 194, 344, 221, 89),
        )
        for (case in cases) {
            val bitmap = frame(case.file)
            val cx = case.plateX + case.plateW / 2.0
            val cy = case.plateY + case.plateH / 2.0
            var best = 0.0
            var bestTag = ""
            var windows = 0
            for (asset in PetNavigation.ACADEMY_LABEL_ASSETS) {
                val t = BitmapFactory.decodeStream(templateContext.assets.open(asset))
                // Sweep the window width around the frame's OWN measured plate width. The aspect
                // comes from the template so the window never distorts it.
                for (w in (case.plateW - 4)..(case.plateW + 4)) {
                    if (w < 8) continue
                    val h = (w * t.height.toDouble() / t.width).toInt()
                    if (h < 8) continue
                    for (dw in -6..6) {
                        for (dh in -6..6) {
                            val x = Math.round(cx - w / 2.0).toInt() + dw
                            val y = Math.round(cy - h / 2.0).toInt() + dh
                            if (x < 0 || y < 0 || x + w > bitmap.width || y + h > bitmap.height) {
                                continue
                            }
                            windows++
                            val s = matcher.match(
                                bitmap,
                                PetProfile.Probe("p", Rect(x, y, x + w, y + h), asset),
                                THR,
                            ).score
                            if (s > best) {
                                best = s
                                bestTag =
                                    "${asset.substringAfterLast('/')} ${w}x$h@($x,$y)"
                            }
                        }
                    }
                }
            }
            println(
                "ACADEMY_FEASIBILITY frame=${case.file} state=${case.state} " +
                    "measuredPlate=${case.plateW}x${case.plateH} windows=$windows " +
                    "best=$best via=$bestTag threshold=$THR clears=${best >= THR}"
            )
            assertTrue(
                "on ${case.file} (${case.state}) the authoritative matcher must clear 0.97 on a " +
                    "correctly positioned window; measured best was $best via $bestTag",
                best >= THR,
            )
            bitmap.recycle()
        }
    }

    /**
     * The focused state with ONLY the overview template is the negative control.
     *
     * It proves the second template is doing real work: without it the focused frames do not
     * resolve. If this ever passed, the focused template would be redundant.
     */
    @Test fun theOverviewTemplateAloneCannotResolveAFocusedFrame() = runBlocking {
        val matcher = TemplateMatcher(templateContext)
        val bitmap = frame("academy-label-original-failure.png")
        var best = 0.0
        val rect = Rect()
        for (dw in -3..3) {
            for (dh in -3..3) {
                rect.set(185 + dw, 352 + dh, 185 + dw + 216, 352 + dh + 83)
                val s = matcher.match(
                    bitmap,
                    PetProfile.Probe("p", Rect(rect), PetNavigation.ACADEMY_LABEL_ASSET),
                    THR,
                ).score
                if (s > best) best = s
            }
        }
        println("ACADEMY_OVERVIEW_ONLY_ON_FOCUSED best=$best")
        assertTrue(
            "the overview template alone must stay below 0.97 on the focused frame (best $best), " +
                "otherwise the focused template is redundant",
            best < THR,
        )
        bitmap.recycle()
    }

    /** The real frame the template was cropped from must resolve to one label, in one scan. */
    @Test fun theRealSourceFrameResolvesToExactlyOneLabel() = runBlocking {
        val bitmap = frame("academy-label-source.png")
        val template = template()
        val scratch = PetNavigation.LabelScratch()
        scratch.beginFrame(bitmap)
        val hit = PetNavigation.searchLabel(
            bitmap.width, bitmap.height, template.width, template.height, THR,
            scoreAt = { x, y -> scratch.scoreWindow(bitmap, x, y, template) ?: 0.0 },
            deadlineMs = System.currentTimeMillis() + DEADLINE_MS,
        )
        assertNotNull("the label must be found in the frame it was cropped from", hit)
        assertEquals("exactly one label must qualify", 1, hit!!.clusters)
        assertTrue("score ${hit.score} must clear the unchanged 0.97 threshold", hit.score >= THR)
        // The measured pill centre. Host replay put it here; the device must agree.
        assertEquals("centre x", 193.5f, hit.centreX, 4f)
        assertEquals("centre y", 422.0f, hit.centreY, 4f)
        bitmap.recycle()
    }

    /**
     * CRITERION 1, SECOND HALF: the fixed-size comparator is NEGATIVE on the focused fixture, and
     * that is now the expected and correct behaviour rather than a failure.
     *
     * The template was cropped at the panoramic plate size, so a fixed 177x66 window cannot clear
     * 0.97 on the focused frame. This assertion is retained deliberately, and it is exactly why
     * criterion 1's multi-scale feasibility test above exists: it proves the focused frame is
     * recoverable by MEASURING its plate, not by relaxing a threshold. A negative fixed-size
     * comparator is the control that makes the multi-scale result meaningful.
     */
    @Test fun theFixedSizeComparatorStaysNegativeOnTheFocusedFrame() = runBlocking {
        val bitmap = frame("academy-label-original-failure.png")
        val template = template()
        val scratch = PetNavigation.LabelScratch()
        scratch.beginFrame(bitmap)
        var best = 0.0
        val hit = PetNavigation.searchLabel(
            bitmap.width, bitmap.height, template.width, template.height, THR,
            scoreAt = { x, y ->
                val s = scratch.scoreWindow(bitmap, x, y, template) ?: 0.0
                if (s > best) best = s
                s
            },
            deadlineMs = System.currentTimeMillis() + DEADLINE_MS,
        )
        assertNull("a fixed-size window must not match the focused frame", hit)
        assertTrue(
            "the fixed-size best window must stay below 0.97 on the focused frame, was $best",
            best < THR,
        )
        println("ACADEMY_FIXED_SIZE_NEGATIVE best=$best")
        bitmap.recycle()
    }

    /**
     * The recorded fixed-size ground truth on the source frame is REUSED, not recomputed.
     *
     * The exhaustive per-window sweep already proved exactly (104,389), (105,389), (106,389) with
     * best 1.0 at (105,389). Re-running it costs ~9 minutes and proves nothing new, so this test
     * instead asserts that the fast path still reproduces those three known hits on a BOUNDED
     * region around them, which is what the candidate locator actually evaluates.
     */
    @Test fun theFastPathReproducesTheAuthoritativeThreeHitsExactly() = runBlocking {
        val bitmap = frame("academy-label-source.png")
        val template = template()
        val scratch = PetNavigation.LabelScratch()
        scratch.beginFrame(bitmap)
        val hits = ArrayList<String>()
        var best = 0.0
        var bestX = -1
        var bestY = -1
        // Bounded around the known ground truth. This is not a weaker claim about the metric: the
        // exhaustive proof is retained as the recorded constant, and what is re-checked here is
        // that the shipping scorer still reproduces it after every change.
        for (y in 380..398) {
            for (x in 95..115) {
                val s = scratch.scoreWindow(bitmap, x, y, template) ?: continue
                if (s > best) { best = s; bestX = x; bestY = y }
                if (s >= THR) hits += "($x,$y)"
            }
        }
        hits.sort()
        assertEquals(
            "the fast path must reproduce the recorded authoritative hits",
            listOf("(104,389)", "(105,389)", "(106,389)"), hits,
        )
        assertEquals("the best window is the perfect match at (105,389)", 105, bestX)
        assertEquals("the best window is the perfect match at (105,389)", 389, bestY)
        assertEquals("the best window scores exactly 1.0", 1.0, best, 0.0)
        bitmap.recycle()
    }

    /**
     * Two qualifying plates must be reported as ambiguous, never resolved into a tap.
     *
     * The duplicate is produced by pasting an exact copy of the real plate into the real frame,
     * so both clusters come from the real metric rather than a synthetic score table.
     */
    @Test fun aSecondRealPlateIsReportedAsAmbiguous() = runBlocking {
        // A decoded asset is immutable, so the frame under test must be a mutable copy before
        // anything can be painted into it.
        val decoded = frame("academy-label-source.png")
        val bitmap = decoded.copy(decoded.config ?: Bitmap.Config.ARGB_8888, true)
        decoded.recycle()
        val template = template()
        val plate = Bitmap.createBitmap(bitmap, 105, 389, template.width, template.height)
        // Paint an EXACT copy of the real plate into the real frame, so the second label is
        // genuine pixel content scored by the shipping metric, not an injected score.
        android.graphics.Canvas(bitmap).drawBitmap(plate, 105f, 1000f, null)
        plate.recycle()

        val scratch = PetNavigation.LabelScratch()
        scratch.beginFrame(bitmap)
        val hit = PetNavigation.searchLabel(
            bitmap.width, bitmap.height, template.width, template.height, THR,
            scoreAt = { x, y -> scratch.scoreWindow(bitmap, x, y, template) ?: 0.0 },
            deadlineMs = System.currentTimeMillis() + DEADLINE_MS,
        )
        assertNotNull("both labels must be found so the ambiguity is visible", hit)
        assertTrue("two qualifying plates must be ambiguous, got ${hit!!.clusters}", hit.clusters > 1)
        bitmap.recycle()
    }

    /**
     * A deadline reached mid-sweep must ABANDON the search, not conclude it with a partial
     * result, and must overshoot by no more than one row.
     */
    @Test fun aReachedDeadlineAbandonsTheSweepWithinOneRow() = runBlocking {
        val bitmap = frame("academy-label-source.png")
        val template = template()
        val scratch = PetNavigation.LabelScratch()
        scratch.beginFrame(bitmap)
        var windows = 0
        val hit = PetNavigation.searchLabel(
            bitmap.width, bitmap.height, template.width, template.height, THR,
            // The row boundary is 544 windows (720-177+1), so the deadline falls DURING row 4
            // and the per-row check can only notice at the start of row 5.
            nowMs = { (windows / 544L) * 1000L },
            deadlineMs = 3000L,
            scoreAt = { _, _ -> windows++; 0.0 },
        )
        assertNull("an overrunning sweep must be abandoned, not concluded", hit)
        assertTrue(
            "the sweep must stop within one row of the deadline, took $windows of 835040 windows",
            windows <= 5 * 544,
        )
        bitmap.recycle()
    }

    /** A deadline that is never reached must not change the answer. */
    @Test fun anUnreachedDeadlineDoesNotChangeTheResult() = runBlocking {
        val bitmap = frame("academy-label-source.png")
        val template = template()
        val scratch = PetNavigation.LabelScratch()
        scratch.beginFrame(bitmap)
        val score = { x: Int, y: Int -> scratch.scoreWindow(bitmap, x, y, template) ?: 0.0 }
        val without = PetNavigation.searchLabel(
            bitmap.width, bitmap.height, template.width, template.height, THR, scoreAt = score,
        )
        val with = PetNavigation.searchLabel(
            bitmap.width, bitmap.height, template.width, template.height, THR, scoreAt = score,
            nowMs = { 1L }, deadlineMs = Long.MAX_VALUE,
        )
        assertEquals(without!!.centreX, with!!.centreX, 0f)
        assertEquals(without.centreY, with.centreY, 0f)
        assertEquals(without.clusters, with.clusters)
        assertEquals(without.score, with.score, 0.0)
        bitmap.recycle()
    }

    /**
     * Cancelling the coroutine must interrupt the sweep instead of letting it outlive the
     * session, and no input may follow.
     */
    @Test fun aCancelledSweepAbandonsInsteadOfOutlivingTheSession() = runBlocking {
        val bitmap = frame("academy-label-source.png")
        val template = template()
        val scratch = PetNavigation.LabelScratch()
        scratch.beginFrame(bitmap)
        var cancelled = false
        var windows = 0
        val job = launch(Dispatchers.Default) {
            try {
                PetNavigation.searchLabel(
                    bitmap.width, bitmap.height, template.width, template.height, THR,
                    scoreAt = { _, _ -> windows++; 0.0 },
                )
            } catch (e: CancellationException) {
                cancelled = true
                throw e
            }
        }
        // Let the sweep get well into the frame, then stop the session underneath it.
        while (windows < 50_000) { kotlinx.coroutines.yield() }
        job.cancel()
        job.join()
        assertTrue("a cancelled sweep must surface cancellation, not return a result", cancelled)
        assertTrue(
            "cancellation must interrupt the scan, not let it run to the end ($windows windows)",
            windows < 835_040,
        )
        bitmap.recycle()
    }

    /**
     * The retained fast scorer's per-window cost, bounded to a REGION rather than a whole frame.
     *
     * The full-frame version of this measurement already ran and is recorded: 835,040 windows, i.e.
     * tens of seconds per sweep, which is why a full-frame sweep cannot fit the 8000 ms gate and
     * why the shipping locator is candidate-first. Re-running that ~34 s campaign would prove
     * nothing new, so this measures the same per-window cost on a bounded region and asserts only
     * REGRESSION - if the scorer gets far slower, the optimisation was lost.
     *
     * The ~17.6 us/window measured for [PetNavigation.LabelScratch] is NOT the shipping cost. The
     * shipping locator pays the public matcher's ~1.0 ms/window, measured separately in
     * `theShippingTwoFrameGateFitsItsBudgetOnThisDevice`; these two figures are a ~57x different
     * implementation and quoting the smaller one as the shipping cost is exactly the confusion this
     * comment exists to prevent. Run-to-run spread on this device is real and wide (17.6, 19.6,
     * 28.0, 32.5 us/window across four runs), which is why the regression bound below is deliberately
     * loose - it catches a lost optimisation, not a slow run.
     */
    @Test fun theFastScorerHoldsItsMeasuredPerWindowCostOnABoundedRegion() = runBlocking {
        val bitmap = frame("academy-label-source.png")
        val template = template()
        val scratch = PetNavigation.LabelScratch()
        scratch.beginFrame(bitmap)
        // A bounded 600-window region: enough to time the per-window cost, far from a full sweep.
        val samples = 600
        withContext(Dispatchers.Default) {
            var sink = 0.0
            // Warm up so JIT and the first canvas allocation are not charged to the mean.
            for (i in 0 until 100) {
                sink += scratch.scoreWindow(bitmap, 100 + (i % 20), 380 + (i % 20), template) ?: 0.0
            }
            val start = System.nanoTime()
            for (i in 0 until samples) {
                sink += scratch.scoreWindow(bitmap, 100 + (i % 20), 380 + (i % 20), template) ?: 0.0
            }
            val perWindowUs = (System.nanoTime() - start) / 1_000.0 / samples
            println(
                "ACADEMY_FAST_REGION_COST samples=$samples perWindowUs=$perWindowUs " +
                    "fullFrameWindows=835040 projectedFullScanMs=${perWindowUs * 835_040 / 1000.0} " +
                    "sink=$sink"
            )
            assertTrue(
                "the fast per-window path cost $perWindowUs us/window, far slower than the " +
                    "~17.6 us/window measured for it - the optimisation is not in effect",
                perWindowUs <= 20.3 * 4,
            )
        }
        bitmap.recycle()
    }

    /**
     * The SHIPPING locator must abandon on a deadline instead of concluding a partial search.
     *
     * `searchLabel`'s row-wise clock is pinned above, but the candidate locator has its own clock
     * checks around the proposal loop and inside every template neighbourhood. A deadline that has
     * already passed must return null - "abandon", never a coordinate derived from partial work.
     */
    @Test fun theShippingLocatorAbandonsOnceTheDeadlineHasPassed() = runBlocking {
        val bitmap = frame("academy-label-original-failure.png")
        val counter = PetNavigation.LabelLocator.WindowCounter()
        val hit = PetNavigation.LabelLocator.locate(
            frame = bitmap,
            templates = shippingTemplates(),
            bounds = PetNavigation.primaryAcademyTarget.plateBounds(
                PetNavigation.geometryFor(
                    bitmap.width, bitmap.height,
                    templateContext.resources.displayMetrics.densityDpi,
                )
            ),
            matcher = TemplateMatcher(templateContext),
            matchThreshold = PetProfile.calibrated().matchThreshold,
            // Expired before the search starts.
            deadlineMs = android.os.SystemClock.elapsedRealtime() - 1L,
            counter = counter,
        )
        println("ACADEMY_SHIPPING_DEADLINE hit=${hit != null} windows=${counter.count}")
        assertNull("an expired deadline must abandon the search, not conclude it", hit)
        bitmap.recycle()
    }

    /**
     * Cancelling the coroutine must interrupt the shipping locator, and no coordinate may survive.
     *
     * The handshake makes this deterministic rather than a race: the worker signals that it has
     * STARTED the locate, and only then is the job cancelled. The focused frame needs both
     * templates over a 9-width x 13x13-offset neighbourhood - thousands of real matcher windows,
     * ~2 s of measured work - so the cancellation lands mid-search rather than after it finished.
     * Without the handshake the search could complete first and the assertion would test nothing.
     */
    @Test fun aCancelledShippingLocateAbandonsInsteadOfOutlivingTheSession() = runBlocking {
        val bitmap = frame("academy-label-original-failure.png")
        val counter = PetNavigation.LabelLocator.WindowCounter()
        val started = CompletableDeferred<Unit>()
        var cancelled = false
        val job = launch(Dispatchers.Default) {
            try {
                started.complete(Unit)
                locate(bitmap, counter)
            } catch (e: CancellationException) {
                cancelled = true
                throw e
            }
        }
        started.await()
        job.cancel()
        job.join()
        println("ACADEMY_SHIPPING_CANCEL cancelled=$cancelled windowsBeforeCancel=${counter.count}")
        assertTrue(
            "a cancelled locate must surface cancellation rather than return a coordinate",
            cancelled,
        )
        bitmap.recycle()
    }

    /**
     * A cancelled locate must stop promptly instead of running the whole neighbourhood out.
     *
     * `withTimeout` is the deterministic form of the same check: the timeout is far shorter than
     * the ~2 s this frame's neighbourhood costs, so if the locator honoured cancellation it must
     * abandon almost immediately. The elapsed time is printed so the abandonment is measured
     * rather than assumed.
     */
    @Test fun theShippingLocatorStopsPromptlyWhenItsCoroutineIsCancelled() = runBlocking {
        val bitmap = frame("academy-label-original-failure.png")
        var cancelled = false
        val start = android.os.SystemClock.elapsedRealtime()
        try {
            withTimeout(80L) { locate(bitmap) }
        } catch (e: TimeoutCancellationException) {
            cancelled = true
        }
        val elapsedMs = android.os.SystemClock.elapsedRealtime() - start
        println(
            "ACADEMY_SHIPPING_CANCEL_PROMPT cancelled=$cancelled elapsedMs=$elapsedMs timeoutMs=80"
        )
        assertTrue("a timeout must cancel the locate, not let it finish", cancelled)
        assertTrue(
            "the locator must abandon on cancellation, but took ${elapsedMs} ms for an 80 ms " +
                "timeout - it is not checking the coroutine",
            elapsedMs < 2_000L,
        )
        bitmap.recycle()
    }

    /**
     * A NEAR-SIGNATURE second copy must be refused too, not only an exact duplicate.
     *
     * The exact-duplicate test pastes a byte-identical crop, which is the easy half. Criterion 2/6
     * also ask about a target that is *nearly* the same: a brighter/dimmer or slightly degraded copy
     * of the same label, which is what a second academy plate at a different map scale, or the same
     * plate under a HUD tint, would actually look like. The question is whether the locator notices
     * it as a SECOND physical label instead of merging it into one and calling the result unique.
     *
     * MEASURED FIRST, JUDGED SECOND - the rule that this test exists to honour. The perturbed paste
     * is only a valid near-signature case if the real public matcher actually scores it at or above
     * the unchanged 0.97; a perturbation that drops below the threshold is a *different* case (a
     * low-contrast near-miss the locator is allowed to ignore) and must not be reported as a missed
     * duplicate. So the perturbed score is measured through `TemplateMatcher.match` and printed, the
     * test asserts it clears 0.97, and only then asserts the locator refuses two labels. Synthetic
     * perturbation of a REAL frame is used deliberately: no archived bad frame has to be conjured.
     */
    @Test fun aNearSignatureSecondCopyIsRefusedRatherThanMergedIntoOne() = runBlocking {
        // The measured 1.0 window on the overview frame: the real plate at its real size.
        val base = frame("academy-label-source.png")
        val plate = Bitmap.createBitmap(base, 105, 389, 177, 66)

        // A "near signature": same shape and text, uniformly darkened. This is the mildest change
        // that degrades the plate's pixels without touching its geometry, standing in for a second
        // plate seen through a HUD tint or at a slightly different exposure.
        //
        // THE STRENGTH IS MEASURED, NOT GUESSED. At 24x24 the metric compares 576 samples and
        // tolerates a total L1 of 13219.2 at 0.97, i.e. ~7.65 per channel per sample, so only a FEW
        // PERCENT of darkening can stay above the threshold: a veil of alpha 38 was measured at
        // 0.8899, far below. Rather than hardcode a magic alpha, a small ladder is scored through
        // the real public matcher and the STRONGEST veil that still clears 0.97 is used. The ladder
        // and the chosen score are printed, so the case is demonstrably a near-signature at the
        // unchanged threshold and the test stays valid if the metric or assets ever shift.
        val matcher = TemplateMatcher(templateContext)
        val profile = PetProfile.calibrated()
        fun veiled(alpha: Int): Bitmap {
            val out = Bitmap.createBitmap(177, 66, Bitmap.Config.ARGB_8888)
            val c = Canvas(out)
            c.drawBitmap(plate, 0f, 0f, null)
            c.drawRect(0f, 0f, 177f, 66f, Paint().apply { color = Color.argb(alpha, 0, 0, 0) })
            return out
        }
        val ladder = listOf(4, 8, 12, 16, 20, 26)
        val chosen = ladder
            .map { alpha -> alpha to veiled(alpha) }
            .map { (alpha, bmp) ->
                Triple(alpha, bmp, matcher.match(
                    bmp,
                    PetProfile.Probe("probe", Rect(0, 0, 177, 66), PetNavigation.ACADEMY_LABEL_ASSET),
                    profile.matchThreshold,
                ).score)
            }
            .filter { (_, _, s) -> s >= THR }
            // The STRONGEST veil that still clears the threshold: the closest near-signature to the
            // decision boundary, which is the case most likely to expose a merge bug.
            .maxByOrNull { (alpha, _, _) -> alpha }
        assertNotNull(
            "none of the veil ladder $ladder stayed at or above the unchanged 0.97 - the metric's " +
                "tolerance for this plate is smaller than the ladder's mildest step, so the " +
                "near-signature case cannot be constructed this way and must not be faked",
            chosen,
        )
        val (chosenAlpha, perturbed, isolatedScore) = chosen!!

        // Paste the perturbed copy far enough away to be a genuinely distinct physical label.
        val composite = base.copy(Bitmap.Config.ARGB_8888, true)
        Canvas(composite).drawBitmap(perturbed, 60f, 1100f, null)

        // MEASURE: does the real public matcher accept the pasted copy at the unchanged 0.97? This is
        // the window the locator will actually see, so it - not the isolated plate - is the score the
        // validity of the case rests on.
        val probe = PetProfile.Probe(
            "near-signature", Rect(60, 1100, 60 + 177, 1100 + 66),
            PetNavigation.ACADEMY_LABEL_ASSET,
        )
        val perturbedScore = matcher.match(composite, probe, profile.matchThreshold).score
        println(
            "ACADEMY_NEAR_SIGNATURE_CONFIRM chosenAlpha=$chosenAlpha isolatedScore=$isolatedScore " +
                "pastedScore=$perturbedScore threshold=$THR"
        )
        assertTrue(
            "the perturbation must remain a near-signature at or above the unchanged 0.97 to be a " +
                "valid duplicate case; it scored $perturbedScore - use a milder perturbation",
            perturbedScore >= THR,
        )

        // JUDGE: a second qualifying label must be refused, not merged into one and called unique.
        val hit = locate(composite)
        println(
            "ACADEMY_NEAR_SIGNATURE clusters=${hit?.clusters} candidates=${hit?.candidates} " +
                "accepted=${hit?.accepted} score=${hit?.score}"
        )
        assertNotNull("the near-signature composite must still be searched", hit)
        assertTrue(
            "a near-signature second copy at $perturbedScore must be refused as a second label, " +
                "but the locator reported ${hit!!.clusters} distinct label(s)",
            hit.clusters != 1,
        )
        base.recycle(); composite.recycle(); plate.recycle(); perturbed.recycle()
    }

    /**
     * The already-open course panel must be DETECTABLE on a fresh frame, which is what makes the
     * production "no entry tap" branch reachable rather than aspirational.
     *
     * Criterion 4/6 require that an already-open panel returns without an entry tap, and that the
     * post-tap check uses the real panel probe at 0.97. Production decides that with
     * `score(frame, listOf(profile.courseListProbe))` against the calibrated panel rect. This test
     * exercises the SAME probe and threshold through the public matcher on (a) the real academy-map
     * frame, where the panel is genuinely absent, and (b) that frame with the real shipped
     * `course_list.png` composited into the calibrated panel rect, which is what an open panel puts
     * on screen. Both scores are printed, and the test asserts the discrimination is real in BOTH
     * directions - a guard that fires on everything would send no input ever, and one that never
     * fires would tap a panel that is already up and toggle it closed (the real
     * `PAGE_MISMATCH:SELECT_COURSE` failure this repair exists to fix).
     *
     * The composite is labelled as synthetic: it is the real template bitmap placed at the real
     * calibrated rect, which is a faithful stand-in for the panel's pixels but is NOT a captured
     * frame. No input is sent and no Service is started.
     */
    @Test fun theCoursePanelProbeDiscriminatesOpenFromAbsentOnFreshFrames() = runBlocking {
        val profile = PetProfile.calibrated()
        val matcher = TemplateMatcher(templateContext)
        val panelRect = profile.courseListProbe.rect
        val base = frame("academy-label-source.png")

        val absentScore = matcher.match(base, profile.courseListProbe, profile.matchThreshold).score

        val panelAsset = BitmapFactory.decodeStream(
            templateContext.assets.open("pet_mvp/course_list.png")
        )
        assertNotNull("the shipped course-panel template must exist", panelAsset)
        val open = base.copy(Bitmap.Config.ARGB_8888, true)
        Canvas(open).drawBitmap(
            Bitmap.createScaledBitmap(
                panelAsset!!, panelRect.width(), panelRect.height(), true,
            ),
            panelRect.left.toFloat(), panelRect.top.toFloat(), null,
        )
        val openScore = matcher.match(open, profile.courseListProbe, profile.matchThreshold).score

        println(
            "ACADEMY_PANEL_PROBE rect=${panelRect.left},${panelRect.top}," +
                "${panelRect.right},${panelRect.bottom} absentOnRealMap=$absentScore " +
                "openOnComposite=$openScore threshold=$THR"
        )
        assertTrue(
            "the panel must be positively ABSENT on the real academy-map frame so the entry tap is " +
                "reachable, but the probe scored $absentScore",
            absentScore < THR,
        )
        assertTrue(
            "the panel must be positively OPEN on the composite so the no-tap branch is reachable, " +
                "but the probe scored $openScore",
            openScore >= THR,
        )
        base.recycle(); open.recycle(); panelAsset.recycle()
    }
}
