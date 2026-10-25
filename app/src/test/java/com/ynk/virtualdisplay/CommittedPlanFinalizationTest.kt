package com.ynk.virtualdisplay

import com.ynk.virtualdisplay.automation.*
import java.io.File
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class CommittedPlanFinalizationTest {
    private class Fixture {
        val root=File("../.build-tmp/committed-finalization/${UUID.randomUUID()}").absoluteFile.apply {mkdirs()}
        val runs=File(root,"runs").apply {mkdirs()}
        val store=FileRunStore(runs)
        val runId="already-worked-and-cared"
        val work=WorkStep("w","work:verified")
        val care=CareStep("c",100,100)
        val request=RunRequest("old-request",PlanV2(planId="p",name="wc",dailyBudgetMinutes=480,steps=listOf(work,care)),
            "pet-local-device",6,RunOrigin.PLAN,1)
        val checkpointFile=File(root,"checkpoint.json").apply {writeText("{\"sessionId\":\"$runId\",\"actionPending\":false}")}
        val oldRecord=File(root,"run-record.json").apply {writeText("{\"terminalState\":\"FAILED\",\"courseStarts\":0}")}
        val checkpoint=FinalizationCheckpoint(runId,false,checkpointFile.absolutePath)
        val budgetFile=File(root,"budget.bin")
        val budget=PlanBudgetLedger(PersistentBudgetStore(budgetFile),today={"2026-10-23"})
        init {
            store.save(RunSnapshot(runId,request,state=RunState.RUNNING,startedAtMs=1))
            listOf(work to 10,care to 0).forEach { (step,minutes) ->
                val id=AttemptId(runId,step.stepId,1)
                store.persistIntent(ActivityAttempt(id,minutes,AttemptState.INPUT_PENDING,inputPending=true,inputDispatchedAtMs=10))
                store.persistCompleted(id,100)
                budget.reserve(id.value,minutes);budget.completed(id.value)
            }
            store.save(checkNotNull(store.load(runId)).copy(state=RunState.UNCONFIRMED,counts=RunCounts(1,1,0),endedAtMs=200,
                stopReason="HEALTH_STALE:OBSERVATION_EXPIRED_OR_CLOCK_REVERSED",
                steps=listOf(StepExecution(runId,"w",0,PlanStepType.WORK,StepState.COMPLETED,startedAtMs=10,endedAtMs=100),
                    StepExecution(runId,"c",1,PlanStepType.CARE,StepState.UNCONFIRMED,
                        reason="HEALTH_STALE:OBSERVATION_EXPIRED_OR_CLOCK_REVERSED",startedAtMs=100,endedAtMs=200))))
        }
        fun baseline()=baselineFor(6,20000,root)
        fun finalize()=CommittedPlanFinalizer(runs).finish(runId,checkpoint,baseline(),20000)
        fun snapshot()=checkNotNull(store.load(runId))
    }
    companion object {
        private fun baselineFor(display:Int,now:Long,root:File):CareRoomBaseline {
            val first=File(root,"first.png").apply {writeText("observer-proof-1")}.absolutePath
            val second=File(root,"second.png").apply {writeText("observer-proof-2")}.absolutePath
            return CareRoomBaseline(display,now-1000,now-500,true,true,true,
                ObservedValue.Known(15,now-1000,1.0,first,ObservationSource.DIGIT_RECOGNITION),
                ObservedValue.Known(15,now-500,1.0,second,ObservationSource.DIGIT_RECOGNITION),first,second)
        }
    }
    private fun refused(block:()->Unit) {
        try {block();fail("must refuse without changing a committed run")} catch(_:IllegalStateException) {}
    }
    @Test fun completesOnlyTheOriginalTailWithExactCountsAndImmutableAudit() {
        val f=Fixture();val before=File(f.runs,"${f.runId}.json").readBytes()
        val attempts=f.store.attempts(f.runId);val cp=f.checkpointFile.readBytes();val record=f.oldRecord.readBytes();val budget=f.budgetFile.readBytes()
        val result=f.finalize()
        assertEquals(RunState.COMPLETED,result.state);assertEquals(RunCounts(1,1,0),result.counts)
        assertTrue(result.steps.all {it.state==StepState.COMPLETED});assertNull(result.stopReason)
        assertEquals(attempts,FileRunStore(f.runs).attempts(f.runId))
        assertArrayEquals(cp,f.checkpointFile.readBytes());assertArrayEquals(record,f.oldRecord.readBytes());assertArrayEquals(budget,f.budgetFile.readBytes())
        val audit=CommittedPlanFinalizer(f.runs).auditFile(f.runId)
        assertTrue(audit.isFile);assertTrue(audit.readText().contains("HEALTH_STALE"))
        assertArrayEquals(before,File(audit.parentFile,"original-run.json").readBytes())
        assertNull(PlanRunPendingGate.blockingRunId(f.runs))
        val auditBytes=audit.readBytes();f.finalize();assertArrayEquals(auditBytes,audit.readBytes())
    }
    @Test fun realArchivedWorkCareRunFinalizesFromPersistedProofNotCurrentHealth() {
        val archive=File("../.build-tmp/device-acceptance/continuation-20261022-211328-845483-plan-work-care-recovered")
        // The archive is local device evidence that is never committed; skip where it is absent (CI).
        org.junit.Assume.assumeTrue(File(archive,"persisted-plan-run.json").isFile)
        val source=File(archive,"persisted-plan-run.json").readBytes()
        val record=PlanRunJsonCodec.decode(source.toString(Charsets.UTF_8))
        assertEquals("b5600c4f-f334-4013-9db8-f95d5fc4cb0a",record.snapshot.runId)
        assertEquals(RunCounts(1,1,0),record.snapshot.counts)
        val root=File("../.build-tmp/committed-finalization/archive-${UUID.randomUUID()}").absoluteFile.apply {mkdirs()}
        val runs=File(root,"runs").apply {mkdirs()};File(runs,"${record.snapshot.runId}.json").writeBytes(source)
        val cp=File(root,"checkpoint.json").apply {writeBytes(File(archive,"checkpoint.json").readBytes())}
        val old=File(root,"run-record.json").apply {writeBytes(File(archive,"run-record.json").readBytes())}
        assertTrue(cp.readText().contains("\"actionPending\":false"))
        val cpBefore=cp.readBytes();val oldBefore=old.readBytes()
        val now=checkNotNull(record.snapshot.endedAtMs)+2000
        val result=CommittedPlanFinalizer(runs).finish(record.snapshot.runId,
            FinalizationCheckpoint(record.snapshot.runId,false,cp.absolutePath),baselineFor(record.snapshot.request.displayId,now,root),now)
        assertEquals(RunState.COMPLETED,result.state);assertEquals(record.snapshot.counts,result.counts)
        assertEquals(record.attempts,FileRunStore(runs).attempts(result.runId))
        assertArrayEquals(cpBefore,cp.readBytes());assertArrayEquals(oldBefore,old.readBytes())
    }
    @Test fun pendingCheckpointWrongSessionAndMissingProofAllRefuse() {
        val f=Fixture();val before=File(f.runs,"${f.runId}.json").readBytes();val finalizer=CommittedPlanFinalizer(f.runs)
        refused {finalizer.finish(f.runId,f.checkpoint.copy(actionPending=true),f.baseline(),20000)}
        refused {finalizer.finish(f.runId,f.checkpoint.copy(sessionId="other"),f.baseline(),20000)}
        refused {CommittedPlanFinalizationPolicy.requireCommittedPrefix(f.snapshot(),f.store.attempts(f.runId).dropLast(1),f.checkpoint)}
        val pending=f.store.attempts(f.runId).map {if(it.attemptId.stepId=="c") it.copy(state=AttemptState.UNCONFIRMED,inputPending=true) else it}
        refused {CommittedPlanFinalizationPolicy.requireCommittedPrefix(f.snapshot(),pending,f.checkpoint)}
        assertArrayEquals(before,File(f.runs,"${f.runId}.json").readBytes())
    }
    @Test fun studyRemainsForbiddenEvenWithACompletedAttempt() {
        val step=StudyStep("s","course",1);val plan=PlanV2(planId="p",name="study",dailyBudgetMinutes=480,steps=listOf(step))
        val request=RunRequest("req",plan,"pet-local-device",6,RunOrigin.PLAN,1)
        val run=RunSnapshot("study",request,RunState.UNCONFIRMED,counts=RunCounts(studyRoundsCompleted=1),stopReason="HEALTH_STALE",
            steps=listOf(StepExecution("study","s",0,PlanStepType.STUDY,StepState.UNCONFIRMED,reason="HEALTH_STALE")))
        refused {CommittedPlanFinalizationPolicy.requireCommittedPrefix(run,
            listOf(ActivityAttempt(AttemptId("study","s",1),30,AttemptState.COMPLETED,inputPending=false,completionConfirmedAtMs=100)),
            FinalizationCheckpoint("study",false,"cp.json"))}
    }
    @Test fun otherFailuresUnfinishedPrefixAndIncorrectCountsRefuse() {
        val f=Fixture();val run=f.snapshot();val attempts=f.store.attempts(f.runId)
        refused {CommittedPlanFinalizationPolicy.requireCommittedPrefix(run.copy(stopReason="CLEANUP_FAILED"),attempts,f.checkpoint)}
        refused {CommittedPlanFinalizationPolicy.requireCommittedPrefix(run.copy(steps=listOf(run.steps[0].copy(state=StepState.PENDING),run.steps[1])),attempts,f.checkpoint)}
        refused {CommittedPlanFinalizationPolicy.requireCommittedPrefix(run.copy(counts=RunCounts(1,0,0)),attempts,f.checkpoint)}
        refused {CommittedPlanFinalizationPolicy.requireCommittedPrefix(run,attempts+attempts.last(),f.checkpoint)}
    }
    @Test fun skipCannotHideASelectedConsumedStepOrUnprovenWork() {
        val f=Fixture();val run=f.snapshot()
        val skipped=run.copy(steps=listOf(run.steps[0].copy(state=StepState.SKIPPED,reason=SkipReason.FUNDS_SUFFICIENT.name),run.steps[1]))
        refused {CommittedPlanFinalizationPolicy.requireCommittedPrefix(skipped,f.store.attempts(f.runId),f.checkpoint)}
        refused {CommittedPlanFinalizationPolicy.requireCommittedPrefix(skipped,f.store.attempts(f.runId).drop(1),f.checkpoint)}
    }
    @Test fun baselineWalletPageAndDisplayFailuresDoNotTouchOriginalRun() {
        val f=Fixture();val finalizer=CommittedPlanFinalizer(f.runs);val before=File(f.runs,"${f.runId}.json").readBytes()
        val b=f.baseline()
        listOf(b.copy(roomVerified=false),b.copy(overlaysAbsent=false),b.copy(stable=false),b.copy(displayId=5),
            b.copy(secondWallet=ObservedValue.Unknown("OCR",19500,b.secondEvidence))).forEach {
            refused {finalizer.finish(f.runId,f.checkpoint,it,20000)}
        }
        assertArrayEquals(before,File(f.runs,"${f.runId}.json").readBytes())
        assertFalse(finalizer.auditFile(f.runId).exists())
    }
    @Test fun otherPendingRunCannotBeOverlooked() {
        val f=Fixture();f.store.save(RunSnapshot("other-pending",f.request,state=RunState.RUNNING))
        refused {f.finalize()}
        assertEquals(2,PlanRunPendingGate.pendingRunIds(f.runs).size)
    }
    @Test fun interruptionAfterAuditKeepsOriginalUnconfirmedAndNeverReplays() {
        val f=Fixture();val before=File(f.runs,"${f.runId}.json").readBytes()
        val finalizer=CommittedPlanFinalizer(f.runs,beforeRunCommit={error("STOP_BEFORE_COMMIT")})
        refused {finalizer.finish(f.runId,f.checkpoint,f.baseline(),20000)}
        assertArrayEquals(before,File(f.runs,"${f.runId}.json").readBytes())
        val audit=finalizer.auditFile(f.runId);assertTrue(audit.isFile)
        val prior=audit.readBytes();refused {f.finalize()};assertArrayEquals(prior,audit.readBytes())
        assertEquals(RunState.UNCONFIRMED,f.snapshot().state)
    }
    @Test fun revokedObservationCannotPrepareOrCommitACompletion() {
        val f=Fixture();val before=File(f.runs,"${f.runId}.json").readBytes()
        refused {CommittedPlanFinalizer(f.runs).finish(f.runId,f.checkpoint,f.baseline(),20000) {error("OWNER_REVOKED")}}
        assertArrayEquals(before,File(f.runs,"${f.runId}.json").readBytes())
        assertFalse(CommittedPlanFinalizer(f.runs).auditFile(f.runId).exists())
    }
}
