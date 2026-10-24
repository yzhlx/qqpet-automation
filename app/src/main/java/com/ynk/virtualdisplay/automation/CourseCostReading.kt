package com.ynk.virtualdisplay.automation

/**
 * The coin icon is not text: the course's 消耗 row binds tuition to the following 当前 balance.
 *
 * ML Kit on this page is not stable. Measured 2026-10-20 on the same device:
 * - 田径运动课 at 17:56 / 19:39 produced a digitised coin icon and/or spaced CJK
 *   ("消 耗 0 / 66 （ 当 前 01 ）"), and the course intro itself contains the word 体力
 *   ("提升体力和反应"). Cutting the page text at the FIRST 体力 therefore discarded the
 *   real 消耗 row and raised COURSE_COST_UNREADABLE even when ML Kit had already
 *   emitted "|消耗66(当前1),体力15(当前13),".
 * - 蹦蹦跳跳体能课 at 18:17 on an otherwise identical layout read tuition=22 cleanly
 *   because that course's intro does not say 体力 before the cost row.
 *
 * CJK keywords therefore allow optional spaces between characters. The window used for
 * parsing starts at 消耗 and ends at the first 体力 AFTER that label, so an earlier 体力
 * in the intro cannot hide the coin column. The strict pattern is tried first; when it
 * cannot produce a positive tuition, every "N（当前 M）" pair in that window is walked and
 * the first positive N is accepted — that pair is the coin column on every measured frame
 * of this page.
 */
data class CourseCostReading(val tuitionCoins: Int, val balanceCoins: Int) {
    companion object {
        // ML Kit frequently inserts spaces between CJK glyphs on this page; every keyword
        // below tolerates optional whitespace between its characters.
        private val consumption =
            Regex("""消\s*耗[^0-9]*([0-9]+)\s*[（(]\s*当\s*前[^0-9]*([0-9]+)""")
        private val currentPair =
            Regex("""([0-9]+)\s*[（(]\s*当\s*前[^0-9]*([0-9]+)""")
        private val consumptionLabel = Regex("""消\s*耗""")
        private val energyMarker = Regex("""体\s*力""")

        fun parse(text: String): CourseCostReading? {
            val label = consumptionLabel.find(text) ?: return null
            // Only the 体力 that follows 消耗 closes the coin window. An earlier 体力 in the
            // course intro (measured on 田径运动课: "提升体力和反应") must not cut the page.
            val afterLabel = text.substring(label.range.first)
            val beforeEnergy = energyMarker.find(afterLabel)?.let {
                afterLabel.substring(0, it.range.first)
            } ?: afterLabel
            consumption.find(beforeEnergy)?.let { match ->
                reading(match.groupValues[1], match.groupValues[2])?.let { return it }
            }
            // Fallback: a digitised coin icon (or similar noise) sat between 消耗 and the real
            // tuition, so the strict capture either missed or took a non-positive. Walk every
            // （当前 pair in the coin window and keep the first positive tuition.
            for (match in currentPair.findAll(beforeEnergy)) {
                reading(match.groupValues[1], match.groupValues[2])?.let { return it }
            }
            return null
        }

        private fun reading(tuitionRaw: String, balanceRaw: String): CourseCostReading? {
            val tuition = tuitionRaw.toIntOrNull()?.takeIf { it > 0 } ?: return null
            val balance = balanceRaw.toIntOrNull() ?: return null
            return CourseCostReading(tuition, balance)
        }
    }
}
