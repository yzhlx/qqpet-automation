package com.ynk.virtualdisplay.automation

/**
 * Pure resource rules for the three actions, so every "may this start?" decision is JVM-testable
 * without a device.
 *
 * The rules deliberately do NOT share one gate:
 *  - WORK spends the job's OWN energy and cleanliness cost plus a fixed duration. Both costs and
 *    both current values must be READ, and equality is enough. The care plan's 60..100 thresholds
 *    are NOT a work gate.
 *  - STUDY spends tuition only. It does not require energy/cleanliness at any value, and an
 *    unreadable/stale health observation must never block starting, recovering or registering a
 *    completed lesson. The course page may still REPORT what a lesson costs in health; that is
 *    recorded for later WORK/CARE planning, not used as a gate.
 *  - CARE lifts the pet toward the user's thresholds. It consumes OWNED stock first and buys only
 *    the missing units. An unknown stock count is unknown, never zero, and the session's purchase
 *    ALLOWANCE is not a wallet: a purchase needs a read, sufficient wallet that also leaves every
 *    still-unpaid lesson's tuition intact.
 *
 * Unknown is never substituted with a number. A decision that cannot be made from the available
 * observation is [ResourceDecision.NeedsObservation]; only a proven shortfall is
 * [ResourceDecision.Blocked].
 */

/** One care consumable, matching the game's two +10 items. */
enum class CareItem { COOKIE, SOAP }
enum class InventoryPrecision { EXACT, LOWER_BOUND }
enum class ResourceBasis { OBSERVED, PREDICTED }

/** A Known lower bound proves only that many owned units; exhausting it does not prove zero stock. */
data class InventoryObservation(
    val cookieCount: ObservedValue<Int>,
    val soapCount: ObservedValue<Int>,
    val cookiePrecision: InventoryPrecision = InventoryPrecision.EXACT,
    val soapPrecision: InventoryPrecision = InventoryPrecision.EXACT,
) {
    init {
        listOf(cookieCount, soapCount).forEach { count ->
            require(count !is ObservedValue.Known || count.value >= 0) { "INVENTORY_COUNT_NEGATIVE" }
        }
    }
    fun countOf(item: CareItem): ObservedValue<Int> =
        if (item == CareItem.COOKIE) cookieCount else soapCount
    fun precisionOf(item: CareItem): InventoryPrecision =
        if (item == CareItem.COOKIE) cookiePrecision else soapPrecision

    companion object {
        /** Only for callers that have genuinely observed nothing yet; never for "maybe empty". */
        fun unread(reason: String, at: Long): InventoryObservation = InventoryObservation(
            ObservedValue.Unknown(reason, at, "inventory-cookie-unread"),
            ObservedValue.Unknown(reason, at, "inventory-soap-unread"),
        )
    }
}

/** A read of the pet's spendable coins. Never a payroll award line and never a purchase allowance. */
data class WalletObservation(val coins: ObservedValue<Int>)

/**
 * A read of the pet's energy/cleanliness. STUDY treats it as optional observation only; WORK and
 * CARE need the two values as current facts.
 */
data class HealthObservation(
    val energy: ObservedValue<Int>,
    val cleanliness: ObservedValue<Int>,
)

/**
 * The job's own cost, taken from the page for THIS shift: fixed duration and both spend values.
 * All three are required facts for starting a shift.
 */
data class WorkCostSnapshot(
    val optionId: String,
    val actualMinutes: ObservedValue<Int>,
    val energyCost: ObservedValue<Int>,
    val cleanlinessCost: ObservedValue<Int>,
)

