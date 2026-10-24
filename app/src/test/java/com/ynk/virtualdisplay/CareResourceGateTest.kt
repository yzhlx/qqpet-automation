package com.ynk.virtualdisplay

import com.ynk.virtualdisplay.automation.*
import org.junit.Assert.*
import org.junit.Test

class CareResourceGateTest {
    private val now=10_000L
    private fun known(n:Int,at:Long=now,source:ObservationSource=ObservationSource.DIGIT_RECOGNITION,
        confidence:Double=1.0)=ObservedValue.Known(n,at,confidence,"digit-crop",source)
    private fun blocked(action:()->Unit) { var failed=false;try { action() } catch(_:IllegalStateException) { failed=true }; assertTrue(failed) }

    @Test fun ownedStockIsConsumedWithoutPurchase() {
        assertEquals(CareResourceGate.StockAction.CONSUME_OWNED,CareResourceGate.stockAction(known(5),now))
        blocked { CareResourceGate.requirePurchase(known(5),1,now) }
    }
    @Test fun exactZeroAllowsOnlyTheNextOneItemDeficit() {
        assertEquals(CareResourceGate.StockAction.BUY_ONE,CareResourceGate.stockAction(known(0),now))
        CareResourceGate.requirePurchase(known(0),1,now)
        blocked { CareResourceGate.requirePurchase(known(0),2,now) }
    }
    @Test fun unknownStockCannotAuthorizeConsumeOrPurchase() {
        val value=ObservedValue.Unknown("no visible digit",now,"crop")
        blocked { CareResourceGate.stockAction(value,now) }
        blocked { CareResourceGate.requirePurchase(value,1,now) }
    }
    @Test fun staleOrFutureStockCannotAuthorizeInput() {
        blocked { CareResourceGate.stockAction(known(1,now-5001),now) }
        blocked { CareResourceGate.stockAction(known(0,now+1),now) }
    }
    @Test fun barEstimateOrWeakDigitIsNotStockProof() {
        blocked { CareResourceGate.stockAction(known(0,source=ObservationSource.SYSTEM),now) }
        blocked { CareResourceGate.stockAction(known(1,confidence=0.8),now) }
    }
    @Test fun emptyStockSceneProofAllowsOnlyExactZeroPurchase() {
        val zero=known(0,source=ObservationSource.EMPTY_STOCK_SCENE_PROOF,confidence=0.985)
        assertEquals(CareResourceGate.StockAction.BUY_ONE,CareResourceGate.stockAction(zero,now))
        CareResourceGate.requirePurchase(zero,1,now)
        blocked { CareResourceGate.stockAction(known(2,source=ObservationSource.EMPTY_STOCK_SCENE_PROOF),now) }
        blocked { CareResourceGate.requireWallet(known(66,source=ObservationSource.EMPTY_STOCK_SCENE_PROOF),5,0,now,now) }
    }
    @Test fun sceneCounterLowerBoundAllowsConsumeButNotPurchaseOrWallet() {
        val owned=known(1,source=ObservationSource.SCENE_COUNTER_LOWER_BOUND,confidence=1.0)
        assertEquals(CareResourceGate.StockAction.CONSUME_OWNED,CareResourceGate.stockAction(owned,now))
        blocked { CareResourceGate.requirePurchase(owned,1,now) }
        blocked { CareResourceGate.requireWallet(owned,5,0,now,now) }
        blocked { CareResourceGate.stockAction(known(0,source=ObservationSource.SCENE_COUNTER_LOWER_BOUND),now) }
    }
    @Test fun negativeStockStops() { blocked { CareResourceGate.stockAction(known(-1),now) } }
    @Test fun sufficientFreshWalletProtectsEveryUnpaidTuition() {
        assertEquals(203,CareResourceGate.requireWallet(known(203),5,198,now,now))
    }
    @Test fun oneCoinShortCannotPay() {
        blocked { CareResourceGate.requireWallet(known(202),5,198,now,now) }
    }
    @Test fun unknownWalletIsNotAZeroOrAnAllowance() {
        blocked { CareResourceGate.requireWallet(ObservedValue.Unknown("unread",now,"crop"),5,0,now,now) }
    }
    @Test fun cachedWalletDoesNotMeetThisPaymentReadBoundary() {
        blocked { CareResourceGate.requireWallet(known(999,now-1),5,0,now,now) }
        CareResourceGate.requireWallet(known(66),5,61,now,now)
    }
    @Test fun expiredWalletCannotBeUsedAfterSlowAuthorization() {
        blocked { CareResourceGate.requireWallet(known(999),5,0,now+5001,now) }
    }
    @Test fun computedBudgetOrPayrollSourceIsNotAWallet() {
        blocked { CareResourceGate.requireWallet(known(999,source=ObservationSource.SYSTEM),5,0,now,now) }
    }
    @Test fun failedPossiblePaymentCannotBeSentAgain() {
        val gate=CarePaymentAttemptGate()
        gate.requireNoPending();gate.beforePay()
        blocked { gate.requireNoPending() }
        blocked { gate.beforePay() }
    }
    @Test fun positivePaymentConfirmationReleasesOnlyThePaymentLatch() {
        val gate=CarePaymentAttemptGate()
        gate.beforePay();gate.confirmedPaid();gate.requireNoPending()
    }
}
