package com.ynk.virtualdisplay

import com.ynk.virtualdisplay.automation.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class PetManagedReconcileTest {
    private class Store:CheckpointStore {
        var cp:SessionCheckpoint?=null
        override fun save(checkpoint:SessionCheckpoint) {cp=checkpoint}
        override fun load()=cp
    }
    @Test fun onlyOwnedWaitingCourseMayReconcileToConfirmedResult():Unit=runBlocking {
        for(phase in listOf("WAIT_RESULT","START_COURSE")) {
            val store=Store();var allowed=false
            val ledger=PetRoundLedger();ledger.startAttempt(1);ledger.started(1)
            val recovery=SessionRecovery("s",store,{1000},{1000},{_,_,_->},{allowed=false},{},{allowed=true},
                reconcile={cp,page ->cp.phase=="WAIT_RESULT" && cp.trustedPage=="LEARNING" && page=="result" && ledger.canReconcileResult(cp.round)})
            recovery.confirm(1,phase,"LEARNING")
            val result=runCatching {recovery.recover("locked",{false}) {RecoveryObservation(true,true,"result")}}
            if(phase=="WAIT_RESULT") {assertTrue(result.isSuccess);assertTrue(allowed);assertEquals("result",store.cp!!.trustedPage)}
            else {assertTrue(result.isFailure);assertFalse(allowed)}
        }
    }
}
