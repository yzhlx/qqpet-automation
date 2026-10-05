package com.ynk.virtualdisplay

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ynk.virtualdisplay.automation.GateTestActivity
import com.ynk.virtualdisplay.automation.HeadlessDisplayCapture
import com.ynk.virtualdisplay.automation.PetInputController
import com.ynk.virtualdisplay.data.repository.ConnectionStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class PetDisplayGateTest {
    @Test
    fun headlessThenSingleScopedTap(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as MyApplication
        val repo = app.displayRepository
        val tag = "PetDisplayGates"
        var id: Int? = null
        var capture: HeadlessDisplayCapture? = null
        withContext(Dispatchers.Main) { app.acquireDisplayRepository(tag) }
        try {
            withTimeout(15_000) { repo.connectionStatus.first { it == ConnectionStatus.CONNECTED } }
            val target = repo.createDisplay("Pet-MVP-Gate-Test", 720, 1600, 320).getOrThrow()
            id = target
            assertTrue(target > 0)
            fun launch(displayId: Int): String = instrumentation.uiAutomation.executeShellCommand(
                "am start --display $displayId -f 0x18000000 -n com.ynk.virtualdisplay/.automation.GateTestActivity"
            ).use { fd -> android.os.ParcelFileDescriptor.AutoCloseInputStream(fd).bufferedReader().readText() }
            assertFalse(launch(0).contains("Error"))
            assertFalse(launch(target).contains("Error"))
            withTimeout(10_000) {
                while (!GateTestActivity.touches.containsKey(0) || !GateTestActivity.touches.containsKey(target)) delay(100)
            }
            val activeCapture = HeadlessDisplayCapture(repo, target, 720, 1600)
            capture = activeCapture
            activeCapture.start()
            val evidence = File(app.getExternalFilesDir(null), "pet_mvp_gates").apply { mkdirs() }
            // A real rerun captured the Activity's transition colour before the
            // test blue became visible. Wait for the known fixture, with a bound.
            withTimeout(5000) {
                while(true) {
                    val f=activeCapture.awaitFrame()
                    val ready=try {
                        val p=f.getPixel(360,800)
                        kotlin.math.abs(Color.red(p)-32)<8 && kotlin.math.abs(Color.green(p)-96)<8 &&
                            kotlin.math.abs(Color.blue(p)-160)<8
                    } finally { f.recycle() }
                    if(ready) break
                    delay(100)
                }
            }
            repeat(3) { index ->
                delay(250)
                val frame = activeCapture.awaitFrame(5_000)
                try {
                    assertFalse("Capture returned recycled bitmap", frame.isRecycled)
                    assertEquals(720, frame.width)
                    assertEquals(1600, frame.height)
                    val pixel = frame.getPixel(360, 800)
                    assertTrue("Unexpected target frame: $pixel", kotlin.math.abs(Color.blue(pixel) - 160) < 8)
                    File(evidence, "headless_$index.png").outputStream().use {
                        assertTrue(frame.compress(Bitmap.CompressFormat.PNG, 100, it))
                    }
                } finally { frame.recycle() }
            }
            Log.i(tag, "GATE_A_PASS display=$target preview=false frames=3 size=720x1600")
            val mainBefore = GateTestActivity.touches.getValue(0).get()
            val targetBefore = GateTestActivity.touches.getValue(target).get()
            PetInputController(repo, target, 720, 1600).tap(360f, 800f)
            withTimeout(5_000) {
                while (GateTestActivity.touches.getValue(target).get() == targetBefore) delay(100)
            }
            delay(500)
            assertEquals(targetBefore + 1, GateTestActivity.touches.getValue(target).get())
            assertEquals("Physical display received input", mainBefore, GateTestActivity.touches.getValue(0).get())
            Log.i(tag, "GATE_B_PASS display=$target targetDelta=1 physicalDelta=0 taps=1")
        } finally {
            capture?.stop()
            id?.let { repo.releaseDisplay(it).getOrThrow() }
            withContext(Dispatchers.Main) { app.releaseDisplayRepository(tag) }
        }
    }
}
