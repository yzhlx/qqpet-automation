package com.ynk.virtualdisplay.automation

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.Rect
import android.os.SystemClock
import kotlinx.coroutines.delay
import kotlinx.coroutines.TimeoutCancellationException
import org.json.JSONObject
import java.io.File

enum class Consumable { COOKIE, SOAP }
enum class ReservationState { RESERVED, CONSUMED, RELEASED }
enum class ReservationOutcome { CONSUMED, RETAINED_UNCONFIRMED, RELEASED }

/**
 * One allowance of a consumable, taken BEFORE the action that may spend it.
 *
 * The caller may only [releaseUnused] when it can prove nothing was delivered, because an
 * indeterminate outcome (the input was sent but the result could not be confirmed) may already have
 * spent the item. Anything unproven stays reserved, so the batch cap is never widened by a failure.
 */
class ConsumableReservation internal constructor(private val budget: CareConsumableBudget,
    val consumable: Consumable, val sequence: Int, val limit: Int) {
    var state = ReservationState.RESERVED; private set
    /** The action was delivered and its effect was measured: the allowance is firmly spent. */
    fun confirmConsumed() {
        check(state == ReservationState.RESERVED) { "CARE_RESERVATION_NOT_ACTIVE:$state" }
        state = ReservationState.CONSUMED
    }
    /**
     * Returns the allowance. Only for an outcome that PROVES no consumable input was delivered and
     * therefore nothing could have been spent; an indeterminate outcome must keep it reserved.
     */
    fun releaseUnused(reason: String) {
        check(state == ReservationState.RESERVED) { "CARE_RESERVATION_NOT_ACTIVE:$state" }
        require(reason.isNotBlank()) { "CARE_RELEASE_REASON_REQUIRED" }
        state = ReservationState.RELEASED
        budget.release(this)
    }
    /**
     * Settles this allowance from measured delivery evidence. [deliveredEvents] counts input events
     * that verifiably reached the game during the action that could spend this item.
     *
     * A non-zero count means the consumable input was delivered while its effect stayed unconfirmed,
     * so the item may already be gone: the allowance is RETAINED, never refunded, and the batch cap
     * can not be widened by a failure. Zero is the only case where nothing could have been spent.
     * Idempotent: a spend already confirmed is returned untouched rather than re-decided.
     */
    fun settleAfterFailure(deliveredEvents: Int): ReservationOutcome =
        when(state) {
            // Already settled: a confirmed spend is final, and a returned allowance stays returned.
            // Neither may be re-decided by a later failure path.
            ReservationState.CONSUMED -> ReservationOutcome.CONSUMED
            ReservationState.RELEASED -> ReservationOutcome.RELEASED
            ReservationState.RESERVED ->
                if(deliveredEvents > 0) { confirmConsumed();ReservationOutcome.RETAINED_UNCONFIRMED }
                else { releaseUnused("NO_INPUT_DELIVERED");ReservationOutcome.RELEASED }
        }
}

/**
 * Cumulative hard limit on consumable care actions across EVERY recover() call of one session.
 *
 * The per-recover retry limits (5 feeds / 3 cleans) are NOT the batch limit: recover() runs once
 * per round, so on their own they would permit up to 15 cookies and 9 soaps in a 3-round run. This
 * budget is owned by one PetCareFlow instance, which PetSessionController constructs once per
 * session, so it accumulates across rounds. Measured on-device: a stock-out gesture can burn one
 * allowance with zero gain before the restock lands, and 90-reach from the 60s needs 3 soaps
 * (+10 each), so the soap budget carries one spare (4) and the cookie budget one spare (6).
 *
 * An allowance is counted the moment it is RESERVED, before any consumable input, so
 * [cookiesUsed]/[soapsUsed] mean "spent or possibly spent" and can never regress on a failed or
 * unconfirmed action. Every reservation is refused BEFORE the next action begins, so an exhausted
 * budget can never be disclosed only after the fact as an overrun. Pure Kotlin, no Android types:
 * unit-testable on JVM.
 */
class CareConsumableBudget(val cookies: Int = 6, val soaps: Int = 4) {
    init { require(cookies > 0 && soaps > 0) { "CARE_BUDGET_INVALID:$cookies/$soaps" } }
    var cookiesUsed = 0; private set
    var soapsUsed = 0; private set
    fun requireCookieAvailable() = check(cookiesUsed < cookies) { "CARE_COOKIE_BUDGET_EXHAUSTED:$cookiesUsed/$cookies" }
    fun requireSoapAvailable() = check(soapsUsed < soaps) { "CARE_SOAP_BUDGET_EXHAUSTED:$soapsUsed/$soaps" }

