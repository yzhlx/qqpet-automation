package com.ynk.virtualdisplay.bot

/** A pet with health, stock and coins behind a [FakeGame], for testing actions end to end. */
class FakePet(
    var energy: Int,
    var cleanliness: Int,
    var cookies: Int = 5,
    var soaps: Int = 5,
    var coins: Int = 100,
    /** Gain per item; 0 simulates an item the game ignored. */
    var gainPerItem: Int = 10,
    extraMoves: Map<Pair<Page, Point>, Page> = emptyMap(),
) : Gauges {
    var healthReadable = true
    var sheet: Item? = null
    var sheetQuantity = 0
    val purchases = mutableListOf<Pair<Item, Int>>()

    val game = FakeGame(Page.ROOM, FakeGame.standardMoves + mapOf(
        (Page.ROOM to Layout.openHealth) to Page.HEALTH_PANEL,
        (Page.HEALTH_PANEL to Layout.closeHealth) to Page.ROOM,
        (Page.ROOM to Layout.enterFood) to Page.FOOD_SCENE,
        (Page.ROOM to Layout.enterBath) to Page.BATH_SCENE,
    ) + extraMoves).also { g ->
        g.onTap = { game, point -> tapped(game, point) }
        g.onDrag = { game ->
            if (game.page == Page.BATH_SCENE && sheet == null && soaps > 0) {
                soaps--
                cleanliness = minOf(100, cleanliness + gainPerItem)
            }
        }
    }

    private fun tapped(game: FakeGame, point: Point) {
        val open = sheet
        when {
            open != null && point == Layout.exchangeMinus -> sheetQuantity = maxOf(1, sheetQuantity - 1)
            open != null && point == Layout.exchangePay -> {
                val total = sheetQuantity * open.unitCoins
                if (coins >= total) {
                    coins -= total
                    if (open == Item.COOKIE) cookies += sheetQuantity else soaps += sheetQuantity
                    purchases += open to sheetQuantity
                    sheet = null
                }
            }
            open == null && point == Layout.openExchange && game.page == Page.FOOD_SCENE && cookies == 0 -> openSheet(Item.COOKIE)
            open == null && point == Layout.openExchange && game.page == Page.BATH_SCENE && soaps == 0 -> openSheet(Item.SOAP)
            open == null && point == Layout.feedCookie && game.page == Page.FOOD_SCENE && cookies > 0 -> {
                cookies--
                energy = minOf(100, energy + gainPerItem)
            }
        }
    }

    private fun openSheet(item: Item) {
        sheet = item
        sheetQuantity = 5
    }

    override suspend fun health() = if (healthReadable && game.page == Page.HEALTH_PANEL) Health(energy, cleanliness) else null
    override suspend fun outOfStock(item: Item) = if (item == Item.COOKIE) cookies == 0 else soaps == 0
    override suspend fun exchangeOpen(item: Item) = sheet == item
    override suspend fun exchangeQuantity() = if (sheet != null) sheetQuantity else null
    override suspend fun exchangeTotal() = sheet?.let { it.unitCoins * sheetQuantity }

    /** Texts visible per page, with where they sit; tests add what they need. */
    val texts = mutableMapOf<Page, MutableMap<String, Point>>()
    var highFiveShown = false
    /** What OCR sees on the current screen; tests may replace it to model pages without templates. */
    var screen: () -> Map<String, Point> = { texts[game.page].orEmpty() }
    override suspend fun findText(text: String) = screen().entries.firstOrNull { TextMatch.matches(it.key, text) }?.value
    override suspend fun screenText() = screen().keys.joinToString(" ")
    override suspend fun highFiveVisible() = highFiveShown && game.page == Page.LEARNING
}
