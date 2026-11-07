package com.ynk.virtualdisplay.automation

/** Read-only waiting before the first encouragement input. Clock and frame check are injectable. */
object StudyEncouragementWindow {
    suspend fun await(start: Long, now: () -> Long,
        observe: suspend () -> Boolean, wait: suspend (Long) -> Unit) {
        while (now() - start < 30_000L) {
            observe()
            val remaining = 30_000L - (now() - start)
            if (remaining > 0) wait(minOf(1_000L, remaining))
        }
    }
}
