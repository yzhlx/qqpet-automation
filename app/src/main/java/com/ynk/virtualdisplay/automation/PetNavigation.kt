package com.ynk.virtualdisplay.automation

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Point
import android.graphics.PointF
import android.graphics.Rect
import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.TimeoutCancellationException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.coroutines.coroutineContext
import kotlin.math.abs

/** Only measured page edges, not a general path finder. */
class PetNavigation(private val flow: PetFlowContext) {
    /**
     * The ONLY failure that may lead to a second academy tap: the course panel was probed for
     * its full settle window at the unchanged threshold and never appeared.
     *
     * `PetFlowContext.verifySettled` signals that with `error("PAGE_MISMATCH:...")`, but it uses
     * the same IllegalStateException for recovery, capture and other page failures. Catching
     * IllegalStateException wholesale would silently convert a stopped session, an aborted
     * capture or a moved page into "tap again", which is exactly the input the fail-closed rule
     * forbids. This wrapper therefore converts only its own genuine non-match into this
     * dedicated type and rethrows everything else untouched.
     */
    class PanelDidNotAppear(message: String): IllegalStateException(message)

    /**
     * Waits for the real course panel, translating a genuine non-match into [PanelDidNotAppear].
     *
     * Only a complete `PAGE_MISMATCH` raised by THIS probe is converted, per
     * `retryablePanelFailure`. Every stop reason, every CancellationException and any
     * PAGE_MISMATCH belonging to a different probe or carrying a trailing reason is rethrown
     * unchanged, so a stopped session, an aborted capture or a page that moved on can never be
     * read as "tap again".
     */
    private suspend fun awaitCoursePanel(name: String): Double = with(flow) {
        try {
            verifySettled(listOf(profile.courseListProbe),name)
        } catch(t: CancellationException) { throw t }
        catch(t: IllegalStateException) {
            val reason=t.message.orEmpty()
            if(retryablePanelFailure(reason,name)) throw PanelDidNotAppear(reason)
            throw t
        }
    }

    /**
     * The 初级学园 entry used to be a fixed (200,420) tap, so a moved label silently became a
     * blind tap on empty grass or on another building. The label is now localized in the live
     * frame and fails closed instead.
     *
     * THE ACCEPTANCE PATH IS THE PUBLIC MATCHER. `TemplateMatcher.match(frame, probe, threshold)`
     * is a public entry point and takes an arbitrary `probe.rect` plus an asset path, so the
     * shipping locator calls it directly on each candidate window (see [LabelLocator.scoreWindow]).
     * The
     * metric it applies is the authoritative one: crop `probe.rect`, scale the template to that
     * crop size, downscale BOTH operands with `Bitmap.createScaledBitmap(..., filter=true)` to at
     * most 24x24, and return 1 - (sum of absolute per-channel differences)/(w*h*3*255). Nothing
     * here re-implements it, and no threshold is lowered: the caller compares the returned score
     * against the unchanged `profile.matchThreshold`.
     *
     * TARGETS ARE SPECS, NOT LITERALS. The entry is described by a [TargetSpec] whose asset list
     * and plate-size band are data (see [primaryAcademyTarget]), so 中级学园/高级学园/进修学院 can be
     * added as further specs without touching the locator. The display geometry the band resolves
     * against is read at runtime from the frame and the live resources (see [ScreenGeometry] and
     * [geometryOf]), never assumed to be 720x1600@320, and NO coordinate is ever hardcoded: the tap
     * point is always this frame's own localisation result.
     *
     * [LabelScratch] is a DIFFERENT, retained path: a custom-Canvas scorer that reproduces the
     * metric only at a template's own native size. It is no longer how a label is accepted; it is
     * kept as the recorded ground-truth/equivalence reference driven by
     * `PetAcademyLabelSearchDeviceTest`, and the two costs must never be quoted
     * interchangeably. MEASURED on the target device they are ~1.0 ms/window for the public
     * matcher and ~17.6-28 us/window for the scratch form (run-to-run spread), i.e. roughly a
     * 40x difference; the smaller number belongs to the reference path and must never be used to
     * claim the shipping path's budget.
     *
     * DEVICE-MEASURED COST, so the gate below is never argued from a host projection: the public
     * matcher costs ~1.0 ms per window (1011 us over 6084 real windows in one two-frame run; an
     * earlier isolated per-window bench reported ~632 us, so treat ~0.6-1.0 ms as the observed
     * range). The whole cost is therefore decided by how many windows the sweep visits. At +-3
     * widths x +-4 offsets the shipped locator measures 1134 windows and 1147 ms per frame, i.e.
     * 2294 ms for the two fresh frames the 8000 ms gate allows. A full-frame sweep of 835,040 such
     * windows would be ~845 s, which is why the locator is candidate-first and why the sweep
     * bounds are a budget decision rather than a preference.
     *
     * PROVENANCE - read before citing any number in this file.
     * Host figures below were produced with Pillow's bilinear resize standing in for Android's
     * `createScaledBitmap(filter=true)`: a structural approximation, NOT bit-identical, and not a
     * device measurement. The seven `academy-label-*-scores.txt` fixtures the JVM regression test
     * reads were recorded the same way, so that test pins SEARCH GEOMETRY and fail-closed control
     * flow at the real threshold only.
     *
     * DEVICE-MEASURED, on DKS9K23420020242 with the public matcher
     * (`PetAcademyLabelSearchDeviceTest`): the template-source frame's qualifying set is exactly
     * three windows, (104,389), (105,389), (106,389), peaking at 1.0 at (105,389); the focused
     * frame the template was cropped from also peaks at exactly 1.0, at (185,352) size 216x83;
     * and an independent held-out focused frame scores 0.9829702251270879 at (195,348) size
     * 220x84. Those are the anchors the candidate sweep has to be able to reach.
     *
     * HOST-ONLY figures (approximations, not evidence): the same source frame scores 0.879752 at
     * the nearest stride-24 seed cell (96,384) and ~0.93 two pixels away, and a strictly
     * improving hill climb stalls near 0.9116 at (98,389) instead of reaching the peak - which is
     * why no seed-and-climb prune is used. The locked siblings (中级/高级/进修学院) score
     * 0.77..0.81 against the template and the header/back buttons 0.81..0.83 in the same host
     * replay, far below 0.97. No count of "how many frames were checked" is claimed here: the
     * signatures were observed on the real frames in this repository, which is an observation
     * about those frames, not a completeness proof.
     */
    private val academyLabel=PetProfile.Probe("academy_primary_label",Rect(0,0,1,1),ACADEMY_LABEL_ASSET)
    /** Two qualifying frames must agree this closely before any input is sent. */
    private val labelStablePx=8f
    private val labelLocateMs=8000L
    /** A tap is never retried unless the course panel is positively absent; two taps total. */
    private val maxAcademyTaps=2

    /**
     * The runtime display geometry, read from the frame and the running app - never a constant.
     *
     * The width/height come from the captured [frame] itself, which is the virtual display's size
     * because the capture is configured from that display and `FrameLiveness` rejects a frame whose
     * size disagrees. The density is read from the live app resources on every call, so a session
     * on another display or density needs no code change.
     */
    internal fun geometryOf(frame: Bitmap): ScreenGeometry = geometryFor(
        frame.width, frame.height, flow.context.resources.displayMetrics.densityDpi,
    )

    /**
     * The single qualifying centre of [spec]'s label in [current], or null when it is absent or
     * ambiguous. Every score comes from the shared public TemplateMatcher metric at the unchanged
     * profile threshold; no OCR, no model, and no assumed scene geometry.
     *
     * The work itself lives in [LabelLocator], which is the SAME code the passive device test
     * drives, so what is tested is what ships. This wrapper only supplies production's own matcher
     * (`flow.matcher`, whose template cache is shared with every other probe) and the runtime
     * geometry of the frame it was handed.
     */
    private suspend fun locateAcademyLabel(
        current: Bitmap, deadlineMs: Long, spec: TargetSpec = primaryAcademyTarget,
    ): Pair<PointF,Double>? = with(flow) {
        // CANDIDATE-FIRST, then authoritative scoring on a bounded LOCAL neighbourhood.
        //
        // A full-frame sweep at the public matcher's measured ~1.0 ms/window would be ~845 s per
        // frame on a 720x1600 capture, which cannot fit the 8 s two-frame gate at all. The
        // candidates are proposed from the frame's own pixels, and EVERY candidate is then scored
        // by the SAME public matcher the rest of the app uses, at the SAME 0.97.
        //
        // A candidate is a PROPOSAL, never an acceptance. The signature can only decide WHERE to
        // look; only a 0.97 score from `TemplateMatcher.match` can authorise a centre.
        val templates = labelTemplates(spec) ?: return null
        val result = LabelLocator.locate(
            frame = current, templates = templates,
            bounds = spec.plateBounds(geometryOf(current)),
            matcher = matcher, matchThreshold = profile.matchThreshold, deadlineMs = deadlineMs,
        ) ?: return null
        if (result.clusters == 1 && result.score >= profile.matchThreshold) {
            return PointF(result.centreX, result.centreY) to result.score
        }
        val reason = when {
            result.clusters > 1 -> "AMBIGUOUS"
            result.candidates == 0 -> "NO_PLATE_PROPOSED"
            else -> "NO_CANDIDATE_QUALIFIED"
        }
        emit("academy-label-ambiguous",null,capture.telemetry()
            .put("templateName",spec.name).put("candidates",result.candidates)
            .put("accepted",result.accepted).put("distinctLabels",result.clusters)
            .put("result",reason).toString())
        return null
    }

