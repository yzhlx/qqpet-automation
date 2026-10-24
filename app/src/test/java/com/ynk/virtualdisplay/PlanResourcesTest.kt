package com.ynk.virtualdisplay

import com.ynk.virtualdisplay.automation.CareItem
import com.ynk.virtualdisplay.automation.CarePrices
import com.ynk.virtualdisplay.automation.HealthObservation
import com.ynk.virtualdisplay.automation.InventoryPrecision
import com.ynk.virtualdisplay.automation.ResourceBasis
import com.ynk.virtualdisplay.automation.InventoryObservation
import com.ynk.virtualdisplay.automation.ObservationSource
import com.ynk.virtualdisplay.automation.ObservedValue
import com.ynk.virtualdisplay.automation.PlanResourceForecast
import com.ynk.virtualdisplay.automation.PlanResourceRules
import com.ynk.virtualdisplay.automation.ResourceAction
import com.ynk.virtualdisplay.automation.ResourceDecision
import com.ynk.virtualdisplay.automation.ResourceState
import com.ynk.virtualdisplay.automation.TrajectoryDecision
import com.ynk.virtualdisplay.automation.WalletObservation
import com.ynk.virtualdisplay.automation.WorkCostSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The five approved resource rules: work checks its OWN double cost, study never gates on health,
 * care spends stock before cash, a purchase needs a real wallet, and a combination is checked with a
 * step-by-step trajectory instead of "one shift pays for one lesson".
 */
class PlanResourcesTest {

    private val now = 1_000_000L

    private fun known(value: Int, at: Long = now, evidence: String = "test-fixture"): ObservedValue<Int> =
        ObservedValue.Known(value, at, 1.0, evidence, ObservationSource.DIGIT_RECOGNITION)

    private fun unknown(reason: String): ObservedValue<Int> =
        ObservedValue.Unknown(reason, now, "test-fixture")

    private fun stale(value: Int): ObservedValue<Int> = ObservedValue.Stale(
        ObservedValue.Known(value, now - 10 * 60_000L, 1.0, "test-fixture",
            ObservationSource.DIGIT_RECOGNITION),
        now, "OBSERVATION_EXPIRED")

    private fun state(
        energy: ObservedValue<Int> = known(100),
        clean: ObservedValue<Int> = known(100),
        wallet: ObservedValue<Int> = known(0),
        cookies: ObservedValue<Int> = known(0),
        soap: ObservedValue<Int> = known(0),
        minutes: Int = 480,
    ) = ResourceState(
        snapshotId = "snap-1",
        health = HealthObservation(energy, clean),
        wallet = WalletObservation(wallet),
        inventory = InventoryObservation(cookies, soap),
        remainingMinutes = minutes,
    )

    /** The one measured, VERIFIED shift: 10 minutes for -5 energy, -2 cleanliness, +68 coins. */
    private fun workCost(
        minutes: Int = 10,
        energy: Int = 5,
        clean: Int = 2,
    ) = WorkCostSnapshot(
        optionId = "work:竹影武馆/帮武馆跑腿",
        actualMinutes = known(minutes),
        energyCost = known(energy),
        cleanlinessCost = known(clean),
    )

    // ---------------------------------------------------------------- WORK

    @Test fun workPassesWhenBothCostsAreExactlyPayable() {
        val decision = PlanResourceRules.checkWork(
            state(energy = known(5), clean = known(2)), workCost(), now)
        assertTrue("costs 5/2 must be exactly enough", decision is ResourceDecision.Ready)
    }

    @Test fun workRefusesWhenEnergyIsOneShort() {
        val decision = PlanResourceRules.checkWork(
            state(energy = known(4), clean = known(2)), workCost(), now)
        assertTrue(decision is ResourceDecision.Blocked)
        assertEquals("WORK_ENERGY_INSUFFICIENT:4<5", (decision as ResourceDecision.Blocked).reason)
    }

