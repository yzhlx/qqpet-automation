package com.ynk.virtualdisplay

import com.ynk.virtualdisplay.automation.CareBaselinePagePolicy
import org.junit.Test

class CareBaselinePagePolicyTest {
    private val modals=listOf(0.7667642519970952,0.6256944444444444,0.6535743464052288,0.6941607661583151)
    private val learning=listOf(0.805580519244735,0.821539124909223)
    @Test fun recordedRealRoomWithVerifiedExitIsNotALearningOverlay() {
        CareBaselinePagePolicy.requireRoom(0.9753131808278868,modals,learning,true,false)
    }
    @Test(expected=IllegalStateException::class) fun modalAmbiguityStillStopsTheRoomBaseline() {
        CareBaselinePagePolicy.requireRoom(0.99,modals.toMutableList().also {it[0]=0.80},learning,true,false)
    }
    @Test(expected=IllegalStateException::class) fun positiveLearningPixelEvidenceStillStops() {
        CareBaselinePagePolicy.requireRoom(0.99,modals,listOf(0.96,0.82),true,false)
    }
    @Test(expected=IllegalStateException::class) fun learningOrDialogTextStillStopsEvenWithWeakPixels() {
        CareBaselinePagePolicy.requireRoom(0.99,modals,learning,true,true)
    }
    @Test(expected=IllegalStateException::class) fun aMissingOrOccludedRoomExitCannotRebaseResources() {
        CareBaselinePagePolicy.requireRoom(0.99,modals,learning,false,false)
    }
    @Test(expected=IllegalStateException::class) fun roomIdentityThresholdIsUnchanged() {
        CareBaselinePagePolicy.requireRoom(0.969,modals,learning,true,false)
    }
    @Test(expected=IllegalStateException::class) fun incompleteNegativeEvidenceCannotAuthorizeBaseline() {
        CareBaselinePagePolicy.requireRoom(0.99,modals.take(3),learning,true,false)
    }
}
