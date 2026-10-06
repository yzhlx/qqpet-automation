package com.ynk.virtualdisplay

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ynk.virtualdisplay.automation.*
import com.ynk.virtualdisplay.data.repository.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Read-only observation Session using the production recovery boundary; no game input. */
@RunWith(AndroidJUnit4::class)
class PetRecoveryGateTest {
    @Test fun checkpointRoundTrip() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val file=File(context.cacheDir,"checkpoint-roundtrip.json")
        val store=FileCheckpointStore(file)
        val known=ObservedValue.Known(94,1000L,0.99,"frame",ObservationSource.DIGIT_RECOGNITION)
        val health=HealthObservations(known,ObservedValue.Unknown("ambiguous",1001,"frame"),
            ObservedValue.Stale(known,70000,"age"),known,known,ObservedValue.Unsupported("not calibrated"),false,"frame")
        val expected=SessionCheckpoint("test-session",2,"WAIT_RESULT","LEARNING",health,1002,true,true)
        store.save(expected);assertEquals(expected,store.load())
        store.save(expected.copy(round=3));assertEquals(3,store.load()!!.round)
        file.writeText("invalid");assertTrue(runCatching {store.load()}.isFailure)
        file.delete()
    }

    @Test fun faultGate(): Unit=runBlocking {
        val args=InstrumentationRegistry.getArguments()
        val mode=args.getString("recovery_gate") ?: error("RECOVERY_GATE_REQUIRED")
        require(mode in setOf("capture","mismatch","lock"))
        val id=args.getString("display_id")!!.toInt();require(id==4)
        val inst=InstrumentationRegistry.getInstrumentation()
        val app=inst.targetContext.applicationContext as MyApplication
        val original=app.displayRepository
        var broken=false;var wrongPage=false;var captureRequests=0
        val repository=object: IDisplayRepository by original {
            override suspend fun captureDisplay(displayId:Int): Result<ActiveCapturedFrame> {
                captureRequests++
                if(broken) return Result.failure(IllegalStateException("INJECTED_CAPTURE_FAILURE"))
                if(wrongPage) {
                    val bitmap=inst.context.assets.open("health_reading/high.png").use {BitmapFactory.decodeStream(it)}
                    return Result.success(ActiveCapturedFrame(bitmap,id,1,0,0,"TEST_PAGE_FIXTURE",2000))
                }
                return original.captureDisplay(displayId)
            }
        }
        val dir=File(app.getExternalFilesDir(null),"pet_recovery_gate/${System.currentTimeMillis()}").apply {mkdirs()}
        fun status(value:String) {File(dir,"status.json").writeText(JSONObject().put("gate",mode)
            .put("status",value).put("timestamp",System.currentTimeMillis()).toString());println("RECOVERY_GATE $dir $value")}
        fun shell(command:String)=inst.uiAutomation.executeShellCommand(command).use {
            ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().readText()
        }
        fun guard() {
            check(original.connectionStatus.value==ConnectionStatus.CONNECTED) {"DISPLAY_DISCONNECTED"}
            check(id in original.managedDisplayIds.value) {"DISPLAY_UNAVAILABLE"}
            val section=shell("dumpsys activity activities").substringAfter("Display: mDisplayId=$id ","")
                .substringBefore("Display: mDisplayId=")
            check(section.contains("com.tencent.mobileqq")) {"QQ_NOT_ON_TARGET_DISPLAY"}
        }
        val capture=HeadlessDisplayCapture(repository,id,720,1600)
        val states=mutableListOf<String>()
        val room=PetProfile.Probe("HOME",Rect(320,1340,405,1465),"pet_experimental/room.png")
        val boundary=PetRecoveryBoundary(app,capture,::guard,mapOf("HOME" to listOf(room)),0) {state,reason,attempt ->
            states.add(state.name);status("${state.name}:$reason:$attempt")
            if(state==SessionRunState.PAUSED || state==SessionRunState.RESUMING)
                assertTrue(runCatching {capture.facts.requireInput()}.isFailure)
            if(state==SessionRunState.PAUSED && attempt==1 && mode!="lock") {
                broken=false;wrongPage=mode=="mismatch"
            }
        }
        var physical=0
        withContext(Dispatchers.Main) {app.acquireDisplayRepository("PetRecoveryGate")}
        try {
            withTimeout(15000) {original.connectionStatus.first {it==ConnectionStatus.CONNECTED}}
            shell("am start --display 0 -f 0x18000000 -n com.ynk.virtualdisplay/.automation.GateTestActivity")
            withTimeout(5000) {while(!GateTestActivity.touches.containsKey(0)) delay(100)}
            physical=GateTestActivity.touches.getValue(0).get()
            capture.start()
            repeat(2) {n ->
                val frame=boundary.current()
                try {
                    val score=TemplateMatcher(app).match(frame,room,0.97)
                    check(score.matched) {"INITIAL_PAGE_UNKNOWN:${score.score}"}
                    File(dir,"before-$n.png").outputStream().use {frame.compress(Bitmap.CompressFormat.PNG,100,it)}
                    boundary.confirm(1,"OBSERVE","HOME")
                } finally {frame.recycle()}
            }
            val checkpoint=boundary.recovery.checkpoint!!
            if(mode=="lock") {
                status("READY_FOR_MANUAL_LOCK")
                withTimeout(90000) {while(!boundary.locked()) delay(100)}
                capture.facts.events.append(EventSource.USER,EventKind.SESSION_STATE,mapOf("action" to "MANUAL_LOCK_OBSERVED"))
            } else broken=true
            val result=runCatching {boundary.current()}
            if(mode=="mismatch") {
                assertTrue(result.exceptionOrNull()!!.message!!.startsWith("RECOVERY_PAGE_MISMATCH"))
                assertEquals(SessionRunState.STOPPED,boundary.recovery.state)
                assertTrue(runCatching {capture.facts.requireInput()}.isFailure)
            } else {
                val frame=result.getOrThrow()
                try {
                    assertTrue(TemplateMatcher(app).match(frame,room,0.97).matched)
                    File(dir,"after.png").outputStream().use {frame.compress(Bitmap.CompressFormat.PNG,100,it)}
                } finally {frame.recycle()}
                assertEquals(SessionRunState.RUNNING,boundary.recovery.state)
                assertEquals(checkpoint.sessionId,boundary.recovery.checkpoint!!.sessionId)
                assertEquals(checkpoint.round,boundary.recovery.checkpoint!!.round)
                assertEquals(checkpoint.phase,boundary.recovery.checkpoint!!.phase)
                capture.facts.requireInput()
                assertTrue(states.containsAll(listOf("PAUSED","RESUMING","RUNNING")))
                if(mode=="lock") capture.facts.events.append(EventSource.USER,EventKind.SESSION_STATE,
                    mapOf("action" to "MANUAL_UNLOCK_OBSERVED"))
            }
            guard()
            val delta=GateTestActivity.touches.getValue(0).get()-physical
            assertEquals(0,delta)
            val events=File(app.filesDir,"pet-events/${capture.facts.events.sessionId}.jsonl")
            check(!events.readText().contains("\"kind\":\"INPUT_SENT\""))
            events.copyTo(File(dir,"session-events.jsonl"),true)
            boundary.file.copyTo(File(dir,"checkpoint.json"),true)
            File(dir,"verdict.json").writeText(JSONObject().put("verdict","PASS").put("gate",mode)
                .put("states",states).put("physicalDelta",delta).put("appInputs",0)
                .put("displayId",id).put("captureRequests",captureRequests).toString())
            status("PASS")
        } catch(t:Throwable) {
            File(dir,"verdict.json").writeText(JSONObject().put("verdict","STOPPED").put("gate",mode)
                .put("states",states).put("error",t.toString()).toString());status("STOPPED:${t.message}");throw t
        } finally {
            capture.stop()
            withContext(Dispatchers.Main) {app.releaseDisplayRepository("PetRecoveryGate")}
        }
    }
}
