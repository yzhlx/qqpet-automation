package com.ynk.virtualdisplay

import com.ynk.virtualdisplay.automation.*
import java.io.File
import java.util.UUID
import java.util.concurrent.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Real production composition and two real durable stores; device I/O is injected at its seam. */
class PlanDeviceExecutionIntegrationTest {
    private class Harness {
        val directory=File("../.build-tmp/plan-device-integration/${UUID.randomUUID()}").absoluteFile.apply {mkdirs()}
        val runs=File(directory,"runs")
        val store=FileRunStore(runs)
        val budgetFile=File(directory,"budget.bin")
        val ledger=PlanBudgetLedger(PersistentBudgetStore(budgetFile),today={"2026-10-22"})
        val events=mutableListOf<String>()
        var courseExists=true
        var resources:ResourcePreflight=ResourcePreflight.Ready("same-frame",false,false)
        var cancelled=false
        var workFailure:String?=null
        var skipWork=false
        var skipCare=false
        var leavePending=false
        var duplicateStudy=false
        val budget=object:PlanAttemptBudget {
            override fun canAfford(minutes:Int,limit:Int)=ledger.canAfford(minutes,limit)
            override fun reserve(id:String,minutes:Int,limit:Int):Boolean {
                // Verify file intent precedes budget reservation at the real production seam.
                assertTrue(store.attempts("run").any {it.attemptId.value==id && it.inputPending})
                return ledger.reserve(id,minutes,limit).newlyCharged
            }
            override fun started(id:String) {ledger.startConfirmed(id)}
            override fun completed(id:String) {ledger.completed(id)}
            override fun unconfirmed(id:String,reason:String) {ledger.unconfirmed(id,reason)}
        }
        fun request(steps:List<PlanStep>)=RunRequest("request",PlanV2(planId="p",name="test",dailyBudgetMinutes=480,
            steps=steps),"pet-local-device",5,RunOrigin.PLAN,1)
        fun execution()=PlanDeviceExecution(store,budget,PlanPreflight(CourseDependencyPort {
            events+="course"
            if(courseExists) StudyPreflight.Ok(it,"selected",30,66) else StudyPreflight.Rejected("COURSE_NOT_FOUND")
        },ResourcePreflightPort {events+="resources";resources}),
            work={_,ctx ->
                events+="work"
                if(skipWork) StepOutcome.Skipped(SkipReason.FUNDS_SUFFICIENT) else {
                    ctx.beforeConsumingInput(1,10); events+="work-input"
                    ctx.startConfirmed(1)
                    if(leavePending) StepOutcome.Unconfirmed("AWARD_UNKNOWN") else {
                        ctx.completed(1,10)
                        if(workFailure!=null) error(workFailure!!)
                        if(cancelled) throw CancellationException("STOP")
                        StepOutcome.Completed()
                    }
                }
            },care={_,ctx ->
                events+="care"
                if(skipCare) StepOutcome.Skipped(SkipReason.ALREADY_SATISFIED) else {
                    ctx.beforeConsumingInput(1,0); events+="care-input"
                    ctx.beforeConsumingInput(1,0); events+="care-input"
                    ctx.completed(1,0);StepOutcome.Completed()
                }
            },study={step,ctx ->
                events+="study"
                for(i in 1..step.rounds) {
                    ctx.beforeConsumingInput(i,30);events+="study-input"
                    if(duplicateStudy) ctx.beforeConsumingInput(i,30)
                    ctx.startConfirmed(i);ctx.completed(i,30)
                }
                StepOutcome.Completed()
            },checkAuthority={if(cancelled) throw CancellationException("STOP")})
    }
    private val w=WorkStep("own-work","work:verified")
    private val c=CareStep("own-care",90,90)
    private val s=StudyStep("own-study","own-course",2)

