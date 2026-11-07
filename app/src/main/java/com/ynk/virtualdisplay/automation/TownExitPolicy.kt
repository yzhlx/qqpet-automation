package com.ynk.virtualdisplay.automation

/**
 * Decides the next move while walking out of the career town.
 *
 * The room counts as arrived: a back tap that fired during a slow transition lands on the outdoor
 * map's own back arrow and reaches the room, which is where the walk was going anyway.
 */
object TownExitPolicy {
    enum class Page { TOWN, OUTDOOR, ROOM }
    enum class Step { BACK, LEAVE_MAP, DONE, STOP }

    fun next(page: Page, hops: Int, maxHops: Int): Step = when {
        page == Page.ROOM -> Step.DONE
        page == Page.OUTDOOR -> Step.LEAVE_MAP
        hops < maxHops -> Step.BACK
        else -> Step.STOP
    }
}

/** Confirms a paid item on screen. Repeats reads only; it holds no way to pay again. */
object RestockConfirmation {
    suspend fun <T> await(attempts: Int, read: suspend () -> T, owned: (T) -> Boolean,
        wait: suspend () -> Unit, failure: () -> String): T {
        require(attempts > 0) { "RESTOCK_ATTEMPTS_INVALID:$attempts" }
        repeat(attempts) { attempt ->
            val reading = read()
            if (owned(reading)) return reading
            if (attempt < attempts - 1) wait()
        }
        error(failure())
    }
}
