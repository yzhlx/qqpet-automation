package com.ynk.virtualdisplay.automation

/**
 * Session-wide hard budget for exchange purchases, mirroring [CareConsumableBudget] semantics.
 *
 * Coin balance cannot be trusted as a purchase gate (dialog pill vs room page disagreed,
 * OPEN_QUESTION_BALANCE), so the only honest budget is what THIS session spent plus its cap.
 * An allowance is counted RESERVED before the pay tap; an unconfirmed pay outcome is retained,
 * never refunded, so a failure can never widen the cap. Pure Kotlin: JVM-testable.
 */
class ExchangeBudget(
    val maxPurchases: Int = 4,
    val maxCoins: Int = 100,
    val maxQuantityPerPurchase: Int = 3,
) {
    init {
        require(maxPurchases > 0 && maxCoins > 0 && maxQuantityPerPurchase in 1..10) {
            "EXCHANGE_BUDGET_INVALID"
        }
    }

    var coinsSpent = 0; private set
    var purchases = 0; private set

    data class Allowance internal constructor(
        val budget: ExchangeBudget, val item: String, val quantity: Int, val unitPrice: Int,
        val coins: Int, val sequence: Int,
    ) {
        var state = ReservationState.RESERVED; private set
        fun confirmPaid() {
            check(state == ReservationState.RESERVED) { "EXCHANGE_ALLOWANCE_NOT_ACTIVE:$state" }
            state = ReservationState.CONSUMED
        }
        /** Only for outcomes that PROVE no pay tap was delivered. Unknown outcome keeps it reserved. */
        fun releaseUnused(reason: String) {
            check(state == ReservationState.RESERVED) { "EXCHANGE_ALLOWANCE_NOT_ACTIVE:$state" }
            require(reason.isNotBlank()) { "EXCHANGE_RELEASE_REASON_REQUIRED" }
            state = ReservationState.RELEASED
            budget.release(this)
        }
        fun settleAfterFailure(payTapDelivered: Boolean): ReservationOutcome =
            when (state) {
                ReservationState.CONSUMED -> ReservationOutcome.CONSUMED
                ReservationState.RELEASED -> ReservationOutcome.RELEASED
                ReservationState.RESERVED ->
                    if (payTapDelivered) { confirmPaid(); ReservationOutcome.RETAINED_UNCONFIRMED }
                    else { releaseUnused("NO_PAY_TAP_DELIVERED"); ReservationOutcome.RELEASED }
            }
    }

    /** Checked BEFORE any dialog interaction: budget is a precondition, not a post-hoc discovery. */
    fun requirePurchaseAllowed(item: String, quantity: Int, unitPrice: Int) {
        check(purchases < maxPurchases) { "EXCHANGE_BUDGET_EXHAUSTED:$purchases/$maxPurchases" }
        check(quantity in 1..maxQuantityPerPurchase) { "EXCHANGE_QUANTITY_CAP:$quantity>$maxQuantityPerPurchase" }
        val coins = quantity * unitPrice
        check(coinsSpent + coins <= maxCoins) { "EXCHANGE_BUDGET_EXHAUSTED_COINS:$coinsSpent+$coins>$maxCoins" }
    }

    fun reserve(item: String, quantity: Int, unitPrice: Int): Allowance {
        requirePurchaseAllowed(item, quantity, unitPrice)
        purchases++; coinsSpent += quantity * unitPrice
        return Allowance(this, item, quantity, unitPrice, quantity * unitPrice, purchases)
    }

    private fun release(allowance: Allowance) {
        check(coinsSpent >= allowance.coins) { "EXCHANGE_BUDGET_RELEASE_UNDERFLOW" }
        coinsSpent -= allowance.coins
        purchases--
    }
}
