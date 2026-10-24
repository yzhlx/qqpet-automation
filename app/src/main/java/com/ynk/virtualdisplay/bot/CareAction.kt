package com.ynk.virtualdisplay.bot

/** Why care stopped before both targets were met. */
enum class CareStop { DONE, NO_GAIN, PURCHASE_FAILED, HEALTH_UNREADABLE, LIMIT }

data class CareResult(val health: Health?, val feeds: Int, val cleans: Int, val coinsSpent: Int, val stop: CareStop)

/**
 * Feeds and bathes the pet until 体力 and 清洁 reach their targets.
 *
 * Every item is followed by a fresh health reading, so the loop always decides on the current
 * number, and an item that moved nothing ends care instead of being repeated. When the scene shows
 * the game's out-of-stock bubble, one item is bought first; that is the only place care spends
 * coins, and the sheet's quantity and total must both read exactly before the pay tap.
 */
class CareAction(
    private val eyes: Eyes,
    private val hands: Hands,
    private val gauges: Gauges,
    private val navigator: Navigator,
    private val maxItems: Int = 12,
    private val maxCoins: Int = 60,
) {
    private var coinsSpent = 0

    suspend fun run(energyTarget: Int, cleanTarget: Int): CareResult {
        require(energyTarget in 0..100 && cleanTarget in 0..100)
        coinsSpent = 0
        var feeds = 0
        var cleans = 0
        navigator.goHome()
        var health = readHealth() ?: return CareResult(null, 0, 0, 0, CareStop.HEALTH_UNREADABLE)
        while (true) {
            val item = when {
                health.energy < energyTarget -> Item.COOKIE
                health.cleanliness < cleanTarget -> Item.SOAP
                else -> return CareResult(health, feeds, cleans, coinsSpent, CareStop.DONE)
            }
            if (feeds + cleans >= maxItems) return CareResult(health, feeds, cleans, coinsSpent, CareStop.LIMIT)
            if (!useOne(item)) {
                navigator.goHome()
                return CareResult(health, feeds, cleans, coinsSpent, CareStop.PURCHASE_FAILED)
            }
            if (item == Item.COOKIE) feeds++ else cleans++
            navigator.goHome()
            val after = readHealth() ?: return CareResult(health, feeds, cleans, coinsSpent, CareStop.HEALTH_UNREADABLE)
            val gained = if (item == Item.COOKIE) after.energy > health.energy else after.cleanliness > health.cleanliness
            // A full pet cannot gain; anything else that moved nothing would just waste the next item.
            val full = if (item == Item.COOKIE) after.energy >= 100 else after.cleanliness >= 100
            health = after
            if (!gained && !full) return CareResult(health, feeds, cleans, coinsSpent, CareStop.NO_GAIN)
        }
    }

    /** Opens the health panel from the room, reads it, and closes it again. Ends in the room. */
    suspend fun readHealth(attempts: Int = 3): Health? {
        repeat(attempts) {
            navigator.goHome()
            hands.tap(Layout.openHealth)
            if (await(Page.HEALTH_PANEL)) {
                val health = gauges.health()
                hands.tap(Layout.closeHealth)
                hands.pause(Layout.SETTLE_MS)
                if (health != null) return health
            }
        }
        navigator.goHome()
        return null
    }

    /** From the room: enter the item's scene, buy one if the stock is empty, then use one. */
    private suspend fun useOne(item: Item): Boolean {
        val (entry, scene) = if (item == Item.COOKIE) Layout.enterFood to Page.FOOD_SCENE else Layout.enterBath to Page.BATH_SCENE
        hands.tap(entry)
        if (!await(scene)) return false
        if (gauges.outOfStock(item)) {
            if (!buyOne(item)) return false
            if (!await(scene)) return false
        }
        if (item == Item.COOKIE) {
            hands.tap(Layout.feedCookie)
            hands.pause(2_500)
        } else {
            hands.drag(Layout.scrubGesture)
            hands.pause(2_000)
        }
        return true
    }

    /** Buys exactly one [item] on the open scene. False when anything on the sheet does not read as expected. */
    private suspend fun buyOne(item: Item): Boolean {
        if (coinsSpent + item.unitCoins > maxCoins) return false
        hands.tap(Layout.openExchange)
        if (!awaitExchange(item)) return false
        // The sheet opens on some default quantity; step down to one.
        for (step in 0..12) {
            val quantity = gauges.exchangeQuantity()
            if (quantity == 1) break
            if (step == 12 || (quantity != null && quantity < 1)) return false
            hands.tap(Layout.exchangeMinus)
            hands.pause(700)
        }
        if (gauges.exchangeQuantity() != 1 || gauges.exchangeTotal() != item.unitCoins) return false
        hands.tap(Layout.exchangePay)
        // The sheet closes back onto the scene once the payment went through.
        repeat(20) {
            hands.pause(600)
            if (!gauges.exchangeOpen(item)) {
                coinsSpent += item.unitCoins
                return true
            }
        }
        return false
    }

    private suspend fun awaitExchange(item: Item): Boolean {
        repeat(10) {
            if (gauges.exchangeOpen(item)) return true
            hands.pause(400)
        }
        return false
    }

    private suspend fun await(page: Page, tries: Int = 10): Boolean {
        repeat(tries) {
            hands.pause(Layout.SETTLE_MS / 3)
            if (eyes.look().page == page) return true
        }
        return false
    }
}
