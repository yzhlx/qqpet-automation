package com.ynk.virtualdisplay.automation

/**
 * What the work step must do about the pet's state before a shift starts.
 *
 * RULE A, RESTATED FOR THE SHIFT. The work path has NO app-side care threshold: the plan's
 * 体力/清洁 thresholds belong to the lesson, never to the shift, and the shift is not refused
 * because the pet missed them.
 *
 * What the shift does have is the GAME's own requirement, and that is not a threshold but a cost
 * the pet has to be able to pay. Every reachable work option spends cleanliness: the detail page
 * shows 「消耗 体力 5（当前 16），清洁 2（当前 0）」 - five 体力 and two 清洁, with the pet's own
 * numbers next to them. With the pet at 清洁 0 the game replaces the 去打工 button with
 * 「你的宠物清洁值不足，请回家洗澡」 (measured 2026-10-20), so a shift that ignores the cost can
 * never start: the app stopped with WORK_START_BUTTON_NOT_FOUND, and a manual tap on the same
 * spot changed nothing.
 *
 * So the app's answer to the game's cost is to ACT: the pet is cleaned to exactly the option's
 * own cost before the shift. The target is the option's cost - never the plan's threshold, and
 * never a number this class invents.
 *
 * Pure Kotlin with no Android types: the decision is testable on the JVM.
 */
object WorkPreparation {
    /** The cleanliness a shift needs: the work option's own cost, floored at zero. */
    fun requiredCleanliness(optionCleanlinessCost: Int): Int = optionCleanlinessCost.coerceAtLeast(0)

    /** True when the pet can already start the shift. */
    fun isReady(cleanliness: Int, optionCleanlinessCost: Int): Boolean =
        cleanliness >= requiredCleanliness(optionCleanlinessCost)

    /**
     * What one clean costs when the pet has no soap: a 香皂片, measured 2 coins (2026-10-16, the
     * game's own 兑换洗护道具 sheet: 清洁值+10，心情值+1，支付@2 per tablet).
     */
    const val SOAP_COINS = 2

    /**
     * True when the pet's balance can pay for the soap a clean needs.
     *
     * Only a REPORTING aid: the app must not refuse a clean on the balance alone, because the
     * pet may already own a soap and then no purchase is needed at all. The game's own refusal is
     * the fact; this only says what the balance means.
     */
    fun canAffordClean(balanceCoins: Int): Boolean = balanceCoins >= SOAP_COINS

    /**
     * The line the session stops with when payment did not produce a confirmed result. The balance was
     * read off the course page a moment earlier, and the soap price is measured, so the two
     * numbers give balance and price context without claiming the payment was refused.
     */
    fun shortfallReason(balanceCoins: Int, requiredCleanliness: Int): String =
        "WORK_PREP_SOAP_PAYMENT_UNCONFIRMED:balance=$balanceCoins soap=$SOAP_COINS need=$requiredCleanliness"
}