/** Measured unit prices and gains: cookie +10 energy for 5 coins, soap +10 cleanliness for 2. */
data class CarePrices(
    val cookieCoins: Int = 5,
    val soapCoins: Int = 2,
    val cookieGain: Int = 10,
    val soapGain: Int = 10,
) {
    init {
        require(cookieCoins > 0 && soapCoins > 0 && cookieGain > 0 && soapGain > 0) {
            "CARE_PRICES_INVALID"
        }
    }

    fun coinsOf(item: CareItem): Int = if (item == CareItem.COOKIE) cookieCoins else soapCoins
    fun gainOf(item: CareItem): Int = if (item == CareItem.COOKIE) cookieGain else soapGain
}

/**
 * What a CARE action will do about the gap: consume owned stock first, buy only what is missing.
 * [purchaseCoins] is the cash this care action needs; it is 0 when the owned stock covers the gap.
 */
data class CarePurchasePlan(
    val needCookies: Int,
    val needSoap: Int,
    val buyCookies: Int,
    val buySoap: Int,
    val purchaseCoins: Int,
    val reservedTuitionCoins: Int,
) {
    val needsPurchase: Boolean get() = purchaseCoins > 0
}

/** The facts a decision may rely on, bound to one snapshot id so a stale snapshot cannot authorize. */
data class ResourceState(
    val snapshotId: String,
    val health: HealthObservation,
    val wallet: WalletObservation,
    val inventory: InventoryObservation,
    val remainingMinutes: Int,
    val basis: ResourceBasis = ResourceBasis.OBSERVED,
) {
    init {
        require(snapshotId.isNotBlank()) { "RESOURCE_SNAPSHOT_ID_BLANK" }
        require(remainingMinutes >= 0) { "RESOURCE_MINUTES_NEGATIVE" }
    }
}

sealed interface ResourceDecision {
    /** May start now, bound to the current observed snapshot. [carePurchase] is non-null only for CARE. */
    data class Ready(val carePurchase: CarePurchasePlan? = null, val proofSnapshotId: String? = null) : ResourceDecision

    /** A required fact was not read (or has expired). Read it, then decide again. */
    data class NeedsObservation(
        val action: String,
        val missing: List<String>,
        val detail: String,
    ) : ResourceDecision

    /** A required fact was read and does not satisfy the rule. Do not start. */
    data class Blocked(val action: String, val reason: String) : ResourceDecision

    /** A previous input may already have taken effect. Never re-dispatch it. */
    data class Unconfirmed(val action: String, val reason: String) : ResourceDecision
}

object PlanResourceRules {

    /** Observation validity for the decisions below. Values older than this are not current facts. */
    const val DEFAULT_MAX_AGE_MS: Long = 60_000L
    const val MIN_CARE_TARGET: Int = 60
    const val MAX_CARE_TARGET: Int = 100

    private fun <T> ObservedValue<T>.current(nowMs: Long, maxAgeMs: Long): T? =
        (at(nowMs, maxAgeMs) as? ObservedValue.Known)?.value

    private fun ObservedValue<*>.stateName(): String = when (this) {
        is ObservedValue.Known -> "KNOWN"
        is ObservedValue.Unknown -> "UNKNOWN"
        is ObservedValue.Stale -> "STALE"
        is ObservedValue.Unsupported -> "UNSUPPORTED"
    }

    private fun ceilUnits(gap: Int, gain: Int): Int =
        if (gap <= 0) 0 else ((gap.toLong() + gain - 1) / gain).toInt()

