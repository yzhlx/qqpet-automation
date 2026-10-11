package com.ynk.virtualdisplay.automation

/**
 * Fixed-ROI digit reader for the two measured exchange-dialog numeric fields.
 *
 * This is NOT a general OCR: it reads exactly two measured regions of the 720x1600 exchange
 * dialog â€?the quantity stepper readout (dark digits on light ground, ROI 548,1262..622,1318)
 * and the pay-button amount (bright digits on the blue button, ROI 400,1370..545,1448) â€?using
 * digit masks extracted from recorded purchase frames with ledger ground truth. Any geometry
 * drift, unknown digit, low confidence or ambiguous match fails closed with a reason string; the
 * caller must treat every non-Known result as EXCHANGE_QUANTITY_UNREADABLE / total unreadable
 * and hard-stop. Pure Kotlin, no Android types: unit-testable on JVM.
 *
 * Mask format matches assets/pet_exchange/{qty,pay}_digits.txt: lines "digit 768-bit mask",
 * 24 columns x 32 rows, row-major, sampled by nearest-neighbour from the stroke bounding box.
 * Multiple templates per digit are allowed; the best score wins.
 */
class DialogDigitReader(private val templates: Map<Int, List<String>>) {
    init {
        require(templates.isNotEmpty()) { "DIALOG_TEMPLATES_EMPTY" }
        templates.forEach { (digit, masks) ->
            require(digit in 0..9) { "DIALOG_TEMPLATES_DIGIT:$digit" }
            require(masks.isNotEmpty()) { "DIALOG_TEMPLATES_EMPTY_SET:$digit" }
            masks.forEach { require(it.length == 768 && it.all { c -> c == '0' || c == '1' }) { "DIALOG_TEMPLATE_MALFORMED:$digit" } }
        }
    }

    data class Reading(val text: String?, val confidence: Double, val reason: String?) {
        val known: Boolean get() = text != null
        fun value(): Int {
            check(text != null) { "DIALOG_DIGITS_UNKNOWN:$reason" }
            return text.toInt()
        }
    }

    /**
     * Reads one numeric field. [width]/[height] are the measured ROI dimensions; [ink](x,y)
     * reports the stroke predicate (dark-on-light or bright-on-blue, decided by the caller).
     * Grouping is adjacent-column only so the keyboard cursor (1-2 px, separated by a 1 px gap)
     * forms its own group and is dropped by the minimum-width rule; cursor fusion into a digit
     * was measured (s23 qty frame) and must never re-normalize the digit box.
     */
    fun read(width: Int, height: Int, ink: (Int, Int) -> Boolean): Reading {
        if (width != QTY_ROI_W && width != PAY_ROI_W) return Reading(null, 0.0, "DIALOG_ROI_SIZE:$width")
        val columns = (0 until width).filter { x -> (0 until height).any { y -> ink(x, y) } }
        if (columns.isEmpty()) return Reading(null, 0.0, "DIALOG_DIGITS_ABSENT")
        val groups = mutableListOf<MutableList<Int>>()
        columns.forEach { x ->
            if (groups.isEmpty() || x > groups.last().last() + 1) groups.add(mutableListOf())
            groups.last().add(x)
        }
        // The cursor is 1-2 px wide; every real digit measured 9-14 px. Dropping narrow groups
        // is the cursor rule; a real digit can never be 3 px wide at this font size.
        val digitGroups = groups.filter { it.last() - it.first() + 1 >= 4 }
        if (digitGroups.isEmpty()) return Reading(null, 0.0, "DIALOG_ONLY_CURSOR")
        if (digitGroups.size > 3) return Reading(null, 0.0, "DIALOG_TOO_MANY_GROUPS:${digitGroups.size}")
        var text = ""
        var confidence = 1.0
        for (g in digitGroups) {
            val xs = g.first()..g.last()
            val ys = (0 until height).filter { y -> (xs).any { x -> ink(x, y) } }
            if (ys.isEmpty()) return Reading(null, 0.0, "DIALOG_GROUP_EMPTY")
            val l = xs.first; val r = xs.last; val t = ys.first(); val b = ys.last()
            val w = r - l + 1; val h = b - t + 1
            // Measured digit geometry: 9-14 px wide, 19-22 px tall. Anything else is layout drift.
            if (w !in 6..18) return Reading(null, 0.0, "DIALOG_DIGIT_WIDTH:$w")
            if (h !in 16..26) return Reading(null, 0.0, "DIALOG_DIGIT_HEIGHT:$h")
            val mask = buildString {
                for (row in 0 until 32) for (col in 0 until 24) {
                    val x = l + col * w / 24
                    val y = t + row * h / 32
                    append(if (ink(x, y)) '1' else '0')
                }
            }
            val scored = templates.entries
                .flatMap { (digit, masks) -> masks.map { m -> digit to similarity(mask, m) } }
                .sortedByDescending { it.second }
            val best = scored.first()
            if (best.second < MIN_CONFIDENCE) return Reading(null, 0.0, "DIALOG_LOW_CONFIDENCE:${best.second}")
            // Ambiguity means "which DIGIT is it", not "which template": multiple templates of the
            // SAME digit legitimately score near-identically on a clean stroke (the recorded '1'
            // templates are near-duplicates), and comparing across them produced a measured
            // DIALOG_AMBIGUOUS:1~1 hard-stop on a perfect match. Only a runner-up of a DIFFERENT
            // digit within MARGIN is a real ambiguity.
            val runnerUpOtherDigit = scored.firstOrNull { it.first != best.first }
            if (runnerUpOtherDigit != null && best.second - runnerUpOtherDigit.second < MARGIN) {
                return Reading(null, 0.0, "DIALOG_AMBIGUOUS:${best.first}~${runnerUpOtherDigit.first}")
            }
            confidence = minOf(confidence, best.second)
            text += best.first.toString()
        }
        if (text.length > 1 && text[0] == '0') return Reading(null, 0.0, "DIALOG_LEADING_ZERO")
        return Reading(text, confidence, null)
    }

    companion object {
        const val QTY_ROI_W = 74   // 548..622
        const val PAY_ROI_W = 145  // 400..545
        const val MIN_CONFIDENCE = 0.90
        const val MARGIN = 0.06
        fun similarity(a: String, b: String): Double =
            1.0 - a.indices.count { a[it] != b[it] } / 768.0

        /** Parses the asset format: non-blank lines "digit mask". */
        fun parse(text: String): Map<Int, List<String>> =
            text.lineSequence().filter { it.isNotBlank() }
                .map { line -> val p = line.trim().split(' '); p[0].toInt() to p[1] }
                .groupBy({ it.first }, { it.second })
    }
}
