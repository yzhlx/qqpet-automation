package com.ynk.virtualdisplay.automation

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** This is an old checkpoint observation, never a new input authorization. */
data class FinalizationCheckpoint(val sessionId:String,val actionPending:Boolean,val evidence:String)

object CommittedPlanFinalizationPolicy {
    fun requireCommittedPrefix(run:RunSnapshot,attempts:List<ActivityAttempt>,checkpoint:FinalizationCheckpoint) {
        val plan=run.request.planSnapshot
        check(run.request.origin==RunOrigin.PLAN && plan.executionProfile==ExecutionProfile.SELECTED_ACTIONS &&
            plan.steps.none {it is StudyStep}) {"FINALIZATION_STUDY_OR_LEGACY_FORBIDDEN"}
        check(run.state==RunState.UNCONFIRMED && run.stopReason?.startsWith("HEALTH_STALE")==true &&
            run.steps.last().state==StepState.UNCONFIRMED && run.steps.last().reason==run.stopReason &&
            run.steps.dropLast(1).all {it.state in setOf(StepState.COMPLETED,StepState.SKIPPED)}) {
            "FINALIZATION_NOT_UNIQUE_TERMINAL_HEALTH_STALE"
        }
        check(checkpoint.sessionId==run.runId && !checkpoint.actionPending && checkpoint.evidence.isNotBlank()) {
            "FINALIZATION_CHECKPOINT_PENDING_OR_MISMATCH"
        }
        check(attempts.all {it.attemptId.runId==run.runId && !it.inputPending && it.state==AttemptState.COMPLETED} &&
            attempts.map {it.attemptId}.distinct().size==attempts.size &&
            attempts.all {a ->plan.steps.any {it.stepId==a.attemptId.stepId}}) {"FINALIZATION_ATTEMPTS_UNRESOLVED_OR_FOREIGN"}
        var work=0;var care=0
        plan.steps.forEachIndexed {index,step ->
            val execution=run.steps[index]
            val own=attempts.filter {it.attemptId.stepId==step.stepId}
            if(execution.state==StepState.SKIPPED) {
                check(own.isEmpty() && ((step is WorkStep && step.trigger==WorkTrigger.IF_FUNDS_SHORT &&
                    execution.reason==SkipReason.FUNDS_SUFFICIENT.name) ||
                    (step is CareStep && execution.reason==SkipReason.ALREADY_SATISFIED.name))) {"FINALIZATION_INVALID_SKIP"}
            } else {
                val required=if(step is WorkStep) step.shifts else 1
                check(own.size==required && own.map {it.attemptId.iteration}.sorted()==(1..required).toList() &&
                    own.all {it.completionConfirmedAtMs!=null && if(step is CareStep) it.durationMinutes==0 else it.durationMinutes>0}) {
                    "FINALIZATION_COMPLETION_PROOF_MISSING:${step.stepId}"
                }
                if(step is WorkStep) work+=required else care++
            }
        }
        check(run.counts==RunCounts(work,care,0)) {"FINALIZATION_COUNTS_DISAGREE"}
    }
    fun requireBaseline(run:RunSnapshot,b:CareRoomBaseline,now:Long) {
        check(b.displayId==run.request.displayId && b.displayId>0 && b.stable && b.roomVerified && b.overlaysAbsent &&
            b.firstAt>=0 && b.secondAt>b.firstAt && b.secondAt-b.firstAt<=5000 &&
            now>=b.secondAt && now-b.secondAt<=3000 && b.firstEvidence.isNotBlank() &&
            b.secondEvidence.isNotBlank() && b.firstEvidence!=b.secondEvidence) {"FINALIZATION_BASELINE_UNCONFIRMED"}
        fun wallet(value:ObservedValue<Int>,at:Long,path:String):Int {
            val known=value.at(now,3000) as? ObservedValue.Known ?: error("FINALIZATION_WALLET_UNKNOWN_OR_STALE")
            check(known.observedAt==at && known.evidence==path && known.value>=0 && known.confidence>=0.95 &&
                known.source==ObservationSource.DIGIT_RECOGNITION) {"FINALIZATION_WALLET_NOT_SAME_FRAME"}
            return known.value
        }
        check(wallet(b.firstWallet,b.firstAt,b.firstEvidence)==wallet(b.secondWallet,b.secondAt,b.secondEvidence)) {
            "FINALIZATION_WALLET_CHANGED"
        }
        check(now>= (run.endedAtMs ?: run.startedAtMs ?: 0L)) {"FINALIZATION_CLOCK_REVERSED"}
    }
}