    @Test fun workRefusesWhenCleanlinessIsOneShortEvenIfEnergyIsEnough() {
        val decision = PlanResourceRules.checkWork(
            state(energy = known(100), clean = known(1)), workCost(), now)
        assertTrue(decision is ResourceDecision.Blocked)
        assertEquals("WORK_CLEANLINESS_INSUFFICIENT:1<2",
            (decision as ResourceDecision.Blocked).reason)
    }

    @Test fun workIgnoresTheCarePlanThresholdsAndNeedsNoHighHealth() {
        // A 60/60 care plan must not turn into a 60-point work requirement: the job costs 5/2.
        val decision = PlanResourceRules.checkWork(
            state(energy = known(6), clean = known(3)), workCost(), now)
        assertTrue(decision is ResourceDecision.Ready)
    }

    @Test fun workNeedsObservationWhenTheCleanlinessCostIsUnknown() {
        val cost = WorkCostSnapshot(
            optionId = "work:竹影武馆/帮武馆跑腿",
            actualMinutes = known(10),
            energyCost = known(5),
            cleanlinessCost = unknown("CLEANLINESS_ROW_UNREAD"),
        )
        val decision = PlanResourceRules.checkWork(state(), cost, now)
        assertTrue(decision is ResourceDecision.NeedsObservation)
        assertTrue((decision as ResourceDecision.NeedsObservation).missing
            .any { it.startsWith("work.cleanlinessCost") })
    }

    @Test fun workNeedsObservationWhenCurrentHealthIsUnknown() {
        val decision = PlanResourceRules.checkWork(
            state(energy = unknown("HEALTH_PANEL_UNREAD")), workCost(), now)
        assertTrue(decision is ResourceDecision.NeedsObservation)
    }

    @Test fun workRefusesAStaleReading() {
        val decision = PlanResourceRules.checkWork(
            state(energy = stale(100)), workCost(), now)
        assertTrue(decision is ResourceDecision.NeedsObservation)
    }

    @Test fun workRefusesWhenTheShiftDoesNotFitTheRemainingMinutes() {
        val decision = PlanResourceRules.checkWork(state(minutes = 9), workCost(), now)
        assertTrue(decision is ResourceDecision.Blocked)
        assertTrue((decision as ResourceDecision.Blocked).reason.startsWith("DAILY_BUDGET_LIMIT"))
    }

    // ---------------------------------------------------------------- STUDY

    @Test fun studyPassesWithLowHealthBecauseHealthIsNotAStudyGate() {
        val decision = PlanResourceRules.checkStudy(
            state(energy = known(3), clean = known(1), wallet = known(66)),
            tuitionCoins = known(66), durationMinutes = known(30), nowMs = now)
        assertTrue("low health must not block a lesson", decision is ResourceDecision.Ready)
    }

    @Test fun studyPassesWhenHealthIsUnknownOrStale() {
        val unknownHealth = PlanResourceRules.checkStudy(
            state(energy = unknown("PANEL_UNREAD"), clean = stale(100), wallet = known(66)),
            tuitionCoins = known(66), durationMinutes = known(30), nowMs = now)
        assertTrue(unknownHealth is ResourceDecision.Ready)
    }

    @Test fun studyRefusesWhenTuitionIsOneCoinShort() {
        val decision = PlanResourceRules.checkStudy(
            state(wallet = known(65)),
            tuitionCoins = known(66), durationMinutes = known(30), nowMs = now)
        assertTrue(decision is ResourceDecision.Blocked)
        assertTrue((decision as ResourceDecision.Blocked).reason.startsWith(
            "COURSE_COINS_INSUFFICIENT"))
    }

    @Test fun studyPassesWhenTuitionIsExactlyAffordable() {
        val decision = PlanResourceRules.checkStudy(
            state(wallet = known(66)),
            tuitionCoins = known(66), durationMinutes = known(30), nowMs = now)
        assertTrue(decision is ResourceDecision.Ready)
    }

