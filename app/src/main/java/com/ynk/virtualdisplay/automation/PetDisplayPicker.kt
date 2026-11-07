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
     * Managed screens of the calibrated size that 一键创建 left behind empty, safe to release.
     *
     * Only once QQ is confirmed on [keep]: without that proof no screen is known to be spare. A screen
     * QQ is on is never a leftover, and neither is an unmanaged one (this app holds no handle to it).
     */
    fun leftovers(
        displays: List<Candidate>,
        width: Int,
        height: Int,
        managedIds: Set<Int>,
        qqDisplayIds: Set<Int>,
        keep: Int?,
    ): List<Int> {
        if (keep == null || keep !in qqDisplayIds) return emptyList()
        return displays.filter {
            it.width == width && it.height == height && it.id != keep &&
                it.id in managedIds && it.id !in qqDisplayIds
        }.map { it.id }.sorted()
    }

    /**
     * Whether QQ should be launched onto [displayId] before a session starts.
     *
     * Only when QQ is on no screen at all (closed by the user, killed by the system, or lost with a
     * restarted screen service): measured 2026-11-07 02:17, a session on such a screen waited 20 s
     * for a frame that never came and stopped with a raw capture error. If QQ is on ANY screen it is
     * left alone: launching it here would pull the user's own foreground QQ off the phone.
     */
    fun shouldRelaunchQq(hosting: Set<Int>): Boolean = hosting.isEmpty()

    /**
     * Display ids that hold an activity of [packageName], read from `dumpsys activity activities`.
     * Sections start with `Display #N`; an `ActivityRecord{… u0 pkg/… tID}` line inside belongs to N.
     *
     * Only a record that sits in a task counts (`t2760`, never `t-1`). Measured 2026-11-07 02:17 with
     * QQ gone from the pet screen: the dump still named QQ on `Display #49` through a leftover
     * `deepestLastOrientationSource=ActivityRecord{… SplashActivity t-1 f}` bookkeeping line, so an
     * empty screen read as "QQ is on it" and nothing noticed that it was black.
     */
    fun displaysHosting(dumpsys: String, packageName: String): Set<Int> {
        val header = Regex("""^\s*Display #(\d+)""")
        val record = Regex("""ActivityRecord\{\S+ u\d+ ${Regex.escape(packageName)}/\S+ t\d+\b""")
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