    /** Takes the cookie allowance before the consumable input; the guard runs first. */
    fun reserveCookie(): ConsumableReservation {
        requireCookieAvailable();cookiesUsed++
        return ConsumableReservation(this,Consumable.COOKIE,cookiesUsed,cookies)
    }
    /** Takes the soap allowance before the consumable input; the guard runs first. */
    fun reserveSoap(): ConsumableReservation {
        requireSoapAvailable();soapsUsed++
        return ConsumableReservation(this,Consumable.SOAP,soapsUsed,soaps)
    }
    /** Counting entry points retained for the boundary tests; both take an allowance up front. */
    fun consumedCookie() { reserveCookie() }
    fun consumedSoap() { reserveSoap() }

    internal fun release(reservation: ConsumableReservation) {
        when(reservation.consumable) {
            Consumable.COOKIE -> { check(cookiesUsed > 0) { "CARE_BUDGET_RELEASE_UNDERFLOW" };cookiesUsed-- }
            Consumable.SOAP -> { check(soapsUsed > 0) { "CARE_BUDGET_RELEASE_UNDERFLOW" };soapsUsed-- }
        }
    }
}

/**
 * Settles a consumable allowance after a failed care attempt and reports what should be journaled.
 *
 * Kept as a free function so the decision that guards the batch cap is independently testable: the
 * only input is the measured delivery evidence, and the only way to get an allowance back is to
 * prove that no input was delivered. [deliveredEvents] is the count of input events the consuming
 * action verifiably delivered.
 */
internal fun settleAllowanceAfterFailure(reservation: ConsumableReservation, deliveredEvents: Int,
    action: String, journal: (String, ConsumableReservation, String?) -> Unit) {
    when(reservation.settleAfterFailure(deliveredEvents)) {
        // Success already settled this allowance explicitly; nothing to add.
        ReservationOutcome.CONSUMED -> Unit
        ReservationOutcome.RETAINED_UNCONFIRMED -> journal("$action-unconfirmed",reservation,"DELIVERED_BUT_UNCONFIRMED")
        ReservationOutcome.RELEASED -> journal("$action-release",reservation,"NO_INPUT_DELIVERED")
    }
}

/**
 * Decides the delivery evidence of ONE consuming action from the controller's inject-call counts.
 *
 * [consumableAttempted] is the flow's own marker for "the consumable input was reached".
 * [injectionCallsBefore] must be read immediately before that consumable action and
 * [injectionCallsAfter] after the failure, so the difference is exactly the inject calls THIS action
 * started - never the events an earlier navigation tap or frame verification produced. A difference
 * of zero proves no inject call ever started, which is the only case that may return an allowance; a
 * call that started and then returned false, failed or was cancelled stays unconfirmed, because the
 * transport may already have applied it. Counts are monotonic, so a caller that read a stale baseline
 * can only ever over-retain, never refund a possibly-spent item.
 */
internal fun consumableDeliveryEvidence(consumableAttempted: Boolean, injectionCallsBefore: Int,
    injectionCallsAfter: Int): Int {
    if(!consumableAttempted) return 0
    // A baseline further ahead than the current count means the caller's snapshot is not this
    // action's; retain rather than refund an item that may already be spent.
    if(injectionCallsBefore > injectionCallsAfter) return 1
    return if(injectionCallsAfter > injectionCallsBefore) 1 else 0
}

