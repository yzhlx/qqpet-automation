package com.ynk.virtualdisplay.automation

import android.app.KeyguardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.Rect
import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.io.File

/** Debug-only, fixed three-round experiment. Default service protection is untouched. */
class PetExperimentalSession(
    private val context: Context,
    private val capture: HeadlessDisplayCapture,
    private val input: PetInputController,
    private val evidence: File,
    private val guard: () -> Unit,
    private val logger: (String, Double?, String, String, String?) -> Unit,
    private val resumeRecord: JSONObject? = null,
) {
    private val profile = PetProfile.calibrated()
    private val matcher = TemplateMatcher(context)
    private var round = 0
    private var state = "CHECK_STATE"
    private var serial = 0
    private var record = JSONObject()
    var completed = 0; private set
    var stopReason: String? = null; private set
    private fun probe(name: String, l: Int, t: Int, r: Int, b: Int) =
        PetProfile.Probe(name, Rect(l,t,r,b), "pet_experimental/$name.png")
    private val home = probe("home",600,130,670,190)
    private val room = probe("room",320,1340,405,1465)
    private val healthPanel = probe("health",169,228,250,415)
    private val cookie = probe("cookie",280,1255,440,1360)
    private val soap = PetProfile.Probe("soap",Rect(280,1280,440,1400),"pet_mvp/soap.png")
    private val outdoor = probe("outdoor",126,610,275,663)
    private val academy = probe("academy",330,158,346,177)
    private val highFive = probe("high_five",284,344,355,400)
    private val gradeA = probe("grade_a",545,385,695,525)
    private val upgrade = probe("upgrade",245,558,480,610)

    private fun safety() {
        guard()
        check(!(context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isKeyguardLocked) { "PHONE_LOCKED" }
    }
    private fun emit(action: String, score: Double? = null, result: String = "PASS", reason: String? = null) {
        logger(state, score, "round-$round:$action", result, reason)
    }
    private fun save(frame: Bitmap, name: String) {
        evidence.mkdirs()
        File(evidence, "round-$round-$name.png").outputStream().use { check(frame.compress(Bitmap.CompressFormat.PNG,100,it)) }
    }
    private suspend fun frame(latest: Boolean = false): Bitmap {
        safety()
        return try {
            val f = if(latest) capture.latestRenderedFrame() else capture.awaitFrame()
            emit("FRAME", result=capture.telemetry().toString())
            f
        } catch (t: TimeoutCancellationException) {
            throw IllegalStateException("CAPTURE_TIMEOUT:${t.message}; ${capture.diagnostics()}", t)
        }
    }
    private fun score(frame: Bitmap, probes: List<PetProfile.Probe>) =
        probes.maxOf { matcher.match(frame,it,profile.matchThreshold).score }
    private suspend fun verify(probes: List<PetProfile.Probe>, name: String, saveEvidence: Boolean = true, latest: Boolean = false): Double {
        val f = frame(latest)
        try {
            if(saveEvidence) save(f,name)
            val matches = probes.map { it to matcher.match(f,it,profile.matchThreshold).score }
            val best = matches.maxBy { it.second }
            val s = best.second
            emit(name,s,capture.telemetry().put("action",name).put("templateName",best.first.name)
                .put("matchScore",s).put("result",if(s>=profile.matchThreshold) "PASS" else "FAIL").toString())
            check(s>=profile.matchThreshold) { "PAGE_MISMATCH:$state:$name:$s" }
            return s
        } finally { f.recycle() }
    }
    private suspend fun verifySettled(probes: List<PetProfile.Probe>, name: String): Double {
        val deadline=SystemClock.elapsedRealtime()+8000
        var lastScore=0.0
        while(SystemClock.elapsedRealtime()<deadline) {
            val current=frame()
            try {
                val best=probes.map { it to matcher.match(current,it,profile.matchThreshold).score }.maxBy { it.second }
                lastScore=best.second
                val matched=lastScore>=profile.matchThreshold
                emit(name,lastScore,capture.telemetry().put("templateName",best.first.name)
                    .put("result",if(matched) "PASS" else "SETTLING").toString())
                if(matched) {save(current,name);return lastScore}
            } finally {current.recycle()}
            delay(300)
        }
        error("PAGE_MISMATCH:$state:$name:$lastScore")
    }
    private suspend fun dismissUpgradeIfPresent(action: String): Boolean {
        val current=frame()
        val upgradeScore=try {score(current,listOf(upgrade))} finally {current.recycle()}
        if(upgradeScore<profile.matchThreshold) return false
        emit("$action-upgrade-before",upgradeScore)
        safety();input.tap(360f,1248f);delay(profile.pageSettleMs)
        verifySettled(listOf(academy,profile.courseListProbe),"$action-upgrade-after")
        return true
    }
    private suspend fun returnHomeFromResult(action: String) {
        verify(listOf(profile.resultProbe),"$action-continue-before",latest=true)
        safety();input.tap(profile.continueButton.x,profile.continueButton.y);delay(profile.pageSettleMs)
        val next=frame()
        val upgraded=try {score(next,listOf(upgrade))>=profile.matchThreshold} finally {next.recycle()}
        if(upgraded) dismissUpgradeIfPresent(action)
        else verifySettled(listOf(academy,profile.courseListProbe),"$action-continue-after")
        val current=frame()
        val panelOpen=try {score(current,listOf(profile.courseListProbe))>=profile.matchThreshold} finally {current.recycle()}
        if(panelOpen) tap(profile.courseListProbe,PointF(80f,160f),academy,"$action-close-course")
        tap(academy,PointF(80f,160f),outdoor,"$action-leave-academy")
        tap(outdoor,PointF(80f,160f),room,"$action-return-home")
    }
    private suspend fun tap(before: PetProfile.Probe, point: PointF, after: PetProfile.Probe, action: String) {
        verify(listOf(before),"${serial++}-$action-before",latest=true)
        safety(); input.tap(point.x,point.y)
        delay(profile.pageSettleMs)
        verifySettled(listOf(after),"${serial++}-$action-after")
    }
    private fun healthJson(h: PetHealthCheck.Health) = JSONObject().put("stamina",h.energy)
        .put("cleanliness",h.cleanliness).put("mood",h.mood).put("measurement","conservative_bar_estimate")
        .put("attr1","UNKNOWN").put("attr2","UNKNOWN").put("attr3","UNKNOWN")
    private suspend fun readHealth(label: String): PetHealthCheck.Health {
        val started = SystemClock.elapsedRealtime()
        tap(home,PointF(516f,160f),healthPanel,"open-health")
        val f = frame(true)
        val h = try { save(f,label); PetHealthCheck.read(f) } finally { f.recycle() }
        check(h.energy in 10..100 && h.cleanliness in 10..100 && h.mood in 10..100) { "HEALTH_VALUE_ABNORMAL" }
        emit(label,result=healthJson(h).put("read_duration_ms",SystemClock.elapsedRealtime()-started).toString())
        tap(healthPanel,PointF(355f,454f),home,"close-health")
        return h
    }
    private suspend fun recover(initial: PetHealthCheck.Health): PetHealthCheck.Health {
        state="RECOVER_IF_NEEDED"
        var h = initial
        val recovery=JSONObject().put("feed_count",0).put("feed_duration_ms",0L)
            .put("clean_count",0).put("clean_duration_ms",0L)
        record.put("recovery",recovery)
        var feeds=0
        while(h.energy<90) {
            check(feeds<5) { "FEED_RETRY_LIMIT" }
            val before=h; val started=SystemClock.elapsedRealtime(); val wall=System.currentTimeMillis()
            tap(room,PointF(638f,720f),cookie,"enter-food")
            verify(listOf(cookie),"feed-before",latest=true)
            safety(); input.tap(360f,1287f); delay(2500)
            verify(listOf(cookie),"feed-after")
            h=readHealth("feed-health")
            check(h.energy>before.energy && h.energy-before.energy in 5..12) { "FEED_NO_EXPECTED_GAIN_OR_EMPTY" }
            tap(cookie,PointF(80f,160f),room,"leave-food")
            feeds++; recovery.put("feed_count",feeds)
            recovery.put("feed_duration_ms",recovery.getLong("feed_duration_ms")+SystemClock.elapsedRealtime()-started)
            emit("feed",result=JSONObject().put("feed_start_ts",wall).put("feed_end_ts",System.currentTimeMillis())
                .put("feed_duration_ms",SystemClock.elapsedRealtime()-started).put("stamina_before",before.energy)
                .put("stamina_after",h.energy).put("inventory_before","UNKNOWN").put("inventory_after","UNKNOWN").toString())
        }
        var cleans=0
        while(h.cleanliness<90) {
            check(cleans<3) { "CLEAN_RETRY_LIMIT" }
            val before=h; val started=SystemClock.elapsedRealtime()
            tap(room,PointF(638f,868f),soap,"enter-bath")
            verify(listOf(soap),"clean-before",latest=true)
            val points=buildList {
                add(PointF(360f,1345f));add(PointF(360f,1100f));add(PointF(360f,900f))
                repeat(40) { add(PointF(280f,800f));add(PointF(430f,950f));add(PointF(280f,950f));add(PointF(430f,800f)) }
            }
            safety(); input.drag(points,150); delay(2000)
            verify(listOf(soap),"clean-after")
            h=readHealth("clean-health")
            check(h.cleanliness>before.cleanliness) { "CLEAN_NO_GAIN_OR_EMPTY" }
            tap(soap,PointF(80f,160f),room,"leave-bath")
            cleans++;recovery.put("clean_count",cleans)
            recovery.put("clean_duration_ms",recovery.getLong("clean_duration_ms")+SystemClock.elapsedRealtime()-started)
            emit("clean",result=JSONObject().put("cleanliness_before",before.cleanliness).put("cleanliness_after",h.cleanliness)
                .put("clean_duration_ms",SystemClock.elapsedRealtime()-started).put("inventory_before","UNKNOWN")
                .put("inventory_after","UNKNOWN").toString())
        }
        return h
    }

    /** Enter the academy map, then explicitly open the unlocked primary academy course panel. */
    private suspend fun openAcademyCourseList() {
        state="SELECT_COURSE"
        verify(listOf(outdoor),"academy-entry-before",latest=true)
        safety();input.tap(200f,635f)
        delay(profile.pageSettleMs)
        verifySettled(listOf(academy),"academy-map")
        // Real run 1791201870302 showed the panel was already open before this click;
        // tapping its school label again toggled it closed. Use the existing panel probe.
        val current=frame(latest=true)
        try {
            val panelScore=score(current,listOf(profile.courseListProbe))
            if(panelScore>=profile.matchThreshold) {
                save(current,"academy-course-panel-already-open")
                emit("academy-course-panel-already-open",panelScore,capture.telemetry()
                    .put("templateName",profile.courseListProbe.name).put("result","PASS").toString())
                return
            }
            val mapScore=score(current,listOf(academy))
            check(mapScore>=profile.matchThreshold) { "ACADEMY_PAGE_UNCONFIRMED:$mapScore" }
            save(current,"enter-primary-academy-before")
            emit("enter-primary-academy-before",mapScore,capture.telemetry()
                .put("templateName",academy.name).put("result","PASS").toString())
        } finally {current.recycle()}
        safety();input.tap(200f,420f);delay(profile.pageSettleMs)
        verifySettled(listOf(profile.courseListProbe),"enter-primary-academy-after")
    }
    private suspend fun highFiveWindow(start: Long, course: JSONObject) {
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
            check(first==null || SystemClock.elapsedRealtime()-start-first!!<10000) { "HIGH_FIVE_BURST_TIMEOUT" }
            state="HIGH_FIVE"
            safety();input.tap(325f,375f)
            val offset=SystemClock.elapsedRealtime()-start
            if(first==null) first=offset
            last=offset;sent++
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
    suspend fun run(): String {
        val totalStart=SystemClock.elapsedRealtime()
        try {
            if(resumeRecord!=null) {
                val resumePhase=resumeRecord.getString("lastKnownState")
                check(resumeRecord.getInt("round") in 1..2 && resumeRecord.has("course") &&
                    ((resumePhase=="READ_POST_STATE" && resumeRecord.has("result")) || resumePhase=="WAIT_RESULT")) {
                    "RESUME_NOT_COMPLETED_COURSE"
                }
                round=resumeRecord.getInt("round");record=JSONObject(resumeRecord.toString());state="READ_POST_STATE"
            }
            dismissUpgradeIfPresent("resume")
            // A previously started course can leave an unacknowledged report.
            // Clear only a positively matched report, without counting it as this run.
            val initial=frame()
            val pendingResult=try {
                val rs=score(initial,listOf(profile.resultProbe))
                if(rs>=profile.matchThreshold) {
                    save(initial,"previous-result");emit("previous-result",rs,"VERIFIED")
                    if(resumeRecord?.getString("lastKnownState")=="WAIT_RESULT") {
                        val grade=if(score(initial,listOf(gradeA))>=0.999) "A" else "UNKNOWN"
                        record.put("result",JSONObject().put("grade",grade)).put("result_confirmed_on_resume",true)
                    }
                }
                rs>=profile.matchThreshold
            } finally {initial.recycle()}
            if(resumeRecord?.getString("lastKnownState")=="WAIT_RESULT") check(pendingResult) { "RESUME_RESULT_UNCONFIRMED" }
            if(pendingResult) {
                state="CLEAR_PREVIOUS_RESULT"
                returnHomeFromResult("previous")
            }
            else {
                val current=frame()
                val startPage=try {
                    when {
                        score(current,listOf(room))>=profile.matchThreshold -> "HOME"
                        score(current,listOf(profile.courseListProbe))>=profile.matchThreshold -> "COURSE_PANEL"
                        score(current,listOf(academy))>=profile.matchThreshold -> "ACADEMY"
                        score(current,listOf(outdoor))>=profile.matchThreshold -> "OUTDOOR"
                        else -> "UNKNOWN"
                    }
                } finally {current.recycle()}
                when(startPage) {
                    "COURSE_PANEL" -> {
                        tap(profile.courseListProbe,PointF(80f,160f),academy,"resume-close-course")
                        tap(academy,PointF(80f,160f),outdoor,"resume-leave-academy")
                        tap(outdoor,PointF(80f,160f),room,"resume-return-home")
                    }
                    "ACADEMY" -> {
                        tap(academy,PointF(80f,160f),outdoor,"resume-leave-academy")
                        tap(outdoor,PointF(80f,160f),room,"resume-return-home")
                    }
                    "OUTDOOR" -> tap(outdoor,PointF(80f,160f),room,"resume-return-home")
                    "HOME" -> Unit
                    else -> error("RESUME_PAGE_UNCONFIRMED")
                }
            }
            if(resumeRecord!=null) {
                val after=readHealth("after-resume")
                if(!record.has("after")) record.put("after",healthJson(after))
                else record.put("resume_current_health",healthJson(after))
                record.put("resumed_after_upgrade",true)
                    .put("resume_run",resumeRecord.getString("resume_run"))
                completed=resumeRecord.getInt("round");state="NEXT_ROUND";emit("ROUND_RESUMED",result=record.toString())
            }
            val initialCompleted=completed
            repeat(3-initialCompleted) { index ->
                round=initialCompleted+index+1;state="CHECK_STATE"
                val roundStart=SystemClock.elapsedRealtime()
                record=JSONObject().put("round",round).put("experimentalSession",true).put("allowLowMood",true)
                    .put("roundLimit",3).put("experimental_mood_override",true)
                verify(listOf(room),"initial-home")
                val before=readHealth("before")
                record.put("before",healthJson(before))
                val precheckMs=SystemClock.elapsedRealtime()-roundStart
                recover(before)
                state="VERIFY_READY"
                val ready=readHealth("ready")
                record.put("ready",healthJson(ready))
                check(ready.energy>=90 && ready.cleanliness>=90) { "HEALTH_NOT_READY" }
                // The existing reader underestimates rounded bar ends; never add 1 to authorize a course.
                state="SELECT_COURSE"
                val navigationStart=SystemClock.elapsedRealtime()
                tap(room,PointF(360f,1390f),outdoor,"outdoor")
                openAcademyCourseList()
                tap(profile.courseListProbe,profile.courseButton,profile.courseDetailProbe,"select-fixed-course")
                state="START_COURSE"
                verify(listOf(profile.courseDetailProbe),"course-before-start",latest=true)
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
                            save(current,"course-start");learningConfirmed=true;break
                        }
                    } finally {current.recycle()}
                    delay(300)
                }
                check(learningConfirmed) { "LEARNING_NOT_CONFIRMED_AFTER_START" }
                val course=JSONObject().put("name","蹦蹦跳跳体能课").put("courseStartElapsedRealtime",start)
                    .put("start_ts",startWall).put("enter_course_ms",start-navigationStart)
                record.put("course",course)
                highFiveWindow(start,course)
                state="WAIT_RESULT"
                val waitStart=SystemClock.elapsedRealtime()
                var found=false
                var unconfirmedSince: Long?=null
                while(SystemClock.elapsedRealtime()-start<690000) {
                    val f=frame()
                    try {
                        val rs=score(f,listOf(profile.resultProbe))
                        if(rs>=profile.matchThreshold) {
                            save(f,"result");emit("result",rs)
                            // Grade is optional. A+ must not be labelled A through
                            // a loose match against a mostly white grade region.
                            val grade=if(score(f,listOf(gradeA))>=0.999) "A" else "UNKNOWN"
                            record.put("result",JSONObject().put("grade",grade));found=true;break
                        }
                        val learningScore=score(f,profile.learningProbes)
                        if(learningScore>=profile.matchThreshold) unconfirmedSince=null
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
                val courseMs=SystemClock.elapsedRealtime()-start
                val waitMs=SystemClock.elapsedRealtime()-waitStart
                state="READ_POST_STATE"
                val transitionStart=SystemClock.elapsedRealtime()
                returnHomeFromResult("result")
                val after=readHealth("after")
                record.put("after",healthJson(after)).put("delta_stamina",after.energy-before.energy)
                    .put("delta_cleanliness",after.cleanliness-before.cleanliness).put("delta_mood",after.mood-before.mood)
                    .put("attributes","NOT MEASURED")
                record.put("timing",JSONObject().put("precheck_ms",precheckMs).put("course_ms",courseMs)
                    .put("result_wait_ms",waitMs).put("round_transition_ms",SystemClock.elapsedRealtime()-transitionStart)
                    .put("round_total_ms",SystemClock.elapsedRealtime()-roundStart))
                completed++;state="NEXT_ROUND";emit("ROUND_COMPLETE",result=record.toString())
            }
            state="DONE";emit("SESSION_COMPLETE",result=JSONObject().put("verdict","3_ROUNDS_PASS")
                .put("total_duration_ms",SystemClock.elapsedRealtime()-totalStart).put("completed",completed).toString())
            return "3_ROUNDS_PASS"
        } catch(t: Throwable) {
            stopReason=t.message?:t.javaClass.simpleName
            val last=state
            runCatching { val f=capture.latestRenderedFrame(Long.MAX_VALUE);try {save(f,"STOP-last-frame")}finally{f.recycle()} }
            state="STOPPED"
            emit("SESSION_STOP",result=record.put("lastKnownState",last).put("completed",completed)
                .put("total_duration_ms",SystemClock.elapsedRealtime()-totalStart).toString(),reason=stopReason)
            if(t is CancellationException && t !is TimeoutCancellationException) throw t
            return "STOPPED"
        }
    }
}








