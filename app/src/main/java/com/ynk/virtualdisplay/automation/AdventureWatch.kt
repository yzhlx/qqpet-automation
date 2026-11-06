package com.ynk.virtualdisplay.automation

/**
 * Decides, from successive OCR reads, whether a started adventure is still running, has finished
 * or is not going to be seen finishing. Pure (no clock, no device): the caller feeds it the time
 * and the lines, so the rule is pinned by host tests.
 *
 * MEASURED 2026-11-07 on the real game (附近走走, 用时 45 秒): there is NO settlement popup. After
 * 出发 the outdoor map stays up with a bottom panel "正在冒险 / 剩余 00:00:25 / 召回" and a log of
 * what the pet meets ("我捡到了一个袋子！"). When the countdown ends the pet is simply back in the
 * room, and what it found waits there (a lucky bag, left of the pet). The first version waited for
 * a "结算" popup for 45 seconds, which is the adventure's own length, and reported a timeout for an
 * adventure that had in fact finished.
 *
 * So the finish is read from the panel itself: it was seen while the pet was out, and then it is
 * gone on two consecutive reads. The countdown it shows sets how long to keep waiting.
 */
class AdventureWatch(
    private val startedAtMs: Long,
    /** How long to wait for the panel to show up at all (the adventure's own length plus a margin). */
    private val unseenTimeoutMs: Long = UNSEEN_TIMEOUT_MS,
    /** A countdown longer than this is not waited for (诗和远方 runs two hours). */
    private val maxWaitMs: Long = MAX_WAIT_MS,
) {
    sealed interface Verdict {
        object Waiting : Verdict
        /** The panel was seen and is gone: the pet is back. [events] is what the log said, newest first. */
        data class Finished(val events: List<String>) : Verdict
        /** A settlement popup (older UI versions) carried the result itself. */
        data class Settled(val result: AdventureParser.SettlementResult) : Verdict
        /** The countdown is longer than anyone should wait for; the adventure keeps running in the game. */
        data class TooLong(val remainingSec: Int) : Verdict
        object TimedOut : Verdict
    }

    var sawProgress: Boolean = false; private set
    var lastRemainingSec: Int? = null; private set
    var events: List<String> = emptyList(); private set

    private var misses = 0
    private var tooLongReads = 0
    private var deadlineMs: Long? = null

    fun observe(nowMs: Long, lines: List<TextLine>): Verdict {
        val progress = AdventureParser.readProgress(lines)
        // A popup only counts once the countdown is over: the pet's own log can mention rewards
        // while it is still out.
        if (!progress.inProgress || progress.remainingSec == 0) {
            AdventureParser.parseSettlement(lines)?.let { return Verdict.Settled(it) }
        }
        if (progress.inProgress) {
            sawProgress = true
            misses = 0
            if (progress.events.isNotEmpty()) events = progress.events
            val remaining = progress.remainingSec
            if (remaining != null) {
                lastRemainingSec = remaining
                if (remaining * 1000L > maxWaitMs) {
                    // One frame of misread digits ("10:00:25" for "00:00:25") must not send the pet
                    // away for good; the countdown has to read long twice in a row.
                    tooLongReads++
                    return if (tooLongReads >= TOO_LONG_READS) Verdict.TooLong(remaining) else Verdict.Waiting
                }
                tooLongReads = 0
                // A ticking countdown moves the deadline with it; a countdown stuck at zero must not,
                // or a panel that never clears would keep the wait alive for ever.
                if (remaining > 0) deadlineMs = nowMs + remaining * 1000L + GRACE_MS
                else if (deadlineMs == null) deadlineMs = nowMs + GRACE_MS
            }
            // No countdown ever read (OCR missed it every time): fall back to the longest wait allowed,
            // so a panel that never clears cannot keep the loop alive for ever.
            val deadline = deadlineMs ?: (startedAtMs + maxWaitMs + GRACE_MS)
            return if (nowMs > deadline) Verdict.TimedOut else Verdict.Waiting
        }
        if (sawProgress) {
            misses++
            return if (misses >= GONE_READS) Verdict.Finished(events) else Verdict.Waiting
        }
        return if (nowMs - startedAtMs >= unseenTimeoutMs) Verdict.TimedOut else Verdict.Waiting
    }

    companion object {
        /** 附近走走 takes 45 s in the game; the panel is expected within that, with room to spare. */
        const val UNSEEN_TIMEOUT_MS = 90_000L
        const val MAX_WAIT_MS = 5 * 60_000L
        /** Beyond the countdown: the pet has to walk back and the panel has to clear. */
        const val GRACE_MS = 30_000L
        /** The panel must be absent this many reads in a row after it was seen. */
        const val GONE_READS = 2
        /** A countdown beyond [MAX_WAIT_MS] must read that way this many times in a row. */
        const val TOO_LONG_READS = 2
    }
}