    /**
     * WORK: both of the job's own costs must be payable by the current values, the duration must fit
     * the remaining daily allowance, and every one of those facts must be READ. Equality passes;
     * the care plan's thresholds are irrelevant here.
     */
    fun checkWork(
        state: ResourceState,
        cost: WorkCostSnapshot,
        nowMs: Long,
        maxAgeMs: Long = DEFAULT_MAX_AGE_MS,
    ): ResourceDecision {
        if (state.basis != ResourceBasis.OBSERVED) {
            return ResourceDecision.Unconfirmed("WORK", "RESOURCE_STATE_PREDICTED")
        }
        val minutes = cost.actualMinutes.current(nowMs, maxAgeMs)
        val energyCost = cost.energyCost.current(nowMs, maxAgeMs)
        val cleanCost = cost.cleanlinessCost.current(nowMs, maxAgeMs)
        val energy = state.health.energy.current(nowMs, maxAgeMs)
        val clean = state.health.cleanliness.current(nowMs, maxAgeMs)

        val absent = mutableListOf<String>()
        if (minutes == null) absent += "work.actualMinutes:${cost.actualMinutes.stateName()}"
        if (energyCost == null) absent += "work.energyCost:${cost.energyCost.stateName()}"
        if (cleanCost == null) absent += "work.cleanlinessCost:${cost.cleanlinessCost.stateName()}"
        if (energy == null) absent += "health.energy:${state.health.energy.stateName()}"
        if (clean == null) absent += "health.cleanliness:${state.health.cleanliness.stateName()}"
        if (absent.isNotEmpty()) {
            return ResourceDecision.NeedsObservation(
                "WORK", absent, "打工所需岗位耗值或当前体力/清洁未读到，不能开工",)
        }
        requireNotNull(minutes); requireNotNull(energyCost); requireNotNull(cleanCost)
        requireNotNull(energy); requireNotNull(clean)
        if (minutes <= 0) {
            return ResourceDecision.Blocked("WORK", "WORK_DURATION_UNREAD:$minutes")
        }
        if (energyCost < 0 || cleanCost < 0) {
            return ResourceDecision.Blocked("WORK", "WORK_COST_NEGATIVE:$energyCost/$cleanCost")
        }
        if (energy < energyCost) {
            return ResourceDecision.Blocked(
                "WORK", "WORK_ENERGY_INSUFFICIENT:$energy<$energyCost")
        }
        if (clean < cleanCost) {
            return ResourceDecision.Blocked(
                "WORK", "WORK_CLEANLINESS_INSUFFICIENT:$clean<$cleanCost")
        }
        if (state.remainingMinutes < minutes) {
            return ResourceDecision.Blocked(
                "WORK", "DAILY_BUDGET_LIMIT:work=$minutes remaining=${state.remainingMinutes}")
        }
        return ResourceDecision.Ready(proofSnapshotId = state.snapshotId)
    }

    /**
     * STUDY: course identity/fee/duration/time are the requirements. Energy and cleanliness are
     * deliberately absent from the requirement list at every value - including Unknown or Stale.
     */
    fun checkStudy(
        state: ResourceState,
        tuitionCoins: ObservedValue<Int>,
        durationMinutes: ObservedValue<Int>,
        remainingRounds: Int = 1,
        nowMs: Long,
        maxAgeMs: Long = DEFAULT_MAX_AGE_MS,
    ): ResourceDecision {
        require(remainingRounds in 1..10) { "STUDY_REMAINING_ROUNDS_INVALID:$remainingRounds" }
        if (state.basis != ResourceBasis.OBSERVED) {
            return ResourceDecision.Unconfirmed("STUDY", "RESOURCE_STATE_PREDICTED")
        }
        val tuition = tuitionCoins.current(nowMs, maxAgeMs)
        val minutes = durationMinutes.current(nowMs, maxAgeMs)
        val wallet = state.wallet.coins.current(nowMs, maxAgeMs)

        val absent = mutableListOf<String>()
        if (tuition == null) absent += "study.tuition:${tuitionCoins.stateName()}"
        if (minutes == null) absent += "study.durationMinutes:${durationMinutes.stateName()}"
        if (wallet == null) absent += "wallet.coins:${state.wallet.coins.stateName()}"
        if (absent.isNotEmpty()) {
            return ResourceDecision.NeedsObservation(
                "STUDY", absent, "学习所需学费/时长/真实金币未读到；体力清洁不是学习前置",)
        }
        requireNotNull(tuition); requireNotNull(minutes); requireNotNull(wallet)
        if (minutes <= 0) return ResourceDecision.Blocked("STUDY", "COURSE_DURATION_UNREAD:$minutes")
        if (tuition < 0) return ResourceDecision.Blocked("STUDY", "COURSE_TUITION_NEGATIVE:$tuition")

        val needCoins = tuition.toLong() * remainingRounds
        val needMinutes = minutes.toLong() * remainingRounds
        if (wallet.toLong() < needCoins) {
            return ResourceDecision.Blocked(
                "STUDY", "COURSE_COINS_INSUFFICIENT:$wallet<$needCoins(rounds=$remainingRounds)")
        }
        if (state.remainingMinutes.toLong() < needMinutes) {
            return ResourceDecision.Blocked(
                "STUDY",
                "DAILY_BUDGET_LIMIT:study=$needMinutes remaining=${state.remainingMinutes}",)
        }
        return ResourceDecision.Ready(proofSnapshotId = state.snapshotId)
    }

