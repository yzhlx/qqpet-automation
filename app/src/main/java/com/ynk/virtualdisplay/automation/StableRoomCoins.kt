package com.ynk.virtualdisplay.automation

import kotlinx.coroutines.delay

/**
 * The room's coin count, trusted only once two reads in a row agree.
 *
 * Measured 2026-11-07 02:29: a session that had just entered the room read `coins=0` on the first
 * frame while the real balance was 636 - the top bar's counter is still filling in (or the digits
 * are half drawn). That 0 became the session's "coins before", and the records page then showed
 * "房间顶栏实际读数 0 → 636 +636" as a gain that never happened. A single read of a moving number
 * is not a reading, so a value is accepted only when the next read says the same thing; a number
 * that never settles is Unknown, which only costs the coin line of the record (the payout is proved
 * by the payroll / course page, and nothing is decided from this value).
 */
object StableRoomCoins {
    const val MAX_READS = 5
    const val GAP_MS = 700L

    suspend fun read(
        tag: String,
        maxReads: Int = MAX_READS,
        gapMs: Long = GAP_MS,
        wait: suspend (Long) -> Unit = { delay(it) },
        observe: suspend () -> ObservedValue<Int>,
    ): ObservedValue<Int> {
        require(maxReads >= 2)
        var previous: ObservedValue.Known<Int>? = null
        var last: ObservedValue<Int> = observe()
        var reads = 1
        while (true) {
            val known = last as? ObservedValue.Known<Int>
            if (known != null && previous != null && previous.value == known.value) return known
            if (reads >= maxReads) break
            previous = known
            wait(gapMs)
            last = observe()
            reads++
        }
        val unsettled = last
        return if (unsettled is ObservedValue.Known<Int>)
            ObservedValue.Unknown("ROOM_COINS_UNSTABLE:last=${unsettled.value}", System.currentTimeMillis(), tag)
        else unsettled
    }
}
