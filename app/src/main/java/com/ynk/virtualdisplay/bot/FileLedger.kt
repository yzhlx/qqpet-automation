package com.ynk.virtualdisplay.bot

import java.io.File
import java.time.LocalDate
import java.time.ZoneId

/**
 * Today's used minutes in one small file: "2026-10-24 40". A new day starts from zero.
 * The game's day follows Beijing time, so the date is taken there.
 */
class FileLedger(private val file: File, private val today: () -> LocalDate = { LocalDate.now(ZoneId.of("Asia/Shanghai")) }) : DailyLedger {
    override fun minutesUsed(): Int {
        val parts = runCatching { file.readText().trim().split(" ") }.getOrNull() ?: return 0
        return if (parts.size == 2 && parts[0] == today().toString()) parts[1].toIntOrNull() ?: 0 else 0
    }

    override fun add(minutes: Int) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText("${today()} ${minutesUsed() + minutes}")
        tmp.renameTo(file)
    }
}
