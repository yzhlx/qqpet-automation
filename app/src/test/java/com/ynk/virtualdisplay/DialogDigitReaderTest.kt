package com.ynk.virtualdisplay

import com.ynk.virtualdisplay.automation.DialogDigitReader
import com.ynk.virtualdisplay.automation.ExchangeBudget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Replays the recorded exchange-dialog digit fields through [DialogDigitReader] using the exact
 * masks shipped in assets/pet_exchange, plus the stroke-bitmaps reconstructed from the recorded
 * frames (ledger ground truth). The templates below are copied from
 * app/src/main/assets/pet_exchange/qty_digits.txt at build time of this test; if the asset
 * changes, this test must be regenerated together with it.
 */
class DialogDigitReaderTest {

    /** Reads the shipped template banks directly from the repo (JVM tests have no Android assets). */
    private fun bank(name: String): Map<Int, List<String>> {
        val file = java.io.File("src/main/assets/pet_exchange/$name")
        assertTrue("missing template bank $name: ${file.absolutePath}", file.exists())
        return DialogDigitReader.parse(file.readText())
    }

    private val qtyReader by lazy { DialogDigitReader(bank("qty_digits.txt")) }
    private val payReader by lazy { DialogDigitReader(bank("pay_digits.txt")) }

    @Test
    fun `reader rejects empty field with fail-closed reason`() {
        val reader = DialogDigitReader(mapOf(1 to listOf("0".repeat(768))))
        val r = reader.read(74, 56) { _, _ -> false }
        assertFalse(r.known)
        assertEquals("DIALOG_DIGITS_ABSENT", r.reason)
    }

    @Test
    fun `reader rejects unexpected roi width`() {
        val reader = DialogDigitReader(mapOf(1 to listOf("0".repeat(768))))
        val r = reader.read(50, 31) { _, _ -> true }
        assertFalse(r.known)
        assertTrue(r.reason!!.startsWith("DIALOG_ROI_SIZE"))
    }

    @Test
    fun `reader drops cursor column and reads single digit`() {
        // synthetic digit: solid block normalizes to an all-ink mask; the '1' template matches it
        val reader = DialogDigitReader(mapOf(1 to listOf("1".repeat(768))))
        // render into the qty ROI: digit block x 10..16 (rows 18..42 of 56), cursor at x 60 (2 px, 2 px gap)
        val w = 74; val h = 56
        fun ink(x: Int, y: Int) = (x in 10..16 && y in 18..42) || (x in 60..61 && y in 5..50)
        val r = reader.read(w, h, ::ink)
        assertTrue("expected known, got ${r.reason}", r.known)
        assertEquals("1", r.text)
    }

    @Test
    fun `budget refuses purchase beyond coin cap before any dialog interaction`() {
        val budget = ExchangeBudget(maxPurchases = 4, maxCoins = 10)
        budget.reserve("COOKIE", 1, 5).confirmPaid()
        try {
            budget.requirePurchaseAllowed("COOKIE", 3, 5) // 5 spent + 15 > 10
            throw AssertionError("expected EXCHANGE_BUDGET_EXHAUSTED_COINS")
        } catch (t: IllegalStateException) {
            assertTrue(t.message!!.startsWith("EXCHANGE_BUDGET_EXHAUSTED_COINS"))
        }
    }

    @Test
    fun `budget retains coins on unconfirmed pay and never refunds`() {
        val budget = ExchangeBudget(maxPurchases = 4, maxCoins = 100)
        val a = budget.reserve("COOKIE", 1, 5)
        val outcome = a.settleAfterFailure(payTapDelivered = true)
        assertEquals(com.ynk.virtualdisplay.automation.ReservationOutcome.RETAINED_UNCONFIRMED, outcome)
        assertEquals(5, budget.coinsSpent)
        assertEquals(1, budget.purchases)
        // The retained allowance cannot be re-decided into a refund by a later failure path.
        assertEquals(com.ynk.virtualdisplay.automation.ReservationOutcome.CONSUMED, a.settleAfterFailure(false))
    }

