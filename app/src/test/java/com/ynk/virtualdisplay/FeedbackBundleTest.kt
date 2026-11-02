package com.ynk.virtualdisplay

import com.ynk.virtualdisplay.ui.feedback.FeedbackBundle
import com.ynk.virtualdisplay.ui.feedback.FeedbackReport
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedbackBundleTest {
    private val report = FeedbackReport("点学习没反应", "1.0.86", "abc1234", "FOA-AL00", "13", "9.3.75 (16580)", "2026-11-02 20:00:00")

    private fun session(root: File, name: String) = File(root, name).apply {
        mkdirs()
        File(this, "verdict.json").writeText("{}")
        File(this, "shot.png").writeBytes(byteArrayOf(1, 2, 3))
    }

    private fun entries(sessions: List<File>, screenshots: Boolean, log: List<String> = emptyList()): Map<String, String> {
        val bytes = ByteArrayOutputStream()
        FeedbackBundle.write(bytes, report, log, sessions, screenshots)
        val found = linkedMapOf<String, String>()
        ZipInputStream(ByteArrayInputStream(bytes.toByteArray())).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                found[entry.name] = zip.readBytes().decodeToString()
            }
        }
        return found
    }

    @Test
    fun screenshotsStayOutUnlessTheTesterOptsIn() {
        val root = Files.createTempDirectory("feedback").toFile()
        val names = entries(listOf(session(root, "2"), session(root, "1")), screenshots = false).keys
        assertTrue("sessions/2/verdict.json" in names)
        assertTrue("sessions/1/verdict.json" in names)
        assertFalse(names.any { it.endsWith(".png") })
    }

    @Test
    fun optedInScreenshotsComeOnlyFromTheNewestSession() {
        val root = Files.createTempDirectory("feedback").toFile()
        val names = entries(listOf(session(root, "2"), session(root, "1")), screenshots = true).keys
        assertTrue("sessions/2/shot.png" in names)
        assertFalse("sessions/1/shot.png" in names)
    }

    @Test
    fun reportCarriesTheDescriptionAndVersions() {
        val text = entries(emptyList(), screenshots = false).getValue("report.txt")
        assertTrue(text.contains("点学习没反应"))
        assertTrue(text.contains("1.0.86"))
        assertTrue(text.contains("9.3.75"))
    }

    @Test
    fun ocrDumpsLoseThePetAndOwnerNames() {
        val line = """{"action":"x","stopReason":"at=after|lines=狗子;主人:某昵称;金币+71","y":1}"""
        val kept = FeedbackBundle.trimLog(listOf(line)).single()
        assertFalse(kept.contains("某昵称"))
        assertFalse(kept.contains("狗子"))
        assertTrue(kept.contains("at=after"))
        assertTrue(kept.contains(""""y":1"""))
    }

    @Test
    fun logDropsFrameLinesAndKeepsTheNewestTail() {
        val log = List(FeedbackBundle.MAX_LOG_LINES + 5) { "line-$it" } + "x:FRAME y"
        val kept = FeedbackBundle.trimLog(log)
        assertEquals(FeedbackBundle.MAX_LOG_LINES, kept.size)
        assertEquals("line-5", kept.first())
        assertFalse(kept.any { it.contains(":FRAME") })
    }
}
