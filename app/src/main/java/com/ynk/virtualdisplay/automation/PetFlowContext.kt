package com.ynk.virtualdisplay.automation

import android.app.KeyguardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.Rect
import android.os.SystemClock
import kotlinx.coroutines.delay
import kotlinx.coroutines.TimeoutCancellationException
import org.json.JSONObject
import java.io.File

class PetFlowContext(val context: Context, val capture: HeadlessDisplayCapture,
    val input: PetInputController, val evidence: File, val guard: () -> Unit,
    val logger: (String, Double?, String, String, String?) -> Unit,
    val onObservation: (String,Double?) -> Unit = {_,_->},
    val onRecovery: (SessionRunState,String,Int)->Unit = {_,_,_->},
    val reconcile: (SessionCheckpoint,String)->Boolean={_,_->false},
    val energyThreshold: Int = SessionPlan.DEFAULT_THRESHOLD,
    val cleanlinessThreshold: Int = SessionPlan.DEFAULT_THRESHOLD,
    learningEvidence: (suspend (Bitmap)->Boolean)? = null) {
    val profile=PetProfile.calibrated()
    val matcher=TemplateMatcher(context)
    var round=0
    var state="OBSERVE"
    var serial=0
    // Page probes live in PetPageProbes so recorded frames can be checked against the exact
    // definitions the flows use, without a live capture session.
    fun probe(name: String, l: Int, t: Int, r: Int, b: Int) =
        PetProfile.Probe(name, Rect(l,t,r,b), "pet_experimental/$name.png")
    val home = PetPageProbes.home
    val room = PetPageProbes.room
    val healthPanel = PetPageProbes.healthPanel
    val cookie = PetPageProbes.cookie
    /** FOOD page IDENTITY: the stable floor/cushion scene region, never the cookie plate. */
    val foodScene = PetPageProbes.foodScene
    val foodExchangeBubble = PetPageProbes.foodExchangeBubble
    val bathExchangeBubble = PetPageProbes.bathExchangeBubble
    val soap = PetPageProbes.soap
    /** Bath page IDENTITY: stable scene region, never the pale-repainting soap button. */
    val bath = PetPageProbes.bath
    val outdoor = PetPageProbes.outdoor
    val academy = PetPageProbes.academy
    /** QQ HOST page: proof that the pet mini-program is gone and QQ's search page is showing. */
    val qqHostSearch = PetPageProbes.qqHostSearch
    val highFive = PetPageProbes.highFive
    val gradeA = PetPageProbes.gradeA
    val upgrade = PetPageProbes.upgrade
    val careUpgradeHint = PetPageProbes.careUpgradeHint
    val careUpgradeReward = PetPageProbes.careUpgradeReward
    val recovery=PetRecoveryBoundary(context,capture,guard,
        (listOf(home,room,healthPanel,cookie,foodScene,soap,bath,outdoor,academy,highFive,profile.resultProbe,
            profile.courseListProbe,profile.courseDetailProbe,qqHostSearch)).associate {it.name to listOf(it)}+
            mapOf("LEARNING" to profile.learningProbes),onState=onRecovery,reconcile=reconcile,
            // Recovery around a lesson start must NOT re-impose the care thresholds: a paid-for lesson
            // is gated by course/tuition/minutes, so this only proves a health value was actually READ
            // (no Unknown/Stale masquerading as data). A low value is fine; an unreadable one is not
            // allowed to authorise anything either, but it can no longer block the lesson itself.
            validateHealth={health,at -> health.current(at);Unit},learningEvidence=learningEvidence)
    suspend fun safety() {
        // START_COURSE used to mark health as required, which turned every app restart or capture
        // hiccup during the lesson-start window into RECOVERY_HEALTH_UNCONFIRMED and stopped a lesson
        // the pet could pay for. The lesson's inputs are idempotent-guarded by the flow itself (one
        // tap, then confirm), so recovery only needs the PAGE to match.
        recovery.boundary(round,state,false)
        recovery.beforeBoundary()
        guard()
        check(!(context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isKeyguardLocked) { "PHONE_LOCKED" }
    }
    fun emit(action: String, score: Double? = null, result: String = "PASS", reason: String? = null) {
        onObservation(state, score)
        logger(state, score, "round-$round:$action", result, reason)
        if(reason!=null) capture.facts.events.append(EventSource.APP_AUTOMATION,EventKind.ERROR,
            sessionErrorEventPayload(state, action, result, reason),listOf(evidence.absolutePath))
    }
    fun save(frame: Bitmap, name: String) {
        evidence.mkdirs()
        File(evidence, "round-$round-$name.png").outputStream().use { check(frame.compress(Bitmap.CompressFormat.PNG,100,it)) }
    }
    suspend fun frame(latest: Boolean = false): Bitmap {
        safety()
        return try {
            val f = recovery.current(latest)
            emit("FRAME", result=capture.telemetry().toString())
            f
        } catch (t: TimeoutCancellationException) {
            throw IllegalStateException("CAPTURE_TIMEOUT:${t.message}; ${capture.diagnostics()}", t)
        }
    }
    fun score(frame: Bitmap, probes: List<PetProfile.Probe>) =
        probes.maxOf { matcher.match(frame,it,profile.matchThreshold).score }
    suspend fun verify(probes: List<PetProfile.Probe>, name: String, saveEvidence: Boolean = true, latest: Boolean = false): Double {
        val f = frame(latest)
        try {
            if(saveEvidence) save(f,name)
            val matches = probes.map { it to matcher.match(f,it,profile.matchThreshold).score }
            val best = matches.maxBy { it.second }
            val s = best.second
            emit(name,s,capture.telemetry().put("action",name).put("templateName",best.first.name)
                .put("matchScore",s).put("result",if(s>=profile.matchThreshold) "PASS" else "FAIL").toString())
            check(s>=profile.matchThreshold) { "PAGE_MISMATCH:$state:$name:$s" }
            recovery.confirm(round,state,if(best.first in profile.learningProbes) "LEARNING" else best.first.name,false)
            return s
        } finally { f.recycle() }
    }
    suspend fun verifySettled(probes: List<PetProfile.Probe>, name: String): Double {
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
                if(matched) {
                    recovery.confirm(round,state,if(best.first in profile.learningProbes) "LEARNING" else best.first.name,false)
                    save(current,name);return lastScore
                }
            } finally {current.recycle()}
            delay(300)
        }
        error("PAGE_MISMATCH:$state:$name:$lastScore")
    }
    /**
     * One freshly rendered frame, positively matched before any input is based on it.
     * The live learning panel repaints during tap animations and countdown ticks, so one
     * transient miss must not abort a course that already started. The wait is bounded and
     * an unmatched frame is never returned, so no input is ever sent on an unconfirmed page.
     * Only new frames are accepted: a cached bitmap can show a page that has since changed.
     */
    suspend fun settleLatest(probes: List<PetProfile.Probe>, name: String, saveEvidenceAs: String? = null): Bitmap {
        val deadline = SystemClock.elapsedRealtime() + 8000
        var lastScore = 0.0
        while (SystemClock.elapsedRealtime() < deadline) {
            val current = frame(false)
            var matched = false
            try {
                val best = probes.map { it to matcher.match(current,it,profile.matchThreshold).score }.maxBy { it.second }
                lastScore = best.second
                matched = lastScore >= profile.matchThreshold
                emit(name,lastScore,capture.telemetry().put("templateName",best.first.name)
                    .put("capturePath","FRESH").put("result",if(matched) "PASS" else "SETTLING").toString())
                if (matched) {
                    if (saveEvidenceAs != null) save(current,saveEvidenceAs)
                    recovery.confirm(round,state,"LEARNING")
                    return current
                }
            } catch (t: Throwable) { current.recycle(); throw t }
            current.recycle()
            delay(300)
        }
        error("PAGE_MISMATCH:$state:$name:$lastScore")
    }
    fun healthJson(h: PetCareReading) = JSONObject().put("stamina",h.energy)
        .put("cleanliness",h.cleanliness)
        .put("mood",JSONObject(h.observations.mood.fields()))
        .put("observations",JSONObject(h.observations.fields()))
}