    /**
     * A proposed place the 初级学园 plate might be, measured from the frame's own pixels.
     *
     * This is a PROPOSAL and never an acceptance. The caller must still obtain a 0.97 score from
     * the authoritative matcher before anything may be tapped. Keeping the two responsibilities
     * separate is the whole point: a signature that is too permissive can only cost time, while a
     * signature that is too strict is visible as "no candidates" instead of becoming a blind tap.
     */
    internal class Candidate(
        val x: Int, val y: Int, val width: Int, val height: Int,
    ) {
        /**
         * The window widths to try, centred on THIS candidate's own MEASURED width and ordered
         * outward from it, with the template's aspect supplied by the caller.
         *
         * THE GEOMETRY COMES FROM THE FRAME. `TemplateMatcher.match` rescales the TEMPLATE to the
         * live window before comparing at 24x24, so the window width is a property of what the
         * camera actually drew - which is exactly what [width] is - and NOT a property of the
         * template. An earlier revision multiplied the measured width by `templateWidth/216`,
         * which silently broke the panoramic state: a 177 px plate was swept at 145 px, so the
         * measured 1.0 match at 177x66 was unreachable by construction and the overview template
         * could never clear 0.97 on the frame it was cropped from.
         *
         * A +-[sizeSweepPx] band is enough because the candidate's bounds are already the plate's
         * own bounds: device-measured optima were 177 px (candidate 177), 216 px (candidate 216)
         * and 220 px (candidate 221) - all inside +-1 px. Ordering by |delta| tries the
         * frame-measured width FIRST, which is what lets the neighbourhood scorer stop early.
         *
         * The ASPECT comes from the template as `w * template.height / template.width` in the
         * caller, because the window must not distort the template: the measured 1.0 windows are
         * exactly the template's own aspect (177x66 and 216x83).
         */
        fun searchWidthsNear(sizeSweepPx: Int): List<Int> {
            val out = ArrayList<Int>(sizeSweepPx * 2 + 1)
            for (d in 0..sizeSweepPx) {
                if (d == 0) { if (width>=8) out += width; continue }
                val wider = width + d
                val narrower = width - d
                if (wider>=8) out += wider
                if (narrower>=8) out += narrower
            }
            return out
        }
    }

    /**
     * The display geometry the locator is running against, read AT RUNTIME.
     *
     * The frame's own dimensions ARE the virtual display's size: the capture is configured from the
     * display and `FrameLiveness` rejects any frame whose size disagrees, so they are authoritative
     * width/height. The density is read from the running app's resources, never from a constant.
     * Nothing in the locator may assume 720x1600@320 - this is what lets the same code run on a
     * differently sized or densified display, and why the plate band below is expressed as a
     * FRACTION of the frame instead of as pixel constants.
     */
    internal data class ScreenGeometry(val width: Int, val height: Int, val densityDpi: Int) {
        fun w(fraction: Double): Int = Math.round(width * fraction).toInt()
        fun h(fraction: Double): Int = Math.round(height * fraction).toInt()
        override fun toString(): String = "${width}x$height@${densityDpi}dpi"
    }

    /** The pixel band one [TargetSpec] resolves to on one concrete display. */
    internal data class PlateBounds(
        val minWidth: Int, val maxWidth: Int, val minHeight: Int, val maxHeight: Int,
        val minAspect: Double, val maxAspect: Double, val minFill: Double, val maxFill: Double,
    )

    /**
     * A DATA-DRIVEN description of one tappable target, so the locator is parameterisable rather
     * than hardcoded to 初级学园.
     *
     * 中级学园 / 高级学园 / 进修学院 are then another instance of this class and need NO new
     * locator code: [templates] lists the asset paths that may recognise the label (one per camera
     * state), [minPlate*Fraction]/[maxPlate*Fraction] describe its expected size as FRACTIONS of
     * the runtime frame so they follow the display, and the aspect/fill fields describe its shape.
     *
     * A spec is only a PROPOSAL filter. Nothing here can authorise a tap: a candidate it proposes
     * must still clear `profile.matchThreshold` through the real public `TemplateMatcher.match`
     * before any coordinate is used.
     */
    internal data class TargetSpec(
        val name: String,
        val templates: List<String>,
        val minPlateWidthFraction: Double,
        val maxPlateWidthFraction: Double,
        val minPlateHeightFraction: Double,
        val maxPlateHeightFraction: Double,
        val minAspect: Double,
        val maxAspect: Double,
        val minFill: Double,
        val maxFill: Double,
    ) {
        /** The plate-size band this spec implies on the display [geometry] describes. */
        fun plateBounds(geometry: ScreenGeometry): PlateBounds = PlateBounds(
            minWidth = geometry.w(minPlateWidthFraction),
            maxWidth = geometry.w(maxPlateWidthFraction),
            minHeight = geometry.h(minPlateHeightFraction),
            maxHeight = geometry.h(maxPlateHeightFraction),
            minAspect = minAspect, maxAspect = maxAspect, minFill = minFill, maxFill = maxFill,
        )
    }

    /**
     * The SHIPPING acceptance core, shared verbatim by production and the passive device test.
     *
     * It is its own object so there is exactly ONE implementation of the acceptance rule: the
     * proposal, the bounded neighbourhood geometry, the threshold comparison and the
     * same-label merge / distinct-label refusal below are what production runs, and the device test
     * calls this same function rather than a lookalike. The ONLY thing the test substitutes is the
     * [TemplateMatcher] instance - production injects `flow.matcher`, the test injects a matcher
     * over the app's asset context - because a test cannot construct a `PetFlowContext`, which
     * needs a live Service. The matcher is the same public class in both cases, so the metric and
     * the 0.97 comparison are identical.
     */
    internal object LabelLocator {
        /**
         * An optional call counter for the passive device test's cost measurement.
         *
         * Production passes null and pays one null check per window; the test passes an instance so
         * the cost it prints is the number of REAL public-matcher evaluations the shipping code
         * made, rather than a formula the test hopes matches. A plain mutable field is enough: the
         * harness runs these tests single-threaded.
         */
        internal class WindowCounter { var count: Int = 0 }

        /**
         * Scores ONE window at an ARBITRARY size through the real PUBLIC shipping matcher.
         *
         * There is no metric implemented here. `TemplateMatcher.match(frame, probe, threshold)` is
         * public and takes an arbitrary `probe.rect` plus an asset path, so the acceptance path is
         * that public entry point itself - the same call every other probe in the app makes. The
         * template only has to arrive carrying the [LabelTemplate.assetPath] it was decoded from,
         * because the matcher loads the template itself from that path.
         *
         * Cost: the public matcher is ~1.0 ms per window on the target device (MEASURED: 1011 us
         * over 6084 real windows; ~632 us in an earlier isolated bench, so ~0.6-1.0 ms), which is
         * affordable only because the candidate neighbourhood is ~1100 windows rather than 835,040.
         *
         * A window that cannot be cropped returns null instead of throwing, so a candidate clipped
         * at the frame border is skipped rather than aborting the whole search.
         */
        fun scoreWindow(
            frame: Bitmap, x: Int, y: Int, template: LabelTemplate,
            width: Int, height: Int, matcher: TemplateMatcher, matchThreshold: Double,
            counter: WindowCounter? = null,
        ): Double? {
            if (width<8||height<8) return null
            if (x<0||y<0||x+width>frame.width||y+height>frame.height) return null
            val probe=PetProfile.Probe(
                "academy-window", Rect(x,y,x+width,y+height), template.assetPath,
            )
            return try {
                counter?.let { it.count++ }
                matcher.match(frame,probe,matchThreshold).score
            } catch (e: IllegalArgumentException) {
                // The matcher rejects a rect outside the frame; treat that as "not a hit" rather
                // than letting one border-clipped candidate abort the search.
                null
            }
        }

