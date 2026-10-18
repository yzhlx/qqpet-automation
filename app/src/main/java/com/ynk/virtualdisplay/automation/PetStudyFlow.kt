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
class PetStudyFlow(private val flow: PetFlowContext,private val navigation: PetNavigation,private val ledger:PetRoundLedger,private val courses:CourseSelector?=null) {
    suspend fun start(ready:PetCareReading,plan:SessionPlan=SessionPlan()):JSONObject = with(flow) {
                state="SELECT_COURSE"
                val navigationStart=SystemClock.elapsedRealtime()
                navigation.tap(room,PointF(360f,1390f),outdoor,"outdoor")
                // A selector finds the plan's course by name and proves the detail page. Without
                // one, the only course ever measured on this device is tapped at its fixed point.
                if(courses!=null) courses.select(plan)
                else {
                    check(plan.courseId==SessionPlan.DEFAULT_COURSE_ID) {"COURSE_SELECTOR_REQUIRED:${plan.courseId}"}
                    navigation.openAcademyCourseList()
                    navigation.tap(profile.courseListProbe,profile.courseButton,profile.courseDetailProbe,"select-fixed-course")
                }
                state="START_COURSE"
                verify(listOf(profile.courseDetailProbe),"course-before-start",latest=true)
                PetStudyHealthPolicy.requireReady(ready.observations,System.currentTimeMillis(),plan.energyThreshold,plan.cleanlinessThreshold)
                ledger.startAttempt(round)
                safety();val start=SystemClock.elapsedRealtime();val startWall=System.currentTimeMillis()
                input.tap(profile.startButton.x,profile.startButton.y);delay(profile.pageSettleMs)
                // Actual run: the first fresh frame after Start was an academy transition.
                // Read-only settling does not repeat input or relax the matching threshold.
                // Field evidence (2026-10-12): after 去上课 the pet walks out and the bottom
                // 正在学习 panel slides up noticeably later; a 30s window still expired with the
                // panel arriving right after (score held at 0.782, then measured 1.0 minutes in).
                // The panel persists for the whole 10-minute course, so a bounded 120s confirm
                // window stays far inside the course and never masks a real failure.
                val learningDeadline=SystemClock.elapsedRealtime()+120000
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
                capture.facts.events.append(EventSource.GAME_OBSERVATION,EventKind.COURSE_STARTED,mapOf("round" to round,"course" to plan.courseId),listOf(evidence.absolutePath))
                capture.facts.events.append(EventSource.GAME_OBSERVATION,EventKind.LEARNING_CONFIRMED,mapOf("round" to round),listOf(evidence.absolutePath))
                val course=JSONObject().put("name",plan.courseName).put("courseId",plan.courseId).put("courseStartElapsedRealtime",start)
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
        // `first`/`last` are durations measured from the course start; the burst guard needs an
        // absolute timestamp. Keep the two in separate variables so their units cannot be mixed.
        var burstStartedAt=0L
        // Bounded target-selection window, started only once the learning page is confirmed,
        // so page settling can never be mistaken for a missing high-five cue.
        var targetDeadline=0L
        while(sent<10) {
            // The live learning panel can move vertically between captures, and the click
            // animation of a previous tap can hide it for a moment. Require a positively
            // matched frame (bounded retry) instead of accepting one transient miss, and
            // never send the next tap while the learning page is unconfirmed.
            val f=settleLatest(profile.learningProbes,"high-five-${sent+1}-before",
                if(sent==0) "before-high-five" else null)
            if(targetDeadline==0L) targetDeadline=SystemClock.elapsedRealtime()+20000
            val s=try { score(f,listOf(highFive)) } finally { f.recycle() }
            bestScore=maxOf(bestScore,s)
            // Select the high-five point once from a positively matched target at T+~30s.
            // The user explicitly requests a 10-tap burst; its click animation hides the cue.
            // Subsequent taps retain that selected point, with LEARNING checked before/after each tap.
            if(sent==0 && s<profile.matchThreshold) {
                check(SystemClock.elapsedRealtime()<targetDeadline) { "HIGH_FIVE_TARGET_NOT_FOUND" }
                delay(300);continue
            }
            // Bounded burst: capped by the 10-tap business limit, plus a finite wall clock guard so
            // encouragement can never run indefinitely if a tap stalls. Measured from the first
            // tap's absolute timestamp, never from a duration offset (mixing the two is silently
            // always-true, because uptime is far larger than any burst duration).
            // Budget: a healthy 10-tap burst costs ~25s (each pre/post capture settles in ~1s),
            // and every capture verification above is independently hard-bounded at 8s, so this
            // guard only fires on a genuine stall, never on a healthy burst.
            check(burstStartedAt==0L || SystemClock.elapsedRealtime()-burstStartedAt<120000) { "HIGH_FIVE_BURST_TIMEOUT" }
            state="HIGH_FIVE"
            safety();input.tap(325f,375f)
            val offset=SystemClock.elapsedRealtime()-start
            if(first==null) {first=offset;burstStartedAt=SystemClock.elapsedRealtime()}
            last=offset;sent++;ledger.encouraged(round)
            emit("high-five-$sent",s,JSONObject().put("action","HIGH_FIVE_BURST")
                .put("displayId",4).put("tapIndex",sent).put("tapLimit",10)
                .put("targetSelected",true).put("currentTargetScore",s).put("offsetMs",offset)
                .put("result","INPUT_SENT_BUSINESS_UNCONFIRMED").toString())
            verifySettled(profile.learningProbes,"high-five-$sent-after")
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
