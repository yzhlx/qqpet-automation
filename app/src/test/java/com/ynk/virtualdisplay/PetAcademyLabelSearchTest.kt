package com.ynk.virtualdisplay

import com.ynk.virtualdisplay.automation.PetNavigation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression gate for the academy-label search: it must find a label, refuse a frame that has
 * none, and refuse a frame that has two, all at the unchanged 0.97 threshold.
 *
 * PROVENANCE, READ THIS BEFORE TRUSTING A NUMBER HERE. This drives the REAL
 * `PetNavigation.searchLabel` - the same call `openAcademyCourseList` makes - but it feeds it a
 * score table recorded on the HOST with Pillow's bilinear resize standing in for Android's
 * `Bitmap.createScaledBitmap(filter=true)`. That is a structural approximation of the same
 * operation, NOT a bit-identical one, and no score in the fixtures is a device measurement.
 *
 * So this file pins the SEARCH GEOMETRY and the FAIL-CLOSED CONTROL FLOW at the real threshold -
 * that a unique label is found, an absent one is refused, a duplicated one is ambiguous, and a
 * slow or cancelled sweep is abandoned rather than concluded. It does NOT prove what the
 * shipping bitmap path computes on a device. `PetAcademyLabelSearchDeviceTest` (androidTest) is
 * what runs the real Android metric on real frames; it also proves the fast per-window path is
 * bit-identical to the literal transcription of `TemplateMatcher.similarity`.
 *
 * The search takes a frame SIZE plus a scorer, so this needs no Android graphics at all.
 */
class PetAcademyLabelSearchTest {
    private companion object {
        const val W = 720
        const val H = 1600
        const val TW = 177
        const val TH = 66
        const val THR = 0.97
    }

    /** Set when a cancelled sweep surfaces its CancellationException, for the cancel test. */
    @Volatile private var cancelled = false

    /** Parses `x,y,score` lines into (window key, score) pairs for one recorded frame. */
    private fun scorer(entries: List<Pair<Long, Double>>): (Int, Int) -> Double {
        val table = HashMap<Long, Double>()
        for ((key, score) in entries) table[key] = score
        return { x, y ->
            table[y.toLong() * (W - TW + 1) + x] ?: 0.0
        }
    }

    private fun parse(text: String): List<Pair<Long, Double>> = text.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") }
        .map { line ->
            val parts = line.split(",")
            val x = parts[0].trim().toInt()
            val y = parts[1].trim().toInt()
            val s = parts[2].trim().toDouble()
            assertTrue("score $s at ($x,$y) must be inside [0,1]", s in 0.0..1.0)
            (y.toLong() * (W - TW + 1) + x) to s
        }
        .toList()

    private val templateSource: List<Pair<Long, Double>> = parse(
        javaClass.getResourceAsStream("/academy-label-source-scores.txt")!!.readBytes().decodeToString()
    )
    private val realFailureFrame: List<Pair<Long, Double>> = parse(
        javaClass.getResourceAsStream("/academy-label-absent-scores.txt")!!.readBytes().decodeToString()
    )
    private val duplicateLabel: List<Pair<Long, Double>> = parse(
        javaClass.getResourceAsStream("/academy-label-duplicate-scores.txt")!!.readBytes().decodeToString()
    )

    private suspend fun search(entries: List<Pair<Long, Double>>) =
        PetNavigation.searchLabel(W, H, TW, TH, THR, scoreAt = scorer(entries))

    /**
     * The frame the template was cropped from: exactly one label, at the measured centre.
     *
     * This is the case that broke the previous implementation. The label peaks at exactly
     * 1.000000 at (105,389), but a strictly-improving 1 px hill climb stalls at ~0.9116 at
     * (98,389) and never reaches it, so a greedy climb-based search misses a perfect match.
     */
    @Test fun templateSourceFrameResolvesToExactlyOneLabelAtTheMeasuredCentre() = runBlocking {
        val hit = search(templateSource)
        assertNotNull("the label must be found in the frame it was cropped from", hit)
        assertEquals("exactly one label must qualify", 1, hit!!.clusters)
        assertTrue("score ${hit.score} must clear the unchanged 0.97 threshold", hit.score >= THR)
        assertEquals("centre x", 193.5f, hit.centreX, 4f)
        assertEquals("centre y", 422.0f, hit.centreY, 4f)
    }

