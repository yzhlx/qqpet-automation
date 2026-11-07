package com.ynk.virtualdisplay.automation

/**
 * Geometry of the QQ exchange bottom sheet (720x1600 virtual display).
 *
 * The sheet is not nailed to fixed screen rows. Measured 2026-11-05 on DKS9K23420020242: the
 * same sheet sat 22 px higher on virtual display 40 than it did on display 39 - title ink
 * 840..873 instead of 798..895, product tile 952..1187 instead of 974..1209, quantity digit
 * 1267..1286 instead of 1289..1308, pay button 1348..1443 instead of 1370..1465. The title
 * template scores 0.9961 at -22, the same score it reaches at 0 on the old display, so the
 * sheet itself moved; the old fixed rows simply read the wrong pixels and the run stopped with
 * EXCHANGE_DIALOG_UNCONFIRMED:0.629.
 *
 * Every region and every tap inside the sheet therefore moves with the drift the title template
 * reports, exactly like the health panel rows (PetHealthCheck). The baseline below is the
 * display-39 calibration; the drift is added to it.
 *
 * Pure Kotlin, no Android types: unit-testable on the JVM.
 */
object ExchangeSheetLayout {
    /** A rectangle in screen pixels. */
    data class Region(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width: Int get() = right - left
        val height: Int get() = bottom - top
    }

    /** Title of the food sheet, calibrated on display 39 (2026-10-16). */
    val TITLE_FOOD = Region(278, 848, 432, 902)

    /** Title of the soap sheet, calibrated on display 39 (2026-10-16). */
    val TITLE_SOAP = Region(268, 846, 512, 906)

    /** The blue frame around the selected product tile. */
    val SELECTED = Region(30, 975, 205, 1200)

    /** The quantity digits, right of the minus bar. */
    val QUANTITY = Region(535, 1285, 625, 1315)

    /** The pay amount printed on the pay button. */
    val PAY_TOTAL = Region(390, 1400, 445, 1435)

    /** The coin counter of the sheet. */
    val COIN = Region(616, 848, 692, 915)

    /** The quantity minus tap, calibrated on display 39. */
    const val MINUS_X = 517
    const val MINUS_Y = 1277

    /** The pay tap, calibrated on display 39. */
    const val PAY_X = 360
    const val PAY_Y = 1425

    /** The sheet title, for the food sheet ([soap] = false) or the soap sheet. */
    fun title(soap: Boolean, drift: Int) = shift(if (soap) TITLE_SOAP else TITLE_FOOD, drift)

    /** The selected product tile frame. */
    fun selected(drift: Int) = shift(SELECTED, drift)

    /** The quantity digits. */
    fun quantity(drift: Int) = shift(QUANTITY, drift)

    /** The pay amount. */
    fun payTotal(drift: Int) = shift(PAY_TOTAL, drift)

    /** The coin counter. */
    fun coin(drift: Int) = shift(COIN, drift)

    /** The pay button band, the last element of the sheet to be painted. */
    fun payBand(drift: Int) = shift(Region(ExchangeDialogRenderPolicy.BAND_LEFT,
        ExchangeDialogRenderPolicy.BAND_TOP, ExchangeDialogRenderPolicy.BAND_RIGHT,
        ExchangeDialogRenderPolicy.BAND_BOTTOM), drift)

    /** The quantity minus tap, moved with the sheet. */
    fun minusTap(drift: Int) = MINUS_X to (MINUS_Y + drift)

    /** The pay tap, moved with the sheet. */
    fun payTap(drift: Int) = PAY_X to (PAY_Y + drift)

    private fun shift(region: Region, drift: Int) =
        Region(region.left, region.top + drift, region.right, region.bottom + drift)
}