    @Test fun studyNeedsObservationWhenTheWalletIsUnread() {
        val decision = PlanResourceRules.checkStudy(
            state(wallet = unknown("BALANCE_UNREAD")),
            tuitionCoins = known(66), durationMinutes = known(30), nowMs = now)
        assertTrue(decision is ResourceDecision.NeedsObservation)
    }

    @Test fun studyChargesEveryRemainingLesson() {
        // Two lessons of 30 minutes need 60 minutes and 2x tuition.
        val decision = PlanResourceRules.checkStudy(
            state(wallet = known(66), minutes = 30),
            tuitionCoins = known(66), durationMinutes = known(30),
            remainingRounds = 2, nowMs = now)
        assertTrue(decision is ResourceDecision.Blocked)
        assertTrue((decision as ResourceDecision.Blocked).reason.startsWith(
            "COURSE_COINS_INSUFFICIENT"))
    }

    // ---------------------------------------------------------------- CARE

    @Test fun careAtTargetIsAEasyPassAndNeedsNoStockOrWalletObservation() {
        val decision = PlanResourceRules.checkCare(
            state(energy = known(100), clean = known(100),
                wallet = unknown("BALANCE_UNREAD"),
                cookies = unknown("STOCK_UNREAD"), soap = unknown("STOCK_UNREAD")),
            energyTarget = 100, cleanlinessTarget = 100, nowMs = now)
        assertTrue(decision is ResourceDecision.Ready)
        val plan = (decision as ResourceDecision.Ready).carePurchase
        assertEquals(0, plan?.needsPurchase?.let { if (it) 1 else 0 })
    }

    @Test fun careWithEnoughStockNeedsNoCashAtAll() {
        // Zero coins, one cookie owned: the gap is covered by stock, so no wallet read is needed.
        val decision = PlanResourceRules.checkCare(
            state(energy = known(80), clean = known(100), wallet = known(0),
                cookies = known(1), soap = unknown("SOAP_NOT_NEEDED")),
            energyTarget = 90, cleanlinessTarget = 100, nowMs = now)
        assertTrue("stock covers the gap, so cash must not be required",
            decision is ResourceDecision.Ready)
        val plan = (decision as ResourceDecision.Ready).carePurchase!!
        assertEquals(0, plan.purchaseCoins)
        assertEquals(0, plan.buyCookies)
    }

    @Test fun careBuysOnlyTheMissingUnits() {
        // Needs 2 cookies (gap 11 > 10), owns 1: buy exactly 1 for 5 coins.
        val decision = PlanResourceRules.checkCare(
            state(energy = known(79), clean = known(100), wallet = known(5),
                cookies = known(1), soap = known(0)),
            energyTarget = 90, cleanlinessTarget = 100, nowMs = now)
        assertTrue(decision is ResourceDecision.Ready)
        val plan = (decision as ResourceDecision.Ready).carePurchase!!
        assertEquals(2, plan.needCookies)
        assertEquals(1, plan.buyCookies)
        assertEquals(5, plan.purchaseCoins)
    }

    @Test fun careUnknownStockIsNotZeroStock() {
        val decision = PlanResourceRules.checkCare(
            state(energy = known(80), clean = known(100), wallet = known(100),
                cookies = unknown("STOCK_UNREAD"), soap = known(0)),
            energyTarget = 90, cleanlinessTarget = 100, nowMs = now)
        assertTrue("an unread cookie count must be observed, not bought on a guess",
            decision is ResourceDecision.NeedsObservation)
        assertTrue((decision as ResourceDecision.NeedsObservation).missing
            .any { it.startsWith("inventory.cookie") })
    }

