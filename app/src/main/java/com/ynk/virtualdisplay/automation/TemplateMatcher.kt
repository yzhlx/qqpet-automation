package com.ynk.virtualdisplay.automation

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import kotlin.math.abs

/** Simple deterministic ROI matcher for MVP. No OCR/ML/LLM. */
class TemplateMatcher(private val context: Context) {
    data class Result(val matched: Boolean, val score: Double)
    private val cache = mutableMapOf<String, Bitmap>()

    fun match(frame: Bitmap, probe: PetProfile.Probe, threshold: Double): Result {
        val r = probe.rect
        require(r.left >= 0 && r.top >= 0 && r.right <= frame.width && r.bottom <= frame.height)
        val live = Bitmap.createBitmap(frame, r.left, r.top, r.width(), r.height())
        return try {
            val template = cache.getOrPut(probe.assetPath) {
                context.assets.open(probe.assetPath).use { BitmapFactory.decodeStream(it) }
                    ?: error("Cannot decode template ${probe.assetPath}")
            }
            val scaled = if (template.width == live.width && template.height == live.height) template
            else Bitmap.createScaledBitmap(template, live.width, live.height, true)
            try {
                val score = similarity(live, scaled)
                Result(score >= threshold, score)
            } finally {
                if (scaled !== template) scaled.recycle()
            }
        } finally { live.recycle() }
    }

    private fun similarity(a: Bitmap, b: Bitmap): Double {
        // Downsample so matching stays cheap and deterministic.
        val w = 24.coerceAtMost(a.width)
        val h = 24.coerceAtMost(a.height)
        val aa = Bitmap.createScaledBitmap(a, w, h, true)
        val bb = Bitmap.createScaledBitmap(b, w, h, true)
        return try {
            var diff = 0.0
            for (y in 0 until h) for (x in 0 until w) {
                val p = aa.getPixel(x, y)
                val q = bb.getPixel(x, y)
                diff += abs(Color.red(p) - Color.red(q))
                diff += abs(Color.green(p) - Color.green(q))
                diff += abs(Color.blue(p) - Color.blue(q))
            }
            val max = w.toDouble() * h.toDouble() * 3.0 * 255.0
            (1.0 - diff / max).coerceIn(0.0, 1.0)
        } finally {
            // createScaledBitmap may return its source when the dimensions already match.
            // In that case b can be the cached template; recycling it breaks the next probe.
            if (aa !== a) aa.recycle()
            if (bb !== b) bb.recycle()
        }
    }
}
