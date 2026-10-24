package com.ynk.virtualdisplay.bot

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.LocalDate

class FileLedgerTest {
    private val dir: File = Files.createTempDirectory("ledger").toFile()

    @Test fun addsUpWithinADayAndResetsTheNext() {
        var day = LocalDate.of(2026, 10, 24)
        val ledger = FileLedger(File(dir, "l.txt")) { day }
        assertEquals(0, ledger.minutesUsed())
        ledger.add(10)
        ledger.add(30)
        assertEquals(40, ledger.minutesUsed())
        day = day.plusDays(1)
        assertEquals(0, ledger.minutesUsed())
        ledger.add(10)
        assertEquals(10, ledger.minutesUsed())
    }

    @Test fun aBrokenFileCountsAsZero() {
        val f = File(dir, "broken.txt").apply { writeText("garbage") }
        assertEquals(0, FileLedger(f).minutesUsed())
    }
}