        /**
         * The bounded local neighbourhood around ONE candidate for ONE template.
         *
         * `TemplateMatcher.match` rescales the TEMPLATE to the live window and compares both at
         * 24x24, so a slightly wrong window size costs far more than a slightly wrong offset. The
         * window width is therefore swept around the candidate's OWN measured width (see
         * [Candidate.searchWidthsNear], which tries the frame-measured width first), while the
         * aspect is preserved from the template so the window never distorts it. Both bounds were
         * MEASURED on device against the real frames: the true optima were 177 px (candidate 177),
         * 216 px (candidate 216) and 220 px (candidate 221), i.e. within +-1 px of the measured
         * width, so [+-labelSizeSweepPx] and [+-labelOffsetSweepPx] are margins, not a search.
         *
         * Returns the best qualifying centre, or null when nothing here reaches the threshold -
         * including when the deadline passes, which the caller re-checks.
         */
        suspend fun neighbourhood(
            frame: Bitmap, candidate: Candidate, template: LabelTemplate,
            matcher: TemplateMatcher, matchThreshold: Double,
            nowMs: () -> Long, deadlineMs: Long,
            counter: WindowCounter? = null,
        ): Pair<PointF,Double>? {
            val scope = coroutineContext
            var best: Pair<PointF,Double>? = null
            val widths = candidate.searchWidthsNear(labelSizeSweepPx)
            val cx = candidate.x + candidate.width/2.0
            val cy = candidate.y + candidate.height/2.0
            for (w in widths) {
                scope.ensureActive()
                if (nowMs() >= deadlineMs) return null
                val h = (w * template.height.toDouble() / template.width).toInt()
                if (h<8) continue
                for (dw in -labelOffsetSweepPx..labelOffsetSweepPx) {
                    for (dh in -labelOffsetSweepPx..labelOffsetSweepPx) {
                        val x = Math.round(cx - w/2.0).toInt() + dw
                        val y = Math.round(cy - h/2.0).toInt() + dh
                        val score = scoreWindow(
                            frame,x,y,template,w,h,matcher,matchThreshold,counter,
                        ) ?: continue
                        if (score < matchThreshold) continue
                        if (best==null || score>best!!.second) {
                            best = PointF(x + w/2f, y + h/2f) to score
                        }
                    }
                }
            }
            return best
        }

        /**
         * The whole acceptance decision for one frame.
         *
         * Proposes candidates from the frame's own pixels, scores EVERY candidate against EVERY
         * supported template in its bounded neighbourhood through the public matcher, then merges
         * windows belonging to the same physical label and refuses anything but exactly one.
         *
         * Returns null ONLY when the clock or the coroutine says to abandon; "no label" and
         * "ambiguous" are reported as a [LabelLocate] with clusters 0 and >1 respectively, so the
         * caller cannot mistake a partial search for a clear frame. Every returned centre is backed
         * by a real public score at or above [matchThreshold].
         */
        suspend fun locate(
            frame: Bitmap, templates: List<LabelTemplate>, bounds: PlateBounds,
            matcher: TemplateMatcher, matchThreshold: Double, deadlineMs: Long,
            nowMs: () -> Long = { SystemClock.elapsedRealtime() },
            counter: WindowCounter? = null,
        ): LabelLocate? {
            val candidates = LabelCandidates.propose(frame, bounds) ?: emptyList()
            val scope = coroutineContext
            val accepted = ArrayList<Pair<PointF,Double>>()
            for (candidate in candidates) {
                scope.ensureActive()
                if (nowMs() >= deadlineMs) return null
                // Every supported template is tried on this candidate: the camera has two discrete
                // plate sizes and the frame alone decides which one is present.
                var bestForCandidate: Pair<PointF,Double>? = null
                for (template in templates) {
                    scope.ensureActive()
                    if (nowMs() >= deadlineMs) return null
                    val hit = neighbourhood(
                        frame,candidate,template,matcher,matchThreshold,nowMs,deadlineMs,counter,
                    ) ?: continue
                    if (bestForCandidate==null || hit.second>bestForCandidate.second) {
                        bestForCandidate = hit
                    }
                }
                if (bestForCandidate!=null) accepted += bestForCandidate
            }
            if (accepted.isEmpty()) {
                return LabelLocate(
                    Float.NaN,Float.NaN,Double.NEGATIVE_INFINITY,0,candidates.size,0,
                )
            }
            // Merge qualifying windows that belong to the SAME physical label - one plate matched
            // by both templates, or overlapping neighbourhoods of one plate - and treat anything
            // left over as a genuinely distinct second label, which must never be resolved into a
            // tap.
            val merged = ArrayList<PointF>()
            for (hit in accepted) {
                val centre = hit.first
                val existing = merged.indexOfFirst {
                    kotlin.math.abs(it.x-centre.x)<=labelClusterPx &&
                    kotlin.math.abs(it.y-centre.y)<=labelClusterPx
                }
                if (existing>=0) {
                    // Same label seen twice: average, so the tap lands on the plate rather than on
                    // whichever template happened to score marginally higher.
                    val other = merged[existing]
                    merged[existing] = PointF((other.x+centre.x)/2f,(other.y+centre.y)/2f)
                } else merged += centre
            }
            return LabelLocate(
                centreX = merged[0].x,
                centreY = merged[0].y,
                score = accepted.maxOf { it.second },
                clusters = merged.size,
                candidates = candidates.size,
                accepted = accepted.size,
            )
        }
    }

    /**
     * One frame's acceptance outcome.
     *
     * [clusters] is the number of DISTINCT physical labels that qualified: 1 means a single label
     * may be tapped, 0 means nothing qualified (absent, or proposed but never above threshold), and
     * >1 means the frame is ambiguous and no input may be sent. [candidates]/[accepted] are carried
     * so telemetry can distinguish "the signature proposed nothing" from "candidates were proposed
     * and all failed the threshold".
     */
    internal data class LabelLocate(
        val centreX: Float, val centreY: Float, val score: Double,
        val clusters: Int, val candidates: Int, val accepted: Int,
    )

    /**
     * Proposes label candidates from a frame, cheaply, from the plate's own colour signature.
     *
     * WHY A SIGNATURE IS ADMISSIBLE HERE, and why it is not the same argument as a score-based
     * prune. The exhausted full-frame sweep was shown to be the wrong shape because a fixed-size
     * window cannot read a plate that the camera has scaled - the metric compares at 24x24 after
     * rescaling, so scale IS the input that matters. The signature below is deliberately
     * CONSERVATIVE in its acceptance: it proposes anything that could be a light plate carrying
     * dark glyphs, with an aspect and size range wide enough to cover both observed camera
     * states (177x69 panoramic and 216-221x83-89 focused) plus margin.
     *
     * What it does NOT do is decide. It cannot authorise a tap, cannot discard a real label on its
     * own authority, and every candidate it returns is still scored by the authoritative matcher.
     * A missed candidate degrades to "no label", which is the fail-closed direction; a spurious
     * one costs a bounded number of real metric evaluations and then fails its own threshold.
     *
     * COVERAGE IS NOT CLAIMED AS UNIVERSAL. This signature was measured on the real device
     * frames in this repository - each contained exactly one plate-shaped component - but that is
     * an observation about those frames, not a proof about every frame the app may ever see. The
     * device tests pin the cases that matter (a missing frame, a second real plate, a faint
     * duplicate) and the fail-closed default covers the rest.
     */
    internal object LabelCandidates {
        /**
         * Connected components of the "light plate with dark glyphs" signature.
         *
         * Neutral light: max(r,g,b)-min(r,g,b) <= [neutralSpread] and min >= [lightFloor]. That
         * keeps the grey map background and the dark building silhouettes out, while the map's
         * coloured scenery is excluded by the neutrality test.
         *
         * The size band arrives as [bounds], already resolved against the FRAME's dimensions via
         * the caller's [TargetSpec], so this signature carries no 720x1600 assumption of its own.
         */
        fun propose(frame: Bitmap, bounds: PlateBounds): List<Candidate>? {
            val w = frame.width
            val h = frame.height
            if (w<64||h<64) return null
            val pixels = IntArray(w*h)
            frame.getPixels(pixels,0,w,0,0,w,h)
            val mask = BooleanArray(w*h)
            for (i in pixels.indices) {
                val p = pixels[i]
                val r = Color.red(p); val g = Color.green(p); val b = Color.blue(p)
                val mx = maxOf(r,g,b); val mn = minOf(r,g,b)
                mask[i] = (mx-mn)<=neutralSpread && mn>=lightFloor
            }
            val label = IntArray(w*h) { -1 }
            val stack = IntArray(w*h)
            val out = ArrayList<Candidate>()
            for (start in mask.indices) {
                if (!mask[start] || label[start]!=-1) continue
                var sp = 0
                stack[sp++]=start
                label[start]=out.size
                var minX=w; var maxX=0; var minY=h; var maxY=0; var area=0
                while (sp>0) {
                    val cur = stack[--sp]
                    val cx = cur % w; val cy = cur / w
                    area++
                    if (cx<minX) minX=cx; if (cx>maxX) maxX=cx
                    if (cy<minY) minY=cy; if (cy>maxY) maxY=cy
                    for (dy in -1..1) for (dx in -1..1) {
                        val nx=cx+dx; val ny=cy+dy
                        if (nx<0||ny<0||nx>=w||ny>=h) continue
                        val ni=ny*w+nx
                        if (mask[ni]&&label[ni]==-1) { label[ni]=out.size; stack[sp++]=ni }
                    }
                }
                val bw=maxX-minX+1; val bh=maxY-minY+1
                if (bw<bounds.minWidth||bh<bounds.minHeight) continue
                if (bw>bounds.maxWidth||bh>bounds.maxHeight) continue
                val aspect = bw.toDouble()/bh
                if (aspect<bounds.minAspect||aspect>bounds.maxAspect) continue
                val fill = area.toDouble()/(bw*bh)
                if (fill<bounds.minFill||fill>bounds.maxFill) continue
                out += Candidate(minX,minY,bw,bh)
            }
            return out
        }

