package com.ynk.virtualdisplay.automation

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.Rect
import android.os.SystemClock
import kotlinx.coroutines.delay
import kotlinx.coroutines.TimeoutCancellationException
import org.json.JSONObject
import java.io.File

/** Existing measured learning chain, separate from the experimental runner. */
class PetStudyFlow(private val flow: PetFlowContext,private val navigation: PetNavigation,private val ledger:PetRoundLedger) {
    suspend fun start(ready:PetCareReading):JSONObject = with(flow) {
                state="SELECT_COURSE"
                val navigationStart=SystemClock.elapsedRealtime()
                navigation.tap(room,PointF(360f,1390f),outdoor,"outdoor")
                navigation.openAcademyCourseList()
                navigation.tap(profile.courseListProbe,profile.courseButton,profile.courseDetailProbe,"select-fixed-course")
                state="START_COURSE"
                verify(listOf(profile.courseDetailProbe),"course-before-start",latest=true)
                PetStudyHealthPolicy.requireReady(ready.observations,System.currentTimeMillis())
                ledger.startAttempt(round)
                safety();val start=SystemClock.elapsedRealtime();val startWall=System.currentTimeMillis()
                input.tap(profile.startButton.x,profile.startButton.y);delay(profile.pageSettleMs)
                // Actual run: the first fresh frame after Start was an academy transition.
                // Read-only settling does not repeat input or relax the matching threshold.
                val learningDeadline=SystemClock.elapsedRealtime()+8000
                var learningConfirmed=false
                while(SystemClock.elapsedRealtime()<learningDeadline) {
                    val current=frame()
                    try {
                        val learningScore=score(current,profile.learningProbes)
                        emit("course-start-observe",learningScore,capture.telemetry()
                            .put("result",if(learningScore>=profile.matchThreshold) "PASS" else "SETTLING").toString())
                        if(learningScore>=profile.matchThreshold) {
                            save(current,"course-start");recovery.confirm(round,"LEARNING","LEARNING");learningConfirmed=true;break
                        }
                    } finally {current.recycle()}
                    delay(300)
                }
                check(learningConfirmed) { "LEARNING_NOT_CONFIRMED_AFTER_START" }
                capture.facts.events.append(EventSource.GAME_OBSERVATION,EventKind.COURSE_STARTED,mapOf("round" to round,"course" to "PHYSICAL_10_MIN"),listOf(evidence.absolutePath))
                capture.facts.events.append(EventSource.GAME_OBSERVATION,EventKind.LEARNING_CONFIRMED,mapOf("round" to round),listOf(evidence.absolutePath))
                val course=JSONObject().put("name","蹦蹦跳跳体能课").put("courseStartElapsedRealtime",start)
                    .put("start_ts",startWall).put("enter_course_ms",start-navigationStart)
                ledger.started(round)
                course
    }
    suspend fun highFiveWindow(start: Long, course: JSONObject) = with(flow) {
        state="WAIT_HIGH_FIVE_WINDOW"
        while(SystemClock.elapsedRealtime()-start<30000) {
            verify(profile.learningProbes,"learning-wait",false)
            delay(minOf(1000L,30000-(SystemClock.elapsedRealtime()-start)).coerceAtLeast(1))
        }
        course.put("high_five_attempted",true).put("high_five_target_count",10)
        var sent=0; var first: Long?=null; var last: Long?=null; var bestScore=0.0
        val began=SystemClock.elapsedRealtime()
        while(sent<10) {
            check(sent>0 || SystemClock.elapsedRealtime()-began<8000) { "HIGH_FIVE_TARGET_NOT_FOUND" }
            val f=frame()
            val s=try {
                check(score(f,profile.learningProbes)>=profile.matchThreshold) { "LEARNING_PAGE_LOST" }
                if(sent==0) save(f,"before-high-five")
                score(f,listOf(highFive))
            } finally { f.recycle() }
            bestScore=maxOf(bestScore,s)
            // Select the high-five point once from a positively matched target at T+~30s.
            // The user explicitly requests a 10-tap burst; its click animation hides the cue.
            // Subsequent taps retain that selected point, with LEARNING checked before/after each tap.
            if(sent==0 && s<profile.matchThreshold) { delay(300);continue }
            check(first==null || SystemClock.elapsedRealtime()-start-first!!<45000) { "HIGH_FIVE_BURST_TIMEOUT" }
            state="HIGH_FIVE"
            safety();input.tap(325f,375f)
            val offset=SystemClock.elapsedRealtime()-start
            if(first==null) first=offset
            last=offset;sent++;ledger.encouraged(round)
            emit("high-five-$sent",s,JSONObject().put("action","HIGH_FIVE_BURST")
                .put("displayId",4).put("tapIndex",sent).put("tapLimit",10)
                .put("targetSelected",true).put("currentTargetScore",s).put("offsetMs",offset)
                .put("result","INPUT_SENT_BUSINESS_UNCONFIRMED").toString())
            verify(profile.learningProbes,"high-five-$sent-after",false)
            delay(80)
        }
        verify(profile.learningProbes,"after-high-five")
        course.put("high_five_sent_count",sent).put("high_five_success_count",JSONObject.NULL)
            .put("high_five_first_offset_ms",first?:JSONObject.NULL).put("high_five_last_offset_ms",last?:JSONObject.NULL)
            .put("high_five_duration_ms",SystemClock.elapsedRealtime()-began).put("high_five_match_score",bestScore)
            .put("high_five_result",when(sent) {0->"MISS";10->"SENT_UNCONFIRMED";else->"PARTIAL"})
        emit("high-five-summary",bestScore,course.toString())
    }
    suspend fun waitResult(start:Long):JSONObject = with(flow) {
                var resultGrade="UNKNOWN"
                state="WAIT_RESULT"
                val waitStart=SystemClock.elapsedRealtime()
                var found=false
                var unconfirmedSince: Long?=null
                while(SystemClock.elapsedRealtime()-start<690000) {
                    val f=frame()
                    try {
                        val rs=score(f,listOf(profile.resultProbe))
                        if(rs>=profile.matchThreshold) {
                            save(f,"result");recovery.confirm(round,state,profile.resultProbe.name);emit("result",rs)
                            // Grade is optional. A+ must not be labelled A through
                            // a loose match against a mostly white grade region.
                            val grade=if(score(f,listOf(gradeA))>=0.999) "A" else "UNKNOWN"
                            resultGrade=grade;found=true;break
                        }
                        val learningScore=score(f,profile.learningProbes)
                        if(learningScore>=profile.matchThreshold) {
                            recovery.confirm(round,state,"LEARNING");unconfirmedSince=null
                            emit("learning-confirmed",learningScore)
                        }
                        else {
                            val now=SystemClock.elapsedRealtime()
                            if(unconfirmedSince==null) unconfirmedSince=now
                            emit("result-transition-observe",maxOf(rs,learningScore),capture.telemetry().put("result","SETTLING").toString())
                            check(now-unconfirmedSince!!<8000) { "UNKNOWN_WAIT_RESULT_PAGE" }
                        }
                    } finally { f.recycle() }
                    delay(1000)
                }
                check(found) { "RESULT_TIMEOUT" }
                JSONObject().put("grade",resultGrade).put("course_ms",SystemClock.elapsedRealtime()-start)
                    .put("result_wait_ms",SystemClock.elapsedRealtime()-waitStart)
    }
}
