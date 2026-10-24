package com.ynk.virtualdisplay.bot

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import com.ynk.virtualdisplay.automation.CourseNameReader
import com.ynk.virtualdisplay.automation.DialogDigitReader
import com.ynk.virtualdisplay.automation.ObservedValue
import com.ynk.virtualdisplay.automation.PetHealthCheck
import com.ynk.virtualdisplay.automation.PetPageProbes
import com.ynk.virtualdisplay.automation.PetProfile
import com.ynk.virtualdisplay.automation.TemplateMatcher

/** [Gauges] on the real virtual display, reusing the earlier measured readers and regions. */
class DeviceGauges(
    private val context: Context,
    private val frame: suspend () -> Bitmap,
    private val matcher: TemplateMatcher,
    private val ocr: CourseNameReader,
) : Gauges {
    private val qtyReader by lazy { digits("pet_exchange/qty_digits.txt") }
    private val payReader by lazy { digits("pet_exchange/pay_digits.txt") }

    // Exchange sheet titles, measured 2026-10-15/16 (PetExchangeFlow).
    private val foodTitle = PetProfile.Probe("exchange_food_title", Rect(278, 848, 432, 902), "pet_exchange/dialog_food_title.png")
    private val soapTitle = PetProfile.Probe("exchange_soap_title", Rect(268, 846, 512, 906), "pet_exchange/dialog_soap_title.png")

    private fun digits(asset: String) =
        DialogDigitReader(DialogDigitReader.parse(context.assets.open(asset).bufferedReader().use { it.readText() }))

    private suspend fun <T> onFrame(block: (Bitmap) -> T): T {
        val current = frame()
        return try { block(current) } finally { current.recycle() }
    }

    /** The game's own digits when readable, otherwise the conservative bar estimate. */
    override suspend fun health(): Health? = onFrame { f ->
        val seen = PetHealthCheck.observe(context, f, System.currentTimeMillis(), "bot-health")
        fun pick(digit: ObservedValue<Int>, bar: ObservedValue<Int>) =
            (digit as? ObservedValue.Known)?.value ?: (bar as? ObservedValue.Known)?.value
        val energy = pick(seen.energy, seen.barEnergy)
        val clean = pick(seen.cleanliness, seen.barCleanliness)
        if (energy != null && clean != null && energy in 0..100 && clean in 0..100) Health(energy, clean) else null
    }

    override suspend fun outOfStock(item: Item): Boolean = onFrame { f ->
        val bubble = if (item == Item.COOKIE) PetPageProbes.foodExchangeBubble else PetPageProbes.bathExchangeBubble
        matcher.match(f, bubble, PageClassifier.THRESHOLD).score >= PageClassifier.THRESHOLD
    }

    override suspend fun exchangeOpen(item: Item): Boolean = onFrame { f ->
        matcher.match(f, if (item == Item.COOKIE) foodTitle else soapTitle, 0.9).score >= 0.9
    }

    // Field regions measured 2026-10-16 on FOA_AL00 (PetExchangeFlow.readQuantity / readPayTotal).
    override suspend fun exchangeQuantity(): Int? = onFrame { f -> readField(f, 535, 1285, 625, 1315, false, qtyReader) }

    override suspend fun exchangeTotal(): Int? = onFrame { f -> readField(f, 390, 1400, 445, 1435, true, payReader) }

    override suspend fun findText(text: String): Point? = onFrame { f ->
        ocr.read(f).firstOrNull { TextMatch.matches(it.text, text) }
            ?.let { Point(it.bounds.exactCenterX(), it.bounds.exactCenterY()) }
    }

    override suspend fun highFiveVisible(): Boolean = onFrame { f ->
        matcher.match(f, PetPageProbes.highFive, PageClassifier.THRESHOLD).score >= PageClassifier.THRESHOLD
    }

    private fun readField(f: Bitmap, l: Int, t: Int, r: Int, b: Int, bright: Boolean, reader: DialogDigitReader): Int? {
        if (f.width != 720 || f.height != 1600) return null
        val w = r - l
        val h = b - t
        val px = IntArray(w * h)
        f.getPixels(px, 0, w, l, t, w, h)
        val reading = reader.read(w, h) { x, y ->
            val c = px[y * w + x]
            val red = (c shr 16) and 0xFF
            val green = (c shr 8) and 0xFF
            val blue = c and 0xFF
            if (bright) red > 200 && green > 200 && blue > 200 else maxOf(red, green, blue) < 100
        }
        return reading.text?.toIntOrNull()
    }
}