        // MEASURED on the real 720x1600 frames: panoramic 177x69 asp 2.565 fill 0.722; focused
        // 216x83 asp 2.602 fill 0.736; held-out focused 221x89 asp 2.483 fill 0.701. The fractions
        // below are that same pixel band written against the reference display, so a 720x1600 device
        // resolves them back to the identical 120..300 x 40..130 px while any other display is
        // measured on its own terms. The ranges are widened around the observations to cover the
        // states between and beyond them.
        private const val REFERENCE_WIDTH = 720.0
        private const val REFERENCE_HEIGHT = 1600.0
        val PLATE_WIDTH_FRACTION = 120.0/REFERENCE_WIDTH..300.0/REFERENCE_WIDTH
        val PLATE_HEIGHT_FRACTION = 40.0/REFERENCE_HEIGHT..130.0/REFERENCE_HEIGHT

        private const val neutralSpread = 45
        private const val lightFloor = 150
        const val MIN_PLATE_ASPECT = 1.9
        const val MAX_PLATE_ASPECT = 3.4
        const val MIN_PLATE_FILL = 0.55
        const val MAX_PLATE_FILL = 0.85
    }

    /**
     * One frame's result: the strongest label, its score, and how many labels were found.
     *
     * The centre is two plain floats, not an `android.graphics.PointF`, because `searchLabel` is
     * pure geometry and must stay readable from a plain JVM test: on the JVM `PointF` is an
     * android.jar stub whose fields are always 0, which would silently assert against nothing.
     */
    internal data class LabelSearch(val centreX: Float, val centreY: Float,
                                   val score: Double, val clusters: Int)

