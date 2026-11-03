package com.ynk.virtualdisplay.automation

/** Every payment uses its own fresh wallet observation, plus all tuition still unpaid. */
object CarePaymentPolicy {
    const val MAX_WALLET_AGE_MS=3_000L

    fun unpaidTuition(actualTuitionCoins:Int,remainingRounds:Int):Int {
        require(actualTuitionCoins>=0 && remainingRounds in 0..10) { "CARE_TUITION_RESERVE_INVALID" }
        return Math.multiplyExact(actualTuitionCoins,remainingRounds)
    }

    fun requireAffordable(wallet:ObservedValue<Int>,quantity:Int,unitCoins:Int,totalCoins:Int,
        unpaidTuitionCoins:Int,nowMs:Long):Int {
        require(quantity>0 && unitCoins>=0 && totalCoins>=0 && unpaidTuitionCoins>=0) { "CARE_PAYMENT_INVALID" }
        require(quantity.toLong()*unitCoins==totalCoins.toLong()) { "CARE_PAYMENT_TOTAL_MISMATCH" }
        val observed=wallet as? ObservedValue.Known ?: error("CARE_WALLET_UNREAD")
        check(observed.value>=0) { "CARE_WALLET_INVALID" }
        val age=nowMs-observed.observedAt
        check(age in 0..MAX_WALLET_AGE_MS) { "CARE_WALLET_STALE" }
        val required=totalCoins.toLong()+unpaidTuitionCoins
        check(observed.value.toLong()>=required) {
            "CARE_COINS_INSUFFICIENT:wallet=${observed.value} required=$required purchase=$totalCoins tuitionReserve=$unpaidTuitionCoins"
        }
        return observed.value
    }
}
