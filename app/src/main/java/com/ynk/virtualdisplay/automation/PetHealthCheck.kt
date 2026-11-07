package com.ynk.virtualdisplay.automation

import android.graphics.Bitmap
import android.graphics.Color
import android.content.Context
import android.graphics.Rect
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Health bars measured on this QQ build, at fixed rows relative to the panel. The panel itself can
 * sit higher between sessions (measured 2026-10-31: 21 px, between the coarse search's -16 and -24,
 * so an open panel failed with PAGE_MISMATCH at 0.9256), so every row and the close tap move with
 * the drift the template match reports. Recorded replay: 339 panel frames all locate at drift 0,
 * and the 1508 other frames score at most 0.8979 at any drift, under the 0.95 gate.
 */
object PetHealthCheck {
    val probe by lazy { PetProfile.Probe("health",Rect(169,228,250,415),"pet_health/health.png",exactDrift=true) }

    fun digitRows(drift: Int) = listOf(228,304,380).map { it+drift }
    fun barRows(drift: Int) = listOf(244,320,396).map { it+drift }
    fun closeY(drift: Int) = 454f+drift

    // ---- Pure reading helpers: the pixel maths is JVM testable, the frame is not. ----

    private fun red(pixel: Int) = (pixel shr 16) and 0xFF
    private fun green(pixel: Int) = (pixel shr 8) and 0xFF
    private fun blue(pixel: Int) = pixel and 0xFF

    /** True when the pixel is within tolerance of [color]. */
    internal fun near(pixel: Int, color: Int) =
        abs(red(pixel) - red(color)) <= 8 && abs(green(pixel) - green(color)) <= 8 &&
            abs(blue(pixel) - blue(color)) <= 8

    /**
     * A neutral grey: the colour of a blank bar's cap under a semi-transparent panel. The panel
     * blends with the wallpaper behind it (measured 2026-11-05 on virtual display 41: d5d2d2 over
     * the bath scene, d3d6d9 beside it, d6d0c5 in the room), so the grey is matched by neutrality
     * rather than by one exact colour. The beige wallpaper and the white panel are both outside
     * the range.
     */
    internal fun neutral(pixel: Int): Boolean {
        val r = red(pixel); val g = green(pixel); val b = blue(pixel)
        return r in 190..235 && abs(r - g) <= 10 && abs(g - b) <= 10
    }

    /**
     * The colour a bar's track blends to when the bar is EMPTY, over any scene. Measured on
     * FOA_AL00 2026-11-05: 214,208,197 on virtual display 44, where the mood bar sat at 0 and the
     * old [neutral] range rejected it by one unit of blue, and 204,200,190 at the right end of the
     * full blue bar. Low chroma and mid brightness separate it from both the white panel
     * (252,246,231) and the beige wallpaper (237,208,151).
     */
    internal fun blankTrack(pixel: Int): Boolean {
        val mx = maxOf(red(pixel), green(pixel), blue(pixel))
        val mn = minOf(red(pixel), green(pixel), blue(pixel))
        return mx in 170..245 && mx - mn <= 26
    }

    /**
     * True when the row carries a bar. The proof is the bar's LEFT CAP: a filled bar shows its own
     * colour there and an empty one its grey cap. The old check demanded 8 px of fill anywhere on
     * the row, which a low bar cannot satisfy (心情 5 is 7 px of fill, and the session died in
     * BAR_UNCONFIRMED on virtual display 41 on 2026-11-05).
     *
     * [row] holds the pixels from [BAR_CAP_LEFT] to the end of the bar.
     */
    internal fun barPresent(row: IntArray, color: Int): Boolean {
        var run = 0
        for (x in row.indices) {
            if (x > BAR_CAP_RIGHT - BAR_CAP_LEFT) break
            val ownColor = near(row[x], color)
            if (ownColor || (!ownColor && neutral(row[x]) && x > 0 && near(row[x - 1], color))) {
                run++
                if (run >= BAR_CAP_RUN_PX) return true
            } else run = 0
        }
        return blankBar(row)
    }

    /**
     * A bar that is present and EMPTY: the game draws no fill of its own colour at all, only the
     * track, so the cap rule above can never prove it - there is no own-colour pixel to sit next
     * to. Measured 2026-11-05 on virtual display 44 with 心情 0: the session stopped at
     * BAR_UNCONFIRMED while the digits read 66 / 8 / 0, exactly the pet that most needs care. The
     * proof is a track run that starts at the cap and spans the bar: 184 px from x 322 on that
     * frame, while the rows between the bars break after 84 px and the wallpaper above the panel
     * after 9 px.
     */
    internal fun blankBar(row: IntArray): Boolean {
        var x = 0
        while (x < row.size && !blankTrack(row[x])) x++
        if (x > BAR_CAP_RIGHT - BAR_CAP_LEFT) return false
        var end = x
        while (end + 1 < row.size && blankTrack(row[end + 1])) end++
        return end - x + 1 >= BAR_TRACK_MIN_PX
    }