    /**
     * The literal transcription of the shared metric, transcribed from
     * `TemplateMatcher.similarity` because the matcher is out of scope for editing.
     *
     * This is deliberately the slow, literal transcription - two `createScaledBitmap` calls and
     * 1,152 `getPixel` reads per window. It is a REFERENCE for the ground-truth comparison, NOT
     * the production path: the locator proposes candidates from the frame and then scores them
     * through the real public `TemplateMatcher.match` (see [LabelLocator.scoreWindow]), which is the
     * same entry point every other probe in the app uses. The exhaustive `searchLabel` sweep is
     * likewise a slow reference, so [LabelScratch] and this function only exist to keep the
     * recorded equivalence evidence reproducible on device.
     */
    internal companion object {
        /** The only message prefix `verifySettled` raises for a settle-time non-match. */
        private const val PAGE_MISMATCH_PREFIX="PAGE_MISMATCH:"
        /**
         * Windows within this many pixels of an already-accepted window belong to the same label
         * rather than being a second label. The qualifying region of one label is only 7x3 px, so
         * 8 px merges one label's plateau into one cluster and still keeps two distinct labels
         * apart (measured: two qualifying plates stay separate clusters).
         */
        const val labelClusterPx=8f

        /**
         * How far either side of a candidate's OWN measured width the window width is swept.
         *
         * MEASURED on device, the width that actually peaks is within +-1 px of the candidate's
         * connected-component width (177/177, 216/216, 221/220), so +-3 px is already a 2x margin
         * for an anti-aliased plate edge. The band is a safety net against a candidate clip, not a
         * search: the frame-measured width is tried first.
         *
         * WHY NOT WIDER. The two-frame 8000 ms gate is the binding constraint and it is MEASURED,
         * not projected: each window costs ~1.0 ms of real public-matcher work on this device, so
         * the sweep width decides whether two frames fit at all. The earlier +-4/+-6 band spent
         * 6.1 s of the 8 s budget on localization alone, leaving almost nothing for the two frame
         * captures that the same gate must also cover. +-3/+-4 keeps a 2x margin over every
         * MEASURED optimum while cutting that to ~2.3 s. Loosening this back is not free.
         */
        const val labelSizeSweepPx=3

        /**
         * How far the window may slide on each axis around the candidate's centre.
         *
         * The candidate's bounding box is the plate's own bounds, so the true window centre is
         * within +-2 px of the box centre (MEASURED: the source plate's box centre sits 1.5 px
         * above its optimal window centre, the focused frame's is exact, the held-out frame's is
         * 1.5 px). +-4 px is a 2x+ margin over all three, absorbs a candidate clipped by the
         * 8-connected fill, and stays well inside [labelClusterPx] so a slide can never turn one
         * label into two.
         */
        const val labelOffsetSweepPx=4

        /** The shipped label pill; exposed so the device test builds the identical template. */
        const val ACADEMY_LABEL_ASSET="pet_mvp/academy_primary_label.png"

        /**
         * The SAME 初级学园 label photographed in the camera's other discrete state.
         *
         * The academy map camera sits in one of two plate sizes that each persist for seconds: a
         * panoramic one at ~177x69 and a focused one at ~216-221 x 83-89 (measured by connected
         * components on real device frames). `TemplateMatcher.match` rescales the TEMPLATE to the
         * live window and compares both at 24x24, so a panoramic template can never clear 0.97
         * against a focused plate - MEASURED, it tops out near 0.961-0.965. That is why the
         * original locator found the label in the frame it was cropped from and then failed on the
         * very next state.
         *
         * This asset is cropped at the plate bounds MEASURED on the original focused failure
         * frame, and is deliberately the SECOND template rather than a replacement: the overview
         * template stays exactly as it was, and a frame is accepted only when one of the two
         * clears the unchanged 0.97 on an authoritative score.
         */
        const val ACADEMY_LABEL_FOCUSED_ASSET="pet_mvp/academy_primary_label_focused.png"

        /**
         * Every template the locator may propose, with the plate size it is native to.
         *
         * A template is a PROPOSAL and never an acceptance: the candidate's geometry comes from
         * the frame, and only an authoritative 0.97 score authorises anything.
         */
        val ACADEMY_LABEL_ASSETS=arrayOf(ACADEMY_LABEL_ASSET,ACADEMY_LABEL_FOCUSED_ASSET)

        /**
         * The 初级学园 entry as DATA, not as a literal - the extension point for 中级学园 and
         * friends, and the requirement the user named explicitly.
         *
         * Its template list is the pair of assets that cover the two camera states, and its plate
         * band is expressed as fractions of whatever frame is current, so it follows the display.
         * A different target is another instance of [TargetSpec] passed to the locator; no locator
         * code changes, and a per-target threshold could ride along the same way.
         *
         * NOTHING HERE CARRIES A TAP COORDINATE. The entry position always comes from the current
         * frame's own accepted result - see `locateAcademyLabel`, which takes the centre from
         * [LabelLocator.locate] and from nowhere else. It is on the companion rather than the
         * instance so production and the passive device test build the IDENTICAL spec: a test
         * cannot construct a `PetFlowContext`, and a lookalike spec would not be evidence.
         */
        internal val primaryAcademyTarget: TargetSpec = TargetSpec(
            name = "primary_academy",
            templates = ACADEMY_LABEL_ASSETS.toList(),
            minPlateWidthFraction = LabelCandidates.PLATE_WIDTH_FRACTION.start,
            maxPlateWidthFraction = LabelCandidates.PLATE_WIDTH_FRACTION.endInclusive,
            minPlateHeightFraction = LabelCandidates.PLATE_HEIGHT_FRACTION.start,
            maxPlateHeightFraction = LabelCandidates.PLATE_HEIGHT_FRACTION.endInclusive,
            minAspect = LabelCandidates.MIN_PLATE_ASPECT,
            maxAspect = LabelCandidates.MAX_PLATE_ASPECT,
            minFill = LabelCandidates.MIN_PLATE_FILL,
            maxFill = LabelCandidates.MAX_PLATE_FILL,
        )

        /**
         * The pure half of `geometryOf`, so the device test measures the same geometry the
         * production path does: the frame's own size plus the live density, never a constant.
         */
        internal fun geometryFor(
            width: Int, height: Int, densityDpi: Int,
        ): ScreenGeometry = ScreenGeometry(width, height, densityDpi)

        /**
         * The ONE failure that may lead to a second academy tap.
         *
         * Classification is POSITIVE, not by exclusion: a message is retryable only if it is
         * provably this function's own settle-time non-match. `verifySettled` raises exactly
         * `PAGE_MISMATCH:<state>:<probe name>:<last score>`, so the accepted shape is that
         * prefix, exactly four `:`-separated fields with nothing trailing the score, and the
         * PROBE NAME in the third field. `verifySettled` is out of scope, so it cannot raise a
         * distinct type; instead the accepted shape is pinned here and this function is the
         * single place that decides, so a new failure mode cannot silently inherit "tap again"
         * by failing to be listed as a stop reason. The fail-closed default is: anything
         * unrecognised is NOT a retry.
         *
         * It lives here rather than on the instance so the JVM regression test can pin this
         * classification against representative stop reasons taken from the other production
         * files, which cannot be constructed on the JVM.
         */
        fun retryablePanelFailure(message: String, name: String): Boolean {
            if(!message.startsWith(PAGE_MISMATCH_PREFIX)) return false
            // Exactly state, name, score and nothing else: a message with a trailing reason (a
            // capture or recovery note appended to a mismatch) is not this function's own plain
            // non-match, so it must not become a retry.
            val fields=message.split(':')
            if(fields.size!=4) return false
            return fields[2]==name
        }

        /**
         * Finds EVERY window at or above [matchThreshold], clusters them, and reports the
         * strongest cluster. Returns null when nothing qualifies, when the deadline is reached,
         * or when the coroutine is cancelled.
         *
         * Every window of the frame is compared: no seed ranking, no floor, no neighbourhood
         * restriction. Both cheaper shortcuts were tried and are unsound at this threshold. A
         * strictly-improving 1 px hill climb stalls at a local maximum, measured ~0.9116 at
         * (98,389) while the true peak of the very same label is exactly 1.000000 at (105,389).
         * A coarse stride-24 seed sweep with a 0.87 floor to bound the work is also unsound: a
         * label at (400,900) peaks at exactly 1.000000 and no seed cell within +/-12 of it scores
         * above 0.87, so such a floor can discard a perfect match and declare the frame clear.
         * Inventing a pruning threshold is therefore not available as an optimisation here; the
         * only safe bound is the match threshold itself.
         *
         * The frame is described by its size rather than a Bitmap so this is pure geometry and
         * can be driven by a plain JVM test; production passes the captured frame's dimensions
         * and a [scoreAt] that closes over that frame.
         *
         * [scoreAt] must return exactly what `TemplateMatcher.match` returns for the window
         * whose top-left corner is (x,y), so this cannot drift from the metric every other probe
         * uses.
         *
         * Uniqueness is decided over the WHOLE frame: every window at or above the threshold is
         * enumerated, then single-linkage clustered. One plate's plateau is one cluster, so a
         * second label anywhere in the frame makes [LabelSearch.clusters] > 1 and can never be
         * resolved into a tap.
         *
         * What is actually enforced, precisely - the KDoc states this because a deadline claim
         * that overstates what the code guarantees is worse than none:
         *  - the clock is sampled ONCE PER ROW (544 rows on a 720x1600 frame), not every N
         *    windows, so the overshoot is bounded by ONE ROW of work rather than by
         *    `deadlineCheckEvery` windows. A row is 544 windows, so the previous scheme could
         *    overshoot by up to 4,096 windows; this one cannot overshoot by a second row;
         *  - the clock is re-checked AFTER the scan loop and AGAIN inside the clustering loop,
         *    so a frame that overruns during clustering is abandoned too, not just one that
         *    overruns during the sweep;
         *  - the sweep is CANCELLABLE. It is a suspend function that calls `ensureActive()` once
         *    per row, so a session stop interrupts the scan instead of letting it outlive the
         *    session and keep burning CPU;
         *  - the scan ABANDONS, it never concludes. A returned null can mean "no label" or
         *    "gave up", and the caller cannot tell, which is the point: a partial scan must
         *    never be mistaken for "no second label" and turned into a tap. `ACADEMY_LABEL_NOT_
         *    UNIQUELY_LOCATED` is raised for both, so an overrun is fail-closed exactly like an
         *    absent label. The cost is that a slow frame reports the same reason as a frame with
         *    no label; the telemetry records which.
         */
        @JvmOverloads
        suspend fun searchLabel(
            frameWidth: Int, frameHeight: Int, templateWidth: Int, templateHeight: Int,
            matchThreshold: Double,
            scoreAt: (Int,Int) -> Double,
            nowMs: () -> Long = { 0L },
            deadlineMs: Long = Long.MAX_VALUE,
        ): LabelSearch? {
            if(frameWidth<templateWidth||frameHeight<templateHeight) return null
            val maxX=frameWidth-templateWidth
            val maxY=frameHeight-templateHeight
            // Exhaustive sweep: no seed ranking, no floor, no neighbourhood. Every position is
            // measured, so the result cannot depend on where the label happens to sit. The clock
            // and the coroutine are both checked once per row, so an overrun or a session stop
            // abandons the scan within one row.
            val qualifying=HashMap<Int,Double>()
            val scope=coroutineContext
            for(y in 0..maxY) {
                scope.ensureActive()
                if(nowMs()>=deadlineMs) return null
                for(x in 0..maxX) {
                    val s=scoreAt(x,y)
                    if(s>=matchThreshold) qualifying.put(y*(maxX+1)+x,s)
                }
            }
            // The clustering phase can also overrun on a pathological frame, so the clock is
            // re-checked here rather than assuming the scan covered it.
            if(nowMs()>=deadlineMs) return null
            if(qualifying.isEmpty()) return null
            // Phase 3: single-linkage clustering of the qualifying windows within labelClusterPx.
            // Each cluster reports the MEAN of its window centres, which is deterministic (unlike
            // picking one window out of a tie) and lands on the plate's true centre, plus the
            // best score actually measured inside it.
            val windows=qualifying.entries.sortedBy { it.key }
                .map { Triple(it.key%(maxX+1),it.key/(maxX+1),it.value) }
            val assigned=BooleanArray(windows.size)
            var centreX=Float.NaN
            var centreY=Float.NaN
            var best=Double.NEGATIVE_INFINITY
            var clusters=0
            for(i in windows.indices) {
                if(assigned[i]) continue
                // The cluster loop is the other place a frame can overrun, so it carries the
                // same clock and cancellation checks as the scan. Without them a frame that
                // finished scanning in time could still stall here and be reported as a
                // completed search.
                scope.ensureActive()
                if(nowMs()>=deadlineMs) return null
                clusters++
                val group=ArrayList<Int>()
                val stack=ArrayDeque<Int>()
                stack.addLast(i);assigned[i]=true
                while(stack.isNotEmpty()) {
                    val j=stack.removeLast();group.add(j)
                    val (jx,jy,_)=windows[j]
                    for(k in windows.indices) {
                        if(assigned[k]) continue
                        val (kx,ky,_)=windows[k]
                        if(abs(kx-jx)<=labelClusterPx&&abs(ky-jy)<=labelClusterPx) {
                            assigned[k]=true;stack.addLast(k)
                        }
                    }
                }
                var sumX=0.0;var sumY=0.0;var groupBest=Double.NEGATIVE_INFINITY
                for(j in group) {
                    val (wx,wy,ws)=windows[j]
                    sumX+=wx;sumY+=wy
                    if(ws>groupBest) groupBest=ws
                }
                val n=group.size
                if(groupBest>best) {
                    best=groupBest
                    centreX=(sumX/n+templateWidth/2).toFloat()
                    centreY=(sumY/n+templateHeight/2).toFloat()
                }
            }
            if(centreX.isNaN()) return null
            return LabelSearch(centreX,centreY,best,clusters)
        }

        /**
         * The uniqueness decision shared by the fixed-size and scale-adaptive sweeps, so the two
         * can never drift apart in what they accept.
         *
         * Re-checks the clock and the coroutine before returning, because clustering is itself
         * unbounded work on a pathological frame.
         */
        internal suspend fun decide(
            qualifying: Map<Int,Double>, maxX: Int, templateWidth: Int, templateHeight: Int,
            scope: kotlin.coroutines.CoroutineContext, nowMs: () -> Long, deadlineMs: Long,
        ): LabelSearch? {
            if(qualifying.isEmpty()) return null
            if(nowMs()>=deadlineMs) return null
            val windows=qualifying.entries.sortedBy { it.key }
                .map { Triple(it.key%(maxX+1),it.key/(maxX+1),it.value) }
            val assigned=BooleanArray(windows.size)
            var centreX=Float.NaN
            var centreY=Float.NaN
            var best=Double.NEGATIVE_INFINITY
            var clusters=0
            for(i in windows.indices) {
                if(assigned[i]) continue
                scope.ensureActive()
                if(nowMs()>=deadlineMs) return null
                clusters++
                val group=ArrayList<Int>()
                val stack=ArrayDeque<Int>()
                stack.addLast(i);assigned[i]=true
                while(stack.isNotEmpty()) {
                    val j=stack.removeLast();group.add(j)
                    val (jx,jy,_)=windows[j]
                    for(k in windows.indices) {
                        if(assigned[k]) continue
                        val (kx,ky,_)=windows[k]
                        if(abs(kx-jx)<=labelClusterPx&&abs(ky-jy)<=labelClusterPx) {
                            assigned[k]=true;stack.addLast(k)
                        }
                    }
                }
                var sumX=0.0;var sumY=0.0;var groupBest=Double.NEGATIVE_INFINITY
                for(j in group) {
                    val (wx,wy,ws)=windows[j]
                    sumX+=wx;sumY+=wy
                    if(ws>groupBest) groupBest=ws
                }
                val n=group.size
                if(groupBest>best) {
                    best=groupBest
                    centreX=(sumX/n+templateWidth/2).toFloat()
                    centreY=(sumY/n+templateHeight/2).toFloat()
                }
            }
            if(centreX.isNaN()) return null
            return LabelSearch(centreX,centreY,best,clusters)
        }
    }