    /**
     * The other real device frames that carry the label must all resolve to the same single
     * centre. The previous implementation returned NULL on every one of them, so the academy
     * entry could never be tapped even though the label was plainly on screen.
     */
    @Test fun everyOtherRealFrameCarryingTheLabelResolvesToOneLabel() = runBlocking {
        val others = listOf(
            "slice2-final/academy-anim/where-now.png" to "/academy-label-where-now-scores.txt",
            "slice2-final/academy-hud/hud-before.png" to "/academy-label-hud-before-scores.txt",
            "slice2-final/academy-failure/live-after-tap.png" to "/academy-label-live-after-tap-scores.txt",
            "test-evidence/academy-map-after-back.png" to "/academy-label-after-back-scores.txt",
        )
        for ((frame, resource) in others) {
            val entries = parse(javaClass.getResourceAsStream(resource)!!.readBytes().decodeToString())
            val hit = search(entries)
            assertNotNull("no label found in $frame, but it carries the label", hit)
            assertEquals("exactly one label must qualify in $frame", 1, hit!!.clusters)
            assertTrue("score ${hit.score} in $frame must clear 0.97", hit.score >= THR)
            assertEquals("centre x in $frame", 193.5f, hit.centreX, 4f)
            assertEquals("centre y in $frame", 422.0f, hit.centreY, 4f)
        }
    }

    /**
     * The real run that failed to enter the academy: nothing there reaches 0.97, so the search
     * must return null, and the caller must then send no input at all.
     */
    @Test fun theRealFailureFrameResolvesToNothingSoNoInputIsSent() = runBlocking {
        assertNull(
            "a frame with no qualifying label must fail closed, never fall back to a coordinate",
            search(realFailureFrame),
        )
    }

    /**
     * Two qualifying labels are an ambiguity and must never be resolved into a tap on one of
     * them. This is the case the previous top-N-seeds uniqueness check got wrong: it saw only
     * the neighbourhood of its favourite seed and reported a single label.
     */
    @Test fun twoQualifyingLabelsAreReportedAsAmbiguousNotSilentlyResolved() = runBlocking {
        val hit = search(duplicateLabel)
        assertNotNull("both labels must be found so the ambiguity is visible", hit)
        assertTrue(
            "two qualifying labels must be reported as ambiguous, got ${hit!!.clusters} cluster(s)",
            hit.clusters > 1,
        )
    }

    /** A frame too small to hold the template can never be asserted on. */
    @Test fun aFrameSmallerThanTheTemplateIsRefused() = runBlocking {
        val hit = PetNavigation.searchLabel(100, 40, TW, TH, THR, scoreAt = { _, _ -> 1.0 })
        assertNull("a frame that cannot hold the label must not be asserted on", hit)
    }

    /**
     * The sweep is bounded by a deadline because one 720x1600 frame is 835,040 windows. A frame
     * that outruns it must be ABANDONED, not concluded: a partial scan that returned a result
     * would have searched only part of the frame, so a second label in the unscanned remainder
     * would be invisible and the frame would be resolved into a tap. It must return null.
     */
    @Test fun aSweepThatOverrunsItsDeadlineIsAbandonedNotConcluded() = runBlocking {
        // A frame whose entire left edge qualifies: a partial scan would find a label and stop.
        val leftEdge = duplicateLabel.filter { (key, _) ->
            val x = (key % (W - TW + 1)).toInt()
            x < 200
        }
        assertTrue("fixture must have qualifying windows early in the sweep", leftEdge.isNotEmpty())

        var ticks = 0L
        val hit = PetNavigation.searchLabel(
            W, H, TW, TH, THR, scorer(leftEdge),
            nowMs = { ++ticks * 1000L },
            deadlineMs = 3000L,
        )
        assertNull("an overrunning sweep must be abandoned, not concluded with a partial result", hit)
    }

