package com.ynk.virtualdisplay.automation

import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.Rect
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.io.File

/**
 * App-autonomous exchange purchase: the historical agentadb purchase chain (tap plate +
 * at (360,1287) -> verify dialog identity -> step quantity -> verify product/quantity/total
 * -> pay at (360,1390) -> confirm dialog closed) rebuilt inside the existing flow framework.
 *
 * Hard stops (no input is ever sent after one of these):
 *  - EXCHANGE_BUDGET_EXHAUSTED      session purchase/coin budget exhausted (checked before dialog)
 *  - EXCHANGE_DIALOG_UNCONFIRMED    dialog identity not matched after the entry tap
 *  - EXCHANGE_PRODUCT_UNCONFIRMED   expected product not the selected row
 *  - EXCHANGE_QUANTITY_BELOW        stepper went past the target (clamped at 1)
 *  - EXCHANGE_QUANTITY_STEPS        stepper did not reach the target within the step bound
 *  - EXCHANGE_QUANTITY_UNREADABLE   final quantity read failed (unknown digit / low confidence)
 *  - EXCHANGE_TOTAL_MISMATCH        pay-button amount != quantity * unit price (never blind-pay)
 *  - EXCHANGE_PAY_UNCONFIRMED       pay tap delivered but the outcome could not be confirmed
 *
 * Unknown intermediate stepper values (e.g. 9 or 2, whose digit shapes have no recorded sample)
 * are skipped while stepping down: only the FINAL quantity and the pay total must be fully
 * readable, and the minus stepper clamps at 1, so the terminal state is always reachable.
 */