    @Test fun careUnreadItemThatIsNotNeededDoesNotBlock() {
        // Cleanliness is already at target: an unread soap count must not stop the cookie care.
        val decision = PlanResourceRules.checkCare(
            state(energy = known(80), clean = known(100), wallet = known(10),
                cookies = known(1), soap = unknown("SOAP_NOT_NEEDED")),
            energyTarget = 90, cleanlinessTarget = 100, nowMs = now)
        assertTrue(decision is ResourceDecision.Ready)
    }

    @Test fun carePurchaseMustLeaveTheWholeUnpaidTuitionIntact() {
        // 66 tuition + 5 cookie = 71 needed; 70 coins is short even though it covers the cookie.
        val decision = PlanResourceRules.checkCare(
            state(energy = known(80), clean = known(100), wallet = known(70),
                cookies = known(0), soap = known(0)),
            energyTarget = 90, cleanlinessTarget = 100,
            remainingTuitionCoins = 66, nowMs = now)
        assertTrue(decision is ResourceDecision.Blocked)
        assertTrue((decision as ResourceDecision.Blocked).reason.startsWith(
            "CARE_COINS_INSUFFICIENT"))
    }

    @Test fun carePurchasePassesWithExactlyThePurchasePlusTuition() {
        val decision = PlanResourceRules.checkCare(
            state(energy = known(80), clean = known(100), wallet = known(71),
                cookies = known(0), soap = known(0)),
            energyTarget = 90, cleanlinessTarget = 100,
            remainingTuitionCoins = 66, nowMs = now)
        assertTrue(decision is ResourceDecision.Ready)
    }

    @Test fun careRefusesWhenThePurchaseAllowanceIsExhaustedEvenWithCash() {
        val decision = PlanResourceRules.checkCare(
            state(energy = known(80), clean = known(100), wallet = known(100),
                cookies = known(0), soap = known(0)),
            energyTarget = 90, cleanlinessTarget = 100,
            remainingPurchaseCoins = 4, prices = CarePrices(), nowMs = now)
        assertTrue(decision is ResourceDecision.Blocked)
        assertTrue((decision as ResourceDecision.Blocked).reason.startsWith(
            "CARE_PURCHASE_ALLOWANCE_EXCEEDED"))
    }

    // ---------------------------------------------------------------- TRAJECTORY

    @Test fun oneShiftDoesNotPayForCarePlusTheLesson() {
        // Measured: 100/100, 0 coins, no stock. Shift -> 95/98 and a predicted +68.
        // Care back to 100/100 needs 1 cookie (5) + 1 soap (2) = 7, and tuition is 66: 73 > 68.
        val initial = state(energy = known(100), clean = known(100), wallet = known(0),
            cookies = known(0), soap = known(0), minutes = 480)
        val decision = PlanResourceForecast.forecast(
            initial,
            listOf(
                ResourceAction.Work(workCost(), known(68)),
                ResourceAction.Care(100, 100, remainingTuitionCoins = 66),
                ResourceAction.Study(known(66), known(30), rounds = 1),
            ),
            now)
        assertTrue("68 coins cannot cover 7 care + 66 tuition", decision is TrajectoryDecision.Blocked)
        assertEquals(1, (decision as TrajectoryDecision.Blocked).stepIndex)
        assertTrue(decision.reason.startsWith("CARE_COINS_INSUFFICIENT"))
    }

    @Test fun oneShiftDoesPayWhenTheFeeIsSmaller() {
        val initial = state(energy = known(100), clean = known(100), wallet = known(0),
            cookies = known(0), soap = known(0), minutes = 480)
        val decision = PlanResourceForecast.forecast(
            initial,
            listOf(
                ResourceAction.Work(workCost(), known(68)),
                ResourceAction.Care(100, 100, remainingTuitionCoins = 50),
                ResourceAction.Study(known(50), known(30)),
            ),
            now)
        assertTrue(decision is TrajectoryDecision.Ready)
        val last = (decision as TrajectoryDecision.Ready).projections.last()
        assertEquals("STUDY", last.action)
        assertEquals(11, last.walletCoins) // 0 + 68 - 7 = 61, then -50 = 11
    }