    /** A deadline that is never reached must not change the answer. */
    @Test fun aReachedDeadlineDoesNotChangeTheResult() = runBlocking {
        val withoutDeadline = search(templateSource)
        val withDeadline = PetNavigation.searchLabel(
            W, H, TW, TH, THR, scorer(templateSource),
            nowMs = { 1L }, deadlineMs = Long.MAX_VALUE,
        )
        assertNotNull(withoutDeadline)
        assertNotNull(withDeadline)
        assertEquals(withoutDeadline!!.centreX, withDeadline!!.centreX, 0f)
        assertEquals(withoutDeadline.centreY, withDeadline.centreY, 0f)
        assertEquals(withoutDeadline.clusters, withDeadline.clusters)
        assertEquals(withoutDeadline.score, withDeadline.score, 0.0)
    }

    /**
     * Every recorded window that qualifies must be assigned to exactly one cluster, so no
     * qualifying label can be dropped from the uniqueness decision.
     */
    @Test fun everyQualifyingWindowIsAccountedForInSomeCluster() = runBlocking {
        val entries = duplicateLabel
        val qualifying = entries.filter { it.second >= THR }
        assertTrue("the duplicate fixture must contain qualifying windows", qualifying.isNotEmpty())
        val hit = search(entries)!!
        // The fixture is two well-separated plates, so exactly two clusters, and both plates
        // must be represented: a search that collapsed them into one would tap one of them.
        assertEquals("two separated plates are two clusters", 2, hit.clusters)
    }

    /**
     * Cancelling the coroutine must interrupt the sweep, so a session stop cannot be outlived
     * by a scan that is still burning CPU.
     *
     * Without `ensureActive()` in the row loop the sweep would run all 835,040 windows to
     * completion after the job was cancelled, and the caller would then be acting on a result
     * nobody is waiting for.
     */
    @Test fun aCancelledSweepAbandonsInsteadOfRunningTheWholeFrame() = runBlocking {
        var windows = 0
        val job = launch(Dispatchers.Default) {
            try {
                PetNavigation.searchLabel(
                    W, H, TW, TH, THR,
                    scoreAt = { _, _ -> windows++; 0.0 },
                )
            } catch (e: CancellationException) {
                cancelled = true
                throw e
            }
        }
        while (windows < 20_000) { yield() }
        job.cancel()
        job.join()
        assertTrue("a cancelled sweep must surface cancellation, not return a result", cancelled)
        assertTrue(
            "cancellation must interrupt the scan, not let it run to the end ($windows windows)",
            windows < 835_040,
        )
    }

    /**
     * A sweep that overruns its deadline mid-scan must be ABANDONED, and the clock is sampled
     * per ROW, so it may not overshoot by more than one row.
     *
     * The scorer never qualifies a window, so clustering has nothing to do and the assertion
     * measures the SCAN's deadline behaviour alone. (With every window qualifying, clustering
     * would be quadratic and the scan would have finished regardless.)
     */
    @Test fun aSweepAbandonsWithinOneRowOfItsDeadline() = runBlocking {
        var windows = 0
        val hit = PetNavigation.searchLabel(
            W, H, TW, TH, THR,
            // The row boundary is 544 windows (720-177+1), so this crosses the deadline DURING
            // row 4 and the per-row check can only notice at the start of row 5. Sampling the
            // clock per row means the overshoot is bounded by one row, not by the whole frame.
            nowMs = { (windows / 544L) * 1000L },
            deadlineMs = 3000L,
            scoreAt = { _, _ -> windows++; 0.0 },
        )
        assertNull("an overrunning sweep must be abandoned, not concluded", hit)
        assertTrue(
            "the sweep must stop within one row of the deadline, took $windows of 835040 windows",
            windows <= 5 * 544,
        )
    }

