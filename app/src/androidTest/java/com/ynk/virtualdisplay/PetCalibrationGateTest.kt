package com.ynk.virtualdisplay

import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.Rect
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ynk.virtualdisplay.automation.GateTestActivity
import com.ynk.virtualdisplay.automation.HeadlessDisplayCapture
import com.ynk.virtualdisplay.automation.PetInputController
import com.ynk.virtualdisplay.automation.PetProfile
import com.ynk.virtualdisplay.automation.TemplateMatcher
import com.ynk.virtualdisplay.data.repository.ConnectionStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Explicit opt-in only: the human must put QQ on the measured soap page first. */
@RunWith(AndroidJUnit4::class)
class PetCalibrationGateTest {
    @Test fun authorizedSoapGesture(): Unit = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("soap_authorized") == "yes")
        val id = args.getString("display_id")!!.toInt()
        require(id > 0)
        val instrument = InstrumentationRegistry.getInstrumentation()
        val app = instrument.targetContext.applicationContext as MyApplication
        val repo = app.displayRepository
        withContext(Dispatchers.Main) { app.acquireDisplayRepository("SoapCalibration") }
        var capture: HeadlessDisplayCapture? = null
        try {
            withTimeout(15000) { repo.connectionStatus.first { it == ConnectionStatus.CONNECTED } }
            instrument.uiAutomation.executeShellCommand(
                "am start --display 0 -f 0x18000000 -n com.ynk.virtualdisplay/.automation.GateTestActivity"
            ).use { ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().readText() }
            withTimeout(5000) { while (!GateTestActivity.touches.containsKey(0)) delay(100) }
            val physical = GateTestActivity.touches.getValue(0).get()
            val c = HeadlessDisplayCapture(repo, id, 720, 1600)
            capture = c
            c.start()
            val dir = File(app.getExternalFilesDir(null), "pet_mvp_soap").apply { mkdirs() }
            suspend fun save(name: String) {
                val frame = c.awaitFrame()
                try { File(dir, name).outputStream().use { frame.compress(Bitmap.CompressFormat.PNG, 100, it) } }
                finally { frame.recycle() }
            }
            val before = c.awaitFrame()
            try {
                File(dir, "before.png").outputStream().use { before.compress(Bitmap.CompressFormat.PNG, 100, it) }
                val matcher = TemplateMatcher(app)
                val match = listOf("soap", "soap_idle").map { name -> matcher.match(before,
                    PetProfile.Probe(name, Rect(280, 1280, 440, 1400), "pet_mvp/$name.png"), 0.97) }
                    .maxBy { it.score }
                assertTrue("Soap page mismatch score=${match.score}; no gesture sent", match.matched)
            } finally { before.recycle() }
            val points = mutableListOf(PointF(360f, 1345f), PointF(360f, 1100f), PointF(360f, 900f))
            repeat(40) {
                points += listOf(PointF(280f, 800f), PointF(430f, 950f), PointF(280f, 950f), PointF(430f, 800f))
            }
            val gesture = async { PetInputController(repo, id, 720, 1600, c.facts).drag(points, 150) }
            delay(3000)
            save("during.png")
            gesture.await()
            delay(2000)
            save("after.png")
            assertEquals("Physical display received input", physical, GateTestActivity.touches.getValue(0).get())
        } finally {
            capture?.stop()
            withContext(Dispatchers.Main) { app.releaseDisplayRepository("SoapCalibration") }
        }
    }
}