    /**
     * The right edge of the fill on one bar row, counted from [BAR_CAP_LEFT], or null when the row
     * has no fill of its own (an empty bar). The fill starts behind the cap (x 323 measured), so
     * the scan runs from the first coloured pixel to the end of that fill.
     */
    internal fun fillEnd(row: IntArray, color: Int): Int? {
        var x = 0
        while (x < row.size && !near(row[x], color)) x++
        if (x == row.size) return null
        var end = x
        while (end + 1 < row.size && near(row[end + 1], color)) end++
        return end
    }

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
        val located = if (frame.width == 720 && frame.height == 1600) TemplateMatcher(context).match(frame,probe,0.97) else null
        val anchored = located?.matched == true
        val drift = located?.drift ?: 0
        fun digits(y: Int): ObservedValue<Int> = if (!anchored) ObservedValue.Unknown("HEALTH_PANEL_UNCONFIRMED",at,evidence)
            else reader.read(52,31,{ x,j -> val p=frame.getPixel(256+x,y+j)
                maxOf(Color.red(p),Color.green(p),Color.blue(p))<100 },at,evidence)
        val attempt = if (anchored) runCatching { read(frame,drift) } else null
        attempt?.onFailure { e -> android.util.Log.e("PetHealthCheck","read failed drift=$drift",e) }
        val bars = attempt?.getOrNull()
        // The reason goes into the session record: a bare BAR_UNCONFIRMED could not tell a panel
        // that never opened apart from a bar the reader refused, which cost a full device session
        // to find out on 2026-11-05.
        val barReason = when {
            !anchored -> "HEALTH_PANEL_UNCONFIRMED"
            attempt == null -> "BAR_UNCONFIRMED"
            else -> "BAR_UNCONFIRMED:" + attempt.exceptionOrNull()?.message
        }
        fun bar(v: Int?): ObservedValue<Int> = if(v==null) ObservedValue.Unknown(barReason,at,evidence)
            else ObservedValue.Known(v,at,1.0,evidence,ObservationSource.CONSERVATIVE_BAR_ESTIMATE)
        val (energyRow,cleanRow,moodRow) = digitRows(drift)
        return HealthObservations(digits(energyRow),digits(cleanRow),digits(moodRow),bar(bars?.energy),bar(bars?.cleanliness),
            bar(bars?.mood),bars?.ready==true,evidence)
    }

    fun forBusiness(snapshot: HealthObservations, now: Long): Health {
        val v=snapshot.current(now)
        return Health(v.energy,v.cleanliness,v.mood,v.ready,snapshot)
    }

    fun read(frame: Bitmap, drift: Int = 0): Health {
        require(frame.width == 720 && frame.height == 1600)
        val ys = barRows(drift)
        val colors = listOf(Color.rgb(0, 153, 255), Color.rgb(100, 201, 0), Color.rgb(255, 128, 0))
        // Track colour measured on FOA_AL00 2026-10-16 (memory.md "数字 OCR 真机基线"): an EMPTY
        // bar (value 0) shows only the grey track at the anchor — that is a legitimate reading,
        // not an occluded panel. The old strict fill-colour check turned "cleanliness = 0", the
        // state that most needs care, into a hard stop (BAR_UNCONFIRMED).
        fun row(y: Int): IntArray {
            val out = IntArray(505 - BAR_CAP_LEFT + 1)
            var x = BAR_CAP_LEFT
            while (x <= 505) { out[x - BAR_CAP_LEFT] = frame.getPixel(x, y); x++ }
            return out
        }
        val values = ys.zip(colors).map { (y, color) ->
            check(barPresent(row(y), color)) { "no bar at y=$y color=$color" }
            // The fill starts behind the cap (x 323 measured), so the scan runs from the first
            // coloured pixel to the end of that fill: the old 330 start missed the tail of a short
            // bar and read 心情 5 as 0.
            val end = fillEnd(row(y), color)
            barValue(end?.let { BAR_CAP_LEFT + it } ?: BAR_LEFT_EDGE - 1)
        }
        // Require solid bar colour past the measured 90% boundary. This is conservative
        // around the rounded end cap; a borderline reading never authorizes a course.
        val ready = values.all { it >= 90 } && ys.zip(colors).all { (y, color) ->
            (486..487).all { near(frame.getPixel(it, y), color) }
        }
        return Health(values[0], values[1], values[2], ready)
    }

    /** The left cap of every bar: the fill starts here, so the cap proves the bar is present. */
    internal const val BAR_CAP_LEFT = 320
    internal const val BAR_CAP_RIGHT = 336

    /** Two cap pixels are enough: a 5% bar is only 7 px of fill. */
    internal const val BAR_CAP_RUN_PX = 2

    /**
     * How far a blank bar's track has to run to prove the bar. The bar itself is 184 px
     * (measured 2026-11-05, virtual display 44); the longest grey run anywhere else on the panel
     * was 84 px, so this sits between the two with room on both sides.
     */
    internal const val BAR_TRACK_MIN_PX = 150

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
    internal const val BAR_LEFT_EDGE = 318
}