    @Test fun sevenSubsetsUseSelectedTypesAndPersistExactCountsAndMinutes()=runBlocking {
        for(mask in 1..7) {
            val h=Harness(); val steps=listOf(w,c,s).filterIndexed {i,_->mask and (1 shl i)!=0}
            val result=h.execution().run(h.request(steps),"run")
            assertEquals(RunState.COMPLETED,result.state)
            assertEquals(steps.map {it.type.name.lowercase()},h.events.filter {it in listOf("work","care","study")})
            assertEquals(if(w in steps) 1 else 0,result.counts.workShiftsCompleted)
            assertEquals(if(c in steps) 1 else 0,result.counts.careCompleted)
            assertEquals(if(s in steps) 2 else 0,result.counts.studyRoundsCompleted)
            assertEquals((if(w in steps) 10 else 0)+(if(s in steps) 60 else 0),h.ledger.usedMinutes())
            assertEquals(if(s in steps) 1 else 0,h.events.count {it=="course"})
            val reloaded=FileRunStore(h.runs)
            assertEquals(result.counts,reloaded.load("run")!!.counts)
            assertFalse(reloaded.hasPendingInput("run"))
            assertTrue(PersistentBudgetStore(h.budgetFile).read().attempts.values.all {it.status==ReserveStatus.COMPLETED})
        }
    }
    @Test fun missingCourseMeansNoPrefixInputAndNoReservation()=runBlocking {
        val h=Harness();h.courseExists=false
        val result=h.execution().run(h.request(listOf(w,c,s)),"run")
        assertEquals(RunState.FAILED,result.state)
        assertEquals(listOf("course"),h.events)
        assertTrue(h.store.attempts("run").isEmpty());assertEquals(0,h.ledger.usedMinutes())
    }
    @Test fun workCareNeverLoadsAnUnselectedCourse()=runBlocking {
        val h=Harness();h.courseExists=false
        assertEquals(RunState.COMPLETED,h.execution().run(h.request(listOf(w,c)),"run").state)
        assertFalse("course" in h.events);assertFalse("study" in h.events)
    }
    @Test fun legalSkipsContinueAndCreateNeitherAttemptsNorMinutes()=runBlocking {
        val h=Harness();h.skipWork=true;h.skipCare=true
        val request=h.request(listOf(w.copy(trigger=WorkTrigger.IF_FUNDS_SHORT),c,s))
        val result=h.execution().run(request,"run")
        assertEquals(RunState.COMPLETED,result.state)
        assertEquals(listOf(StepState.SKIPPED,StepState.SKIPPED,StepState.COMPLETED),result.steps.map {it.state})
        assertEquals(RunCounts(studyRoundsCompleted=2),result.counts)
        assertEquals(60,h.ledger.usedMinutes());assertEquals(2,h.store.attempts("run").size)
    }
    @Test fun cleanupThrowKeepsAwardAndMinutesButNeverRunsCare()=runBlocking {
        val h=Harness();h.workFailure="WORK_CLEANUP_FAILED"
        val result=h.execution().run(h.request(listOf(w,c)),"run")
        assertEquals(RunState.UNCONFIRMED,result.state)
        assertEquals(1,result.counts.workShiftsCompleted);assertEquals(10,h.ledger.usedMinutes())
        assertFalse("care" in h.events);assertEquals(StepState.PENDING,result.steps[1].state)
        assertEquals(ReserveStatus.COMPLETED,PersistentBudgetStore(h.budgetFile).read().attempts.values.single().status)
        assertEquals("run",PlanRunPendingGate.blockingRunId(h.runs))
    }
    @Test fun pendingStepRetainsBudgetAndCannotBeReplayedAfterReopen()=runBlocking {
        val h=Harness();h.leavePending=true;val request=h.request(listOf(w,c))
        val first=h.execution().run(request,"run");val events=h.events.toList()
        assertEquals(RunState.UNCONFIRMED,first.state)
        assertTrue(h.store.hasPendingInput("run"));assertEquals(10,h.ledger.usedMinutes())
        assertEquals(ReserveStatus.UNCONFIRMED_RETAINED,PersistentBudgetStore(h.budgetFile).read().attempts.values.single().status)
        assertEquals(first.counts,h.execution().run(request,"run").counts)
        assertEquals(events,h.events);assertFalse("care" in h.events)
    }
    @Test fun cancelAfterConfirmedAwardKeepsCountAndNoSuffix()=runBlocking {
        val h=Harness()
        // Cancellation comes from the owner after the work award, at the cleanup boundary.
        h.workFailure=null
        val execution=PlanDeviceExecution(h.store,h.budget,
            PlanPreflight(null,ResourcePreflightPort {ResourcePreflight.Ready("frame",false,false)}),
            work={_,ctx ->ctx.beforeConsumingInput(1,10);ctx.startConfirmed(1);ctx.completed(1,10);throw CancellationException("STOP")},
            care={_,_->error("MUST_NOT_RUN")},study={_,_->error("MUST_NOT_RUN")},checkAuthority={})
        try {execution.run(h.request(listOf(w,c)),"run");fail("cancel must propagate")} catch(_:CancellationException) {}
        assertEquals(RunState.STOPPED,h.store.load("run")!!.state)
        assertEquals(1,h.store.load("run")!!.counts.workShiftsCompleted)
        assertEquals(10,h.ledger.usedMinutes());assertEquals(StepState.PENDING,h.store.load("run")!!.steps[1].state)
    }
    @Test fun repeatedStudyInputIsRejectedWithoutSecondCharge()=runBlocking {
        val h=Harness();h.duplicateStudy=true
        val result=h.execution().run(h.request(listOf(s)),"run")
        assertEquals(RunState.UNCONFIRMED,result.state);assertEquals(30,h.ledger.usedMinutes())
        assertEquals(1,h.events.count {it=="study-input"});assertEquals(0,result.counts.studyRoundsCompleted)
    }
    @Test fun conditionalWorkMayNotCreateAnUnobservedCareDeficit() {
        assertFalse(PlanDevicePreflight.careNeedsNoItemsAfterWork(90,90,c,true,5,2))
        assertTrue(PlanDevicePreflight.careNeedsNoItemsAfterWork(95,92,c,true,5,2))
        assertTrue(PlanDevicePreflight.careNeedsNoItemsAfterWork(90,90,c,false,5,2))
        assertFalse(PlanDevicePreflight.careNeedsNoItemsAfterWork(null,100,c,false,5,2))
    }
}