/** Completes only bookkeeping over already committed attempts. Contains no executor or input port. */
class CommittedPlanFinalizer(directory:File,
    private val beforeRunCommit:()->Unit = {}) {
    private val root=directory.canonicalFile
    private val store=FileRunStore(root)
    fun auditFile(runId:String):File {
        require(runId.matches(Regex("[A-Za-z0-9_-]{1,128}")))
        return File(root,"finalizations/$runId/finalization.json")
    }
    fun requireEligible(runId:String,checkpoint:FinalizationCheckpoint):RunSnapshot {
        val run=checkNotNull(store.load(runId)) {"FINALIZATION_RUN_MISSING"}
        check(PlanRunPendingGate.pendingRunIds(root)==listOf(runId)) {"FINALIZATION_OTHER_RUN_PENDING"}
        CommittedPlanFinalizationPolicy.requireCommittedPrefix(run,store.attempts(runId),checkpoint)
        return run
    }
    fun finish(runId:String,checkpoint:FinalizationCheckpoint,baseline:CareRoomBaseline,
        now:Long,checkAuthority:()->Unit={}):RunSnapshot {
        val audit=auditFile(runId)
        val originalFile=File(root,"$runId.json")
        val bytes=originalFile.readBytes()
        val original=PlanRunJsonCodec.decode(bytes.toString(Charsets.UTF_8))
        // A completed receipt is never another execution grant and never rewrites its audit.
        if(original.snapshot.state==RunState.COMPLETED && audit.isFile) return original.snapshot
        check(PlanRunPendingGate.pendingRunIds(root)==listOf(runId)) {"FINALIZATION_OTHER_RUN_PENDING"}
        CommittedPlanFinalizationPolicy.requireCommittedPrefix(original.snapshot,original.attempts,checkpoint)
        CommittedPlanFinalizationPolicy.requireBaseline(original.snapshot,baseline,now)
        checkAuthority()
        check(!audit.exists()) {"FINALIZATION_AUDIT_ALREADY_PREPARED_REQUIRES_REVIEW"}
        val target=original.snapshot.copy(state=RunState.COMPLETED,stopReason=null,endedAtMs=now,
            steps=original.snapshot.steps.mapIndexed {index,step ->
                if(index==original.snapshot.steps.lastIndex) step.copy(state=StepState.COMPLETED,reason=null,
                    endedAtMs=now,evidenceIds=step.evidenceIds+listOf(baseline.firstEvidence,baseline.secondEvidence,audit.absolutePath))
                else step
            })
        val targetDocument=PlanRunJsonCodec.encode(original.copy(snapshot=target))
        val observations=mapOf("displayId" to baseline.displayId,"firstAt" to baseline.firstAt,"secondAt" to baseline.secondAt,
            "stable" to baseline.stable,"roomVerified" to baseline.roomVerified,"overlaysAbsent" to baseline.overlaysAbsent,
            "firstWallet" to baseline.firstWallet.fields(),"secondWallet" to baseline.secondWallet.fields(),
            "firstEvidence" to baseline.firstEvidence,"secondEvidence" to baseline.secondEvidence)
        val auditText=EventJson.encode(mapOf("schemaVersion" to 1,"action" to "FINALIZE_ALREADY_COMMITTED_NON_STUDY",
            "runId" to runId,"at" to now,"checkpointEvidence" to checkpoint.evidence,
            "originalRunDocument" to bytes.toString(Charsets.UTF_8),"finalizedRunDocument" to targetDocument,
            "baseline" to observations,"note" to "Audit intent precedes commit; RunStore remains authoritative. No input or activity replay."))
        val parent=audit.parentFile
        check(parent.mkdirs() || parent.isDirectory) {"FINALIZATION_AUDIT_DIRECTORY_FAILED"}
        check(parent.canonicalFile==parent.absoluteFile && !Files.isSymbolicLink(parent.toPath())) {"FINALIZATION_AUDIT_PATH_INVALID"}
        val oldCopy=File(parent,"original-run.json")
        if(oldCopy.exists()) check(oldCopy.readBytes().contentEquals(bytes)) {"FINALIZATION_ORIGINAL_COPY_CHANGED"}
        else writeOnce(oldCopy,bytes)
        writeOnce(audit,auditText.toByteArray(Charsets.UTF_8))
        // A crash here leaves an explicit prepared audit and the original UNCONFIRMED run. No replay.
        checkAuthority();beforeRunCommit()
        check(originalFile.readBytes().contentEquals(bytes)) {"FINALIZATION_RUN_CHANGED"}
        store.save(target)
        check(store.attempts(runId)==original.attempts && store.countsOf(runId,target.request.planSnapshot)==target.counts) {
            "FINALIZATION_ATTEMPTS_CHANGED"
        }
        return target
    }
    private fun writeOnce(target:File,bytes:ByteArray) {
        check(!target.exists() && !Files.isSymbolicLink(target.toPath())) {"FINALIZATION_AUDIT_EXISTS"}
        val temp=File.createTempFile(".finalize-",".tmp",target.parentFile)
        try {
            FileOutputStream(temp).use {it.write(bytes);it.fd.sync()}
            Files.move(temp.toPath(),target.toPath(),StandardCopyOption.ATOMIC_MOVE)
        } finally {Files.deleteIfExists(temp.toPath())}
    }
}
