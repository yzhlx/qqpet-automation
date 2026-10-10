package com.ynk.virtualdisplay

import android.graphics.PointF
import android.graphics.Bitmap
import android.graphics.Color
import android.os.SystemClock
import android.view.Surface
import android.view.InputEvent
import android.view.MotionEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ynk.virtualdisplay.automation.*
import com.ynk.virtualdisplay.data.repository.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** No real input. The real controller runs against a recording repository transport. */
@RunWith(AndroidJUnit4::class)
class PetRuntimeFactsTest {
    @Test fun realCapturePathBoundsInvalidFramesAndReacquiresBeforeInput(): Unit = runBlocking {
        val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MyApplication
        val dir=File(app.cacheDir,"capture-facts-${UUID.randomUUID()}")
        var attempts=0;var sent=0;var recover=false
        val repo=object: IDisplayRepository by app.displayRepository {
            override val automationEventsDirectory=dir
            override val connectionStatus=MutableStateFlow(ConnectionStatus.CONNECTED)
            override val managedDisplayIds=MutableStateFlow(setOf(4))
            override suspend fun setDisplaySurface(displayId: Int,surface: Surface?)=Result.success(Unit)
            override suspend fun injectInputWithDisplayId(event: InputEvent,displayId: Int): Result<Boolean> {
                sent++;return Result.success(true)
            }
            override suspend fun captureDisplay(displayId: Int): Result<ActiveCapturedFrame> {
                attempts++
                val bitmap=Bitmap.createBitmap(720,1600,Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(if(recover && attempts>1) Color.BLUE else Color.BLACK)
                return Result.success(ActiveCapturedFrame(bitmap,4,0,0,0,"TEST_DECODED_BITMAP",2000))
            }
        }
        try {
            val failed=HeadlessDisplayCapture(repo,4,720,1600)
            failed.start()
            try {
                assertTrue(runCatching {failed.awaitFrame(0)}.isFailure)
                assertEquals(3,attempts);assertTrue(failed.facts.frames.stopped)
                assertEquals(0,failed.statistics().getInt("shizukuFallbackSuccess"))
                assertTrue(runCatching {PetInputController(repo,4,720,1600,failed.facts).tap(360f,800f,0)}.isFailure)
                assertEquals(0,sent)
            } finally {failed.stop()}
            attempts=0;recover=true
            val recovered=HeadlessDisplayCapture(repo,4,720,1600)
            recovered.start()
            try {
                val frame=recovered.awaitFrame(0);frame.recycle()
                assertEquals(2,attempts);assertFalse(recovered.facts.frames.stopped)
                assertEquals(1,recovered.statistics().getInt("shizukuFallbackSuccess"))
                PetInputController(repo,4,720,1600,recovered.facts).tap(360f,800f,0)
                assertEquals(2,sent)
                val events=recovered.facts.events.file.readLines().map {JSONObject(it)}
                assertEquals(listOf("FRAME_INVALID","FRAME_VALID","INPUT_SENT"),events.map {it.getString("kind")})
            } finally {recovered.stop()}
            println("BOUNDED_CAPTURE_PASS invalidAttempts=3 invalidInputCount=0 recoveryAttempts=2")
        } finally {dir.deleteRecursively()}
    }

    @Test fun invalidCaptureBlocksActualControllerAndContinuousMoveIsPreserved(): Unit = runBlocking {
        val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MyApplication
        val sent=mutableListOf<Triple<Int,Long,Int>>()
        var afterSend: ((Int)->Unit)?=null
        val repo=object: IDisplayRepository by app.displayRepository {
            override val connectionStatus=MutableStateFlow(ConnectionStatus.CONNECTED)
            override val managedDisplayIds=MutableStateFlow(setOf(4))
            override suspend fun injectInputWithDisplayId(event: InputEvent,displayId: Int): Result<Boolean> {
                val motion=event as MotionEvent
                sent.add(Triple(motion.action,motion.downTime,displayId));afterSend?.invoke(motion.action)
                return Result.success(true)
            }
        }
        val dir=File(app.cacheDir,"facts-test-${UUID.randomUUID()}")
        try {
            val facts=AutomationFacts(dir,4)
            val input=PetInputController(repo,4,720,1600,facts)
            assertTrue(runCatching {input.tap(360f,800f,0)}.isFailure);assertTrue(sent.isEmpty())
            // A VALID frame with NO capture receipt is trusted for liveness but must not authorise a
            // new action: the freshness barrier refuses it instead of assuming it was just received.
            facts.frame(FrameLivenessResult(FrameStatus.VALID),"TEST_UNKNOWN_RECEIPT")
            assertTrue(facts.frames.inputAllowed)
            assertTrue(runCatching {input.tap(360f,800f,0)}.isFailure);assertTrue(sent.isEmpty())
            // A real capture receipt is what authorises the action.
            facts.frame(FrameLivenessResult(FrameStatus.VALID,"TEST",SystemClock.elapsedRealtime()),"TEST")
            input.tap(360f,800f,0)
            assertEquals(2,sent.size);sent.clear()
            facts.frame(FrameLivenessResult(FrameStatus.INVALID_BLACK_FRAME),"TEST")
            assertTrue(runCatching {input.tap(360f,800f,0)}.isFailure);assertTrue(sent.isEmpty())
            facts.frame(FrameLivenessResult(FrameStatus.VALID,"TEST",SystemClock.elapsedRealtime()),"TEST")
            input.drag(listOf(PointF(360f,1345f),PointF(360f,900f),PointF(280f,800f),PointF(430f,950f)),0)
            assertEquals(listOf(MotionEvent.ACTION_DOWN,MotionEvent.ACTION_MOVE,MotionEvent.ACTION_MOVE,
                MotionEvent.ACTION_MOVE,MotionEvent.ACTION_UP),sent.map {it.first})
            assertEquals(1,sent.map {it.second}.toSet().size);assertTrue(sent.all {it.third==4})
            sent.clear()
            afterSend={action -> if(action==MotionEvent.ACTION_MOVE)
                facts.frame(FrameLivenessResult(FrameStatus.INVALID_BLACK_FRAME),"TEST_DURING_DRAG")}
            assertTrue(runCatching {input.drag(listOf(PointF(360f,1345f),PointF(360f,900f),PointF(280f,800f)),0)}.isFailure)
            assertEquals(listOf(MotionEvent.ACTION_DOWN,MotionEvent.ACTION_MOVE,MotionEvent.ACTION_UP),sent.map {it.first})
            afterSend=null;sent.clear()
            facts.frame(FrameLivenessResult(FrameStatus.VALID),"TEST")
            repeat(3) {facts.frame(FrameLivenessResult(FrameStatus.CAPTURE_FAILED),"TEST")}
            assertTrue(facts.frames.stopped);assertTrue(runCatching {input.tap(360f,800f,0)}.isFailure)
            assertTrue(sent.isEmpty())
            assertTrue(runCatching {PetInputController(repo,0,720,1600,facts)}.isFailure)
            val events=facts.events.file.readLines().map {JSONObject(it)}
            events.forEachIndexed {i,e -> assertEquals(i+1L,e.getLong("sequence"))}
            assertEquals(2,events.count {it.getString("kind")=="INPUT_SENT"})
            assertTrue(events.filter {it.getString("kind")=="INPUT_SENT"}.all {it.getString("source")=="APP_AUTOMATION"})
            // An explicitly recorded external debug input has a distinct source.
            facts.events.append(EventSource.DEBUG_ADB,EventKind.INPUT_SENT,mapOf("fixture" to true))
            assertEquals("DEBUG_ADB",JSONObject(facts.events.file.readLines().last()).getString("source"))
            println("RUNTIME_FACTS_PASS invalidInputCount=0 continuousMoves=3 defaultDisplayRefused=true")
        } finally {dir.deleteRecursively()}
    }

    /**
     * Compile-only here: this file is built into the instrumentation APK but is NOT executed in this
     * round (no device authorisation). It exists so the fail-stop rule is also expressed against the
     * REAL controller with real MotionEvents, not only against the Android-free helper in the JVM
     * suite. A refused UP acknowledgement must fail the action and latch the session, so nothing is
     * journaled as sent and no later tap can press the display again.
     */
    @Test fun anUnconfirmedUpFailsTheRealActionAndLatchesTheSession(): Unit = runBlocking {
        val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MyApplication
        val dir=File(app.cacheDir,"up-failstop-${UUID.randomUUID()}")
        val sent=mutableListOf<Int>()
        // The game is pressed, then refuses to confirm the UP - both as a refused ack and as a throw.
        var upMode="false"
        val repo=object: IDisplayRepository by app.displayRepository {
            override val connectionStatus=MutableStateFlow(ConnectionStatus.CONNECTED)
            override val managedDisplayIds=MutableStateFlow(setOf(4))
            override suspend fun injectInputWithDisplayId(event: InputEvent,displayId: Int): Result<Boolean> {
                val action=(event as MotionEvent).action;sent.add(action)
                if(action!=MotionEvent.ACTION_UP) return Result.success(true)
                if(upMode=="throw") throw IllegalStateException("UP transport fault")
                return Result.failure(IllegalStateException("ACTION_UP rejected"))
            }
        }
        try {
            val facts=AutomationFacts(dir,4)
            val input=PetInputController(repo,4,720,1600,facts)
            facts.frame(FrameLivenessResult(FrameStatus.VALID,"TEST",SystemClock.elapsedRealtime()),"TEST")
            val failed=runCatching {input.tap(360f,800f,0)}
            assertTrue("a refused UP acknowledgement must fail the action",failed.isFailure)
            assertTrue(failed.exceptionOrNull()?.message?.contains("ACTION_UP rejected")==true)
            // The release was attempted on the same display even though its ack failed.
            assertEquals(listOf(MotionEvent.ACTION_DOWN,MotionEvent.ACTION_UP),sent)
            assertEquals("ACTION_UP_CLEANUP_FAILED",facts.inputHalted)
            var events=facts.events.file.readLines().map {JSONObject(it)}
            assertEquals("a failed action must not claim an INPUT_SENT",0,events.count {it.getString("kind")=="INPUT_SENT"})
            assertTrue(events.any {it.getString("kind")=="ERROR" && it.getString("reason")=="ACTION_UP_CLEANUP_FAILED"})
            // A fresh, authorising frame must NOT re-enable input: the latch outranks the frame gate.
            sent.clear()
            facts.frame(FrameLivenessResult(FrameStatus.VALID,"TEST",SystemClock.elapsedRealtime()),"TEST")
            val refused=runCatching {input.tap(360f,800f,0)}
            assertTrue("the session latch must keep refusing",refused.isFailure)
            assertTrue(refused.exceptionOrNull()?.message?.contains("SESSION_INPUT_HALTED_BY_UNCONFIRMED_UP")==true)
            assertTrue("no further DOWN may reach the display",sent.isEmpty())
            // The same rule for a UP that fails by throwing: a new session, because the first is latched.
            upMode="throw";sent.clear()
            val facts2=AutomationFacts(dir,4)
            val input2=PetInputController(repo,4,720,1600,facts2)
            facts2.frame(FrameLivenessResult(FrameStatus.VALID,"TEST",SystemClock.elapsedRealtime()),"TEST")
            val thrown=runCatching {input2.tap(360f,800f,0)}
            assertTrue(thrown.isFailure)
            assertTrue(thrown.exceptionOrNull()?.message?.contains("UP transport fault")==true)
            assertEquals(listOf(MotionEvent.ACTION_DOWN,MotionEvent.ACTION_UP),sent)
            assertEquals("ACTION_UP_CLEANUP_FAILED",facts2.inputHalted)
            assertEquals(0,facts2.events.file.readLines().map {JSONObject(it)}.count {it.getString("kind")=="INPUT_SENT"})
            println("UP_FAILSTOP_PASS refusedAckFailed=true latched=true laterDownCount=0")
        } finally {dir.deleteRecursively()}
    }
}
