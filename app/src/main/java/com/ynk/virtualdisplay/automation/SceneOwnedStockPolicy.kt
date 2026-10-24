package com.ynk.virtualdisplay.automation

/**
 * After a purchase the food scene shows the cookie on the plate and the empty-stock bubble is gone.
 * Measured 2026-10-22 after-pay frame: digit 「1」 sits above the cookie, bubble absent. ML Kit on the
 * tight digit crop can miss that stylised 「1」, so a positive cookie-target match with a verified
 * food scene and no empty-stock bubble proves at least one owned cookie without inventing stock from
 * artwork alone (the bubble-absent condition is required).
 */
object SceneOwnedStockPolicy {
    fun resolve(
        page: InventoryScenePage,
        digitCount: ObservedValue<Int>,
        emptyStockBubbleMatched: Boolean,
        cookieTargetMatched: Boolean,
        cookieScore: Double,
        matchThreshold: Double,
        observedAt: Long,
        evidence: String,
    ): ObservedValue<Int> {
        if (digitCount is ObservedValue.Known && digitCount.value > 0) return digitCount
        if (page != InventoryScenePage.FOOD) return digitCount
        if (emptyStockBubbleMatched) return digitCount
        if (!cookieTargetMatched || cookieScore < matchThreshold) return digitCount
        return ObservedValue.Known(
            1,
            observedAt,
            cookieScore.coerceIn(0.0, 1.0),
            evidence,
            ObservationSource.OWNED_STOCK_SCENE_PROOF,
        )
    }
}
