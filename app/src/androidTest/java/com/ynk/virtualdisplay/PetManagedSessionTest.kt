package com.ynk.virtualdisplay

import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ynk.virtualdisplay.automation.*
import com.ynk.virtualdisplay.data.repository.ConnectionStatus
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import android.content.Intent
import android.os.SystemClock

@RunWith(AndroidJUnit4::class)
class PetManagedSessionTest {
    @Test fun authorizedThreeRoundServiceAcceptance():Unit=runBlocking {
        val args=InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("managed_authorized")=="yes")
        require(args.getString("rounds")=="3")
        val inst=InstrumentationRegistry.getInstrumentation()
        val app=inst.targetContext.applicationContext as MyApplication
        val dir=File(app.getExternalFilesDir(null),"pet_managed_acceptance/${System.currentTimeMillis()}").apply {mkdirs()}
        fun shell(cmd:String)=inst.uiAutomation.executeShellCommand(cmd).use {
            ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().readText()
        }
        shell("am start --display 0 -f 0x18000000 -n com.ynk.virtualdisplay/.automation.GateTestActivity")
        withTimeout(5000) {while(!GateTestActivity.touches.containsKey(0)) delay(100)}
        val physical=GateTestActivity.touches.getValue(0).get()
        var currentId:String?=null;var previous="";val states=mutableListOf<String>()
        withContext(Dispatchers.Main) {app.startForegroundService(PetAutomationService.startIntent(app,4,3))}
        try {
            withTimeout(15_000L) {
                while (PetManagedRuntime.state.value.sessionId == null) {
                    val startup = PetRuntime.state.value
                    check(!startup.status.startsWith("会话异常")) { startup.status }
                    delay(100)
                }
            }
            withTimeout(40*60*1000L) {
                while(true) {
                    val state=PetManagedRuntime.state.value
                    if(state.sessionId!=null && state.runState==SessionRunState.RUNNING && currentId==null) currentId=state.sessionId
                    if(currentId!=null && state.sessionId==currentId) {
                        if(previous!=state.runState.name) {states.add(state.runState.name);previous=state.runState.name}
                        File(dir,"status.json").writeText(JSONObject().put("sessionId",currentId)
                            .put("state",state.runState.name).put("step",state.currentStep).put("round",state.round)
                            .put("completed",state.completedRounds).put("nextStep",state.nextStep).put("reason",state.pauseReason)
                            .put("physicalDelta",GateTestActivity.touches.getValue(0).get()-physical).toString())
                        check(GateTestActivity.touches.getValue(0).get()==physical) {"PHYSICAL_TOUCH_DETECTED"}
                        if(state.runState==SessionRunState.STOPPED) {
                            assertEquals("Managed session stopped: ${state.pauseReason}",3,state.completedRounds)
                            assertTrue("A real pause/resume is required",states.containsAll(listOf("PAUSED","RESUMING","RUNNING")))
                            break
                        }
                    }
                    delay(250)
                }
            }
            val events=File(app.filesDir,"pet-events/$currentId.jsonl").readLines().map {JSONObject(it)}
            val starts=events.filter {it.getString("kind")=="COURSE_STARTED"}
            assertEquals(3,starts.size)
            assertEquals(setOf(1,2,3),starts.map {it.getJSONObject("payload").getInt("round")}.toSet())
            val inputs=events.filter {it.getString("kind")=="INPUT_SENT"}
            assertTrue(inputs.all {it.getString("source")=="APP_AUTOMATION" && it.getJSONObject("payload").getInt("displayId")==4})
            val firstOwnedStart=starts.minOf {it.getLong("sequence")}
            fun taps(x:Double,y:Double)=inputs.count {val p=it.getJSONObject("payload")
                it.getLong("sequence")>firstOwnedStart &&
                p.getString("action")=="TAP" && p.getDouble("x")==x && p.getDouble("y")==y}
            assertEquals("All encouragement must be App-sent",30,taps(325.0,375.0))
            assertEquals("Each owned result acknowledged once",3,taps(530.0,1488.0))
            var paused=false
            for(event in events) {
                if(event.getString("kind")=="SESSION_STATE") {
                    val s=event.getJSONObject("payload").optString("state")
                    if(s=="PAUSED" || s=="RESUMING") paused=true
                    if(s=="RUNNING" || s=="STOPPED") paused=false
                }
                if(paused) assertNotEquals("INPUT_SENT",event.getString("kind"))
            }
            File(dir,"verdict.json").writeText(JSONObject().put("verdict","PASS").put("sessionId",currentId)
                .put("states",org.json.JSONArray(states)).put("completed",3).put("physicalDelta",0)
                .put("manualAdbInputs",0).put("courseStarts",3).put("appEncouragementInputs",30)
                .put("resultAcknowledgements",3).toString())
            File(app.filesDir,"pet-events/$currentId.jsonl").copyTo(File(dir,"session-events.jsonl"),true)
            println("MANAGED_ACCEPTANCE $dir")
        } catch(t:Throwable) {
            File(dir,"verdict.json").writeText(JSONObject().put("verdict","STOPPED").put("sessionId",currentId)
                .put("error",t.toString()).put("states",org.json.JSONArray(states)).toString())
            app.startService(PetAutomationService.stopIntent(app))
            throw t
        }
    }
    @Test fun extractedCareProbe(): Unit=runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("care_probe")=="yes")
        val inst=InstrumentationRegistry.getInstrumentation()
        val app=inst.targetContext.applicationContext as MyApplication
        val repo=app.displayRepository
        withContext(Dispatchers.Main) {app.acquireDisplayRepository("ManagedCareProbe")}
        val capture=HeadlessDisplayCapture(repo,4,720,1600)
        var flow:PetFlowContext?=null
        val dir=File(app.getExternalFilesDir(null),"pet_managed_probe/${System.currentTimeMillis()}").apply {mkdirs()}
        fun shell(cmd:String)=inst.uiAutomation.executeShellCommand(cmd).use {
            ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().readText()
        }
        try {
            withTimeout(15000) {repo.connectionStatus.first {it==ConnectionStatus.CONNECTED}}
            shell("am start --display 0 -f 0x18000000 -n com.ynk.virtualdisplay/.automation.GateTestActivity")
            withTimeout(5000) {while(!GateTestActivity.touches.containsKey(0)) delay(100)}
            val physical=GateTestActivity.touches.getValue(0).get()
            fun guard() {
                check(4 in repo.managedDisplayIds.value)
                check(repo.connectionStatus.value==ConnectionStatus.CONNECTED)
                val target=shell("dumpsys activity activities").substringAfter("Display: mDisplayId=4 ","")
                    .substringBefore("Display: mDisplayId=")
                check(target.contains("com.tencent.mobileqq")) {"QQ_NOT_ON_TARGET_DISPLAY"}
                check(GateTestActivity.touches.getValue(0).get()==physical)
            }
            capture.start()
            val f=PetFlowContext(app,capture,PetInputController(repo,4,720,1600,capture.facts),dir,::guard,
                {state,score,action,result,reason -> File(dir,"trace.jsonl").appendText(JSONObject()
                    .put("state",state).put("score",score).put("action",action).put("result",result)
                    .put("reason",reason).toString()+"\n")})
            flow=f
            f.verify(listOf(f.room),"initial-home")
            val care=PetCareFlow(f,PetNavigation(f))
            val health=care.readHealth("care-probe")
            assertTrue(health.observations!!.energy is ObservedValue.Known)
            assertEquals(0,GateTestActivity.touches.getValue(0).get()-physical)
            File(dir,"verdict.json").writeText(JSONObject().put("verdict","PASS")
                .put("health",JSONObject(health.observations.fields())).put("physicalDelta",0)
                .put("gameInputs",2).put("consumablesUsed",0).toString())
            println("MANAGED_CARE_PROBE $dir")
        } finally {
            flow?.recovery?.recovery?.finish("CARE_PROBE_ENDED")
            capture.stop()
            withContext(Dispatchers.Main) {app.releaseDisplayRepository("ManagedCareProbe")}
        }
    }
}