    /**
     * The deadline is re-checked AFTER the scan and inside the clustering loop, so a frame that
     * finishes scanning in time but overruns while clustering is still abandoned, not reported
     * as a completed search.
     */
    @Test fun aDeadlineReachedAfterTheScanAlsoAbandonsTheSearch() = runBlocking {
        // Every window qualifies, so clustering has real work to do after the scan completes.
        var checks = 0
        val hit = PetNavigation.searchLabel(
            W, H, TW, TH, THR,
            // Reach the deadline only after the scan's own per-row checks have all passed.
            nowMs = { if (++checks <= H - TH) 0L else 1_000_000L },
            deadlineMs = 1_000_000L,
            scoreAt = { _, _ -> 1.0 },
        )
        assertNull("a sweep that overruns during clustering must be abandoned", hit)
    }

    /**
     * Only this function's OWN complete settle-time non-match may lead to a second tap.
     *
     * The classification is positive, so the representative stop reasons from the other
     * production files are pinned here as NOT retryable. They are taken verbatim from the
     * `error(...)`/`check(...)` sites in SessionRecovery, PetRecoveryBoundary, ShizukuActiveCapture,
     * PetStudyFlow, PetStudyHealthPolicy, PetManagedRuntime, FrameLiveness, PetInputController,
     * PetAutomationService and PetStateMachine, none of which is in scope for this change, so
     * they cannot be produced on the JVM - the message string is the contract under test.
     *
     * This matters because the earlier implementation classified by EXCLUSION ("retry unless it
     * looks like a stop reason"), so any NEW failure mode that nobody had listed would have been
     * read as "the panel simply did not appear" and tapped again.
     */
    @Test fun onlyTheOwnSettleTimeNonMatchIsRetryable() {
        val name = "enter-primary-academy-after"
        // Exactly the shape `PetFlowContext.verifySettled` raises: PAGE_MISMATCH:<state>:<name>:<score>.
        val retryable = PetNavigation.retryablePanelFailure("PAGE_MISMATCH:SELECT_COURSE:$name:0.51", name)
        assertTrue("this probe's own complete non-match is the one retryable case", retryable)

        // Representative stop reasons, one per file, that must NEVER become a retry.
        val notRetryable = listOf(
            "SESSION_STOPPED",                                                  // PetRecoveryBoundary
            "RECOVERY_ALREADY_STOPPED",                                         // SessionRecovery
            "SHIZUKU_CAPTURE_FAILED:no permission",                             // ShizukuActiveCapture
            "CAPTURE_CAPTURE_FAILED",                                           // ShizukuActiveCapture
            "CAPTURE_TIMEOUT:awaitFrame",                                       // PetFlowContext
            "FRAME_NOT_TRUSTED:3",                                              // FrameLiveness
            "FRAME_FAILURE_LIMIT",                                              // HeadlessDisplayCapture
            "DISPLAY_UNAVAILABLE",                                              // PetInputController
            "RESULT_TIMEOUT",                                                   // PetStudyFlow
            "UNKNOWN_WAIT_RESULT_PAGE",                                         // PetStudyFlow
            "LEARNING_NOT_CONFIRMED_AFTER_START",                               // PetStudyFlow
            "HEALTH_VALUE_ABNORMAL",                                            // PetStudyHealthPolicy
            "HEALTH_UNKNOWN:stale",                                             // ObservedValue
            "ENCOURAGEMENT_LIMIT",                                              // PetManagedRuntime
            "CHECKPOINT_OBSERVATION_INVALID",                                   // FileCheckpointStore
            "EVENT_JOURNAL_INVALID",                                            // SessionEvent
            "Only the calibrated FOA-AL00 device is supported",                  // PetAutomationService
            "Cannot verify STOPPED_ERROR",                                      // PetStateMachine
            // A foreign probe's mismatch, and a mismatch carrying a trailing reason: both are
            // not this function's own plain non-match, so neither may become a retry.
            "PAGE_MISMATCH:SELECT_COURSE:some-other-probe:0.51",
            "PAGE_MISMATCH:SELECT_COURSE:$name:0.51:recovered",
            // A stop reason that merely CONTAINS the probe name, so a positive prefix/field
            // match cannot be spoofed by an unrelated failure mentioning it.
            "PANEL_NOT_OPEN:$name:0.51",
        )
        for (reason in notRetryable) {
            assertFalse(
                "'$reason' must never be converted into a retry",
                PetNavigation.retryablePanelFailure(reason, name),
            )
        }
    }
}
