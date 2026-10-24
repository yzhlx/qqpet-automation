package com.ynk.virtualdisplay.automation

/** Decisions about actual observations, never about a session allowance or inferred stock-out. */
object CareResourceGate {
    const val MAX_OBSERVATION_AGE_MS = 5_000L
    enum class StockAction { CONSUME_OWNED, BUY_ONE }

    private fun number(value: ObservedValue<Int>, nowMs: Long, observedAfterMs: Long, label: String): Int {
        val known = value.at(nowMs, MAX_OBSERVATION_AGE_MS) as? ObservedValue.Known
            ?: error("${label}_UNCONFIRMED")
        check(known.observedAt >= observedAfterMs) { "${label}_NOT_REFRESHED" }
        val digitOk = known.source == ObservationSource.DIGIT_RECOGNITION && known.confidence >= 0.95
        val emptyOk = label == "CARE_INVENTORY" &&
            known.source == ObservationSource.EMPTY_STOCK_SCENE_PROOF &&
            known.value == 0 && known.confidence >= 0.95
        val ownedOk = label == "CARE_INVENTORY" &&
            known.source == ObservationSource.OWNED_STOCK_SCENE_PROOF &&
            known.value >= 1 && known.confidence >= 0.95
        val counterOk = label == "CARE_INVENTORY" &&
            known.source == ObservationSource.SCENE_COUNTER_LOWER_BOUND &&
            known.value >= 1 && known.confidence >= 0.95
        check(digitOk || emptyOk || ownedOk || counterOk) { "${label}_DIGIT_PROOF_REQUIRED" }
        check(known.value >= 0) { "${label}_NEGATIVE" }
        return known.value
    }

    fun stockAction(count: ObservedValue<Int>, nowMs: Long, observedAfterMs: Long = 0): StockAction =
        if (number(count, nowMs, observedAfterMs, "CARE_INVENTORY") > 0) StockAction.CONSUME_OWNED
        else StockAction.BUY_ONE

    /** Each purchase only fills the next one-item deficit, after an actual numeric zero. */
    fun requirePurchase(count: ObservedValue<Int>, quantity: Int, nowMs: Long) {
        check(stockAction(count, nowMs) == StockAction.BUY_ONE) { "CARE_OWNED_STOCK_MUST_BE_USED_FIRST" }
        check(quantity == 1) { "CARE_PURCHASE_EXCEEDS_NEXT_DEFICIT" }
    }

    fun requireWallet(wallet: ObservedValue<Int>, totalCoins: Int, reservedTuitionCoins: Int,
        nowMs: Long, observedAfterMs: Long): Int {
        require(totalCoins > 0 && reservedTuitionCoins >= 0) { "CARE_PAYMENT_AMOUNT_INVALID" }
        val balance = number(wallet, nowMs, observedAfterMs, "CARE_WALLET")
        val required = Math.addExact(totalCoins, reservedTuitionCoins)
        check(balance >= required) { "CARE_COINS_INSUFFICIENT:wallet=$balance purchase=$totalCoins tuition=$reservedTuitionCoins" }
        return balance
    }
}

/** Per-flow latch: a possible payment must settle positively before this flow can pay again. */
internal class CarePaymentAttemptGate {
    private var pending=false
    fun requireNoPending() { check(!pending) { "EXCHANGE_PREVIOUS_PAY_UNCONFIRMED" } }
    fun beforePay() { requireNoPending(); pending=true }
    fun confirmedPaid() { check(pending); pending=false }
}
