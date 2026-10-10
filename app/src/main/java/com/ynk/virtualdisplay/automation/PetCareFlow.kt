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
 * session, so it accumulates across rounds.
 *
 * An allowance is counted the moment it is RESERVED, before any consumable input, so
 * [cookiesUsed]/[soapsUsed] mean "spent or possibly spent" and can never regress on a failed or
 * unconfirmed action. Every reservation is refused BEFORE the next action begins, so an exhausted
 * budget can never be disclosed only after the fact as an overrun. Pure Kotlin, no Android types:
 * unit-testable on JVM.
 */
class CareConsumableBudget(val cookies: Int = 5, val soaps: Int = 3) {
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
    private val budget: CareConsumableBudget = CareConsumableBudget(),
    private val onHealth: (HealthObservations)->Unit={}) {
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
                navigation.tap(room,PointF(638f,720f),foodScene,"enter-food")
                verify(listOf(foodScene),"feed-before",latest=true)
                consuming=true
                // Read immediately before the cookie action, which owns every inject call started
                // after this point: navigation events can not mask a refused consumable action.
                callsBefore=flow.input.injectionAttempts
                safety(); input.tap(360f,1287f); delay(2500)
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
                throw t
            }
        }
        var cleans=0
        while(h.cleanliness<90) {
            check(cleans<3) { "CLEAN_RETRY_LIMIT" }
            // Batch-wide soap limit, checked before the gesture begins and reserved on the spot.
            val reservation=budget.reserveSoap()
            budgetEvent("clean-reserve",reservation)
            var consuming=false
            var callsBefore=0
            try {
                val before=h.cleanliness; val started=SystemClock.elapsedRealtime()
                navigation.tap(room,PointF(638f,868f),bath,"enter-bath")
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
                throw t
            }
        }
        h
    }

}
