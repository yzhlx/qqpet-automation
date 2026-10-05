package com.ynk.virtualdisplay

import android.app.KeyguardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ynk.virtualdisplay.automation.*
import com.ynk.virtualdisplay.data.repository.ConnectionStatus
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Phone-local Shizuku capture gate. No courses; only opt-in calibrated health navigation. */
@RunWith(AndroidJUnit4::class)
class PetActiveCaptureTest {
    @Test fun captureGate(): Unit = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val mode = args.getString("capture_gate") ?: error("CAPTURE_GATE_REQUIRED")
        require(mode in setOf("static", "transition", "hybrid"))
        val id = args.getString("display_id")!!.toInt(); require(id == 4)
        val inst = InstrumentationRegistry.getInstrumentation()
        val app = inst.targetContext.applicationContext as MyApplication
        val repo = app.displayRepository
        var hybrid: HeadlessDisplayCapture? = null
        val dir = File(app.getExternalFilesDir(null), "pet_capture_gate/${System.currentTimeMillis()}").apply { mkdirs() }
        val events = File(dir, "events.jsonl")
        val durations = mutableListOf<Long>()
        var count = 0
        var physical = 0
        val matcher = TemplateMatcher(app)
        val room = PetProfile.Probe("room", Rect(320,1340,405,1465), "pet_experimental/room.png")
        val health = PetProfile.Probe("health", Rect(169,228,250,415), "pet_experimental/health.png")
        fun shell(command: String) = inst.uiAutomation.executeShellCommand(command).use {
            ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().readText()
        }
        fun guard(input: Boolean) {
            check(repo.managedDisplayIds.value.contains(id)) { "DISPLAY_DISAPPEARED" }
            check(repo.connectionStatus.value == ConnectionStatus.CONNECTED) { "DISPLAY_DISCONNECTED" }
            val section = shell("dumpsys activity activities").substringAfter("Display: mDisplayId=$id ", "")
                .substringBefore("Display: mDisplayId=")
            check(section.contains("com.tencent.mobileqq")) { "QQ_NOT_ON_TARGET_DISPLAY" }
            check(GateTestActivity.touches.getValue(0).get() == physical) { "PHYSICAL_TOUCH_DETECTED" }
            if (input) check(!(app.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isKeyguardLocked) { "PHONE_LOCKED" }
        }
        suspend fun capture(name: String, expected: String) {
            guard(false)
            val frame = repo.captureDisplay(id).getOrThrow()
            try {
                assertEquals(720, frame.bitmap.width); assertEquals(1600, frame.bitmap.height)
                val rs = matcher.match(frame.bitmap, room, 0.97).score
                val hs = matcher.match(frame.bitmap, health, 0.97).score
                val valid = if (expected == "HEALTH") hs >= 0.97 else rs >= 0.97 && hs < 0.97
                val score = if (expected == "HEALTH") hs else rs
                File(dir, "$name.png").outputStream().use { frame.bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
                events.appendText(JSONObject().put("timestamp", System.currentTimeMillis()).put("action", name)
                    .put("displayId", id).put("source", frame.source).put("uid", frame.uid)
                    .put("width", frame.bitmap.width).put("height", frame.bitmap.height)
                    .put("byte_size", frame.byteCount).put("duration_ms", frame.durationMs)
                    .put("captureDurationMs", frame.captureDurationMs).put("templateName", expected)
                    .put("template_score", score).put("health_score", hs)
                    .put("result", if(valid) "PASS" else "FAIL").toString() + "\n")
                check(valid) { "CAPTURE_TEMPLATE_MISMATCH:$expected:room=$rs,health=$hs" }
                durations.add(frame.durationMs); count++
            } finally { frame.bitmap.recycle() }
        }
        withContext(Dispatchers.Main) { app.acquireDisplayRepository("PetCaptureGate") }
        try {
            withTimeout(15000) { repo.connectionStatus.first { it == ConnectionStatus.CONNECTED } }
            shell("am start --display 0 -f 0x18000000 -n com.ynk.virtualdisplay/.automation.GateTestActivity")
            withTimeout(5000) { while (!GateTestActivity.touches.containsKey(0)) delay(100) }
            physical = GateTestActivity.touches.getValue(0).get()
            assertTrue(runCatching { repo.captureDisplay(0).getOrThrow() }.isFailure)
            if (mode == "static") repeat(10) { capture("static-$it", "HOME"); delay(150) }
            else if (mode == "hybrid") {
                check(args.getString("capture_navigation_authorized") == "yes") { "NAVIGATION_AUTH_REQUIRED" }
                val c = HeadlessDisplayCapture(repo,id,720,1600); hybrid = c; c.start()
                val input = PetInputController(repo,id,720,1600)
                suspend fun forcedAfter(name: String, expected: String) {
                    val began = SystemClock.elapsedRealtime()
                    val f = c.awaitFrame(0) // Deterministic ImageReader timeout, never a stale-cache PASS.
                    try {
                        val hs=matcher.match(f,health,0.97).score
                        val rs=matcher.match(f,room,0.97).score
                        val valid=if(expected=="HEALTH") hs>=0.97 else rs>=0.97 && hs<0.97
                        val meta=c.telemetry().put("action",name).put("templateName",expected)
                            .put("matchScore",if(expected=="HEALTH")hs else rs).put("result",if(valid)"PASS" else "FAIL")
                        events.appendText(meta.toString()+"\n")
                        File(dir,"$name.png").outputStream().use { f.compress(Bitmap.CompressFormat.PNG,100,it) }
                        assertEquals("TIMEOUT",meta.getString("imageReaderResult"))
                        assertEquals("SHIZUKU_FALLBACK",meta.getString("captureSource"))
                        assertTrue(meta.getBoolean("frameDeliveryMiss")); check(valid) { "HYBRID_PAGE_MISMATCH" }
                        durations.add(SystemClock.elapsedRealtime()-began); count++
                    } finally { f.recycle() }
                }
                capture("hybrid-open-before","HOME")
                guard(true);input.tap(516f,160f);delay(900);forcedAfter("hybrid-open-after","HEALTH")
                capture("hybrid-close-before","HEALTH")
                guard(true);input.tap(355f,454f);delay(900);forcedAfter("hybrid-close-after","HOME")
                assertEquals(2,c.statistics().getInt("shizukuFallbackSuccess"))
            }
            else {
                check(args.getString("capture_navigation_authorized") == "yes") { "NAVIGATION_AUTH_REQUIRED" }
                val input = PetInputController(repo,id,720,1600)
                repeat(3) { n ->
                    capture("$n-open-before", "HOME")
                    guard(true); input.tap(516f,160f); delay(900)
                    capture("$n-open-after", "HEALTH")
                    capture("$n-close-before", "HEALTH")
                    guard(true); input.tap(355f,454f); delay(900)
                    capture("$n-close-after", "HOME")
                }
            }
            guard(false)
            val sorted = durations.sorted()
            val verdict = JSONObject().put("verdict", "SHIZUKU_CAPTURE_PASS").put("gate", mode)
                .put("success", count).put("total", when(mode){"static"->10;"hybrid"->4;else->12})
                .put("transitionActions", when(mode){"transition"->6;"hybrid"->2;else->0}).put("displayId",id)
                .put("physicalDelta", GateTestActivity.touches.getValue(0).get()-physical)
                .put("captureStatistics",hybrid?.statistics()?:JSONObject.NULL)
                .put("p50_ms", sorted[(sorted.size-1)/2]).put("p95_ms", sorted.last())
            File(dir,"verdict.json").writeText(verdict.toString())
            println("CAPTURE_GATE $dir $verdict")
        } catch(t: Throwable) {
            File(dir,"verdict.json").writeText(JSONObject().put("verdict","SHIZUKU_CAPTURE_NOT_VIABLE")
                .put("gate",mode).put("success",count).put("error",t.toString()).toString())
            throw t
        } finally {
            hybrid?.stop()
            withContext(Dispatchers.Main) { app.releaseDisplayRepository("PetCaptureGate") }
        }
    }
}
