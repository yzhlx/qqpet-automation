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
        fun near(pixel: Int, color: Int) = abs(Color.red(pixel) - Color.red(color)) <= 8 &&
            abs(Color.green(pixel) - Color.green(color)) <= 8 && abs(Color.blue(pixel) - Color.blue(color)) <= 8
        val values = ys.zip(colors).map { (y, color) ->
            check(near(frame.getPixel(330, y), color)) { "Health panel absent or occluded" }
            val end = (330..504).takeWhile { near(frame.getPixel(it, y), color) }.lastOrNull() ?: 329
            ((end + 1 - 318) * 100.0 / 188).roundToInt().coerceIn(0, 100)
        }
        // Require solid bar colour past the measured 90% boundary. This is conservative
        // around the rounded end cap; a borderline reading never authorizes a course.
        val ready = values.all { it >= 90 } && ys.zip(colors).all { (y, color) ->
            (486..487).all { near(frame.getPixel(it, y), color) }
        }
        return Health(values[0], values[1], values[2], ready)
    }
}
