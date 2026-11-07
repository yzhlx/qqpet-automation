package com.ynk.virtualdisplay

import com.ynk.virtualdisplay.automation.*
import java.io.File
import java.nio.file.Files
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Real file-backed restart feedback: invoking a runner again must never restart an executor. */
class PlanRunnerReuseTest {
    private fun store(): FileRunStore {
        val root=File(".build-tmp/runner-reuse-tests").absoluteFile
        check(root.mkdirs() || root.isDirectory)
        return FileRunStore(Files.createTempDirectory(root.toPath(),"run-").toFile())
    }
    private fun request(id:String="request")=RunRequest(id,
        PlanV2(planId="p",name="care",dailyBudgetMinutes=480,steps=listOf(CareStep("care",90,90))),
        "device",4,RunOrigin.PLAN,1L)
    private fun preflight()=PlanPreflight(null,ResourcePreflightPort {
        ResourcePreflight.Ready("snapshot",false,false)
    })

    @Test fun completedRunReturnsOriginalReceiptWithoutCallingExecutorAgain()=runBlocking {
        val store=store()
        var inputCount=0
        val executor=StepExecutor { _,ctx ->
            ctx.beforeConsumingInput(1,0)
            inputCount++
            ctx.completed(1,0)
            StepOutcome.Completed(listOf("care-target-proved"))
        }
        val runner=PlanRunner(store,preflight(),mapOf(PlanStepType.CARE to executor))
        val request=request()
        val run="run-"+UUID.randomUUID()
        val first=runner.run(request,run)
        val second=runner.run(request,run)
        assertEquals(RunState.COMPLETED,second.state)
        assertEquals(first.endedAtMs,second.endedAtMs)
        assertEquals(1,inputCount)
        assertEquals(1,second.counts.careCompleted)
    }

    @Test fun pendingRunAfterRestartDoesNotEnterAnyExecutor()=runBlocking {
        val store=store()
        val request=request()
        val run="run-"+UUID.randomUUID()
        store.save(RunSnapshot(run,request,state=RunState.RUNNING,startedAtMs=2L))
        val id=AttemptId(run,"care",1)
        store.persistIntent(ActivityAttempt(id,0,AttemptState.INPUT_PENDING,inputPending=true))
        var called=0
        val runner=PlanRunner(store,preflight(),mapOf(PlanStepType.CARE to StepExecutor { _,_ ->
            called++;StepOutcome.Completed()
        }))
        val outcome=runner.run(request,run)
        assertEquals(0,called)
        assertEquals(RunState.UNCONFIRMED,outcome.state)
        assertTrue(store.hasPendingInput(run))
        assertEquals(0,outcome.counts.careCompleted)
    }

    @Test fun aDifferentRequestCannotOverwriteTheOriginalRun()=runBlocking {
        val store=store()
        val run="run-"+UUID.randomUUID()
        store.save(RunSnapshot(run,request("original"),state=RunState.RUNNING,startedAtMs=2L))
        val runner=PlanRunner(store,preflight(),emptyMap())
        var rejected=false
        try { runner.run(request("other"),run) } catch (_:IllegalArgumentException) { rejected=true }
        assertTrue(rejected)
        assertEquals("original",store.load(run)!!.request.requestId)
        assertEquals(RunState.RUNNING,store.load(run)!!.state)
    }
}
