package com.ynk.virtualdisplay.bot

data class Health(val energy: Int, val cleanliness: Int)

enum class Item(val unitCoins: Int) {
    /** Base cookie: +10 energy (PetCareFlow, measured 2026-10-05). */
    COOKIE(5),
    /** Base soap: +10 cleanliness. */
    SOAP(2),
}

/**
 * Numbers the bot reads off the current frame. Every read returns null when the number cannot be
 * read; the caller decides whether to look again or give up. The device implementation reuses the
 * earlier digit readers and measured regions.
 */
interface Gauges {
    /** 体力 and 清洁 on the open health panel. */
    suspend fun health(): Health?
    /** True when the scene shows its 兑换 bubble, which the game only draws once [item] is used up. */
    suspend fun outOfStock(item: Item): Boolean
    /** True while the exchange sheet for [item] is open. */
    suspend fun exchangeOpen(item: Item): Boolean
    /** Quantity and total price on the open exchange sheet. */
    suspend fun exchangeQuantity(): Int?
    suspend fun exchangeTotal(): Int?
    /** All OCR text on screen, lines joined by spaces. */
    suspend fun screenText(): String
    /**
     * Centre of the first OCR line on screen that names [text] (see [TextMatch]); a label that OCR
     * split over consecutive lines counts too. Null if absent.
     */
    suspend fun findText(text: String): Point?
    /** The high-five cue that appears early in a lesson. */
    suspend fun highFiveVisible(): Boolean
}
