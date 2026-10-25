package com.ynk.virtualdisplay.automation

import android.graphics.Bitmap
import android.graphics.Color
import android.content.Context
import android.graphics.Rect
import kotlin.math.abs
import kotlin.math.roundToInt

/** Fixed measured health bars on this QQ build; no text reading or anchor detection. */
object PetHealthCheck {
    class Health(private val measuredEnergy: Int, private val measuredCleanliness: Int,
        private val measuredMood: Int, private val measuredReady: Boolean,
        val observations: HealthObservations? = null) {
        private fun current() = observations?.current(System.currentTimeMillis())
        val energy get() = if (observations == null) measuredEnergy else current()!!.energy
        val cleanliness get() = if (observations == null) measuredCleanliness else current()!!.cleanliness
        val mood get() = if (observations == null) measuredMood else current()!!.mood
        val ready get() = if (observations == null) measuredReady else current()!!.ready
    }

    fun observe(context: Context, frame: Bitmap, at: Long, evidence: String): HealthObservations {
        val reader = HealthDigitReader(HealthDigitReader.parse(context.assets.open("pet_health/digits.txt")
            .bufferedReader().use { it.readText() }))
        val anchored = frame.width == 720 && frame.height == 1600 && TemplateMatcher(context).match(frame,
            PetProfile.Probe("health",Rect(169,228,250,415),"pet_health/health.png"),0.97).score >= 0.97
        fun digits(y: Int): ObservedValue<Int> = if (!anchored) ObservedValue.Unknown("HEALTH_PANEL_UNCONFIRMED",at,evidence)
            else reader.read(52,31,{ x,j -> val p=frame.getPixel(256+x,y+j)
                maxOf(Color.red(p),Color.green(p),Color.blue(p))<100 },at,evidence)
        val bars = if (anchored) runCatching { read(frame) }.getOrNull() else null
        fun bar(v: Int?): ObservedValue<Int> = if(v==null) ObservedValue.Unknown("BAR_UNCONFIRMED",at,evidence)
            else ObservedValue.Known(v,at,1.0,evidence,ObservationSource.CONSERVATIVE_BAR_ESTIMATE)
        return HealthObservations(digits(228),digits(304),digits(380),bar(bars?.energy),bar(bars?.cleanliness),
            bar(bars?.mood),bars?.ready==true,evidence)
    }

    fun forBusiness(snapshot: HealthObservations, now: Long): Health {
        val v=snapshot.current(now)
        return Health(v.energy,v.cleanliness,v.mood,v.ready,snapshot)
    }

    fun read(frame: Bitmap): Health {
        require(frame.width == 720 && frame.height == 1600)
        val ys = listOf(244, 320, 396)
        val colors = listOf(Color.rgb(0, 153, 255), Color.rgb(100, 201, 0), Color.rgb(255, 128, 0))
        // Track colour measured on FOA_AL00 2026-10-16 (memory.md "数字 OCR 真机基线"): an EMPTY
        // bar (value 0) shows only the grey track at the anchor — that is a legitimate reading,
        // not an occluded panel. The old strict fill-colour check turned "cleanliness = 0", the
        // state that most needs care, into a hard stop (BAR_UNCONFIRMED).
        val track = Color.rgb(214, 208, 197)
        fun near(pixel: Int, color: Int) = abs(Color.red(pixel) - Color.red(color)) <= 8 &&
            abs(Color.green(pixel) - Color.green(color)) <= 8 && abs(Color.blue(pixel) - Color.blue(color)) <= 8
        // Panel presence is proven by a RUN of fill-or-track pixels along the bar row, not by one
        // anchor pixel. A single anchor lands on the fill/track boundary's anti-aliased gradient at
        // low values (measured 2026-10-20: cleanliness 5 put (330,320) at (112,201,20), neither fill
        // nor track within tolerance, and the session died in BAR_UNCONFIRMED). The page background
        // is ~(252,246,231), far outside both tolerances, so an absent panel still fails closed.
        fun barPresent(rowY: Int, color: Int): Boolean {
            var run = 0
            for (x in 318..504) {
                val p = frame.getPixel(x, rowY)
                if (near(p, color) || near(p, track)) {
                    run++
                    if (run >= BAR_RUN_PX) return true
                } else run = 0
            }
            return false
        }
        val values = ys.zip(colors).map { (y, color) ->
            check(barPresent(y, color)) { "Health panel absent or occluded" }
            // No fill colour from x=330 on means the bar is empty (the left cap alone is too short
            // to reach the scan), so the reading is the empty bar's own left edge.
            val end = (330..505).takeWhile { near(frame.getPixel(it, y), color) }.lastOrNull()
                ?: BAR_LEFT_EDGE - 1
            barValue(end)
        }
        // Require solid bar colour past the measured 90% boundary. This is conservative
        // around the rounded end cap; a borderline reading never authorizes a course.
        val ready = values.all { it >= 90 } && ys.zip(colors).all { (y, color) ->
            (486..487).all { near(frame.getPixel(it, y), color) }
        }
        return Health(values[0], values[1], values[2], ready)
    }

    /** Consecutive fill-or-track pixels along one bar row that prove the panel is present. */
    private const val BAR_RUN_PX = 8

    /**
     * The number a filled pixel bar reports when its last fully coloured pixel is [end].
     *
     * Geometry measured on FOA_AL00 2026-10-21 (session 1792549392632, frames
     * round-1-20/26/34-open-health-after.png) and cross-checked against the game's own digits
     * (体力 93/100/12, 清洁 61/6, 心情 20/21/2, plus the older 72/76/15 samples): the bar's fill
     * starts at the rounded left cap and ends at BAR_LEFT + BAR_WIDTH * value / 100.
     *
     * The old normalization ((end + 1 - 318) * 100 / 188, rounded) divided by 188 while counting
     * the fill from 318, so a FULL bar (fill through x=504) read 99 instead of 100 and the low end
     * over-read by one to two points (体力 12 read 13). A threshold of 100 was therefore
     * unreachable: the care loop fed a pet that was already full, the measured gain was 0 and the
     * session died with FEED_NO_EXPECTED_GAIN_OR_EMPTY (measured 2026-10-21, session 1792549392632).
     *
     * The fitted line reproduces ten of the eleven measured digits exactly; the eleventh (心情 2)
     * reads 3, an over-read in the harmless direction because the digit always wins the minimum.
     */
    fun barValue(end: Int): Int =
        ((end + 1 - BAR_LEFT) * 100.0 / BAR_WIDTH).roundToInt().coerceIn(0, 100)

    /** Left edge of the measured bar fill, in the health panel's own pixels. */
    private const val BAR_LEFT = 320.4
    /** Width of the measured bar, in the health panel's own pixels. */
    private const val BAR_WIDTH = 184.6
    /** First pixel of the measured bar track, used as the empty bar's boundary. */
    private const val BAR_LEFT_EDGE = 318
}