    /**
     * CARE: consume owned stock first, buy only [CarePurchasePlan.buyCookies] / [buySoap].
     *
     * Only the items actually needed have to be READ; an unrelated unread item cannot block care.
     * A care action whose gap is already covered by owned stock needs no wallet read at all, which is
     * what lets a zero-coin pet with stock be cared for. When cash is needed, the wallet must cover
     * the purchase AND every still-unpaid lesson's tuition, and the session allowance must allow it.
     */
    fun checkCare(
        state: ResourceState,
        energyTarget: Int,
        cleanlinessTarget: Int,
        remainingTuitionCoins: Int = 0,
        remainingPurchaseCoins: Int = Int.MAX_VALUE,
        prices: CarePrices = CarePrices(),
        nowMs: Long,
        maxAgeMs: Long = DEFAULT_MAX_AGE_MS,
    ): ResourceDecision {
        if (state.basis != ResourceBasis.OBSERVED) {
            return ResourceDecision.Unconfirmed("CARE", "RESOURCE_STATE_PREDICTED")
        }
        require(remainingTuitionCoins >= 0) { "CARE_TUITION_RESERVE_NEGATIVE:$remainingTuitionCoins" }
        if (energyTarget !in MIN_CARE_TARGET..MAX_CARE_TARGET) {
            return ResourceDecision.Blocked("CARE", "CARE_ENERGY_TARGET_OUT_OF_RANGE:$energyTarget")
        }
        if (cleanlinessTarget !in MIN_CARE_TARGET..MAX_CARE_TARGET) {
            return ResourceDecision.Blocked(
                "CARE", "CARE_CLEANLINESS_TARGET_OUT_OF_RANGE:$cleanlinessTarget")
        }
        val energy = state.health.energy.current(nowMs, maxAgeMs)
        val clean = state.health.cleanliness.current(nowMs, maxAgeMs)
        val absent = mutableListOf<String>()
        if (energy == null) absent += "health.energy:${state.health.energy.stateName()}"
        if (clean == null) absent += "health.cleanliness:${state.health.cleanliness.stateName()}"
        if (absent.isNotEmpty()) {
            return ResourceDecision.NeedsObservation(
                "CARE", absent, "护理前体力/清洁未读到，无法计算护理缺口")
        }
        requireNotNull(energy); requireNotNull(clean)

        val needCookies = ceilUnits(energyTarget - energy, prices.cookieGain)
        val needSoap = ceilUnits(cleanlinessTarget - clean, prices.soapGain)
        if (needCookies == 0 && needSoap == 0) {
            // Already at target: a legal no-op that must not require stock or cash observations.
            return ResourceDecision.Ready(CarePurchasePlan(0, 0, 0, 0, 0, remainingTuitionCoins), state.snapshotId)
        }

        val unreadNeeded = mutableListOf<String>()
        val ownedCookies = if (needCookies > 0) {
            state.inventory.countOf(CareItem.COOKIE).current(nowMs, maxAgeMs).also {
                if (it == null) unreadNeeded +=
                    "inventory.cookie:${state.inventory.countOf(CareItem.COOKIE).stateName()}"
            }
        } else 0
        val ownedSoap = if (needSoap > 0) {
            state.inventory.countOf(CareItem.SOAP).current(nowMs, maxAgeMs).also {
                if (it == null) unreadNeeded +=
                    "inventory.soap:${state.inventory.countOf(CareItem.SOAP).stateName()}"
            }
        } else 0
        if (unreadNeeded.isNotEmpty()) {
            // Unknown stock is not zero stock: observe it instead of buying on a guess.
            return ResourceDecision.NeedsObservation(
                "CARE", unreadNeeded, "护理所需道具库存未读到；未知库存不能当作没有库存")
        }
        requireNotNull(ownedCookies); requireNotNull(ownedSoap)
        val uncertain = mutableListOf<String>()
        if (needCookies > ownedCookies && state.inventory.cookiePrecision == InventoryPrecision.LOWER_BOUND) {
            uncertain += "inventory.cookie:LOWER_BOUND"
        }
        if (needSoap > ownedSoap && state.inventory.soapPrecision == InventoryPrecision.LOWER_BOUND) {
            uncertain += "inventory.soap:LOWER_BOUND"
        }
        if (uncertain.isNotEmpty()) {
            return ResourceDecision.NeedsObservation("CARE", uncertain, "库存下界不足以证明采购缺口，需重新观察")
        }

        val buyCookies = (needCookies - ownedCookies).coerceAtLeast(0)
        val buySoap = (needSoap - ownedSoap).coerceAtLeast(0)
        val purchaseLong = buyCookies.toLong() * prices.cookieCoins + buySoap.toLong() * prices.soapCoins
        if (purchaseLong > Int.MAX_VALUE) return ResourceDecision.Blocked("CARE", "CARE_PURCHASE_OVERFLOW")
        val purchaseCoins = purchaseLong.toInt()
        val plan = CarePurchasePlan(
            needCookies = needCookies, needSoap = needSoap,
            buyCookies = buyCookies, buySoap = buySoap,
            purchaseCoins = purchaseCoins, reservedTuitionCoins = remainingTuitionCoins,)
        if (purchaseCoins == 0) return ResourceDecision.Ready(plan, state.snapshotId)
        val purchase = checkPurchase(state, purchaseCoins, remainingTuitionCoins,
            remainingPurchaseCoins, nowMs, maxAgeMs)
        return if (purchase is ResourceDecision.Ready) ResourceDecision.Ready(plan, state.snapshotId) else purchase
    }

