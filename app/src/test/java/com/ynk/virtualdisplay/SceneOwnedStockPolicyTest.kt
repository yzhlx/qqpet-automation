package com.ynk.virtualdisplay

import com.ynk.virtualdisplay.automation.*
import org.junit.Assert.*
import org.junit.Test

/**
 * After purchase the cookie sits on the plate and the empty-stock bubble is gone.
 * Measured 2026-10-22 after-pay: digit 「1」 is present but crop OCR can miss it; cookie target
 * match with bubble absent proves at least one owned cookie.
 */
class SceneOwnedStockPolicyTest {
    private val now = 2_000_000L

    @Test fun cookieOnPlateWithoutBubbleIsOwnedOne() {
        val unread = ObservedValue.Unknown("INVENTORY_FOOD_COUNT_UNREAD", now, "roi")
        val count = SceneOwnedStockPolicy.resolve(
            page = InventoryScenePage.FOOD,
            digitCount = unread,
            emptyStockBubbleMatched = false,
            cookieTargetMatched = true,
            cookieScore = 0.982,
            matchThreshold = 0.95,
            observedAt = now,
            evidence = "cookie",
        )
        val known = count as ObservedValue.Known
        assertEquals(1, known.value)
        assertEquals(ObservationSource.OWNED_STOCK_SCENE_PROOF, known.source)
    }

    @Test fun emptyStockBubbleBlocksOwnedProofEvenIfCookieArtMatches() {
        val unread = ObservedValue.Unknown("INVENTORY_FOOD_COUNT_UNREAD", now, "roi")
        val count = SceneOwnedStockPolicy.resolve(
            page = InventoryScenePage.FOOD,
            digitCount = unread,
            emptyStockBubbleMatched = true,
            cookieTargetMatched = true,
            cookieScore = 0.99,
            matchThreshold = 0.95,
            observedAt = now,
            evidence = "cookie",
        )
        assertTrue(count is ObservedValue.Unknown)
    }

    @Test fun positiveDigitIsPreferredOverOwnedSceneProof() {
        val digit = ObservedValue.Known(3, now, 1.0, "roi", ObservationSource.DIGIT_RECOGNITION)
        val count = SceneOwnedStockPolicy.resolve(
            page = InventoryScenePage.FOOD,
            digitCount = digit,
            emptyStockBubbleMatched = false,
            cookieTargetMatched = true,
            cookieScore = 0.99,
            matchThreshold = 0.95,
            observedAt = now,
            evidence = "cookie",
        )
        assertEquals(3, (count as ObservedValue.Known).value)
        assertEquals(ObservationSource.DIGIT_RECOGNITION, count.source)
    }

    @Test fun weakCookieScoreCannotAuthorizeOwnedStock() {
        val unread = ObservedValue.Unknown("INVENTORY_FOOD_COUNT_UNREAD", now, "roi")
        val count = SceneOwnedStockPolicy.resolve(
            page = InventoryScenePage.FOOD,
            digitCount = unread,
            emptyStockBubbleMatched = false,
            cookieTargetMatched = true,
            cookieScore = 0.94,
            matchThreshold = 0.95,
            observedAt = now,
            evidence = "cookie",
        )
        assertTrue(count is ObservedValue.Unknown)
    }

    @Test fun careGateAcceptsOwnedSceneProofForConsumeOnly() {
        val owned = ObservedValue.Known(1, now, 0.982, "cookie", ObservationSource.OWNED_STOCK_SCENE_PROOF)
        assertEquals(CareResourceGate.StockAction.CONSUME_OWNED, CareResourceGate.stockAction(owned, now))
        blocked { CareResourceGate.requirePurchase(owned, 1, now) }
        blocked { CareResourceGate.requireWallet(owned, 5, 0, now, now) }
    }

    private fun blocked(action: () -> Unit) {
        var failed = false
        try { action() } catch (_: IllegalStateException) { failed = true }
        assertTrue(failed)
    }
}
