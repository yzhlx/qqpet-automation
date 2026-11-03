package com.ynk.virtualdisplay.automation

/**
 * Chooses the background screen the pet automation works on.
 *
 * Several screens of the calibrated size can exist at once: every 一键创建 adds one, and earlier
 * ones stay behind empty. Taking the first match picked the oldest, empty screen (measured
 * 2026-11-03: QQ on display 30, automation captured display 28 and stopped on black frames).
 * Order: a managed screen QQ is on, then any screen QQ is on, then the newest managed screen,
 * then the newest screen. Display ids grow as screens are created, so the largest id is newest.
 */
object PetDisplayPicker {
    const val QQ_PACKAGE = "com.tencent.mobileqq"

    data class Candidate(val id: Int, val width: Int, val height: Int)

    fun pick(
        displays: List<Candidate>,
        width: Int,
        height: Int,
        managedIds: Set<Int>,
        qqDisplayIds: Set<Int>,
    ): Int? {
        val sized = displays.filter { it.width == width && it.height == height }.map { it.id }
        return sized.filter { it in qqDisplayIds && it in managedIds }.maxOrNull()
            ?: sized.filter { it in qqDisplayIds }.maxOrNull()
            ?: sized.filter { it in managedIds }.maxOrNull()
            ?: sized.maxOrNull()
    }

    /**
     * Display ids that hold an activity of [packageName], read from `dumpsys activity activities`.
     * Sections start with `Display #N`; an `ActivityRecord{… u0 pkg/…}` line inside belongs to N.
     */
    fun displaysHosting(dumpsys: String, packageName: String): Set<Int> {
        val header = Regex("""^\s*Display #(\d+)""")
        val record = Regex("""ActivityRecord\{\S+ u\d+ ${Regex.escape(packageName)}/""")
        var current: Int? = null
        val found = mutableSetOf<Int>()
        dumpsys.lineSequence().forEach { line ->
            header.find(line)?.let { current = it.groupValues[1].toInt(); return@forEach }
            val display = current ?: return@forEach
            if (record.containsMatchIn(line)) found += display
        }
        return found
    }
}