    /** PurchaseReadiness: an allowance is not cash, and every unpaid lesson remains protected. */
    fun checkPurchase(
        state: ResourceState,
        purchaseCoins: Int,
        remainingTuitionCoins: Int = 0,
        remainingPurchaseCoins: Int = Int.MAX_VALUE,
        nowMs: Long,
        maxAgeMs: Long = DEFAULT_MAX_AGE_MS,
    ): ResourceDecision {
        require(purchaseCoins >= 0 && remainingTuitionCoins >= 0 && remainingPurchaseCoins >= 0) {
            "PURCHASE_INPUT_NEGATIVE"
        }
        if (state.basis != ResourceBasis.OBSERVED) {
            return ResourceDecision.Unconfirmed("CARE", "RESOURCE_STATE_PREDICTED")
        }
        if (purchaseCoins == 0) return ResourceDecision.Ready(proofSnapshotId = state.snapshotId)
        if (purchaseCoins > remainingPurchaseCoins) {
            return ResourceDecision.Blocked("CARE", "CARE_PURCHASE_ALLOWANCE_EXCEEDED:$purchaseCoins>$remainingPurchaseCoins")
        }
        val wallet = state.wallet.coins.current(nowMs, maxAgeMs)
            ?: return ResourceDecision.NeedsObservation("CARE",
                listOf("wallet.coins:${state.wallet.coins.stateName()}"), "护理需要采购，但实际余额未确认")
        val required = purchaseCoins.toLong() + remainingTuitionCoins
        if (wallet.toLong() < required) {
            return ResourceDecision.Blocked("CARE", "CARE_COINS_INSUFFICIENT:$wallet<purchase=$purchaseCoins+tuition=$remainingTuitionCoins")
        }
        return ResourceDecision.Ready(proofSnapshotId = state.snapshotId)
    }
}

