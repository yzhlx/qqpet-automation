package com.ynk.virtualdisplay.automation

/**
 * Empty stock is proven only when the digit ROI is unread AND the scene's measured empty-stock
 * bubble is positively matched at the unchanged threshold. A lone Unknown digit never becomes 0.
 *
 * Measured 2026-10-22 on DKS9K23420020242 (display 4, 720x1600): the food scene with no cookies
 * shows the 「兑换食物」 bubble at ~(275,1330,450,1420) and no digit 「0」. The bubble is absent when
 * a purchased cookie sits on the plate (historical food_scene identity notes).
 */
object SceneEmptyStockPolicy {
    fun resolve(
        page: InventoryScenePage,
        digitCount: ObservedValue<Int>,
        emptyStockBubbleMatched: Boolean,
        bubbleScore: Double,
        matchThreshold: Double,
        observedAt: Long,
        evidence: String,
    ): ObservedValue<Int> {
        if (digitCount is ObservedValue.Known) return digitCount
        if (page != InventoryScenePage.FOOD && page != InventoryScenePage.BATH) return digitCount
        if (!emptyStockBubbleMatched || bubbleScore < matchThreshold) return digitCount
        return ObservedValue.Known(
            0,
            observedAt,
            bubbleScore.coerceIn(0.0, 1.0),
            evidence,
            ObservationSource.EMPTY_STOCK_SCENE_PROOF,
        )
    }
}