class PetExchangeFlow(private val flow: PetFlowContext, private val navigation: PetNavigation,
    private val budget: ExchangeBudget = ExchangeBudget()) {

    val qtyReader: DialogDigitReader by lazy {
        DialogDigitReader(DialogDigitReader.parse(
            flow.context.assets.open("pet_exchange/qty_digits.txt").bufferedReader().readText()))
    }
    val payReader: DialogDigitReader by lazy {
        DialogDigitReader(DialogDigitReader.parse(
            flow.context.assets.open("pet_exchange/pay_digits.txt").bufferedReader().readText()))
    }

    private val foodTitle = PetProfile.Probe("exchange_food_title", Rect(240, 878, 480, 922),
        "pet_exchange/dialog_food_title.png")
    private val soapTitle = PetProfile.Probe("exchange_soap_title", Rect(240, 878, 480, 922),
        "pet_exchange/dialog_soap_title.png")
    private val cookieSelected = PetProfile.Probe("exchange_cookie_selected", Rect(16, 916, 108, 1204),
        "pet_exchange/food_cookie_selected.png")
    private val soapSelected = PetProfile.Probe("exchange_soap_selected", Rect(16, 916, 108, 1204),
        "pet_exchange/soap_soap_selected.png")

    private fun titleProbe(item: Consumable) = if (item == Consumable.COOKIE) foodTitle else soapTitle
    private fun selectedProbe(item: Consumable) = if (item == Consumable.COOKIE) cookieSelected else soapSelected
    private fun sceneProbe(item: Consumable) = if (item == Consumable.COOKIE) flow.foodScene else flow.bath
    fun unitPrice(item: Consumable) = if (item == Consumable.COOKIE) 5 else 2

    private fun titleScore(frame: Bitmap, item: Consumable): Double =
        flow.matcher.match(frame, titleProbe(item), 0.5).score

    /**
     * True when the item's exchange dialog is verifiably open on a fresh frame. Used by the
     * feed/clean stock-out repair to recognize the dialog a failed consumable action left open;
     * a false answer is the safe direction (the repair falls back to the fail-closed path).
     */
    suspend fun dialogOpen(item: Consumable): Boolean {
        val f = flow.frame(latest = true)
        return try { titleScore(f, item) >= 0.90 } finally { f.recycle() }
    }

    /** True while the exchange dialog is verifiably on screen. */
    private suspend fun awaitDialog(item: Consumable): Bitmap = with(flow) {
        val deadline = System.currentTimeMillis() + 8000
        var last = 0.0
        while (System.currentTimeMillis() < deadline) {
            val f = frame(latest = true)
            try {
                last = titleScore(f, item)
                if (last >= 0.95) { save(f, "exchange-dialog-open"); return f }
            } catch (t: Throwable) { f.recycle(); throw t }
            f.recycle()
            delay(400)
        }
        error("EXCHANGE_DIALOG_UNCONFIRMED:$last")
    }

    private fun readField(frame: Bitmap, l: Int, t: Int, r: Int, b: Int, bright: Boolean,
        reader: DialogDigitReader): DialogDigitReader.Reading {
        val w = r - l; val h = b - t
        val px = IntArray(w * h)
        frame.getPixels(px, 0, w, l, t, w, h)
        fun ink(x: Int, y: Int): Boolean {
            val c = px[y * w + x]
            val red = (c shr 16) and 0xFF; val green = (c shr 8) and 0xFF; val blue = c and 0xFF
            return if (bright) red > 200 && green > 200 && blue > 200 else maxOf(red, green, blue) < 100
        }
        return reader.read(w, h, ::ink)
    }

    fun readQuantity(frame: Bitmap) = readField(frame, 548, 1262, 622, 1318, false, qtyReader)
    fun readPayTotal(frame: Bitmap) = readField(frame, 400, 1370, 545, 1448, true, payReader)

    /**
     * Steps the quantity down to [target] with the minus button (516,1287). Unreadable
     * intermediate states are skipped (bounded); the final value must read exactly [target].
     */
    private suspend fun stepTo(item: Consumable, target: Int): Int {
        var steps = 0
        flow.apply {
            while (true) {
                val f = frame(latest = true)
                val reading = try { readQuantity(f).also { if (it.known) save(f, "exchange-qty-" + it.text) } } finally { f.recycle() }
                if (reading.known) {
                    val v = reading.value()
                    when {
                        v == target -> return v
                        v < target -> error("EXCHANGE_QUANTITY_BELOW:$v<$target")
                        // v > target: keep stepping down.
                    }
                } else if (reading.reason in HARD_UNREADABLE) {
                    error("EXCHANGE_QUANTITY_UNREADABLE:${reading.reason}")
                }
                // Unknown intermediate (unrecorded digit shape): skip it and keep stepping.
                check(steps < 12) { "EXCHANGE_QUANTITY_STEPS:$steps" }
                steps++
                safety(); input.tap(516f, 1287f); delay(700)
                if (item == Consumable.COOKIE) verify(listOf(foodTitle), "exchange-dialog-still-open")
                else verify(listOf(soapTitle), "exchange-dialog-still-open")
            }
        }
        @Suppress("UNREACHABLE_CODE")
        error("EXCHANGE_QUANTITY_STEPS:unreachable")
    }

    /**
     * Buys [quantity] units of [item]. By default it must be called while the flow is standing
     * on the item's scene (food scene for cookies, bath scene for soap) — the plate "+" tap both
     * feeds and opens this dialog, so the entry tap is only issued from the scene itself.
     * With [dialogAlreadyOpen] the stock-out dialog that the failed feed/clean just opened is
     * reused verbatim: no entry tap is sent at all (the dialog covers the scene, and a blind
     * tap could dismiss it or select something else). Returns the recorded allowance after the
     * dialog verifiably closed; the flow ends back on the scene.
     */
    suspend fun buy(item: Consumable, quantity: Int, origin: String, dialogAlreadyOpen: Boolean = false): ExchangeBudget.Allowance = with(flow) {
        state = "EXCHANGE_BUY"
        val unit = unitPrice(item)
        // Budget is a precondition: refuse BEFORE any dialog interaction.
        val allowance = budget.reserve(item.name, quantity, unit)
        budgetEvent("purchase-reserve", allowance, origin)
        var payDelivered = false
        var callsBefore = 0
        try {
            if (!dialogAlreadyOpen) {
                // Entry: the plate tap opens the dialog only when the stock is empty; a fed cookie
                // instead would leave the dialog absent, which stops the flow (never a blind buy).
                safety(); input.tap(360f, 1287f)
            }
            val dialog = awaitDialog(item)
            try {
                val product = matcher.match(dialog, selectedProbe(item), 0.5).score
                check(product >= 0.90) { "EXCHANGE_PRODUCT_UNCONFIRMED:$product" }
                emit("exchange-product", product)
            } finally { dialog.recycle() }
            val finalQty = stepTo(item, quantity)
            // Terminal full verification: quantity and pay total must both be readable numbers.
            val f = frame(latest = true)
            val qtyRead: Int; val totalRead: Int
            try {
                save(f, "exchange-before-pay")
                val q = readQuantity(f)
                check(q.known) { "EXCHANGE_QUANTITY_UNREADABLE:${q.reason}" }
                qtyRead = q.value()
                val p = readPayTotal(f)
                check(p.known) { "EXCHANGE_TOTAL_UNREADABLE:${p.reason}" }
                totalRead = p.value()
            } finally { f.recycle() }
            check(qtyRead == quantity) { "EXCHANGE_QUANTITY_MISMATCH:$qtyRead!=$quantity" }
            val expected = quantity * unit
            check(totalRead == expected) { "EXCHANGE_TOTAL_MISMATCH:$totalRead!=$expected" }
            emit("exchange-verified", result = JSONObject().put("item", item.name).put("quantity", qtyRead)
                .put("total", totalRead).put("unit", unit).toString())
            // Pay. The tap baseline decides whether an unknown outcome could have spent coins.
            callsBefore = input.injectionAttempts
            safety(); input.tap(360f, 1390f)
            payDelivered = input.injectionAttempts > callsBefore
            confirmClosed(item)
            allowance.confirmPaid()
            budgetEvent("purchase-paid", allowance, "total=$totalRead")
            emit("exchange-paid", result = JSONObject().put("item", item.name).put("quantity", qtyRead)
                .put("total", totalRead).put("origin", origin).toString())
        } catch (t: Throwable) {
            // No re-pay here and no second attempt anywhere: an unconfirmed outcome is retained.
            val outcome = allowance.settleAfterFailure(payDelivered)
            budgetEvent("purchase-settle", allowance, outcome.name)
            throw t
        }
        allowance
    }

    /**
     * Confirms the dialog closed after the pay tap (historical rule: the dialog closes and the
     * flow lands back on the scene). Anything else after a delivered pay tap is UNKNOWN: hard stop.
     */
    private suspend fun confirmClosed(item: Consumable) = with(flow) {
        val deadline = System.currentTimeMillis() + 12000
        var lastTitle = 1.0; var lastScene = 0.0
        while (System.currentTimeMillis() < deadline) {
            val f = frame(latest = true)
            try {
                lastTitle = titleScore(f, item)
                lastScene = matcher.match(f, sceneProbe(item), 0.5).score
                if (lastTitle < 0.90 && lastScene >= profile.matchThreshold) {
                    save(f, "exchange-after-pay")
                    return@with
                }
            } finally { f.recycle() }
            delay(600)
        }
        error("EXCHANGE_PAY_UNCONFIRMED:title=$lastTitle,scene=$lastScene")
    }

    private fun budgetEvent(action: String, allowance: ExchangeBudget.Allowance, reason: String?) {
        val payload = linkedMapOf<String, Any?>("round" to flow.round, "action" to action,
            "item" to allowance.item, "quantity" to allowance.quantity, "unit_price" to allowance.unitPrice,
            "coins" to allowance.coins, "sequence" to allowance.sequence,
            "session_purchases" to budget.purchases, "session_coins_spent" to budget.coinsSpent,
            "max_purchases" to budget.maxPurchases, "max_coins" to budget.maxCoins)
        if (reason != null) payload["reason"] = reason
        flow.capture.facts.events.append(EventSource.APP_AUTOMATION, EventKind.CARE_BUDGET, payload)
    }

    companion object {
        /** Unreadable reasons that must never be skipped while stepping (dialog/layout broken). */
        val HARD_UNREADABLE = setOf("DIALOG_DIGITS_ABSENT", "DIALOG_ONLY_CURSOR", "DIALOG_TOO_MANY_GROUPS",
            "DIALOG_LEADING_ZERO", "DIALOG_ROI_SIZE", "DIALOG_DIGIT_WIDTH", "DIALOG_DIGIT_HEIGHT",
            "DIALOG_GROUP_EMPTY")
    }
}
