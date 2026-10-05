package com.ynk.virtualdisplay

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ynk.virtualdisplay.automation.*
import com.ynk.virtualdisplay.data.repository.ConnectionStatus
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.PowerManager
import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Explicit debug opt-in. Calibration captures never start a course. */
@RunWith(AndroidJUnit4::class)
class PetExperimentalSessionTest {
    @Test fun captureOrAuthorizedExperimentalSession(): Unit = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("experimental_authorized") == "yes")
        val id = args.getString("display_id")!!.toInt()
        require(id > 0)
        val instrument = InstrumentationRegistry.getInstrumentation()
        val app = instrument.targetContext.applicationContext as MyApplication
        val repo = app.displayRepository
        withContext(Dispatchers.Main) { app.acquireDisplayRepository("PetExperimentalTest") }
        var capture: HeadlessDisplayCapture? = null
        try {
            withTimeout(15000) { repo.connectionStatus.first { it == ConnectionStatus.CONNECTED } }
            instrument.uiAutomation.executeShellCommand(
                "am start --display 0 -f 0x18000000 -n com.ynk.virtualdisplay/.automation.GateTestActivity"
            ).use { ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().readText() }
            withTimeout(5000) { while (!GateTestActivity.touches.containsKey(0)) delay(100) }
            val physical = GateTestActivity.touches.getValue(0).get()
            val c = HeadlessDisplayCapture(repo, id, 720, 1600)
            capture = c; c.start()
            val dir = File(app.getExternalFilesDir(null), "pet_experimental_calibration").apply { mkdirs() }
            suspend fun save(name: String) {
                val frame = c.awaitFrame()
                try { File(dir, name).outputStream().use { frame.compress(Bitmap.CompressFormat.PNG, 100, it) } }
                finally { frame.recycle() }
            }
            if(args.getString("mode")!="session") save("before.png")
            if(args.getString("mode")=="session") {
                require(args.getString("rounds")=="3") { "Only explicit three-round experiment allowed" }
                val dm=app.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
                fun guard() {
                    check(Build.MODEL=="FOA-AL00") { "DEVICE_MODEL_CHANGED:${Build.MODEL}" }
                    val qq=app.packageManager.getPackageInfo("com.tencent.mobileqq",0)
                    check(qq.versionName=="9.3.70" && qq.longVersionCode==16410L) { "QQ_VERSION_CHANGED" }
                    check(repo.managedDisplayIds.value.contains(id)) { "DISPLAY_NOT_MANAGED" }
                    check(repo.connectionStatus.value==ConnectionStatus.CONNECTED) { "DISPLAY_DISCONNECTED" }
                    val d=dm.getDisplay(id)?:error("DISPLAY_DISAPPEARED")
                    val p=Point();d.getRealSize(p)
                    val metrics=android.util.DisplayMetrics();d.getRealMetrics(metrics)
                    check(p.x==720 && p.y==1600 && metrics.densityDpi==320) { "DISPLAY_CONFIGURATION_CHANGED" }
                    check(GateTestActivity.touches.getValue(0).get()==physical) { "PHYSICAL_TOUCH_DETECTED" }
                    val dump=instrument.uiAutomation.executeShellCommand("dumpsys activity activities").use {
                        ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().readText()
                    }
                    val section=dump.substringAfter("Display: mDisplayId=$id ","").substringBefore("Display: mDisplayId=")
                    check(section.contains("com.tencent.mobileqq")) { "QQ_NOT_ON_TARGET_DISPLAY" }
                }
                val runDir=File(app.getExternalFilesDir(null),"pet_experimental/${System.currentTimeMillis()}").apply {mkdirs()}
                val logFile=File(app.filesDir,"pet_mvp.log")
                fun log(state:String,score:Double?,action:String,result:String,reason:String?) {
                    val data=JSONObject().put("timestamp",SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS",Locale.US).format(Date()))
                        .put("state",state).put("matchScore",score?:JSONObject.NULL).put("action",action)
                        .put("result",if(result.startsWith("{")) JSONObject(result) else result).put("stopReason",reason?:JSONObject.NULL)
                    logFile.appendText("$data\n");Log.i("PetExperimental",data.toString())
                    File(runDir,"events.jsonl").appendText("$data\n")
                }
                val pm=app.getSystemService(Context.POWER_SERVICE) as PowerManager
                val lock=pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"${app.packageName}:pet-experimental")
                lock.acquire(40*60*1000L)
                try {
                    val resumeId=args.getString("resume_run")
                    val resumeRecord=if(resumeId!=null) {
                        require(resumeId in setOf("1791203355328","1791204208048","1791204552957")) { "Only the verified upgrade-interrupted round may resume" }
                        val prior=File(app.getExternalFilesDir(null),"pet_experimental/$resumeId")
                        val priorVerdict=JSONObject(File(prior,"verdict.json").readText())
                        check(priorVerdict.getString("verdict")=="STOPPED" && priorVerdict.getInt("completed") in 0..1)
                        val priorEvents=File(prior,"events.jsonl").readLines().map {JSONObject(it)}
                        val saved=if(priorVerdict.getInt("completed")==1 && resumeId!="1791204552957")
                            priorEvents.last {it.getString("action").endsWith(":ROUND_COMPLETE")}
                        else priorEvents.last {it.getString("action").endsWith(":SESSION_STOP")}
                        saved.getJSONObject("result").put("resume_run",resumeId)
                    } else null
                    val session=PetExperimentalSession(app,c,PetInputController(repo,id,720,1600,c.facts),runDir,::guard,::log,resumeRecord)
                    val verdict=session.run()
                    File(runDir,"verdict.json").writeText(JSONObject().put("verdict",verdict).put("completed",session.completed)
                        .put("stopReason",session.stopReason?:JSONObject.NULL).put("physicalDelta",GateTestActivity.touches.getValue(0).get()-physical)
                        .put("captureStatistics",c.statistics()).toString())
                    assertEquals("Experimental session stopped: ${session.stopReason}","3_ROUNDS_PASS",verdict)
                } finally {if(lock.isHeld)lock.release()}
            }
            if (args.getString("mode") == "tap") {
                val r = args.getString("roi")!!.split(',').map { it.toInt() }
                val frame = c.awaitFrame()
                try {
                    val p = PetProfile.Probe("calibration", Rect(r[0],r[1],r[2],r[3]), args.getString("asset")!!)
                    val m = TemplateMatcher(app).match(frame, p, 0.97)
                    assertTrue("CALIBRATION_PAGE_MISMATCH ${m.score}", m.matched)
                } finally { frame.recycle() }
                PetInputController(repo,id,720,1600,c.facts).tap(args.getString("x")!!.toFloat(),args.getString("y")!!.toFloat())
                delay(1200); save("after.png")
            }
            assertEquals("Physical screen received input",physical,GateTestActivity.touches.getValue(0).get())
        } finally {
            capture?.stop()
            withContext(Dispatchers.Main) { app.releaseDisplayRepository("PetExperimentalTest") }
        }
    }
}