    @Test fun forecastUsesStockBeforeCashInTheCareStep() {
        val initial = state(energy = known(95), clean = known(98), wallet = known(10),
            cookies = known(1), soap = known(1), minutes = 480)
        val decision = PlanResourceForecast.forecast(
            initial,
            listOf(
                ResourceAction.Work(workCost(), known(68)),
                ResourceAction.Care(100, 100),
            ),
            now)
        assertTrue(decision is TrajectoryDecision.Ready)
        val care = (decision as TrajectoryDecision.Ready).projections[1]
        assertEquals("CARE", care.action)
        assertEquals(78, care.walletCoins) // stock covered both gaps: 0+68, then no purchase
    }

    @Test fun forecastRejectsWorkWhenTheJobCostsMoreThanCurrentEnergy() {
        val initial = state(energy = known(4), clean = known(100), wallet = known(0))
        val decision = PlanResourceForecast.forecast(
            initial, listOf(ResourceAction.Work(workCost(), known(68))), now)
        assertTrue(decision is TrajectoryDecision.Blocked)
        assertEquals(0, (decision as TrajectoryDecision.Blocked).stepIndex)
    }

    @Test fun forecastDoesNotGateStudyOnHealth() {
        val initial = state(energy = known(0), clean = known(0), wallet = known(66))
        val decision = PlanResourceForecast.forecast(
            initial, listOf(ResourceAction.Study(known(66), known(30))), now)
        assertTrue(decision is TrajectoryDecision.Ready)
    }

    @Test fun forecastRecordsTheCourseHealthCostWithoutGating() {
        val initial = state(energy = known(100), clean = known(100), wallet = known(66))
        val decision = PlanResourceForecast.forecast(
            initial,
            listOf(ResourceAction.Study(known(66), known(30),
                recordedEnergyCost = 15, recordedCleanlinessCost = 6)),
            now)
        assertTrue(decision is TrajectoryDecision.Ready)
        val projection = (decision as TrajectoryDecision.Ready).projections.single()
        assertEquals(85, projection.energy)
        assertEquals(94, projection.cleanliness)
    }

    @Test fun forecastNeedsObservationRatherThanGuessingUnknownStock() {
        val initial = state(energy = known(80), clean = known(100), wallet = known(100),
            cookies = unknown("STOCK_UNREAD"), soap = known(0))
        val decision = PlanResourceForecast.forecast(
            initial, listOf(ResourceAction.Care(90, 100)), now)
        assertTrue(decision is TrajectoryDecision.Unconfirmed)
    }

    @Test fun forecastChargesEveryRemainingLesson() {
        val initial = state(energy = known(100), clean = known(100), wallet = known(66),
            minutes = 480)
        val decision = PlanResourceForecast.forecast(
            initial, listOf(ResourceAction.Study(known(66), known(30), rounds = 2)), now)
        assertTrue(decision is TrajectoryDecision.Blocked)
        assertTrue((decision as TrajectoryDecision.Blocked).reason
            .startsWith("COURSE_COINS_INSUFFICIENT"))
    }

    @Test fun forecastPurchasedStockNeverMakesTheNextPurchaseLarger() {
        val decision = PlanResourceForecast.forecast(
            state(energy = known(80), clean = known(100), wallet = known(10)),
            listOf(ResourceAction.Care(90, 100),
                ResourceAction.Study(known(0), known(1), recordedEnergyCost = 10, recordedCleanlinessCost = 0),
                ResourceAction.Care(90, 100)), now)
        assertTrue("Each care should buy one cookie, not one then two", decision is TrajectoryDecision.Ready)
        assertEquals(0, (decision as TrajectoryDecision.Ready).projections.last().walletCoins)
    }

