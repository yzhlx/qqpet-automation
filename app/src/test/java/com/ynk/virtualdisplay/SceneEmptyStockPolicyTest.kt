package com.ynk.virtualdisplay

import com.ynk.virtualdisplay.automation.*
import org.junit.Assert.*
import org.junit.Test

/**
 * Empty stock is proven by a measured scene bubble when the digit ROI has no number.
 * Measured 2026-10-22 on DKS9K23420020242: the food scene with no cookies shows only the
 * 「兑换食物」 bubble and no digit 「0」; inventing zero from Unknown would hide a real OCR miss.
 */
class SceneEmptyStockPolicyTest {
    private val now = 1_000_000L

    @Test fun unreadFoodDigitWithExchangeBubbleIsExactZero() {
        val unread = ObservedValue.Unknown("INVENTORY_FOOD_COUNT_UNREAD", now, "roi")
        val count = SceneEmptyStockPolicy.resolve(
            page = InventoryScenePage.FOOD,
            digitCount = unread,
            emptyStockBubbleMatched = true,
            bubbleScore = 0.985,
            matchThreshold = 0.95,
            observedAt = now,
            evidence = "bubble",
        )
        val known = count as ObservedValue.Known
        assertEquals(0, known.value)
        assertEquals(ObservationSource.EMPTY_STOCK_SCENE_PROOF, known.source)
        assertTrue(known.confidence >= 0.95)
    }

    @Test fun unreadBathDigitWithExchangeBubbleIsExactZero() {
        val unread = ObservedValue.Unknown("INVENTORY_BATH_COUNT_UNREAD", now, "roi")
        val count = SceneEmptyStockPolicy.resolve(
            page = InventoryScenePage.BATH,
            digitCount = unread,
            emptyStockBubbleMatched = true,
            bubbleScore = 0.99,
            matchThreshold = 0.95,
            observedAt = now,
            evidence = "bubble",
        )
        val known = count as ObservedValue.Known
        assertEquals(0, known.value)
        assertEquals(ObservationSource.EMPTY_STOCK_SCENE_PROOF, known.source)
    }

    @Test fun unreadDigitWithoutBubbleStaysUnknown() {
        val unread = ObservedValue.Unknown("INVENTORY_FOOD_COUNT_UNREAD", now, "roi")
        val count = SceneEmptyStockPolicy.resolve(
            page = InventoryScenePage.FOOD,
            digitCount = unread,
            emptyStockBubbleMatched = false,
            bubbleScore = 0.71,
            matchThreshold = 0.95,
            observedAt = now,
            evidence = "bubble",
        )
        assertTrue(count is ObservedValue.Unknown)
    }

    @Test fun aPositiveDigitIsNeverOverriddenByTheBubble() {
        val owned = ObservedValue.Known(10, now, 1.0, "roi", ObservationSource.DIGIT_RECOGNITION)
        val count = SceneEmptyStockPolicy.resolve(
            page = InventoryScenePage.FOOD,
            digitCount = owned,
            emptyStockBubbleMatched = true,
            bubbleScore = 0.99,
            matchThreshold = 0.95,
            observedAt = now,
            evidence = "bubble",
        )
        assertEquals(10, (count as ObservedValue.Known).value)
        assertEquals(ObservationSource.DIGIT_RECOGNITION, count.source)
    }

    @Test fun bubbleBelowThresholdCannotAuthorizeZero() {
        val unread = ObservedValue.Unknown("INVENTORY_FOOD_COUNT_UNREAD", now, "roi")
        val count = SceneEmptyStockPolicy.resolve(
            page = InventoryScenePage.FOOD,
            digitCount = unread,
            emptyStockBubbleMatched = true,
            bubbleScore = 0.94,
            matchThreshold = 0.95,
            observedAt = now,
            evidence = "bubble",
        )
        assertTrue(count is ObservedValue.Unknown)
    }

    @Test fun careGateAcceptsEmptyStockProofForZeroOnly() {
        val zero = ObservedValue.Known(0, now, 0.985, "bubble", ObservationSource.EMPTY_STOCK_SCENE_PROOF)
        assertEquals(CareResourceGate.StockAction.BUY_ONE, CareResourceGate.stockAction(zero, now))
        CareResourceGate.requirePurchase(zero, 1, now)
        val owned = ObservedValue.Known(2, now, 0.985, "bubble", ObservationSource.EMPTY_STOCK_SCENE_PROOF)
        var rejected = false
        try { CareResourceGate.stockAction(owned, now) } catch (_: IllegalStateException) { rejected = true }
        assertTrue("non-zero empty-stock proof must be refused", rejected)
    }
}
