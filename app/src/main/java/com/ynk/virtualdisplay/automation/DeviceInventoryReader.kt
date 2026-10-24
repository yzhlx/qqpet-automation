package com.ynk.virtualdisplay.automation

import android.graphics.Bitmap
import android.graphics.Rect

/**
 * Reads the OWNED count of a care consumable off the food / bath SCENE that shows it.
 *
 * WHY A SCENE READ, NOT A DIALOG. A measured trap (2026-10-21 s21-reenter): the food scene shows the
 * cookie ART even with ZERO in stock, and tapping it opens the exchange sheet. "I can see a cookie"
 * therefore proves nothing, and opening the buy dialog to "probe" stock spends nothing but WOULD
 * mis-report an unknown as a purchase. The only honest sources are the small digit the game prints
 * next to the item on the scene:
 *   - BATH scene: soap count, measured "5" before / "4" after one scrub (EXACT).
 *   - FOOD scene: cookie count, measured "0" on an empty plate (EXACT).
 * When the digit ROI has no number, this reader alone returns UNKNOWN. Callers may then apply
 * [SceneEmptyStockPolicy] with a measured empty-stock bubble to prove exact zero for food only.
 *
 * PAGE IDENTITY IS THE CALLER'S. This parser only trusts a scene the caller has already positively
 * identified by its template probe; it re-verifies the item/page pairing and never navigates.
 */
enum class InventoryScenePage { FOOD, BATH }

object SceneInventoryParser {
    /** The item each scene can truthfully report; anything else is a caller bug and is refused. */
    fun itemOf(page: InventoryScenePage): CareItem =
        if (page == InventoryScenePage.FOOD) CareItem.COOKIE else CareItem.SOAP

    /** Scene digit ROI, in ORIGINAL frame pixels (720x1600 reference), as [left, top, right, bottom]. */
    private data class Roi(val left: Int, val top: Int, val right: Int, val bottom: Int, val name: String)
    private val FOOD_ROI = Roi(392, 1136, 512, 1268, "food-cookie")
    /**
     * Measured 2026-10-22 from care-bath-before4.png by pixel colour analysis: the soap counter
     * digit 「1」 is the blue glyph at x=448..471, y=1224..1255. The box is padded around it.
     */
    private val BATH_ROI = Roi(415, 1195, 505, 1285, "bath-soap")

    private fun roi(page: InventoryScenePage) = if (page == InventoryScenePage.FOOD) FOOD_ROI else BATH_ROI

    private val DIGITS = Regex("""^\d{1,3}$""")

    /**
     * Parse recognised lines for [page]'s item count.
     *
     * A line counts only when its centre lies INSIDE the item's digit ROI AND its text is pure
     * digits. The ROI is deliberately tight around the count so the coin/paw pills at the top bar
     * can never be mistaken for it. More than one in-region digit line is ambiguous → Unknown.
     */
    fun parse(
        page: InventoryScenePage,
        lines: List<TextLine>,
        frameWidth: Int,
        frameHeight: Int,
        observedAt: Long,
        evidence: String,
    ): InventoryObservation {
        val item = itemOf(page)
        val unknownReason = "INVENTORY_${page.name}_COUNT_UNREAD"
        val invalid = frameWidth != 720 || frameHeight != 1600
        val count = if (invalid) {
            ObservedValue.Unknown("INVENTORY_FRAME_SIZE_INVALID", observedAt, evidence)
        } else {
            val box = roi(page)
            val candidates = lines.mapNotNull { line ->
                val b: Rect = line.bounds
                val cx = (b.left + b.right) / 2f
                val cy = (b.top + b.bottom) / 2f
                val inside = cx >= box.left && cx <= box.right && cy >= box.top && cy <= box.bottom &&
                    b.left < b.right && b.top < b.bottom
                val text = line.text.trim()
                if (inside && DIGITS.matches(text)) text.toIntOrNull() else null
            }
            when {
                candidates.size == 1 -> ObservedValue.Known(
                    candidates.single(), observedAt, 1.0, evidence, ObservationSource.DIGIT_RECOGNITION)
                candidates.isEmpty() -> ObservedValue.Unknown(unknownReason, observedAt, evidence)
                else -> ObservedValue.Unknown(
                    "${unknownReason}_AMBIGUOUS:${candidates.joinToString("/")}", observedAt, evidence)
            }
        }
        // Precision is EXACT only when a concrete digit was read; an unknown count carries no claim.
        return if (item == CareItem.COOKIE) {
            InventoryObservation(cookieCount = count, soapCount = ObservedValue.Unknown(
                "INVENTORY_SOAP_NOT_ON_THIS_PAGE", observedAt, evidence), cookiePrecision = InventoryPrecision.EXACT)
        } else {
            InventoryObservation(cookieCount = ObservedValue.Unknown(
                "INVENTORY_COOKIE_NOT_ON_THIS_PAGE", observedAt, evidence), soapCount = count, soapPrecision = InventoryPrecision.EXACT)
        }
    }

    /** Validates a ROI stays inside the frame; a caller that crops must not read outside it. */
    fun validateRoi(page: InventoryScenePage, frameWidth: Int, frameHeight: Int): Boolean {
        val r = roi(page)
        return frameWidth == 720 && frameHeight == 1600 && r.left >= 0 && r.top >= 0 &&
            r.right <= frameWidth && r.bottom <= frameHeight && r.left < r.right && r.top < r.bottom
    }

    fun roiBounds(page: InventoryScenePage): Rect = roi(page).let { Rect(it.left, it.top, it.right, it.bottom) }
}

/**
 * Device adapter: recognises [openedFrame] with the same reader the course scan uses and hands the
 * lines to [SceneInventoryParser]. It re-checks the item/page pairing (a SOAP read on the FOOD scene
 * is a caller bug) and never produces a count from an illegal ROI. It only reads: no tap, no nav.
 */
class DeviceInventoryReader(private val reader: CourseNameReader) {
    /** Read [item]'s owned count from a frame the caller has already identified as [page]. */
    fun read(
        item: CareItem,
        page: InventoryScenePage,
        openedFrame: Bitmap,
        observedAt: Long,
        evidence: String,
        pageIdentityVerified: Boolean = false,
    ): InventoryObservation {
        require(SceneInventoryParser.itemOf(page) == item) {
            "INVENTORY_SCENE_ITEM_MISMATCH:page=$page item=$item"
        }
        if (!pageIdentityVerified) return InventoryObservation.unread("INVENTORY_PAGE_UNCONFIRMED", observedAt)
        if (!SceneInventoryParser.validateRoi(page, openedFrame.width, openedFrame.height)) {
            return InventoryObservation.unread("INVENTORY_ROI_OUT_OF_FRAME", observedAt)
        }
        // Full-frame OCR then ROI filter: stylised scene digits (measured after-pay 「1」) are
        // often missed when the crop is too tight for ML Kit, but appear in the whole frame.
        return try {
            SceneInventoryParser.parse(page, reader.read(openedFrame), openedFrame.width,
                openedFrame.height, observedAt, evidence)
        } catch (t: Exception) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            InventoryObservation.unread("INVENTORY_OCR_FAILED:${t.javaClass.simpleName}", observedAt)
        }
    }
}
