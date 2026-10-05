package com.ynk.virtualdisplay.automation

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.abs
import kotlin.math.roundToInt

/** Fixed measured health bars on this QQ build; no text reading or anchor detection. */
object PetHealthCheck {
    data class Health(val energy: Int, val cleanliness: Int, val mood: Int, val ready: Boolean)

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
