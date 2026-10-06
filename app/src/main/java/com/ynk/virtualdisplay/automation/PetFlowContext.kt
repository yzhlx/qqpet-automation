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
    val reconcile: (SessionCheckpoint,String)->Boolean={_,_->false}) {
    val profile=PetProfile.calibrated()
    val matcher=TemplateMatcher(context)
    var round=0
    var state="OBSERVE"
    var serial=0
    fun probe(name: String, l: Int, t: Int, r: Int, b: Int) =
        PetProfile.Probe(name, Rect(l,t,r,b), "pet_experimental/$name.png")
    val home = probe("home",600,130,670,190)
    val room = probe("room",320,1340,405,1465)
    val healthPanel = probe("health",169,228,250,415)
    val cookie = probe("cookie",280,1255,440,1360)
    val soap = PetProfile.Probe("soap",Rect(280,1280,440,1400),"pet_mvp/soap.png")
    val outdoor = probe("outdoor",126,610,275,663)
    val academy = probe("academy",330,158,346,177)
    val highFive = probe("high_five",284,344,355,400)
    val gradeA = probe("grade_a",545,385,695,525)
    val upgrade = probe("upgrade",245,558,480,610)
    val recovery=PetRecoveryBoundary(context,capture,guard,
        (listOf(home,room,healthPanel,cookie,soap,outdoor,academy,highFive,profile.resultProbe,
            profile.courseListProbe,profile.courseDetailProbe)).associate {it.name to listOf(it)}+
            mapOf("LEARNING" to profile.learningProbes),onState=onRecovery,reconcile=reconcile,
            validateHealth=PetStudyHealthPolicy::requireReady)
    suspend fun safety() {
        recovery.boundary(round,state,state=="START_COURSE")
        recovery.beforeBoundary()
        guard()
        check(!(context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isKeyguardLocked) { "PHONE_LOCKED" }
    }
    fun emit(action: String, score: Double? = null, result: String = "PASS", reason: String? = null) {
        onObservation(state, score)
        logger(state, score, "round-$round:$action", result, reason)
        if(reason!=null) capture.facts.events.append(EventSource.APP_AUTOMATION,EventKind.ERROR,
            mapOf("state" to state,"action" to action,"reason" to reason),listOf(evidence.absolutePath))
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
            recovery.confirm(round,state,if(best.first in profile.learningProbes) "LEARNING" else best.first.name,state=="START_COURSE")
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
                    recovery.confirm(round,state,if(best.first in profile.learningProbes) "LEARNING" else best.first.name,state=="START_COURSE")
                    save(current,name);return lastScore
                }
            } finally {current.recycle()}
            delay(300)
        }
        error("PAGE_MISMATCH:$state:$name:$lastScore")
    }
    fun healthJson(h: PetCareReading) = JSONObject().put("stamina",h.energy)
        .put("cleanliness",h.cleanliness)
        .put("mood",JSONObject(h.observations.mood.at(System.currentTimeMillis(),60000).fields()))
        .put("observations",JSONObject(h.observations.fields()))
}
