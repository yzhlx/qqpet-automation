package com.ynk.virtualdisplay

import com.ynk.virtualdisplay.automation.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SessionRecoveryTest {
    private class Store: CheckpointStore {
        var value: SessionCheckpoint?=null
        override fun save(checkpoint: SessionCheckpoint) {value=checkpoint}
        override fun load()=value
    }
    private class Fixture(val attempts:Int=3,val window:Long=10000) {
        val store=Store();var now=1000L;var blocked=false;var rearmed=0
        val states=mutableListOf<SessionRunState>()
        val recovery=SessionRecovery("session",store,{now},{now},{s,_,_->states.add(s)},
            {blocked=true},{assertTrue(blocked);rearmed++},{blocked=false},attempts,window,{now+=it})
        init {recovery.confirm(2,"WAIT_RESULT","LEARNING")}
    }
    @Test fun lockPausesWaitsForUnlockAndVerifiesBeforeResume(): Unit=runBlocking {
        val f=Fixture();var reads=0
        f.recovery.recover("PHONE_LOCKED",{f.now<2500}) {
            assertTrue(f.blocked);reads++;RecoveryObservation(true,true,"LEARNING")
        }
        assertEquals(listOf(SessionRunState.PAUSED,SessionRunState.RESUMING,SessionRunState.RUNNING),f.states)
        assertEquals(1,reads);assertFalse(f.blocked);assertEquals(2,f.store.value!!.round)
    }
    @Test fun outageRecoversWithBoundedAttemptsAndNoInput(): Unit=runBlocking {
        val f=Fixture();var reads=0
        f.recovery.recover("CAPTURE_INVALID",{false}) {
            reads++;assertTrue(f.blocked)
            if(reads<3) error("capture unavailable")
            RecoveryObservation(true,true,"LEARNING")
        }
        assertEquals(3,reads);assertEquals(3,f.rearmed);assertFalse(f.blocked)
    }
    @Test fun repeatedFailureStopsAfterThree(): Unit=runBlocking {
        val f=Fixture();var reads=0
        assertTrue(runCatching {f.recovery.recover("outage",{false}) {reads++;error("unavailable")}}.isFailure)
        assertEquals(3,reads);assertTrue(f.blocked);assertEquals(SessionRunState.STOPPED,f.recovery.state)
    }
    @Test fun permanentlyLockedHasFiniteDeadline(): Unit=runBlocking {
        val f=Fixture(window=2000);var reads=0
        assertTrue(runCatching {f.recovery.recover("locked",{true}) {reads++;RecoveryObservation(true,true,"LEARNING")}}.isFailure)
        assertEquals(0,reads);assertEquals(3000L,f.now);assertTrue(f.blocked)
        assertEquals(SessionRunState.STOPPED,f.recovery.state)
    }
    @Test fun differentKnownPageStopsWithoutRetryOrGuess(): Unit=runBlocking {
        val f=Fixture();var reads=0
        val result=runCatching {f.recovery.recover("capture",{false}) {reads++;RecoveryObservation(true,true,"RESULT")}}
        assertTrue(result.exceptionOrNull()!!.message!!.startsWith("RECOVERY_PAGE_MISMATCH"))
        assertEquals(1,reads);assertTrue(f.blocked)
    }
    @Test fun unknownPageInvalidDisplayAndInvalidFrameNeverResume(): Unit=runBlocking {
        for(value in listOf(RecoveryObservation(true,true,null),RecoveryObservation(false,true,"LEARNING"),
            RecoveryObservation(true,false,"LEARNING"))) {
            val f=Fixture()
            assertTrue(runCatching {f.recovery.recover("fault",{false}) {value}}.isFailure)
            assertTrue(f.blocked);assertFalse(f.states.last()==SessionRunState.RUNNING)
        }
    }
    @Test fun pendingActionIsNotReplayed(): Unit=runBlocking {
        val f=Fixture();f.recovery.actionPending();var probes=0
        val result=runCatching {f.recovery.recover("locked after input",{false}) {probes++;RecoveryObservation(true,true,"LEARNING")}}
        assertEquals("CHECKPOINT_ACTION_UNCONFIRMED",result.exceptionOrNull()!!.message)
        assertEquals(0,probes);assertTrue(f.blocked)
    }
    @Test fun requiredHealthMustBeNewlyObservedAndFresh(): Unit=runBlocking {
        fun health(at:Long): HealthObservations {
            fun known()=ObservedValue.Known(94,at,1.0,"frame",ObservationSource.DIGIT_RECOGNITION)
            return HealthObservations(known(),known(),known(),known(),known(),known(),true,"frame")
        }
        for(value in listOf(null,health(-60000))) {
            val f=Fixture();f.recovery.confirm(2,"PRECHECK","HEALTH",health(1000),true)
            assertTrue(runCatching {f.recovery.recover("fault",{false}) {RecoveryObservation(true,true,"HEALTH",value)}}.isFailure)
            assertTrue(f.blocked)
        }
        val f=Fixture();f.recovery.confirm(2,"PRECHECK","HEALTH",health(1000),true)
        f.recovery.recover("fault",{false}) {RecoveryObservation(true,true,"HEALTH",health(f.now))}
        assertFalse(f.blocked)
    }
    @Test fun swappedSessionCheckpointStops(): Unit=runBlocking {
        val f=Fixture();f.store.value=f.store.value!!.copy(sessionId="other")
        assertTrue(runCatching {f.recovery.recover("fault",{false}) {error("must not probe")}}.isFailure)
        assertTrue(f.blocked)
    }
}