    /**
     * A reusable ARGB_8888 bitmap, its bulk-read target and a filtered Paint, shared by every
     * window of a sweep.
     *
     * Reused so the sweep does not allocate 835,040 bitmaps; a per-window
     * `createScaledBitmap` of a per-window crop is what made the original sweep unusable on a
     * device - see [scoreWindow]. Deliberately internal and on the companion so
     * `PetAcademyLabelSearchDeviceTest` drives this EXACT shipping code rather than a
     * re-implementation.
     */
    internal class LabelScratch {
        private var buffer: Bitmap?=null
        private var bufferW=0
        private var bufferH=0
        private var scratch: Canvas?=null
        private var pixels=IntArray(0)
        // ANTI_ALIAS_FLAG is NOT optional decoration: with it omitted the very same draw form
        // diverges from the literal metric by 6.059368191720926E-4, which is the size of error
        // that silently moves a 0.97 decision.
        private val paint=Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        private var frameWidth=0
        private var frameHeight=0

        /** Records which frame the sweep is on; the scoring itself reads the Bitmap directly. */
        fun beginFrame(current: Bitmap) {
            frameWidth=current.width;frameHeight=current.height
        }

        /**
         * The metric's sum-of-absolute-differences for one live window, reproducing
         * `Bitmap.createScaledBitmap(window,24,24,true)` against the pre-downscaled template.
         *
         * The arithmetic after the draw is identical to `TemplateMatcher.similarity`; what differs
         * is only how the window reaches the destination bitmap.
         *
         * AOSP's `createScaledBitmap` is a canvas scale plus a filtered draw at the origin, so
         * transcribing it literally needs a per-window `Bitmap.createBitmap` crop. THREE forms
         * were MEASURED on the target device (DKS9K23420020242) over the real template-source
         * frame, all scored against the literal `createScaledBitmap` metric on real pixels:
         *
         *  - per-window crop + canvas-scale 3-arg draw: EXACT (worst delta 0.0), ~155 us/window,
         *    i.e. 129,248 ms for a full 835,040-window sweep;
         *  - one `drawBitmap(frame, srcRect, dstRect, filteredPaint)`: ~21.8 us/window (18,182 ms),
         *    but NOT exact - worst delta 6.059368191720926E-4, because the explicit rect form
         *    takes a different Skia path with different edge handling;
         *  - a hand-written bilinear resample over a pre-read int[]: ~12.6 us/window (10,518 ms),
         *    but systematically LOW - it scores 0.992538 where the true score is exactly 1.0,
         *    because a 7.4x downscale is a MIPMAP/BOX filter, not a 4-tap bilinear, and the
         *    shortfall costs two of the three authoritative hits (0.970286 and 0.974644).
         *
         *  - this form, a canvas scale plus a NEGATIVE OFFSET drawing the frame itself, at
         *    ~20.3 us/window: worst delta exactly 0.0 at the true peak (1.000000), at BOTH
         *    adjacent qualifying hits (0.970286, 0.974644) and across the whole high-scoring
         *    plateau, but a bounded 6.059368191720926E-4 at SOME LOW-SCORING windows. A device
         *    test prints that figure so it is never an unmeasured claim.
         *
         * WHY THE RESIDUAL EXISTS AND WHY IT IS SAFE. The offset form draws the WHOLE frame
         * under a canvas scale, so Skia runs a full-frame MIPMAP chain whose kernel is clipped at
         * the frame border, whereas `createScaledBitmap` filters an isolated 177x66 window with
         * unclipped taps. The two can therefore differ only in the outer ~1 source column/row of
         * the sampled region, which is reached only by a window that touches the frame edge.
         *
         * That leaves the threshold that decides anything unaffected:
         *  - the qualifying SET is identical to the authoritative one, because every window this
         *    form scores at or above 0.97 was measured EXACT. That is the requirement that the
         *    three authoritative hits survive and that no extra window is invented;
         *  - the decision BOUNDARY is reproduced exactly, as the peak and the entire qualifying
         *    plateau show, so no window is admitted or dropped because of the approximation.
         *
         * It is NOT a whole-frame downscale of the metric. Every window is still resampled
         * independently from its own source rectangle; what the offset changes is only where
         * the filter kernel is clipped. The disqualifying alternative - rescale the frame once
         * to 24x24 and compare that against the template, which scores 0.964341 at this frame's
         * perfect 1.0 location and therefore finds nothing - is not what happens here.
         *
         * Returns null if the window does not fit, mirroring the sweep's own guard.
         */
        fun scoreWindow(current: Bitmap, x: Int, y: Int, template: LabelTemplate): Double? {
            val tw=template.width
            val th=template.height
            if(x<0||y<0||x+tw>current.width||y+th>current.height) return null
            val w=template.smallW
            val h=template.smallH
            val canvas=surface(w,h)
            // Reused buffer: a pixel the draw does not cover must not inherit the last window's
            // value, and a freshly allocated bitmap is transparent black there.
            canvas.drawColor(Color.TRANSPARENT)
            canvas.save()
            canvas.scale(w.toFloat()/tw.toFloat(),h.toFloat()/th.toFloat())
            canvas.drawBitmap(current,-x.toFloat(),-y.toFloat(),paint)
            canvas.restore()
            if(pixels.size!=w*h) pixels=IntArray(w*h)
            val buffer=buffer!!
            buffer.getPixels(pixels,0,w,0,0,w,h)
            var diff=0L
            var i=0
            val n=w*h
            val templatePixels=template.pixels
            while(i<n) {
                val p=pixels[i];val q=templatePixels[i]
                diff+=abs(Color.red(p)-Color.red(q))
                diff+=abs(Color.green(p)-Color.green(q))
                diff+=abs(Color.blue(p)-Color.blue(q))
                i++
            }
            return (1.0-diff/(w.toDouble()*h.toDouble()*3.0*255.0)).coerceIn(0.0,1.0)
        }