/** One planned action for a feasibility forecast. Predictions never authorize a real spend. */
sealed interface ResourceAction {
    data class Work(
        val cost: WorkCostSnapshot,
        val predictedCoinReward: ObservedValue<Int>,
    ) : ResourceAction

    data class Care(
        val energyTarget: Int,
        val cleanlinessTarget: Int,
        val prices: CarePrices = CarePrices(),
        val remainingTuitionCoins: Int = 0,
        val remainingPurchaseCoins: Int = Int.MAX_VALUE,
    ) : ResourceAction

    data class Study(
        val tuitionCoins: ObservedValue<Int>,
        val durationMinutes: ObservedValue<Int>,
        val rounds: Int = 1,
        /** What the course page SAYS the lesson spends; recorded, never used as a gate. */
        val recordedEnergyCost: Int? = null,
        val recordedCleanlinessCost: Int? = null,
    ) : ResourceAction
}

/**
 * A forecasted resource value. Every number is explicitly a prediction: it must never be read as an
 * observed wallet, and it cannot authorize a payment.
 */
data class ResourceProjection(
    val afterActionIndex: Int,
    val action: String,
    val forecast: Boolean = true,
    val energy: Int? = null,
    val cleanliness: Int? = null,
    val walletCoins: Int? = null,
    val remainingMinutes: Int,
)

sealed interface TrajectoryDecision {
    data class Ready(val projections: List<ResourceProjection>) : TrajectoryDecision
    data class Blocked(val stepIndex: Int, val reason: String) : TrajectoryDecision
    data class Unconfirmed(val stepIndex: Int, val reason: String) : TrajectoryDecision
}

/**
 * Walks the selected actions in order and answers one question: is this whole combination feasible
 * if every actual cost lands as stated? Work lowers energy/cleanliness and adds only a PREDICTED
 * reward; care is recomputed on the post-work state and spends stock before cash; study spends the
 * real fee and duration. A complete-course fee is checked before care is allowed to spend.
 */
object PlanResourceForecast {

