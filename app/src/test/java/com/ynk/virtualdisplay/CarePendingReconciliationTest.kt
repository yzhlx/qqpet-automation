package com.ynk.virtualdisplay

import com.ynk.virtualdisplay.automation.*
import java.io.File
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class CarePendingReconciliationTest {
    private val now=20000L
    private fun baseline()=CareRoomBaseline(5,19000,19500,true,true,true,
        ObservedValue.Known(15,19000,1.0,"first.png",ObservationSource.DIGIT_RECOGNITION),
        ObservedValue.Known(15,19500,1.0,"second.png",ObservationSource.DIGIT_RECOGNITION),"first.png","second.png")
    private fun attempts()=CarePendingReconciliation.legacySessionIds.map {
        BudgetAttempt(AttemptId(it,"care",1).value,"2026-10-22",0,ReserveStatus.UNCONFIRMED_RETAINED,"old-unknown-$it")
    }
    private fun proofs()=attempts().map {attempt ->
        val id=CarePendingReconciliation.sessionId(attempt)
        LegacyCareStoppedProof(attempt.attemptId,id,false,id,"FAILED","care",0,"checkpoint/$id.json","terminal/$id.json")
    }
    private class Fixture(val initial:List<BudgetAttempt>) {
        val dir=File("../.build-tmp/care-reconciliation/${UUID.randomUUID()}").absoluteFile.apply {mkdirs()}
        val file=File(dir,"budget.bin")
        val disk=PersistentBudgetStore(file)
        init {disk.write(BudgetLedgerState("2026-10-22",100,initial.associateBy {it.attemptId}))}
        val ledger=PlanBudgetLedger(disk,today={"2026-10-23"})
    }
    private fun refused(block:()->Unit) {try {block();fail("must refuse")} catch(_:IllegalStateException) {}}
    private fun rejectsUnchanged(transform:(CareRoomBaseline)->CareRoomBaseline={it},
        proofChange:(List<LegacyCareStoppedProof>)->List<LegacyCareStoppedProof> = {it},v2:Boolean=false) {
        val f=Fixture(attempts());val bytes=f.file.readBytes()
        refused {f.ledger.reconcileStopped(proofChange(proofs()),transform(baseline()),!v2,now)}
        assertArrayEquals(bytes,f.file.readBytes())
    }
    @Test fun sevenLegacyUnknownsBecomeStoppedNotCompletedWithOriginalIdentityAndBudget() {
        val originals=attempts()
        val completed=BudgetAttempt("other-completed","2026-10-22",30,ReserveStatus.COMPLETED,"paid-course")
        val f=Fixture(originals+completed)
        f.ledger.reconcileStopped(proofs(),baseline(),true,now)
        val reloaded=PersistentBudgetStore(f.file).read()
        assertEquals(8,reloaded.attempts.size);assertEquals(100,reloaded.legacyUsedMinutes)
        assertEquals("2026-10-22",reloaded.day)
        assertEquals(130,f.ledger.usedMinutes("2026-10-22"));assertEquals(0,f.ledger.usedMinutes())
        assertEquals(1,reloaded.attempts.values.count {it.status==ReserveStatus.COMPLETED})
        originals.forEach {old ->
            val actual=reloaded.attempts.getValue(old.attemptId)
            assertEquals(old.minutes,actual.minutes);assertEquals(old.day,actual.day);assertEquals(old.limitMinutes,actual.limitMinutes)
            assertEquals(ReserveStatus.RECONCILED_STOPPED,actual.status)
            assertTrue(actual.reason!!.startsWith(old.reason!!));assertTrue(actual.reason!!.contains("OUTCOME_REMAINS_UNKNOWN"))
            assertTrue(actual.reason!!.contains("first.png|second.png"))
        }
        assertEquals(completed,reloaded.attempts.getValue(completed.attemptId))
    }
    @Test fun reconciledAttemptNeverReauthorizesInputOrTurnsIntoSuccess() {
        val f=Fixture(attempts());f.ledger.reconcileStopped(proofs(),baseline(),true,now)
        val id=attempts().first().attemptId
        assertFalse(f.ledger.reserve(id,0).newlyCharged)
        f.ledger.completed(id);f.ledger.startConfirmed(id);f.ledger.unconfirmed(id,"late callback")
        assertEquals(ReserveStatus.RECONCILED_STOPPED,PersistentBudgetStore(f.file).read().attempts.getValue(id).status)
    }
    @Test fun onePendingOldInputRejectsTheWholeBatch()=rejectsUnchanged(proofChange={list ->
        list.mapIndexed {i,p->if(i==3) p.copy(actionPending=true) else p}
    })
    @Test fun missingCheckpointOrTerminalEvidenceRefuses() {
        rejectsUnchanged(proofChange={it.dropLast(1)})
        rejectsUnchanged(proofChange={it.map {p->p.copy(checkpointEvidence="")}})
        rejectsUnchanged(proofChange={it.map {p->p.copy(terminalState="RUNNING")}})
        rejectsUnchanged(proofChange={it.map {p->p.copy(terminalSessionId="another-session")}})
    }
    @Test fun activeV2AlwaysPreventsLegacyReconciliation()=rejectsUnchanged(v2=true)
    @Test fun nonzeroWorkAndNonAllowlistedCareCannotUseTheBridge() {
        val extras=listOf(
            BudgetAttempt(AttemptId(CarePendingReconciliation.legacySessionIds.first(),"work",1).value,"2026-10-22",10,ReserveStatus.UNCONFIRMED_RETAINED),
            attempts().first().copy(minutes=1),
            attempts().first().copy(attemptId=AttemptId("not-allowed","care",1).value),
            attempts().first().copy(day="2026-10-23"))
        extras.forEach {extra ->
            val f=Fixture(attempts().drop(1)+extra);val bytes=f.file.readBytes()
            refused {f.ledger.reconcileStopped(proofs(),baseline(),true,now)}
            assertArrayEquals(bytes,f.file.readBytes())
        }
    }
    @Test fun walletUnknownStaleWrongSourceAndFrameMismatchAllRefuse() {
        rejectsUnchanged(transform={it.copy(secondWallet=ObservedValue.Unknown("OCR",19500,"second.png"))})
        rejectsUnchanged(transform={it.copy(firstWallet=ObservedValue.Known(15,1000,1.0,"first.png",ObservationSource.DIGIT_RECOGNITION))})
        rejectsUnchanged(transform={it.copy(firstWallet=ObservedValue.Known(15,19000,1.0,"first.png",ObservationSource.SCENE_COUNTER_LOWER_BOUND))})
        rejectsUnchanged(transform={it.copy(firstWallet=ObservedValue.Known(15,19000,1.0,"different.png",ObservationSource.DIGIT_RECOGNITION))})
        rejectsUnchanged(transform={it.copy(secondWallet=ObservedValue.Known(14,19500,1.0,"second.png",ObservationSource.DIGIT_RECOGNITION))})
    }
    @Test fun stableRoomAndNoOverlaysAreMandatory() {
        rejectsUnchanged(transform={it.copy(stable=false)})
        rejectsUnchanged(transform={it.copy(roomVerified=false)})
        rejectsUnchanged(transform={it.copy(overlaysAbsent=false)})
        rejectsUnchanged(transform={it.copy(displayId=0)})
        rejectsUnchanged(transform={it.copy(secondAt=19000)})
    }
    @Test fun batchCommitFailureKeepsThePriorRealFileIntact() {
        val f=Fixture(attempts());val prior=f.file.readBytes();var writes=0
        val failing=object:BudgetLedgerStore {
            override fun read()=f.disk.read()
            override fun write(state:BudgetLedgerState) {writes++;error("COMMIT_FAILED")}
        }
        refused {PlanBudgetLedger(failing).reconcileStopped(proofs(),baseline(),true,now)}
        assertEquals(1,writes);assertArrayEquals(prior,f.file.readBytes())
    }
    @Test fun typedObservationSharesOwnershipStopAndDuplicateReceipts() {
        var pending=true
        val coordinator=PlanRunCoordinator("test-${UUID.randomUUID()}",PlanRunCoordinator.PendingGate {if(pending) "legacy" else null})
        assertTrue(coordinator.admit("start","normal") is PlanRunCoordinator.Admission.Rejected)
        val granted=coordinator.admitObservation("recover","observer",PlanRunCoordinator.ObservationTask.LEGACY_ZERO_MINUTE_CARE_RECONCILIATION)
        assertTrue((granted as PlanRunCoordinator.Admission.Accepted).shouldExecute)
        assertTrue(coordinator.admit("another","other") is PlanRunCoordinator.Admission.Rejected)
        assertTrue(coordinator.stop("observer"));assertTrue(coordinator.isStopRequested("observer"))
        assertFalse(coordinator.stop("stale"));coordinator.finish("stale");assertEquals("observer",coordinator.active)
        coordinator.finish("observer");pending=false
        assertTrue((coordinator.admit("start","new-owner") as PlanRunCoordinator.Admission.Accepted).shouldExecute)
        val duplicate=coordinator.admitObservation("recover","duplicate",PlanRunCoordinator.ObservationTask.LEGACY_ZERO_MINUTE_CARE_RECONCILIATION)
        assertFalse((duplicate as PlanRunCoordinator.Admission.Accepted).shouldExecute)
        coordinator.finish("observer");assertEquals("new-owner",coordinator.active)
    }
    @Test fun persistedStatusNameMakesOlderEnumReaderFailClosed() {
        val f=Fixture(attempts());f.ledger.reconcileStopped(proofs(),baseline(),true,now)
        assertTrue(f.file.readBytes().toString(Charsets.ISO_8859_1).contains("RECONCILED_STOPPED"))
        val oldNames=setOf("RESERVED_BEFORE_INPUT","START_CONFIRMED","COMPLETED","UNCONFIRMED_RETAINED")
        assertFalse(ReserveStatus.RECONCILED_STOPPED.name in oldNames)
        // Existing strict readUTF/valueOf readers reject unknown values; no format downgrade/import.
        assertTrue(PersistentBudgetStore(f.file).read().attempts.values.all {it.status==ReserveStatus.RECONCILED_STOPPED})
    }
}