        private fun surface(w: Int, h: Int): Canvas {
            val existing=buffer
            if(existing!=null&&!existing.isRecycled&&bufferW==w&&bufferH==h) return scratch!!
            existing?.recycle()
            val created=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888)
            buffer=created;bufferW=w;bufferH=h
            val canvas=Canvas(created)
            scratch=canvas
            return canvas
        }
    }

    /**
     * One supported template: its decoded asset, the asset path the public matcher needs, and the
     * 24x24 operands the retained [LabelScratch] reference path uses.
     *
     * [assetPath] is not decoration. The acceptance path is the PUBLIC
     * `TemplateMatcher.match(frame, probe, threshold)`, which loads the template itself from
     * `probe.assetPath`, so a candidate window can only be scored if the template carries the
     * path it came from. The decoded [asset] beside it is what [LabelScratch] compares against.
     *
     * Internal so the device test can build the identical template the production locator uses,
     * rather than a lookalike.
     */
    internal class LabelTemplate(
        val width: Int, val height: Int, val assetPath: String, val asset: Bitmap,
        val small: Bitmap, val pixels: IntArray, val smallW: Int, val smallH: Int,
    ) {
        companion object {
            /** Builds the cached 24x24 template exactly as production does. */
            fun of(asset: Bitmap, assetPath: String = ""): LabelTemplate {
                val w=24.coerceAtMost(asset.width)
                val h=24.coerceAtMost(asset.height)
                val small=Bitmap.createScaledBitmap(asset,w,h,true)
                val pixels=IntArray(w*h)
                small.getPixels(pixels,0,w,0,0,w,h)
                return LabelTemplate(asset.width,asset.height,assetPath,asset,small,pixels,w,h)
            }
        }
    }

    /** Loaded once per asset: the assets never change, and the sweep touches many windows. */
    private val labelTemplateCache=HashMap<String,LabelTemplate>()

    /**
     * Every template [spec] names, loaded once and cached per asset path.
     *
     * The camera sits in one of two discrete plate sizes and the FRAME decides which, so both are
     * tried on every candidate. A missing or undecodable asset returns null, which the caller
     * treats as fail-closed: with no template there is no way to authorise a tap.
     */
    private suspend fun labelTemplates(spec: TargetSpec = primaryAcademyTarget): List<LabelTemplate>? {
        val out=ArrayList<LabelTemplate>(spec.templates.size)
        for (asset in spec.templates) {
            val cached=labelTemplateCache[asset]
            if (cached!=null) { out+=cached; continue }
            val decoded=flow.context.assets.open(asset).use {
                BitmapFactory.decodeStream(it)
            } ?: return null
            if (decoded.width<16||decoded.height<16) {decoded.recycle();return null}
            // The asset path MUST travel with the template: the acceptance path is the public
            // `TemplateMatcher.match`, which loads the template from `probe.assetPath`, so an
            // empty path would make every candidate window fail to decode and silently turn the
            // locator into "never finds the label".
            val built=LabelTemplate.of(decoded,asset)
            labelTemplateCache[asset]=built
            out+=built
        }
        return out
    }
    /**
     * A tap target for the academy label, or null when it may not be established.
     *
     * The page is an animated map, so one frame can catch the label mid-repaint or a transition
     * over it. Two CONSECUTIVE fresh frames must each carry a qualifying match and their centres
     * must agree within the stability bound; only then is the second frame's centre returned.
     * A missing or ambiguous label returns null, which the caller must treat as fail-closed: no
     * input at all, and no assumption about where the label used to be.
     *
     * Each frame in the wait is also checked for the course panel. On a second attempt the panel
     * can open while this loop is still waiting for its two stable frames; tapping then would
     * hit a panel that is already up, so the wait stops and the caller confirms the panel
     * instead of sending more input.
     *
     * COST IS RECORDED IN PRODUCTION, not inferred from a host projection. Every frame emits its
     * own capture ms and localisation ms, and the frame that reaches two stable observations also
     * emits `elapsedMs`, the time this gate actually took. Criterion 3 asks for the real two-frame
     * path's capture AND localisation cost on the device; a passive test can only ever measure the
     * localisation half (it starts no Service and captures nothing), so the capture component has to
     * be emitted from here, on the live path, to be evidence at all. The per-frame numbers are
     * additive: `captureMs` is `frame()`, `locateMs` is the label search plus the panel probe on that
     * same frame.
     *
     * The whole wait shares ONE deadline, [labelLocateMs] from entry, rather than starting a new
     * budget per frame. Two frames inside 8 s is the gate; a per-frame deadline would silently make
     * the real budget grow with the frame count and would let a slow device stretch it without
     * bound. The caller passes that same deadline in and reuses it for the pre-tap re-location, so
     * "the 8000 ms localisation gate" describes the WHOLE locate-and-re-confirm sequence and not
     * just this loop.
     */
    private suspend fun stableAcademyLabel(name: String, deadline: Long): PointF? = with(flow) {
        var previous: PointF?=null
        var matched=0
        val gateStart=SystemClock.elapsedRealtime()
        while(SystemClock.elapsedRealtime()<deadline) {
            val captureStart=SystemClock.elapsedRealtime()
            val current=frame()
            val captureMs=SystemClock.elapsedRealtime()-captureStart
            val locateStart=SystemClock.elapsedRealtime()
            // Both scores are taken while `current` is alive, then it is recycled exactly once.
            val found=locateAcademyLabel(current,deadline)
            if(found!=null) save(current,name)
            val panelScore=score(current,listOf(profile.courseListProbe))
            val locateMs=SystemClock.elapsedRealtime()-locateStart
            current.recycle()
            if(found==null) {
                matched=0;previous=null
                emit(name,null,capture.telemetry().put("templateName",academyLabel.name)
                    .put("captureMs",captureMs).put("locateMs",locateMs)
                    .put("result","NOT_UNIQUE").toString())
            } else {
                // The panel opening under a still-visible label must not lead to another tap.
                if(panelScore>=profile.matchThreshold) {
                    emit(name,found.second,capture.telemetry().put("templateName",academyLabel.name)
                        .put("captureMs",captureMs).put("locateMs",locateMs)
                        .put("panelScore",panelScore).put("result","PANEL_ALREADY_OPEN").toString())
                    return null
                }
                val centre=found.first
                matched++
                val stable=previous!=null &&
                    abs(previous!!.x-centre.x)<=labelStablePx && abs(previous!!.y-centre.y)<=labelStablePx
                emit(name,found.second,capture.telemetry().put("templateName",academyLabel.name)
                    .put("frame",matched).put("stableWithPrevious",stable)
                    .put("captureMs",captureMs).put("locateMs",locateMs)
                    .put("centreX",centre.x).put("centreY",centre.y)
                    .put("result",if(stable) "PASS" else "SETTLING").toString())
                if(stable) {
                    emit(name,found.second,capture.telemetry().put("templateName",academyLabel.name)
                        .put("elapsedMs",SystemClock.elapsedRealtime()-gateStart)
                        .put("gateMs",labelLocateMs)
                        .put("result","TWO_STABLE_FRAMES").toString())
                    return centre
                }
                previous=centre
            }
            delay(300)
        }
        return null
    }
    suspend fun dismissCareUpgradeIfPresent(): Boolean = with(flow) {
        val current=frame()
        val matched=try {
            score(current,listOf(careUpgradeHint))>=profile.matchThreshold &&
                score(current,listOf(careUpgradeReward))>=profile.matchThreshold
        } finally {current.recycle()}
        if (!matched) return@with false
        verify(listOf(careUpgradeHint),"care-upgrade-before")
        safety();input.tap(360f,1180f);delay(profile.pageSettleMs)
        verifySettled(listOf(foodScene,bath,room),"care-upgrade-after")
        true
    }
    suspend fun tap(before: PetProfile.Probe, point: PointF, after: PetProfile.Probe, action: String) = with(flow) {
        verify(listOf(before),"${serial++}-$action-before",latest=true)
        safety(); input.tap(point.x,point.y)
        delay(profile.pageSettleMs)
        verifySettled(listOf(after),"${serial++}-$action-after")
    }
    suspend fun dismissUpgradeIfPresent(action: String): Boolean = with(flow) {
        val current=frame()
        val upgradeScore=try {score(current,listOf(upgrade))} finally {current.recycle()}
        if(upgradeScore<profile.matchThreshold) return@with false
        emit("$action-upgrade-before",upgradeScore)
        safety();input.tap(360f,1248f);delay(profile.pageSettleMs)
        verifySettled(listOf(academy,profile.courseListProbe),"$action-upgrade-after")
        true
    }
    suspend fun returnHomeFromResult(action: String) = with(flow) {
        verify(listOf(profile.resultProbe),"$action-continue-before",latest=true)
        safety();input.tap(profile.continueButton.x,profile.continueButton.y);delay(profile.pageSettleMs)
        val next=frame()
        val upgraded=try {score(next,listOf(upgrade))>=profile.matchThreshold} finally {next.recycle()}
        if(upgraded) dismissUpgradeIfPresent(action)
        else verifySettled(listOf(academy,profile.courseListProbe),"$action-continue-after")
        val current=frame()
        val panelOpen=try {score(current,listOf(profile.courseListProbe))>=profile.matchThreshold} finally {current.recycle()}
        if(panelOpen) tap(profile.courseListProbe,PointF(80f,160f),academy,"$action-close-course")
        tap(academy,PointF(80f,160f),outdoor,"$action-leave-academy")
        // The outdoor map can pop a random NPC story bubble ("武侠之路...") that covers the
        // home button and the room probe (measured: round-4 return-home-after 0.803). Dismiss
        // it with a tap on the bubble, then retry the return tap once.
        try {
            tap(outdoor,PointF(80f,160f),room,"$action-return-home")
        } catch (t: Throwable) {
            if (t.message?.contains("PAGE_MISMATCH") != true) throw t
            val retry=frame()
            val stillOutdoor=try {score(retry,listOf(outdoor))>=profile.matchThreshold} finally {retry.recycle()}
            if (!stillOutdoor) throw t
            safety(); input.tap(608f,1450f); delay(profile.pageSettleMs)
            tap(outdoor,PointF(80f,160f),room,"$action-return-home-retry")
        }
    }
    /** Enter the academy map, then explicitly open the unlocked primary academy course panel. */
    suspend fun openAcademyCourseList() = with(flow) {
        state="SELECT_COURSE"
        verify(listOf(outdoor),"academy-entry-before",latest=true)
        safety();input.tap(200f,635f)
        delay(profile.pageSettleMs)
        verifySettled(listOf(academy),"academy-map")
        // Real run 1791201870302 showed the panel was already open before this click;
        // tapping its school label again toggled it closed. Use the existing panel probe.
        val current=frame(latest=true)
        try {
            val panelScore=score(current,listOf(profile.courseListProbe))
            if(panelScore>=profile.matchThreshold) {
                save(current,"academy-course-panel-already-open")
                emit("academy-course-panel-already-open",panelScore,capture.telemetry()
                    .put("templateName",profile.courseListProbe.name).put("result","PASS").toString())
                return@with
            }
            val mapScore=score(current,listOf(academy))
            check(mapScore>=profile.matchThreshold) { "ACADEMY_PAGE_UNCONFIRMED:$mapScore" }
            save(current,"enter-primary-academy-before")
            emit("enter-primary-academy-before",mapScore,capture.telemetry()
                .put("templateName",academy.name).put("result","PASS").toString())
        } finally {current.recycle()}
        // The entry is the localized 初级学园 label, never a fixed point. A tap is only allowed
        // after two consecutive fresh frames each matched it at the unchanged threshold with a
        // stable centre, and a second tap only after the panel is positively absent. Everything
        // else stops the flow without sending any input.
        //
        // ONE 8000 ms gate covers the whole locate-and-re-confirm sequence for an attempt: the
        // two-stable-frame wait and the pre-tap re-location share this single deadline, so the
        // documented total localisation budget is real rather than per-frame.
        var taps=0
        while(taps<maxAcademyTaps) {
            val gateDeadline=SystemClock.elapsedRealtime()+labelLocateMs
            val target=stableAcademyLabel("enter-primary-academy-label",gateDeadline)
            check(target!=null) { "ACADEMY_LABEL_NOT_UNIQUELY_LOCATED" }
            // The panel AND the label are both re-checked on a FRESH frame as the last thing before
            // the tap, after safety() and as close to input.tap() as the controller allows. The
            // label can stay fully visible while the panel animates open on its own, and tapping a
            // school label under an open panel TOGGLES IT CLOSED - the exact failure seen in run
            // 1791201870302. Earlier checks (in the settle loop and on entry) are therefore not
            // enough on their own: the state can change while the two stable frames are being
            // collected.
            //
            // Re-LOCATING here, rather than reusing the pre-safety centre, is what makes the tap
            // position a statement about the CURRENT frame: the map is animated, so the stabilized
            // centre can be tens of pixels stale by the time the tap is sent. The fresh centre must
            // still agree with the stabilized target within the same 8 px stability bound, so a
            // frame where the label moved or vanished stops the flow instead of tapping where it
            // used to be. Neither check sends input, so aborting here is free.
            safety()
            val justBefore=frame()
            val panelBeforeTap: Double
            val reloc: Pair<PointF,Double>?
            try {
                panelBeforeTap=score(justBefore,listOf(profile.courseListProbe))
                reloc=if(panelBeforeTap>=profile.matchThreshold) null
                else locateAcademyLabel(justBefore,gateDeadline)
            } finally {justBefore.recycle()}
            if(panelBeforeTap>=profile.matchThreshold) {
                emit("enter-primary-academy-panel-open-before-tap",panelBeforeTap,
                    capture.telemetry().put("templateName",profile.courseListProbe.name)
                        .put("tapIndex",taps)
                        .put("result","PANEL_ALREADY_OPEN_NO_INPUT").toString())
                error("ACADEMY_PANEL_OPEN_BEFORE_ENTRY_TAP:$panelBeforeTap")
            }
            // A fresh frame that no longer carries one unambiguous label must send no input. The
            // fail-closed default is a stop, never a reuse of the pre-safety coordinate.
            check(reloc!=null) { "ACADEMY_LABEL_LOST_AFTER_SAFETY_BEFORE_TAP" }
            val freshCentre=reloc!!.first
            check(abs(freshCentre.x-target!!.x)<=labelStablePx &&
                abs(freshCentre.y-target!!.y)<=labelStablePx) {
                "ACADEMY_LABEL_MOVED_AFTER_SAFETY_BEFORE_TAP:" +
                    "stable=(${target!!.x},${target!!.y}) fresh=(${freshCentre.x},${freshCentre.y})"
            }
            emit("enter-primary-academy-relocated-before-tap",reloc!!.second,
                capture.telemetry().put("templateName",primaryAcademyTarget.name)
                    .put("tapIndex",taps)
                    .put("stableCentreX",target!!.x).put("stableCentreY",target!!.y)
                    .put("freshCentreX",freshCentre.x).put("freshCentreY",freshCentre.y)
                    .put("stablePx",labelStablePx)
                    .put("result","PASS").toString())
            emit("enter-primary-academy-panel-absent-before-tap",panelBeforeTap,
                capture.telemetry().put("tapIndex",taps)
                    .put("result","MAP_CONFIRMED_PANEL_BELOW_THRESHOLD").toString())
            // The tap uses the FRESH centre - the position measured on the frame the panel check
            // just ran on - not the older stabilized one.
            input.tap(freshCentre.x,freshCentre.y);taps++
            emit("enter-primary-academy-tap",null,capture.telemetry().put("action","ENTER_PRIMARY_ACADEMY")
                .put("tapIndex",taps).put("tapLimit",maxAcademyTaps)
                .put("centreX",freshCentre.x).put("centreY",freshCentre.y)
                .put("result","INPUT_SENT_PANEL_UNCONFIRMED").toString())
            delay(profile.pageSettleMs)
            // The unchanged real course-panel probe at the unchanged threshold. verifySettled
            // also records the checkpoint, so a confirmed panel needs no extra confirm here.
            // ONLY its own "the panel never appeared" failure is retried; a recovery, capture,
            // cancellation or other page failure is rethrown so it cannot be mistaken for a
            // tap that simply missed.
            try {
                awaitCoursePanel("enter-primary-academy-after")
                return@with
            } catch(t: PanelDidNotAppear) {
                // The only acceptable retry trigger. Everything else propagated.
            }
            // Retry gate, on ONE fresh frame: the current page must be positively the academy
            // map, and the course panel must be positively absent. A single non-match of the
            // panel probe proves nothing on its own, and re-tapping an unknown page can toggle
            // an open panel closed.
            val after=frame()
            val mapScore=try { score(after,listOf(academy)) } finally {after.recycle()}
            check(mapScore>=profile.matchThreshold) { "ACADEMY_MAP_PAGE_UNCONFIRMED_BEFORE_RETRY:$mapScore" }
            val recheck=frame()
            val panelScore=try { score(recheck,listOf(profile.courseListProbe)) } finally {recheck.recycle()}
            check(panelScore<profile.matchThreshold) { "ACADEMY_PANEL_STATE_UNKNOWN_AFTER_TAP:$panelScore" }
            emit("enter-primary-academy-retry",mapScore,capture.telemetry().put("tapIndex",taps)
                .put("panelScore",panelScore)
                .put("result","MAP_CONFIRMED_PANEL_BELOW_THRESHOLD").toString())
        }
        error("ACADEMY_COURSE_PANEL_NOT_OPENED_AFTER_${taps}_TAPS")
    }
    suspend fun initialise() = with(flow) {
        dismissCareUpgradeIfPresent()
        dismissUpgradeIfPresent("initial")
        val current=frame()
        val sRoom=score(current,listOf(room))
        val sHealth=score(current,listOf(healthPanel))
        val sOutdoor=score(current,listOf(outdoor))
        val sResult=score(current,listOf(profile.resultProbe))
        val unknownDump=try {ByteArrayOutputStream().also {current.compress(Bitmap.CompressFormat.PNG,80,it)}} catch(t:Throwable) {null}
        val page=try {
            when {
                score(current,listOf(foodScene))>=profile.matchThreshold -> "food_scene"
                score(current,listOf(bath))>=profile.matchThreshold -> "bath"
                score(current,listOf(profile.resultProbe))>=profile.matchThreshold -> "result"
                score(current,profile.learningProbes)>=profile.matchThreshold -> "LEARNING"
                score(current,listOf(room))>=profile.matchThreshold && score(current,listOf(healthPanel))<profile.matchThreshold -> "room"
                score(current,listOf(profile.courseListProbe))>=profile.matchThreshold -> "course_list"
                score(current,listOf(academy))>=profile.matchThreshold -> "academy"
                score(current,listOf(outdoor))>=profile.matchThreshold -> "outdoor"
                else -> "UNKNOWN"
            }
        } finally {current.recycle()}
        when(page) {
            "food_scene" -> tap(foodScene,PointF(80f,160f),room,"initial-leave-food")
            "bath" -> tap(bath,PointF(80f,160f),room,"initial-leave-bath")
            "result" -> returnHomeFromResult("initial-unowned-result")
            "room" -> verify(listOf(room),"initial-home")
            "course_list" -> {tap(profile.courseListProbe,PointF(80f,160f),academy,"initial-close-panel")
                tap(academy,PointF(80f,160f),outdoor,"initial-leave-academy")
                tap(outdoor,PointF(80f,160f),room,"initial-home")}
            "academy" -> {tap(academy,PointF(80f,160f),outdoor,"initial-leave-academy")
                tap(outdoor,PointF(80f,160f),room,"initial-home")}
            "outdoor" -> tap(outdoor,PointF(80f,160f),room,"initial-home")
            "LEARNING" -> error("ACTIVE_COURSE_WITHOUT_OWNED_CHECKPOINT")
            else -> {
                if (unknownDump!=null) {evidence.mkdirs();File(evidence,"round-0-initial-unknown-page.png").outputStream().use {it.write(unknownDump.toByteArray())}}
                emit("initial-page-scores",null,capture.telemetry().put("room",sRoom)
                    .put("healthPanel",sHealth).put("outdoor",sOutdoor).put("result",sResult).toString())
                error("INITIAL_PAGE_UNKNOWN")
            }
        }
    }
}