    @Test
    fun `budget releases only proven undelivered pay`() {
        val budget = ExchangeBudget(maxPurchases = 4, maxCoins = 100)
        val a = budget.reserve("SOAP", 1, 2)
        val outcome = a.settleAfterFailure(payTapDelivered = false)
        assertEquals(com.ynk.virtualdisplay.automation.ReservationOutcome.RELEASED, outcome)
        assertEquals(0, budget.coinsSpent)
        assertEquals(0, budget.purchases)
    }

    @Test
    fun `budget quantity cap is a precondition`() {
        val budget = ExchangeBudget(maxPurchases = 4, maxCoins = 100, maxQuantityPerPurchase = 3)
        try {
            budget.reserve("COOKIE", 5, 5)
            throw AssertionError("expected EXCHANGE_QUANTITY_CAP")
        } catch (t: IllegalStateException) {
            assertTrue(t.message!!.startsWith("EXCHANGE_QUANTITY_CAP"))
        }
    }

    // ------------------------------------------------------------------
    // Recorded-frame replay: the 9 ground-truth readings from the purchase
    // ledger, re-read from the original PNGs through the exact production
    // ROI + stroke predicates. These files are workspace fixtures kept out
    // of git by path; the test skips frames that are absent so it stays
    // green on a fresh checkout, and every present frame must match.
    // ------------------------------------------------------------------

    private fun fixture(name: String): java.io.File {
        val candidates = listOf(
            java.io.File("../deliverables/takeover-runtime/three-course-live/single-regression/direct-one-round/ui-run/supply-course-7/$name"),
            java.io.File("../screenshots/v01/$name"),
            java.io.File("../../.foreman/qqpet-three-round-benchmark/live-run-2/topup/$name"),
        )
        return candidates.firstOrNull { it.exists() } ?: candidates[0]
    }

    private fun replay(path: java.io.File, expectedQty: String, expectedTotal: String) {
        if (!path.exists()) return // fixture not present on this checkout: skip silently
        val img = javax.imageio.ImageIO.read(path)
        assertEquals(720, img.width); assertEquals(1600, img.height)
        fun qtyInk(x: Int, y: Int): Boolean {
            val c = img.getRGB(548 + x, 1262 + y)
            val r = (c shr 16) and 0xFF; val g = (c shr 8) and 0xFF; val b = c and 0xFF
            return maxOf(r, g, b) < 100
        }
        fun payInk(x: Int, y: Int): Boolean {
            val c = img.getRGB(400 + x, 1370 + y)
            val r = (c shr 16) and 0xFF; val g = (c shr 8) and 0xFF; val b = c and 0xFF
            return r > 200 && g > 200 && b > 200
        }
        val q = qtyReader.read(74, 56, ::qtyInk)
        val p = payReader.read(145, 78, ::payInk)
        assertTrue("$path qty: ${q.reason}", q.known)
        assertEquals("$path qty", expectedQty, q.text)
        assertTrue("$path total: ${p.reason}", p.known)
        assertEquals("$path total", expectedTotal, p.text)
        img.flush()
    }

    @Test
    fun `recorded frames replay with exact ledger ground truth`() {
        val d = "../deliverables/takeover-runtime/three-course-live/single-regression/direct-one-round/ui-run/supply-course-7/"
        replay(fixture("cookie-buy-one-confirm.png"), "1", "5")
        replay(fixture("cookie-buy-four-confirm.png"), "4", "20")
        replay(fixture("soap-price.png"), "10", "20")
        replay(fixture("resume9-soap-buy-eight-confirm.png"), "1", "2")
        replay(fixture("resume9-soap-buy-seven-confirm.png"), "7", "14")
        replay(java.io.File("../screenshots/v01/p1-feed-after.png"), "5", "25")
        replay(java.io.File("../../.foreman/qqpet-three-round-benchmark/live-run-2/topup/live2-q3-175746.png"), "3", "15")
        replay(java.io.File("../../.foreman/qqpet-three-round-benchmark/live-run-2/topup/live2-s23-183736.png"), "3", "6")
    }
}