    fun forecast(
        initial: ResourceState,
        actions: List<ResourceAction>,
        nowMs: Long,
        maxAgeMs: Long = PlanResourceRules.DEFAULT_MAX_AGE_MS,
    ): TrajectoryDecision {
        var energy = (initial.health.energy.at(nowMs, maxAgeMs) as? ObservedValue.Known)?.value
        var clean = (initial.health.cleanliness.at(nowMs, maxAgeMs) as? ObservedValue.Known)?.value
        var wallet = (initial.wallet.coins.at(nowMs, maxAgeMs) as? ObservedValue.Known)?.value
        var minutes = initial.remainingMinutes
        var cookies = (initial.inventory.countOf(CareItem.COOKIE).at(nowMs, maxAgeMs)
            as? ObservedValue.Known)?.value
        var soap = (initial.inventory.countOf(CareItem.SOAP).at(nowMs, maxAgeMs)
            as? ObservedValue.Known)?.value
        val projections = mutableListOf<ResourceProjection>()

        actions.forEachIndexed { index, action ->
            when (action) {
                is ResourceAction.Work -> {
                    val costMinutes = (action.cost.actualMinutes.at(nowMs, maxAgeMs)
                        as? ObservedValue.Known)?.value
                        ?: return TrajectoryDecision.Unconfirmed(index, "WORK_DURATION_UNKNOWN")
                    val energyCost = (action.cost.energyCost.at(nowMs, maxAgeMs)
                        as? ObservedValue.Known)?.value
                        ?: return TrajectoryDecision.Unconfirmed(index, "WORK_ENERGY_COST_UNKNOWN")
                    val cleanCost = (action.cost.cleanlinessCost.at(nowMs, maxAgeMs)
                        as? ObservedValue.Known)?.value
                        ?: return TrajectoryDecision.Unconfirmed(index, "WORK_CLEANLINESS_COST_UNKNOWN")
                    if (energy == null || clean == null) {
                        return TrajectoryDecision.Unconfirmed(index, "WORK_HEALTH_UNKNOWN")
                    }
                    if (energy < energyCost) {
                        return TrajectoryDecision.Blocked(index, "WORK_ENERGY_INSUFFICIENT:$energy<$energyCost")
                    }
                    if (clean < cleanCost) {
                        return TrajectoryDecision.Blocked(index, "WORK_CLEANLINESS_INSUFFICIENT:$clean<$cleanCost")
                    }
                    if (minutes < costMinutes) {
                        return TrajectoryDecision.Blocked(index, "DAILY_BUDGET_LIMIT:work=$costMinutes")
                    }
                    energy -= energyCost; clean -= cleanCost; minutes -= costMinutes
                    val reward = (action.predictedCoinReward.at(nowMs, maxAgeMs)
                        as? ObservedValue.Known)?.value
                    if (costMinutes <= 0 || energyCost < 0 || cleanCost < 0) {
                        return TrajectoryDecision.Blocked(index, "WORK_COST_INVALID")
                    }
                    if (reward != null && reward < 0) return TrajectoryDecision.Blocked(index, "WORK_REWARD_INVALID")
                    // A predicted reward is NOT observed money. When it is unknown the wallet is
                    // unknown from here on: a later CARE/STUDY that needs cash must report
                    // Unconfirmed instead of reusing the pre-work Known amount as if the shift paid.
                    val forecastCoins = if (wallet != null && reward != null) wallet.toLong() + reward else null
                    if (forecastCoins != null && forecastCoins > Int.MAX_VALUE) {
                        return TrajectoryDecision.Blocked(index, "WALLET_OVERFLOW")
                    }
                    wallet = forecastCoins?.toInt()
                    projections += ResourceProjection(
                        index, "WORK", energy = energy, cleanliness = clean,
                        walletCoins = wallet, remainingMinutes = minutes,)
                }

                is ResourceAction.Care -> {
                    if (energy == null || clean == null) {
                        return TrajectoryDecision.Unconfirmed(index, "CARE_HEALTH_UNKNOWN")
                    }
                    val prices = action.prices
                    val needCookies = ceil((action.energyTarget - energy), prices.cookieGain)
                    val needSoap = ceil((action.cleanlinessTarget - clean), prices.soapGain)
                    if ((needCookies > 0 && cookies == null) || (needSoap > 0 && soap == null)) {
                        // Unknown stock cannot be assumed sufficient OR empty.
                        return TrajectoryDecision.Unconfirmed(index, "CARE_INVENTORY_UNKNOWN")
                    }
                    if ((needCookies > (cookies ?: 0) && initial.inventory.cookiePrecision == InventoryPrecision.LOWER_BOUND) ||
                        (needSoap > (soap ?: 0) && initial.inventory.soapPrecision == InventoryPrecision.LOWER_BOUND)) {
                        return TrajectoryDecision.Unconfirmed(index, "CARE_INVENTORY_LOWER_BOUND_INSUFFICIENT")
                    }
                    val buyCookies = (needCookies - (cookies ?: 0)).coerceAtLeast(0)
                    val buySoap = (needSoap - (soap ?: 0)).coerceAtLeast(0)
                    val purchaseLong = buyCookies.toLong() * prices.cookieCoins + buySoap.toLong() * prices.soapCoins
                    if (purchaseLong > Int.MAX_VALUE) return TrajectoryDecision.Blocked(index, "CARE_PURCHASE_OVERFLOW")
                    val purchase = purchaseLong.toInt()
                    if (purchase > action.remainingPurchaseCoins) {
                        return TrajectoryDecision.Blocked(index, "CARE_PURCHASE_ALLOWANCE_EXCEEDED:$purchase")
                    }
                    if (purchase > 0) {
                        if (wallet == null) {
                            return TrajectoryDecision.Unconfirmed(index, "CARE_WALLET_UNKNOWN")
                        }
                        val required = purchase.toLong() + action.remainingTuitionCoins
                        if (wallet.toLong() < required) {
                            return TrajectoryDecision.Blocked(
                                index,
                                "CARE_COINS_INSUFFICIENT:$wallet<purchase=$purchase+" +
                                    "tuition=${action.remainingTuitionCoins}",)
                        }
                        wallet -= purchase
                    }
                    if (needCookies > 0) {
                        cookies = cookies!! + buyCookies - needCookies
                        if (cookies == 0 && initial.inventory.cookiePrecision == InventoryPrecision.LOWER_BOUND) cookies = null
                    }
                    if (needSoap > 0) {
                        soap = soap!! + buySoap - needSoap
                        if (soap == 0 && initial.inventory.soapPrecision == InventoryPrecision.LOWER_BOUND) soap = null
                    }
                    energy = minOf(100L, energy.toLong() + needCookies.toLong() * prices.cookieGain).toInt()
                    clean = minOf(100L, clean.toLong() + needSoap.toLong() * prices.soapGain).toInt()
                    projections += ResourceProjection(
                        index, "CARE", energy = energy, cleanliness = clean,
                        walletCoins = wallet, remainingMinutes = minutes,)
                }

                is ResourceAction.Study -> {
                    val tuition = (action.tuitionCoins.at(nowMs, maxAgeMs)
                        as? ObservedValue.Known)?.value
                        ?: return TrajectoryDecision.Unconfirmed(index, "STUDY_TUITION_UNKNOWN")
                    val duration = (action.durationMinutes.at(nowMs, maxAgeMs)
                        as? ObservedValue.Known)?.value
                        ?: return TrajectoryDecision.Unconfirmed(index, "STUDY_DURATION_UNKNOWN")
                    val needCoins = tuition.toLong() * action.rounds
                    val needMinutes = duration.toLong() * action.rounds
                    if (wallet == null) {
                        return TrajectoryDecision.Unconfirmed(index, "STUDY_WALLET_UNKNOWN")
                    }
                    if (wallet.toLong() < needCoins) {
                        return TrajectoryDecision.Blocked(
                            index, "COURSE_COINS_INSUFFICIENT:$wallet<$needCoins")
                    }
                    if (minutes.toLong() < needMinutes) {
                        return TrajectoryDecision.Blocked(
                            index, "DAILY_BUDGET_LIMIT:study=$needMinutes")
                    }
                    wallet = (wallet.toLong() - needCoins).toInt()
                    minutes = (minutes.toLong() - needMinutes).toInt()
                    // Health: only what the page reports is recorded, and it never gates the start.
                    energy = action.recordedEnergyCost?.let { cost ->
                        energy?.let { (it.toLong() - cost.toLong() * action.rounds).coerceAtLeast(0).toInt() }
                    }
                    clean = action.recordedCleanlinessCost?.let { cost ->
                        clean?.let { (it.toLong() - cost.toLong() * action.rounds).coerceAtLeast(0).toInt() }
                    }
                    projections += ResourceProjection(
                        index, "STUDY", energy = energy, cleanliness = clean,
                        walletCoins = wallet, remainingMinutes = minutes,)
                }
            }
        }
        return TrajectoryDecision.Ready(projections)
    }

    private fun ceil(gap: Int, gain: Int): Int = if (gap <= 0) 0 else (gap + gain - 1) / gain
}
