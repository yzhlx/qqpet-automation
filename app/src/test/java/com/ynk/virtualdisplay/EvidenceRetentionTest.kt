package com.ynk.virtualdisplay

import com.ynk.virtualdisplay.automation.EvidenceRetention
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class EvidenceRetentionTest {
    @get:Rule val folder = TemporaryFolder()

    private fun session(name: Long, terminal: String?, frames: List<String>, size: Int = 10): File {
        val dir = File(folder.root, "$name").apply { mkdirs() }
        terminal?.let { File(dir, "run-record.json").writeText("""{"terminalState":"$it"}""") }
        File(dir, "verdict.json").writeText("{}")
        frames.forEachIndexed { i, frame ->
            File(dir, frame).apply { writeBytes(ByteArray(size)); setLastModified(1_000_000L + i * 1000) }
        }
        return dir
    }

    private fun pngs(dir: File) = dir.list()!!.filter { it.endsWith(".png") }.sorted()
    private val many = (1..10).map { "round-1-f$it.png" }

    @Test fun completedSessionKeepsOnlyDiagnosticFrames() {
        val dir = session(4, "COMPLETED", many + "round-1-result-page-skipped.png")
        EvidenceRetention.prune(folder.root)
        assertEquals(listOf("round-1-result-page-skipped.png"), pngs(dir))
        assertTrue(File(dir, "run-record.json").isFile)
    }

    @Test fun failedSessionKeepsItsLastFrames() {
        val dir = session(4, "FAILED", many)
        EvidenceRetention.prune(folder.root)
        assertEquals((5..10).map { "round-1-f$it.png" }.sorted(), pngs(dir))
    }

    @Test fun sessionsPastTheNewestThreeLoseAllFramesButKeepTheirRecords() {
        val old = session(1, "FAILED", many + "round-1-result-unconfirmed-first.png")
        (2L..4L).forEach { session(it, "FAILED", many) }
        EvidenceRetention.prune(folder.root)
        assertTrue(pngs(old).isEmpty())
        assertTrue(File(old, "verdict.json").isFile)
    }

    @Test fun totalKeptFramesStayUnderTheCap() {
        val size = 6 * 1024 * 1024
        val dir = session(4, "FAILED", many, size)
        EvidenceRetention.prune(folder.root)
        assertEquals(5, pngs(dir).size)
    }

    @Test fun aStartingSessionStopsThePass() {
        val dir = session(4, null, many)
        EvidenceRetention.prune(folder.root) { false }
        assertEquals(10, pngs(dir).size)
    }

    @Test fun logIsCutAtALineStart() {
        val log = folder.newFile("pet.log")
        log.writeText("x".repeat(99) + "\n".repeat(1))
        val line = "{\"a\":1}\n"
        log.writeText(line.repeat((EvidenceRetention.MAX_LOG_BYTES / line.length + 10).toInt()))
        EvidenceRetention.trimLog(log)
        assertTrue(log.length() <= EvidenceRetention.KEEP_LOG_BYTES)
        assertTrue(log.readText().startsWith(line))
    }

    @Test fun smallLogIsLeftAlone() {
        val log = folder.newFile("pet.log").apply { writeText("a\nb\n") }
        EvidenceRetention.trimLog(log)
        assertEquals("a\nb\n", log.readText())
    }
}