    @Test fun forecastUnknownWageDoesNotKeepAnApparentlyKnownWallet() {
        val decision = PlanResourceForecast.forecast(
            state(wallet = known(100)), listOf(ResourceAction.Work(workCost(), unknown("WAGE_UNREAD"))), now)
        assertTrue(decision is TrajectoryDecision.Ready)
        assertEquals(null, (decision as TrajectoryDecision.Ready).projections.single().walletCoins)
    }

    @Test fun forecastUnknownWageMakesLaterCashUseUnconfirmed() {
        val decision = PlanResourceForecast.forecast(state(wallet = known(100)),
            listOf(ResourceAction.Work(workCost(), unknown("WAGE_UNREAD")),
                ResourceAction.Study(known(1), known(1))), now)
        assertTrue(decision is TrajectoryDecision.Unconfirmed)
    }

    @Test fun forecastUnknownCourseHealthCostDoesNotInventZeroConsumption() {
        val decision = PlanResourceForecast.forecast(state(wallet = known(100)),
            listOf(ResourceAction.Study(known(1), known(1))), now)
        val projected = (decision as TrajectoryDecision.Ready).projections.single()
        assertEquals(null, projected.energy)
        assertEquals(null, projected.cleanliness)
    }

    @Test fun lowerBoundEnoughForTheWholeGapNeedsNeitherWalletNorOtherStock() {
        val current = state(energy = known(80), clean = known(100), wallet = unknown("WALLET_UNREAD"))
            .copy(inventory = InventoryObservation(known(1), unknown("SOAP_UNREAD"),
                cookiePrecision = InventoryPrecision.LOWER_BOUND))
        val decision = PlanResourceRules.checkCare(current, 90, 100, nowMs = now)
        assertTrue(decision is ResourceDecision.Ready)
        val ready = decision as ResourceDecision.Ready
        assertEquals(0, ready.carePurchase!!.purchaseCoins)
        assertEquals("snap-1", ready.proofSnapshotId)
    }

    @Test fun lowerBoundBelowTheGapCannotProveAnyPurchaseShortfall() {
        val current = state(energy = known(70), wallet = known(100))
            .copy(inventory = InventoryObservation(known(1), known(0),
                cookiePrecision = InventoryPrecision.LOWER_BOUND))
        val decision = PlanResourceRules.checkCare(current, 90, 100, nowMs = now)
        assertTrue(decision is ResourceDecision.NeedsObservation)
    }

    @Test fun allSatisfiedCareIgnoresUnknownWalletAndBothInventories() {
        val current = state(wallet = unknown("WALLET_UNREAD"), cookies = unknown("COOKIE_UNREAD"), soap = unknown("SOAP_UNREAD"))
        val decision = PlanResourceRules.checkCare(current, 100, 100, remainingTuitionCoins = 100, nowMs = now)
        assertTrue(decision is ResourceDecision.Ready)
        assertEquals(0, (decision as ResourceDecision.Ready).carePurchase!!.purchaseCoins)
    }

    @Test fun forecastExhaustedLowerBoundRequiresObservationForLaterCare() {
        val current = state(energy = known(80), wallet = known(100))
            .copy(inventory = InventoryObservation(known(1), known(0),
                cookiePrecision = InventoryPrecision.LOWER_BOUND))
        val decision = PlanResourceForecast.forecast(current,
            listOf(ResourceAction.Care(90, 100),
                ResourceAction.Study(known(0), known(1), recordedEnergyCost = 10, recordedCleanlinessCost = 0),
                ResourceAction.Care(90, 100)), now)
        assertTrue(decision is TrajectoryDecision.Unconfirmed)
        assertEquals(2, (decision as TrajectoryDecision.Unconfirmed).stepIndex)
    }

