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
    /** Legacy source-compatible argument; cached-wallet guards no longer authorize payment. */
    private val payGuard: ((Consumable,Int,Int,Int)->Unit)? = null,
    /** Called per payment with its own fresh sheet wallet; caller protects ALL unpaid suffix tuition. */
    private val freshPayGuard: (suspend (Consumable,Int,Int,Int,ObservedValue<Int>)->Unit)? = null,
    private val beforeConsumingInput: ((Consumable)->Unit)? = null,
    private val onHealth: (HealthObservations)->Unit={}) {
    private val resourceTextReader by lazy { MlKitCourseNameReader() }
    private val inventoryReader by lazy { DeviceInventoryReader(resourceTextReader) }
    private var inventoryReadSerial=0
    val exchange: PetExchangeFlow by lazy {
        PetExchangeFlow(flow,navigation,exchangeBudget,payGuard,
            freshPayGuard=freshPayGuard,walletReader=DeviceWalletReader(resourceTextReader),
            beforeConsumingInput=beforeConsumingInput) }

    /** Read numeric stock only after proving this exact scene frame; never infer it from artwork. */
    private suspend fun observedStock(item: Consumable, label: String): ObservedValue<Int> = with(flow) {
        val f=frame(latest=true)
        val observedAt=System.currentTimeMillis()
        val page=if(item==Consumable.COOKIE) InventoryScenePage.FOOD else InventoryScenePage.BATH
        val expected=if(item==Consumable.COOKIE) foodScene else bath
        try {
            check(f.width==720 && f.height==1600) { "CARE_INVENTORY_DISPLAY_UNSUPPORTED" }
            val matched=score(f,listOf(expected))
            check(matched>=profile.matchThreshold) { "CARE_INVENTORY_SCENE_UNCONFIRMED:$label:$matched" }
            val roi=SceneInventoryParser.roiBounds(page)
            val local=Bitmap.createBitmap(f,roi.left,roi.top,roi.width(),roi.height())
            val path=File(evidence,"round-$round-$label-inventory-${inventoryReadSerial++}.png")
            try { path.outputStream().use { check(local.compress(Bitmap.CompressFormat.PNG,100,it)) } }
            finally { if(local!==f) local.recycle() }
            val careItem=if(item==Consumable.COOKIE) CareItem.COOKIE else CareItem.SOAP
            val reading=inventoryReader.read(careItem,page,f,observedAt,path.absolutePath,pageIdentityVerified=true)
                .countOf(careItem)
            val counterOwned=if(reading is ObservedValue.Known) reading else {
                val box=SceneInventoryParser.roiBounds(page)
                val crop=Bitmap.createBitmap(f,box.left,box.top,box.width(),box.height())
                try {
                    val pixels=IntArray(crop.width*crop.height)
                    crop.getPixels(pixels,0,crop.width,0,0,crop.width,crop.height)
                    if(SceneCounterDetector.hasCounter(pixels,crop.width,crop.height))
                        ObservedValue.Known(1,observedAt,1.0,path.absolutePath,
                            ObservationSource.SCENE_COUNTER_LOWER_BOUND)
                    else reading
                } finally { if(crop!==f) crop.recycle() }
            }
            val resolved=run {
                val bubbleProbe=if(item==Consumable.COOKIE) foodExchangeBubble else bathExchangeBubble
                val bubbleScore=score(f,listOf(bubbleProbe))
                val bubbleMatched=bubbleScore>=profile.matchThreshold
                val bubblePath=File(evidence,"round-$round-$label-empty-stock-${inventoryReadSerial}.png")
                val bubble=Bitmap.createBitmap(f,bubbleProbe.rect.left,bubbleProbe.rect.top,
                    bubbleProbe.rect.width(),bubbleProbe.rect.height())
                try {
                    bubblePath.outputStream().use { check(bubble.compress(Bitmap.CompressFormat.PNG,100,it)) }
                } finally { if(bubble!==f) bubble.recycle() }
                val afterEmpty=SceneEmptyStockPolicy.resolve(
                    page=page,
                    digitCount=counterOwned,
                    emptyStockBubbleMatched=bubbleMatched,
                    bubbleScore=bubbleScore,
                    matchThreshold=profile.matchThreshold,
                    observedAt=observedAt,
                    evidence=bubblePath.absolutePath,
                )
                if(afterEmpty is ObservedValue.Known || item!=Consumable.COOKIE) afterEmpty else {
                    val cookieScore=score(f,listOf(cookie))
                    val cookiePath=File(evidence,"round-$round-$label-owned-stock-${inventoryReadSerial}.png")
                    val cookieCrop=Bitmap.createBitmap(f,cookie.rect.left,cookie.rect.top,
                        cookie.rect.width(),cookie.rect.height())
                    try {
                        cookiePath.outputStream().use { check(cookieCrop.compress(Bitmap.CompressFormat.PNG,100,it)) }
                    } finally { if(cookieCrop!==f) cookieCrop.recycle() }
                    SceneOwnedStockPolicy.resolve(
                        page=page,
                        digitCount=afterEmpty,
                        emptyStockBubbleMatched=bubbleMatched,
                        cookieTargetMatched=cookieScore>=profile.matchThreshold,
                        cookieScore=cookieScore,
                        matchThreshold=profile.matchThreshold,
                        observedAt=observedAt,
                        evidence=cookiePath.absolutePath,
                    )
                }
            }
            emit("$label-inventory",result=EventJson.encode(resolved.fields()))
            resolved
        } finally { f.recycle() }
    }

    private suspend fun ensureOwnedStock(item: Consumable, label: String): ObservedValue<Int> {
        val current=observedStock(item,label)
        if(CareResourceGate.stockAction(current,System.currentTimeMillis())==CareResourceGate.StockAction.CONSUME_OWNED)
            return current
        exchange.buy(item,1,"$label-numeric-zero",ownedCount=current)
        val restocked=observedStock(item,"$label-restocked")
        check(CareResourceGate.stockAction(restocked,System.currentTimeMillis())==CareResourceGate.StockAction.CONSUME_OWNED) {
            "CARE_RESTOCK_NOT_CONFIRMED:$item"
        }
        return restocked
    }
    var careStatistics=JSONObject();private set
    /** The most recent panel reading, so a later stage can reuse it without reopening the panel. */
    var latestReading: PetCareReading? = null; private set
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
        // Care exists precisely to lift low readings back up: the entry guard must accept the
        // full valid sensor range (0..100, matching PetStudyHealthPolicy), not a wellness band.
        // A dirty/hungry pet is the normal input to care, never a refusal reason.
        check(h.energy in 0..100 && h.cleanliness in 0..100) { "HEALTH_VALUE_ABNORMAL" }
        latestReading=h
        emit(label,result=healthJson(h).put("read_duration_ms",SystemClock.elapsedRealtime()-started).toString())
        navigation.tap(healthPanel,PointF(355f,454f),parent,"close-health")
        h
    }
    suspend fun recover(initial: PetCareReading, energyThreshold: Int = 90, cleanlinessThreshold: Int = 90): PetCareReading = with(flow) {
        require(energyThreshold in SessionPlan.MIN_THRESHOLD..SessionPlan.MAX_THRESHOLD)
        require(cleanlinessThreshold in SessionPlan.MIN_THRESHOLD..SessionPlan.MAX_THRESHOLD)
        state="RECOVER_IF_NEEDED"
        var h = initial
        val recovery=JSONObject().put("feed_count",0).put("feed_duration_ms",0L)
            .put("clean_count",0).put("clean_duration_ms",0L)
        careStatistics=recovery
        var feeds=0
        // The feed limit follows the MEASURED gap, not a fixed count. The old cap of five feeds
        // was measured on a pet that already sat at 84-91, so a pet that starts at 16 could never
        // reach a 70 threshold with it: the loop stopped with FEED_RETRY_LIMIT while the
        // threshold still asked for more (measured 2026-10-20, 16 体力 / 0 清洁, 70/70 plan).
        val feedLimit=CareAllowance.feedLimit(h.energy,energyThreshold)
        while(true) {
            // A care iteration can exceed the freshness window (a purchase plus a long gesture), so
            // the decision to keep feeding is taken on a FRESH reading, never on the previous
            // iteration's number. Measured 2026-10-22: an iteration took ~2 minutes.
            if(h.isStale()) h=readHealth("feed-health-fresh")
            val energy=h.recordedEnergy ?: error("FEED_ENERGY_UNREAD")
            if(energy>=energyThreshold) break
            check(feeds<feedLimit) { "FEED_RETRY_LIMIT" }
            // The pet can already sit at the game's own cap (体力 100) while the conservative bar
            // estimate still reads a point below it: feeding then buys a cookie that cannot lift
            // the number any further. Measured 2026-10-21 (session 1792549392632): the pet was
            // full, the loop fed a second cookie, the measured gain was 0, the session died with
            // FEED_NO_EXPECTED_GAIN_OR_EMPTY and cleanliness stayed at 61. A pet the game itself
            // reports as FULL is done: stop feeding and let the clean step run.
            if(h.digitEnergy==GAME_FULL) {
                emit("feed-skipped-full",result="energy=${h.digitEnergy} threshold=$energyThreshold")
                break
            }
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
                val beforeReading=h
                val before=h.energy; val started=SystemClock.elapsedRealtime(); val wall=System.currentTimeMillis()
                // After a stock-out restock the flow is already standing on the food scene
                // (the purchase closes back onto it); only enter from the room otherwise.
                if(feeds>0) enterSceneIfAbsent(foodScene,"enter-food",PointF(638f,720f)) else navigation.tap(room,PointF(638f,720f),foodScene,"enter-food")
                verify(listOf(foodScene),"feed-before",latest=true)
                val stockBefore=ensureOwnedStock(Consumable.COOKIE,"feed")
                consuming=true
                callsBefore=flow.input.injectionAttempts
                safety()
                check(CareResourceGate.stockAction(stockBefore,System.currentTimeMillis())==CareResourceGate.StockAction.CONSUME_OWNED)
                // Exactly one consumable tap. An unexpected sheet or missing gain is unconfirmed,
                // never permission to buy or send this consumption again.
                beforeConsumingInput?.invoke(Consumable.COOKIE)
                check(CareResourceGate.stockAction(stockBefore,System.currentTimeMillis())==CareResourceGate.StockAction.CONSUME_OWNED)
                guard()
                input.tap(360f,1300f); delay(2500)
                navigation.dismissCareUpgradeIfPresent()
                verify(listOf(foodScene),"feed-after")
                h=readHealth("feed-health",foodScene)
                // The gain is measured on the game's own digits when they are readable: the pixel
                // bar estimate carries a point of geometric noise and stops at the cap, so a
                // bar-only delta can read 0 for a cookie that did work (93 -> 100 measures +7).
                val gain=feedGain(beforeReading,h)
                check(gain>0 && (gain in 5..12 || h.digitEnergy==GAME_FULL)) { "FEED_NO_EXPECTED_GAIN_OR_EMPTY" }
                val stockAfter=observedStock(Consumable.COOKIE,"feed-after")
                navigation.returnHomeFromPage(foodScene,"leave-food")
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
                    .put("stamina_after",h.energy).put("inventory_before",JSONObject(stockBefore.fields()))
                    .put("inventory_after",JSONObject(stockAfter.fields())).toString())
            }
            catch(t: Throwable) {
                settle(reservation,"feed",consuming,callsBefore)
                throw t
            }
        }
        h=cleanLoop(h,cleanlinessThreshold,recovery)
        h
    }

    /**
     * The measured energy gain of one feed.
     *
     * The game's own digit is the authority when it is readable: the pixel bar estimate carries a
     * point of geometric noise and stops at the cap, so a bar-only delta can read 0 for a cookie
     * that did work (measured 2026-10-21, session 1792549392632: 体力 93 -> 100 measures +7 on the
     * digit while the bar estimate only moved 93 -> 99).
     */
    private fun feedGain(before: PetCareReading, after: PetCareReading): Int {
        val b=before.recordedEnergy; val a=after.recordedEnergy
        return if(b!=null && a!=null) a-b else after.energy-before.energy
    }

    /** The measured cleanliness gain of one scrub, on the same digit-first rule as [feedGain]. */
    private fun cleanGain(before: PetCareReading, after: PetCareReading): Int {
        val b=before.recordedCleanliness; val a=after.recordedCleanliness
        return if(b!=null && a!=null) a-b else after.cleanliness-before.cleanliness
    }

    /**
     * The game's own cap for 体力 and 清洁. The pixel bar estimate can read a point below it, so
     * only the digit proves that the pet is full.
     */
    private companion object {
        const val GAME_FULL = 100
    }

    /**
     * Cleans the pet until it reaches [target] cleanliness.
     *
     * The limit follows the MEASURED gap (see [CareAllowance]): the old fixed cap of three cleans
     * was measured on a pet that already sat at 84-92, so a pet that starts at 0 could never
     * reach a 70 threshold with it - the clean loop stopped with CLEAN_RETRY_LIMIT while the
     * threshold still asked for more.
     *
     * [target] belongs to the caller. recover() passes the plan's threshold; the work step passes
     * the work option's OWN cleanliness cost, which is the game's requirement for that shift and
     * not an app-side threshold. This function only spends the game's own soap to reach it.
     */
    private suspend fun cleanLoop(start: PetCareReading, target: Int, recovery: JSONObject): PetCareReading = with(flow) {
        var h = start
        var cleans = 0
        val limit = CareAllowance.cleanLimit(h.cleanliness, target)
        while(true) {
            // Same rule as the feed loop: the decision to scrub again needs a fresh reading.
            if(h.isStale()) h=readHealth("clean-health-fresh")
            val cleanliness=h.recordedCleanliness ?: error("CLEAN_CLEANLINESS_UNREAD")
            if(cleanliness>=target) break
            check(cleans < limit) { "CLEAN_RETRY_LIMIT" }
            // A pet the game itself calls FULL cannot be scrubbed any further: the pixel bar
            // estimate may still read a point below the cap, and scrubbing then buys a soap that
            // changes nothing. Stop here instead of spending coins on a soap with no effect.
            if(h.digitCleanliness==GAME_FULL) {
                emit("clean-skipped-full",result="cleanliness=${h.digitCleanliness} target=$target")
                break
            }
            // Batch-wide soap limit, checked before the gesture begins and reserved on the spot.
            val reservation=budget.reserveSoap()
            budgetEvent("clean-reserve",reservation)
            var consuming=false
            var callsBefore=0
            try {
                val beforeReading=h
                val before=h.cleanliness; val started=SystemClock.elapsedRealtime()
                if(cleans>0) enterSceneIfAbsent(bath,"enter-bath",PointF(638f,868f)) else navigation.tap(room,PointF(638f,868f),bath,"enter-bath")
                // Page identity is the stable bath scene, never the soap button: the button repaints
                // to its pale "already clean" state once cleanliness is maxed, which used to fail
                // clean-after verification (PAGE_MISMATCH ... 0.9538353304284677) with cleanliness
                // already at 99. The soap probe stays the measured gesture/tap target only.
                verify(listOf(bath),"clean-before",latest=true)
                val stockBefore=ensureOwnedStock(Consumable.SOAP,"clean")
                val points=buildList {
                    add(PointF(360f,1345f));add(PointF(360f,1100f));add(PointF(360f,900f))
                    repeat(40) { add(PointF(280f,800f));add(PointF(430f,950f));add(PointF(280f,950f));add(PointF(430f,800f)) }
                }
                // The gesture is the consumable action; the controller counts its delivered events.
                consuming=true
                // Read immediately before the soap gesture, which owns every inject call started after
                // this point, so a safety() refusal can still return the allowance.
                callsBefore=flow.input.injectionAttempts
                safety()
                check(CareResourceGate.stockAction(stockBefore,System.currentTimeMillis())==CareResourceGate.StockAction.CONSUME_OWNED)
                beforeConsumingInput?.invoke(Consumable.SOAP)
                check(CareResourceGate.stockAction(stockBefore,System.currentTimeMillis())==CareResourceGate.StockAction.CONSUME_OWNED)
                guard()
                input.drag(points,150); delay(2000)
                navigation.dismissCareUpgradeIfPresent()
                verify(listOf(bath),"clean-after")
                h=readHealth("clean-health",bath)
                // A scrub that measured no gain on the game's own digit is a stock-out symptom
                // (no soap left), not a pet that is already clean - the full-pet case is handled
                // above, before any soap is spent.
                check(cleanGain(beforeReading,h)>0) { "CLEAN_NO_GAIN_OR_EMPTY" }
                val stockAfter=observedStock(Consumable.SOAP,"clean-after")
                navigation.returnHomeFromPage(bath,"leave-bath")
                cleans++
                // Measured gain observed after the gesture: the soap allowance is firmly spent.
                reservation.confirmConsumed()
                budgetEvent("clean-consume",reservation)
                recovery.put("clean_count",cleans)
                recovery.put("session_clean_count",budget.soapsUsed)
                recovery.put("clean_budget",budget.soaps)
                recovery.put("clean_duration_ms",recovery.getLong("clean_duration_ms")+SystemClock.elapsedRealtime()-started)
                emit("clean",result=JSONObject().put("cleanliness_before",before).put("cleanliness_after",h.cleanliness)
                    .put("clean_duration_ms",SystemClock.elapsedRealtime()-started).put("inventory_before",JSONObject(stockBefore.fields()))
                    .put("inventory_after",JSONObject(stockAfter.fields())).toString())
            }
            catch(t: Throwable) {
                settle(reservation,"clean",consuming,callsBefore)
                // A delivered scrub with an unconfirmed outcome is never retried or treated as zero stock.
                throw t
            }
        }
        h
    }

    /**
     * Cleans the pet up to [target] cleanliness and returns the reading that proves it.
     *
     * This is the WORK step's preparation, not a care policy: the shift's own cleanliness cost is
     * the game's requirement, and the app's answer to it is to act on it rather than to refuse
     * the shift. See [WorkPreparation] for the measured reason the shift cannot start dirty.
     *
     * The pet is left where the caller found it: the clean loop returns to the room itself.
     */
    suspend fun cleanTo(target: Int, initial: PetCareReading): PetCareReading = with(flow) {
        require(target in 0..100) { "CLEAN_TARGET_OUT_OF_RANGE:$target" }
        if(initial.cleanliness>=target) return@with initial
        state="CLEAN_TO_TARGET"
        val stats=JSONObject().put("clean_count",0).put("clean_duration_ms",0L)
        cleanLoop(initial,target,stats)
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