/** Extracted measured cookie / soap flow. Unknown is never converted to a number. */
class PetCareFlow(private val flow: PetFlowContext,private val navigation: PetNavigation,
    private val budget: CareConsumableBudget = CareConsumableBudget(cookies=6,soaps=4),
    private val exchangeBudget: ExchangeBudget = ExchangeBudget(),
    private val onHealth: (HealthObservations)->Unit={}) {
    /** Stock-out purchase branch: created lazily so JVM tests without assets never touch it. */
    val exchange: PetExchangeFlow by lazy { PetExchangeFlow(flow,navigation,exchangeBudget) }
    var careStatistics=JSONObject();private set
    /**
     * Allowance-transition sink. Defaults to the session journal, which fsyncs every append; JVM
     * tests substitute it to observe transitions without a capture session.
     */
    var budgetJournal: (EventKind, Map<String, Any?>) -> Unit = { kind, payload ->
        flow.capture.facts.events.append(EventSource.APP_AUTOMATION, kind, payload)
    }
    /**
     * Journals one allowance transition. Written before the consumable input (RESERVED) and after it
     * resolves, so a crash, cancellation or unconfirmed result leaves the spend on record rather
     * than silently freeing the batch budget.
     */
    private fun budgetEvent(action: String,reservation: ConsumableReservation,reason: String?=null) {
        val payload=linkedMapOf<String,Any?>("round" to flow.round,"action" to action,
            "consumable" to reservation.consumable.name,"state" to reservation.state.name,
            "sequence" to reservation.sequence,"limit" to reservation.limit,
            "session_cookie_count" to budget.cookiesUsed,"session_soap_count" to budget.soapsUsed,
            "cookie_budget" to budget.cookies,"soap_budget" to budget.soaps)
        if(reason!=null) payload["reason"]=reason
        budgetJournal(EventKind.CARE_BUDGET,payload)
    }
    /**
     * Settles an allowance after a failure.
     *
     * [consumableAttempted]=false means the flow failed before the consumable input was ever reached,
     * so the input the display saw belonged to navigation and none of it could have spent this item.
     * [injectionCallsBefore] is the controller's monotonic inject-call count read just before this
     * action began, so the only way to release the allowance is proof that THIS action started no
     * inject call at all - a guard refusal or a construction failure. A call that started and then
     * returned false, failed or was cancelled is deliberately kept unconfirmed: the transport may
     * already have applied it.
     */
    private fun settle(reservation: ConsumableReservation,action: String,consumableAttempted: Boolean,
        injectionCallsBefore: Int) = settleAllowanceAfterFailure(reservation,
            consumableDeliveryEvidence(consumableAttempted, injectionCallsBefore,
                flow.input.injectionAttempts),
            action) { phase, held, reason -> budgetEvent(phase,held,reason) }

    suspend fun readHealth(label: String, parent: PetProfile.Probe = flow.home): PetCareReading = with(flow) {
        val started = SystemClock.elapsedRealtime()
        navigation.tap(parent,PointF(516f,160f),healthPanel,"open-health")
        val f = frame(true)
        val h = try {
            save(f,label)
            val snapshot=PetHealthCheck.observe(context,f,System.currentTimeMillis(),File(evidence,"round-$round-$label.png").absolutePath)
            capture.facts.health(snapshot)
            onHealth(snapshot)
            recovery.health(snapshot)
            PetCareReading(snapshot)
        } finally { f.recycle() }
        check(h.energy in 10..100 && h.cleanliness in 10..100) { "HEALTH_VALUE_ABNORMAL" }
        emit(label,result=healthJson(h).put("read_duration_ms",SystemClock.elapsedRealtime()-started).toString())
        navigation.tap(healthPanel,PointF(355f,454f),parent,"close-health")
        h
    }
    suspend fun recover(initial: PetCareReading): PetCareReading = with(flow) {
        state="RECOVER_IF_NEEDED"
        var h = initial
        val recovery=JSONObject().put("feed_count",0).put("feed_duration_ms",0L)
            .put("clean_count",0).put("clean_duration_ms",0L)
        careStatistics=recovery
        var feeds=0
        var restocks=0
        while(h.energy<90) {
            check(feeds<5) { "FEED_RETRY_LIMIT" }
            // The batch-wide limit, checked BEFORE the action begins, and then reserved on the spot:
            // from here the allowance is spent-or-possibly-spent and a failed feed cannot free it.
            val reservation=budget.reserveCookie()
            budgetEvent("feed-reserve",reservation)
            // Set only once the consumable tap itself has been started; until then any failure is
            // provably before the cookie could have been spent. PetInputController scopes its own
            // delivered-event count to this one action.
            var consuming=false
            var callsBefore=0
            try {
                val before=h.energy; val started=SystemClock.elapsedRealtime(); val wall=System.currentTimeMillis()
                // After a stock-out restock the flow is already standing on the food scene
                // (the purchase closes back onto it); only enter from the room otherwise.
                if(feeds>0 || restocks>0) enterSceneIfAbsent(foodScene,"enter-food",PointF(638f,720f)) else navigation.tap(room,PointF(638f,720f),foodScene,"enter-food")
                verify(listOf(foodScene),"feed-before",latest=true)
                consuming=true
                // Read immediately before the cookie action, which owns every inject call started
                // after this point: navigation events can not mask a refused consumable action.
                callsBefore=flow.input.injectionAttempts
                // Game update 2026-10-12: feeding is now the bottom-sheet purchase flow. Tap the
                // scene's 兑换食物 bubble (360,1380), the sheet opens with the cookie selected
                // (default qty may be >1), then buy exactly 1 (fully verified, including the pay
                // total) - the purchased cookie is consumed immediately (体力+10). The exchange
                // budget allowance covers this feed.
                safety(); input.tap(360f,1380f); delay(2500)
                if(!exchange.dialogOpen(Consumable.COOKIE)) error("FEED_SHEET_NOT_OPENED")
                exchange.buy(Consumable.COOKIE,1,"feed",dialogAlreadyOpen=true)
                // The purchased cookie does NOT feed automatically: it waits on the plate and the
                // 兑换食物 pill disappears while food is out. Tap the cookie to feed, measured
                // plate center (360,1300) from the field evidence afterpay.png.
                safety(); input.tap(360f,1300f); delay(2500)
                navigation.dismissCareUpgradeIfPresent()
                verify(listOf(foodScene),"feed-after")
                h=readHealth("feed-health",foodScene)
                check(h.energy>before && h.energy-before in 5..12) { "FEED_NO_EXPECTED_GAIN_OR_EMPTY" }
                navigation.tap(foodScene,PointF(80f,160f),room,"leave-food")
                feeds++
                // The effect was measured on a frame taken after the tap, so the allowance is spent.
                reservation.confirmConsumed()
                budgetEvent("feed-consume",reservation)
                recovery.put("feed_count",feeds)
                recovery.put("session_feed_count",budget.cookiesUsed)
                recovery.put("feed_budget",budget.cookies)
                recovery.put("feed_duration_ms",recovery.getLong("feed_duration_ms")+SystemClock.elapsedRealtime()-started)
                emit("feed",result=JSONObject().put("feed_start_ts",wall).put("feed_end_ts",System.currentTimeMillis())
                    .put("feed_duration_ms",SystemClock.elapsedRealtime()-started).put("stamina_before",before)
                    .put("stamina_after",h.energy).put("inventory_before","UNKNOWN").put("inventory_after","UNKNOWN").toString())
            }
            catch(t: Throwable) {
                settle(reservation,"feed",consuming,callsBefore)
                // Stock-out repair under the sheet feed flow: the feed IS a verified 1-cookie
                // purchase, so an inventory stock-out now surfaces as FEED_SHEET_NOT_OPENED (the
                // bubble no longer opens the sheet) or a failed buy inside the sheet. Recovery is
                // bounded by FEED_RETRY_LIMIT and the exchange budget; every purchase is fully
                // qty/total-verified.
                val message=t.message.orEmpty()
                if(message.startsWith("FEED_SHEET_NOT_OPENED") && restocks<4) {
                    restocks++
                    emit("feed-stockout-restock",reason=message)
                    // The bubble tap may have left a stray sheet; dismiss with back, then re-enter.
                    flow.input.tap(660f,660f); delay(1200)
                    ensureFoodScene()
                    exchange.buy(Consumable.COOKIE,1,"feed-stockout")
                    // After the sheet purchase the cookie is consumed by the feed itself; re-verify.
                    navigation.dismissCareUpgradeIfPresent()
                    verify(listOf(foodScene),"feed-after-restock")
                    h=readHealth("feed-health-restock",foodScene)
                    check(h.energy>0) { "FEED_NO_EXPECTED_GAIN_OR_EMPTY" }
                    navigation.tap(foodScene,PointF(80f,160f),room,"leave-food")
                    feeds++
                    reservation.confirmConsumed()
                    budgetEvent("feed-consume",reservation)
                    continue
                }
                throw t
            }
        }
        var cleans=0
        var soapRestocks=0
        while(h.cleanliness<90) {
            check(cleans<3) { "CLEAN_RETRY_LIMIT" }
            // Batch-wide soap limit, checked before the gesture begins and reserved on the spot.
            val reservation=budget.reserveSoap()
            budgetEvent("clean-reserve",reservation)
            var consuming=false
            var callsBefore=0
            try {
                val before=h.cleanliness; val started=SystemClock.elapsedRealtime()
                if(cleans>0 || soapRestocks>0) enterSceneIfAbsent(bath,"enter-bath",PointF(638f,868f)) else navigation.tap(room,PointF(638f,868f),bath,"enter-bath")
                // Page identity is the stable bath scene, never the soap button: the button repaints
                // to its pale "already clean" state once cleanliness is maxed, which used to fail
                // clean-after verification (PAGE_MISMATCH ... 0.9538353304284677) with cleanliness
                // already at 99. The soap probe stays the measured gesture/tap target only.
                verify(listOf(bath),"clean-before",latest=true)
                val points=buildList {
                    add(PointF(360f,1345f));add(PointF(360f,1100f));add(PointF(360f,900f))
                    repeat(40) { add(PointF(280f,800f));add(PointF(430f,950f));add(PointF(280f,950f));add(PointF(430f,800f)) }
                }
                // The gesture is the consumable action; the controller counts its delivered events.
                consuming=true
                // Read immediately before the soap gesture, which owns every inject call started after
                // this point, so a safety() refusal can still return the allowance.
                callsBefore=flow.input.injectionAttempts
                safety(); input.drag(points,150); delay(2000)
                navigation.dismissCareUpgradeIfPresent()
                verify(listOf(bath),"clean-after")
                h=readHealth("clean-health",bath)
                check(h.cleanliness>before) { "CLEAN_NO_GAIN_OR_EMPTY" }
                navigation.tap(bath,PointF(80f,160f),room,"leave-bath")
                cleans++
                // Measured gain observed after the gesture: the soap allowance is firmly spent.
                reservation.confirmConsumed()
                budgetEvent("clean-consume",reservation)
                recovery.put("clean_count",cleans)
                recovery.put("session_clean_count",budget.soapsUsed)
                recovery.put("clean_budget",budget.soaps)
                recovery.put("clean_duration_ms",recovery.getLong("clean_duration_ms")+SystemClock.elapsedRealtime()-started)
                emit("clean",result=JSONObject().put("cleanliness_before",before).put("cleanliness_after",h.cleanliness)
                    .put("clean_duration_ms",SystemClock.elapsedRealtime()-started).put("inventory_before","UNKNOWN")
                    .put("inventory_after","UNKNOWN").toString())
            }
            catch(t: Throwable) {
                settle(reservation,"clean",consuming,callsBefore)
                // Stock-out repair for soap: measured zero-gain clean -> buy 1 soap, retry once.
                val message=t.message.orEmpty()
                // One measured soap = +10; reaching 90 from the 60s may need 3 soaps, so both
                // stock-out symptoms (measured zero-gain, or the dialog opened by the failed
                // gesture) may recur. Each restock buys ONE verified soap; the count is bounded
                // by CLEAN_RETRY_LIMIT and the exchange budget; every purchase is fully
                // qty/total-verified, so nothing here blind-buys.
                if(message.startsWith("CLEAN_NO_GAIN_OR_EMPTY") && soapRestocks<4) {
                    soapRestocks++
                    emit("clean-stockout-restock",reason=message)
                    ensureBathScene()
                    exchange.buy(Consumable.SOAP,1,"clean-stockout")
                    continue
                }
                // A stock-out clean opens the EXCHANGE dialog instead of a scrubbed scene: the
                // first symptom is PAGE_MISMATCH on clean-after. Reuse that open dialog (no
                // further input) for the restock; bounded like the cookie path, every purchase
                // fully verified; anything else propagates fail-closed.
                if(message.startsWith("PAGE_MISMATCH:") && message.contains(":clean-after:")
                    && exchange.dialogOpen(Consumable.SOAP) && soapRestocks<4) {
                    soapRestocks++
                    emit("clean-stockout-restock",reason="dialog-on-clean-after#$soapRestocks")
                    exchange.buy(Consumable.SOAP,1,"clean-stockout",dialogAlreadyOpen=true)
                    continue
                }
                throw t
            }
        }
        h
    }

    /**
     * Guarantees the flow is standing on the food scene before an exchange buy: the failed feed
     * may have stopped anywhere between room entry and the health panel. Room -> food is the
     * measured scene entry (638,720); scene -> room is the measured leave (80,160).
     */
    private suspend fun ensureFoodScene() = enterSceneIfAbsent(flow.foodScene,"restock-enter-food",PointF(638f,720f))
    private suspend fun ensureBathScene() = enterSceneIfAbsent(flow.bath,"restock-enter-bath",PointF(638f,868f))

    private suspend fun enterSceneIfAbsent(scene: PetProfile.Probe, action: String, point: PointF) = with(flow) {
        val f=frame(latest=true)
        val onScene=try { matcher.match(f,scene,profile.matchThreshold).score>=profile.matchThreshold } finally { f.recycle() }
        if(onScene) return@with
        verifySettled(listOf(room),"$action-find-room")
        navigation.tap(room,point,scene,action)
    }

}