    @Test fun forecastCanUseAnUnexhaustedLowerBoundAgain() {
        val current = state(energy = known(80), wallet = known(0))
            .copy(inventory = InventoryObservation(known(2), known(0),
                cookiePrecision = InventoryPrecision.LOWER_BOUND))
        val decision = PlanResourceForecast.forecast(current,
            listOf(ResourceAction.Care(90, 100),
                ResourceAction.Study(known(0), known(1), recordedEnergyCost = 10, recordedCleanlinessCost = 0),
                ResourceAction.Care(90, 100)), now)
        assertTrue(decision is TrajectoryDecision.Ready)
        assertEquals(0, (decision as TrajectoryDecision.Ready).projections.last().walletCoins)
    }

    @Test fun purchaseReadinessProtectsEveryRemainingTuitionCharge() {
        assertTrue(PlanResourceRules.checkPurchase(state(wallet = known(138)), 7, remainingTuitionCoins = 132, nowMs = now)
            is ResourceDecision.Blocked)
        val ready = PlanResourceRules.checkPurchase(state(wallet = known(139)), 7, remainingTuitionCoins = 132, nowMs = now)
        assertTrue(ready is ResourceDecision.Ready)
        assertEquals("snap-1", (ready as ResourceDecision.Ready).proofSnapshotId)
        assertTrue(PlanResourceRules.checkPurchase(state(wallet = known(139)), 7, remainingPurchaseCoins = 6, nowMs = now)
            is ResourceDecision.Blocked)
    }

    @Test fun predictedStateCannotAuthorizeAnActualSpend() {
        val current = state(wallet = known(100)).copy(basis = ResourceBasis.PREDICTED)
        assertTrue(PlanResourceRules.checkStudy(current, known(66), known(30), nowMs = now) is ResourceDecision.Unconfirmed)
        assertTrue(PlanResourceRules.checkWork(current, workCost(), now) is ResourceDecision.Unconfirmed)
        assertTrue(PlanResourceRules.checkPurchase(current, 1, nowMs = now) is ResourceDecision.Unconfirmed)
    }

    @Test fun forecastAppliesReportedCourseCostsForEveryRound() {
        val decision = PlanResourceForecast.forecast(state(wallet = known(100)),
            listOf(ResourceAction.Study(known(1), known(1), rounds = 4,
                recordedEnergyCost = 5, recordedCleanlinessCost = 2)), now)
        val projection = (decision as TrajectoryDecision.Ready).projections.single()
        assertEquals(80, projection.energy)
        assertEquals(92, projection.cleanliness)
    }

    @Test fun predictedUnknownRewardLeavesWalletUnknownForLaterCare() {
        // Pre-work wallet is Known, but the shift's reward is NOT: after the shift the wallet cannot
        // be assumed to be the old amount. A later CARE that needs cash must therefore be
        // Unconfirmed (observe the real balance), never authorized from the stale pre-work wallet.
        val current = state(energy = known(100), clean = known(100), wallet = known(0))
        val decision = PlanResourceForecast.forecast(current,
            listOf(ResourceAction.Work(workCost(), unknown("REWARD_UNREAD")),
                ResourceAction.Care(100, 100)), now)
        assertTrue("unknown reward must not leave a Known wallet", decision is TrajectoryDecision.Unconfirmed)
        assertEquals(1, (decision as TrajectoryDecision.Unconfirmed).stepIndex)
    }

    @Test fun knownPositiveRewardIsCarriedIntoTheForecast() {
        val decision = PlanResourceForecast.forecast(
            state(energy = known(100), clean = known(100), wallet = known(10)),
            listOf(ResourceAction.Work(workCost(), known(68))), now)
        assertEquals(78, (decision as TrajectoryDecision.Ready).projections.single().walletCoins)
    }

    @Test fun careItemMappingIsStable() {
        val inventory = InventoryObservation(known(3), known(4))
        assertEquals(3, (inventory.countOf(CareItem.COOKIE) as ObservedValue.Known).value)
        assertEquals(4, (inventory.countOf(CareItem.SOAP) as ObservedValue.Known).value)
    }
}
