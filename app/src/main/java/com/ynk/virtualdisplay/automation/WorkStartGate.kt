package com.ynk.virtualdisplay.automation

/**
 * Explicit authorisation for ONE real work start.
 *
 * The game's 去打工 button has no confirmation dialog: one tap is the work itself. So the preview
 * phase and the start phase are separated by this object, and this object is the ONLY way to cross
 * from one to the other.
 *
 * Rules enforced by construction:
 *  - Default is DENY. There is no code path that grants itself permission.
 *  - [maxStarts] is a hard count. One authorisation can never become a loop authorisation.
 *  - [maxCoinsSpent] / [maxEnergySpent] bound the resource cost the user accepted for THIS grant.
 *  - A grant is single-use for its count: [consume] refuses to hand out a second start beyond it.
 *  - Nothing is inferred from navigation permission. A caller that merely reached the page is not
 *    authorised to start work.
 *
 * Pure Kotlin so the gate itself is JVM-testable without a device.
 */
class WorkStartAuthorisation private constructor(
    val venue: String,
    val optionId: String,
    val maxStarts: Int,
    val maxCoinsSpent: Int,
    val maxEnergySpent: Int,
    val grantedBy: String,
    val grantedAtMs: Long,
) {
    private var startsUsed = 0
    private var coinsSpent = 0
    private var energySpent = 0
    private var revokedReason: String? = null

    val remainingStarts: Int get() = maxStarts - startsUsed
    val isRevoked: Boolean get() = revokedReason != null

    /** Snapshot for the event ledger; never carries a boolean that hides a partial grant. */
    fun fields(): Map<String, Any?> = mapOf(
        "venue" to venue, "optionId" to optionId, "maxStarts" to maxStarts,
        "maxCoinsSpent" to maxCoinsSpent, "maxEnergySpent" to maxEnergySpent,
        "grantedBy" to grantedBy, "grantedAtMs" to grantedAtMs,
        "startsUsed" to startsUsed, "coinsSpent" to coinsSpent, "energySpent" to energySpent,
        "revokedReason" to revokedReason,
    )

    /**
     * Records that one start was requested. Throws when the grant does not cover it, so a caller
     * can never reach the tap with an expired, revoked or exhausted grant.
     */
    fun consume(expectedVenue: String, expectedOptionId: String) {
        revokedReason?.let { error("WORK_AUTHORISATION_REVOKED:$it") }
        check(expectedVenue == venue && expectedOptionId == optionId) {
            "WORK_AUTHORISATION_TARGET_MISMATCH:$expectedVenue/$expectedOptionId!=$venue/$optionId"
        }
        check(startsUsed < maxStarts) { "WORK_AUTHORISATION_EXHAUSTED:$startsUsed/$maxStarts" }
        startsUsed++
    }

    /** Books the cost of a session that was actually started. Never released once spent. */
    fun recordSpend(coins: Int, energy: Int) {
        require(coins >= 0 && energy >= 0) { "WORK_SPEND_NEGATIVE" }
        coinsSpent += coins
        energySpent += energy
        check(coinsSpent <= maxCoinsSpent) { "WORK_AUTHORISATION_COIN_CAP:$coinsSpent>$maxCoinsSpent" }
        check(energySpent <= maxEnergySpent) { "WORK_AUTHORISATION_ENERGY_CAP:$energySpent>$maxEnergySpent" }
    }

    /**
     * Withdraws the grant. Used when the page identity cannot be re-confirmed before the tap, or
     * when the user pauses: after a revoke the only way forward is a NEW explicit grant.
     */
    fun revoke(reason: String) {
        require(reason.isNotBlank()) { "WORK_REVOKE_REASON_REQUIRED" }
        if (revokedReason == null) revokedReason = reason
    }

    companion object {
        /**
         * The only way to build a grant. There is deliberately no "default" or "always allow"
         * constructor: work starts are denied until a human names the target, the count and the
         * resource ceiling.
         */
        fun grant(
            venue: String,
            optionId: String,
            maxStarts: Int,
            maxCoinsSpent: Int,
            maxEnergySpent: Int,
            grantedBy: String,
            nowMs: Long,
        ): WorkStartAuthorisation {
            require(venue.isNotBlank() && optionId.isNotBlank()) { "WORK_AUTHORISATION_TARGET_REQUIRED" }
            require(grantedBy.isNotBlank()) { "WORK_AUTHORISATION_GRANTER_REQUIRED" }
            require(maxStarts in 1..3) { "WORK_AUTHORISATION_STARTS_INVALID:$maxStarts" }
            require(maxCoinsSpent >= 0 && maxEnergySpent >= 0) { "WORK_AUTHORISATION_CAPS_NEGATIVE" }
            return WorkStartAuthorisation(
                venue, optionId, maxStarts, maxCoinsSpent, maxEnergySpent, grantedBy, nowMs,
            )
        }
    }
}

/**
 * Guard against the one accident the brief singles out: a start tap was sent but nobody can prove
 * the work began.
 *
 * The game has no confirmation dialog, so "I tapped and the screen looks the same" is exactly the
 * ambiguous case. Re-tapping could start a SECOND real work session and silently burn the user's
 * budget, so this latch makes the second tap structurally impossible: once armed, the flow may only
 * run the status-verification path or stop.
 */
class WorkStartConfirmation {
    private var state = StartState.IDLE

    enum class StartState { IDLE, TAP_DELIVERED_UNCONFIRMED, CONFIRMED_WORKING }

    val current: StartState get() = state
    val tapDelivered: Boolean get() = state != StartState.IDLE

    /**
     * Records that the start tap was DELIVERED. Delivery is not confirmation; the work may or may
     * not have begun.
     */
    fun markTapDelivered() {
        check(state == StartState.IDLE) { "WORK_START_ALREADY_RECORDED:$state" }
        state = StartState.TAP_DELIVERED_UNCONFIRMED
    }

    /** Positive proof the work is running. Only ever called with a matched WORKING page. */
    fun markConfirmedWorking() {
        check(state == StartState.TAP_DELIVERED_UNCONFIRMED) { "WORK_START_NOT_DELIVERED:$state" }
        state = StartState.CONFIRMED_WORKING
    }

    /**
     * True forever once a tap was delivered, regardless of whether the work was later confirmed or
     * disproven. An unproven tap is the case where re-tapping is most dangerous, but a CONFIRMED tap
     * must not be repeated either: the work is already running and a second tap would start a second
     * session. The only way forward after delivery is the status-verification path or a stop.
     */
    val mustNotTapAgain: Boolean get() = state != StartState.IDLE
}
