package com.ynk.virtualdisplay.ui.feedback

import java.io.File
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** What the tester wrote plus the facts a bug report cannot do without. */
data class FeedbackReport(
    val description: String,
    val appVersion: String,
    val gitHash: String,
    val device: String,
    val androidVersion: String,
    val qqVersion: String,
    val createdAt: String,
) {
    val text: String
        get() = buildString {
            appendLine("问题描述：${description.trim().ifEmpty { "（未填写）" }}")
            appendLine("App：$appVersion ($gitHash)")
            appendLine("设备：$device · Android $androidVersion")
            appendLine("QQ：$qqVersion")
            appendLine("时间：$createdAt")
        }
}

/**
 * Packs one feedback zip. It is pure file work so it can be tested without a device.
 *
 * Screenshots show the tester's QQ nickname and avatar, so they only go in when [includeScreenshots]
 * is set, and then only from the newest session. Per-frame log lines are dropped because they are
 * most of the log's size and say nothing a session's JSON does not.
 */
object FeedbackBundle {
    const val MAX_LOG_LINES = 2000
    const val MAX_SESSIONS = 5
    const val MAX_SCREENSHOTS = 12

    fun write(
        output: OutputStream,
        report: FeedbackReport,
        logLines: List<String>,
        sessionsNewestFirst: List<File>,
        includeScreenshots: Boolean,
    ) {
        ZipOutputStream(output).use { zip ->
            zip.putText("report.txt", report.text)
            zip.putText("pet_mvp.log", trimLog(logLines).joinToString("\n"))
            sessionsNewestFirst.take(MAX_SESSIONS).forEachIndexed { index, session ->
                val files = session.listFiles { f -> f.isFile }?.sortedBy { it.name } ?: emptyList()
                files.filter { it.name.endsWith(".json", true) }.forEach { zip.putFile(session, it) }
                if (includeScreenshots && index == 0) {
                    files.filter { it.name.endsWith(".png", true) }
                        .takeLast(MAX_SCREENSHOTS)
                        .forEach { zip.putFile(session, it) }
                }
            }
        }
    }

    /** OCR dumps quote the game screen, which names the pet and its owner's QQ nickname. */
    fun trimLog(lines: List<String>): List<String> =
        lines.filter { it.isNotBlank() && !it.contains(":FRAME") }
            .takeLast(MAX_LOG_LINES)
            .map { it.replace(OCR_DUMP, "lines=<已隐去>") }

    private val OCR_DUMP = Regex("""lines=[^"]*""")

    private fun ZipOutputStream.putText(name: String, text: String) {
        putNextEntry(ZipEntry(name))
        write(text.toByteArray())
        closeEntry()
    }

    private fun ZipOutputStream.putFile(session: File, file: File) {
        putNextEntry(ZipEntry("sessions/${session.name}/${file.name}"))
        file.inputStream().use { it.copyTo(this) }
        closeEntry()
    }
}
