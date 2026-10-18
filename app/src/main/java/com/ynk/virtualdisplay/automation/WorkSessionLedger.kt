package com.ynk.virtualdisplay.automation

/**
 * Per-session guard against recording the same work session's reward twice.
 *
 * The failure mode this exists for: the completion page is read, the flow is interrupted, recovery
 * re-reads the SAME page, and the reward is journalled a second time. That would double-count real
 * coins and 工分 in the ledger, which is the one thing the brief forbids.
 *
 * A session is identified by (venue, optionId, startedAtWallMs). The start timestamp comes from the
 * confirmed WORKING page, so a re-read of a stale page cannot reuse the identity of a session that
 * already completed.
 */
class WorkSessionLedger {
    private val recorded = mutableMapOf<String, RecordedSession>()
    private val inFlight = mutableMapOf<String, Long>()

    data class RecordedSession(
        val key: String,
        val venue: String,
        val optionId: String,
        val startedAtMs: Long,
        val coins: Int,
        val workScore: Int,
        val energy: Int,
    )

    /** Called once the WORKING page is confirmed. Throws if this session was already started. */
    fun beginSession(venue: String, optionId: String, startedAtMs: Long) {
        require(startedAtMs > 0) { "WORK_SESSION_START_TS_REQUIRED" }
        val key = key(venue, optionId, startedAtMs)
        check(!inFlight.containsKey(key)) { "WORK_SESSION_ALREADY_IN_FLIGHT:$key" }
        check(!recorded.containsKey(key)) { "WORK_SESSION_ALREADY_COMPLETED:$key" }
        inFlight[key] = startedAtMs
    }

    /**
     * Books the reward. Second call for the same key throws instead of adding another delta, so a
     * recovery that re-observes a completion page fails loudly rather than crediting coins twice.
     *
     * The ALREADY_RECORDED check comes FIRST: a completed session is removed from [inFlight], so
     * checking only for "never started" would report the wrong reason and, worse, would make a
     * duplicate credit look like a programming error rather than the real duplicate-credit case.
     */
    fun recordReward(
        venue: String,
        optionId: String,
        startedAtMs: Long,
        coins: Int,
        workScore: Int,
        energy: Int,
    ) {
        val key = key(venue, optionId, startedAtMs)
        check(!recorded.containsKey(key)) { "WORK_REWARD_ALREADY_RECORDED:$key" }
        check(inFlight.containsKey(key)) { "WORK_SESSION_NOT_STARTED:$key" }
        recorded[key] = RecordedSession(key, venue, optionId, startedAtMs, coins, workScore, energy)
        inFlight.remove(key)
    }

    fun isRecorded(venue: String, optionId: String, startedAtMs: Long): Boolean =
        recorded.containsKey(key(venue, optionId, startedAtMs))

    val recordedSessions: List<RecordedSession> get() = recorded.values.toList()
    val totalCoins: Int get() = recorded.values.sumOf { it.coins }
    val totalWorkScore: Int get() = recorded.values.sumOf { it.workScore }

    private fun key(venue: String, optionId: String, startedAtMs: Long) = "$venue|$optionId|$startedAtMs"
}
