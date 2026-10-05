package com.ynk.virtualdisplay.automation

/** A measured QQ 9.3.70 numeric ROI, not a general text reader. */
class HealthDigitReader(private val templates: List<DigitTemplate>) {
    data class DigitTemplate(val digit: Int, val mask: String) {
        init { require(digit in 0..9 && mask.length == 768 && mask.all { it == '0' || it == '1' }) }
    }
    init { require(templates.map { it.digit }.toSet() == (0..9).toSet()) }

    fun read(width: Int, height: Int, dark: (Int, Int) -> Boolean, at: Long, evidence: String): ObservedValue<Int> {
        fun unknown(reason: String) = ObservedValue.Unknown(reason, at, evidence)
        if (width != 52 || height != 31) return unknown("DIGIT_ROI_SIZE")
        val columns = (0 until width).filter { x -> (0 until height).any { y -> dark(x, y) } }
        if (columns.isEmpty()) return unknown("DIGITS_ABSENT")
        val groups = mutableListOf<MutableList<Int>>()
        columns.forEach { x ->
            if (groups.isEmpty() || x > groups.last().last() + 1) groups.add(mutableListOf())
            groups.last().add(x)
        }
        if (groups.size !in 1..3 || columns.first() !in 4..8) return unknown("DIGIT_LAYOUT_UNSUPPORTED")
        var text = ""
        var confidence = 1.0
        for (xs in groups) {
            val ys = (0 until height).filter { y -> xs.any { x -> dark(x, y) } }
            val l = xs.first(); val r = xs.last(); val t = ys.first(); val b = ys.last()
            if (t !in 5..8 || b !in 22..24 || r - l + 1 !in 5..16) return unknown("DIGIT_GEOMETRY_UNSUPPORTED")
            val mask = buildString { for (y in 0 until 32) for (x in 0 until 24)
                append(if (dark(l + x * (r-l+1) / 24, t + y * (b-t+1) / 32)) '1' else '0') }
            val candidates = (0..9).map { digit -> digit to templates.filter { it.digit == digit }.maxOf { template ->
                1.0 - mask.indices.count { mask[it] != template.mask[it] } / 768.0
            } }.sortedByDescending { it.second }
            if (candidates[0].second < 0.98) return unknown("LOW_DIGIT_CONFIDENCE:${candidates[0].second}")
            if (candidates[0].second - candidates[1].second < 0.08) return unknown("AMBIGUOUS_DIGIT")
            confidence = minOf(confidence, candidates[0].second)
            text += candidates[0].first.toString()
        }
        if (text.length > 1 && text.startsWith('0')) return unknown("INVALID_LEADING_ZERO")
        val value = text.toInt()
        if (value !in 0..100) return unknown("HEALTH_NUMBER_OUT_OF_RANGE")
        return ObservedValue.Known(value, at, confidence, evidence, ObservationSource.DIGIT_RECOGNITION)
    }

    companion object {
        fun parse(text: String): List<DigitTemplate> = text.lineSequence().filter { it.isNotBlank() }.map {
            val parts = it.split(' '); DigitTemplate(parts[0].toInt(), parts[1])
        }.toList()
    }
}
