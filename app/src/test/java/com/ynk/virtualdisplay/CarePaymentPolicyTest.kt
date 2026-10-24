package com.ynk.virtualdisplay

import com.ynk.virtualdisplay.automation.*
import org.junit.Assert.*
import org.junit.Test

class CarePaymentPolicyTest {
    private fun wallet(value:Int,at:Long=1_000L):ObservedValue<Int> = ObservedValue.Known(
        value,at,1.0,"exchange-wallet-crop",ObservationSource.DIGIT_RECOGNITION)
    private fun rejects(action:()->Unit) {
        var rejected=false
        try {action()} catch (_:IllegalStateException) {rejected=true}
        assertTrue(rejected)
    }
    @Test fun allUnpaidLessonsAreProtectedAtAnExactCashBoundary() {
        val reserve=CarePaymentPolicy.unpaidTuition(66,3)
        assertEquals(198,reserve)
        assertEquals(203,CarePaymentPolicy.requireAffordable(wallet(203),1,5,5,reserve,1_000))
        rejects {CarePaymentPolicy.requireAffordable(wallet(202),1,5,5,reserve,1_000)}
    }
    @Test fun theNextFreshWalletIsNotDebitedForAnEarlierPurchaseAgain() {
        assertEquals(71,CarePaymentPolicy.requireAffordable(wallet(71),1,5,5,66,1_000))
        // The first payment has already reduced the newly observed wallet to 66.
        // A different plan with 61 still unpaid can spend precisely the next 5.
        assertEquals(66,CarePaymentPolicy.requireAffordable(wallet(66,2_000),1,5,5,61,2_000))
    }
    @Test fun unknownAndOldWalletsCannotAuthorizePayment() {
        rejects {CarePaymentPolicy.requireAffordable(ObservedValue.Unknown("OCR_MISS",1_000,"crop"),1,5,5,0,1_000)}
        rejects {CarePaymentPolicy.requireAffordable(wallet(100),1,5,5,0,4_001)}
        rejects {CarePaymentPolicy.requireAffordable(wallet(100),1,5,5,0,999)}
    }
    @Test fun noRemainingLessonsProtectsZeroTuition() {
        assertEquals(0,CarePaymentPolicy.unpaidTuition(66,0))
        assertEquals(2,CarePaymentPolicy.requireAffordable(wallet(2),1,2,2,0,1_000))
    }
    @Test(expected=IllegalArgumentException::class) fun tamperedQuantityTotalIsRejected() {
        CarePaymentPolicy.requireAffordable(wallet(1_000),2,5,5,0,1_000)
    }
    @Test(expected=ArithmeticException::class) fun tuitionCannotOverflowIntoASmallerReserve() {
        CarePaymentPolicy.unpaidTuition(Int.MAX_VALUE,10)
    }
}
