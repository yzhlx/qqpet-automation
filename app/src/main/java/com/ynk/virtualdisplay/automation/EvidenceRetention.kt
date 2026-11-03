package com.ynk.virtualdisplay.automation

import java.io.File

/**
 * Keeps the app's disk use small: frames are only read in memory, so the PNGs a session leaves behind
 * are diagnostics, not state (one device held 562 MB across 28 sessions, 2026-11-01).
 *
 * JSON records stay, so the records page and the recovery paths still read every session. Callers must
 * skip this while anything is pending, because legacy recovery reads old session folders.
 */
object EvidenceRetention {
    const val KEEP_SESSIONS = 3
    const val KEEP_FAILED_FRAMES = 6
    const val MAX_FRAME_BYTES = 30L * 1024 * 1024
    const val MAX_LOG_BYTES = 2L * 1024 * 1024
    const val KEEP_LOG_BYTES = 1L * 1024 * 1024

    private val DIAGNOSTIC = Regex(".*(result-unconfirmed|result-page-skipped).*")
    private val COMPLETED = Regex("\"terminalState\"\\s*:\\s*\"COMPLETED\"")

    /** [proceed] is rechecked per folder so a session starting mid-pass keeps its own frames. */
    fun prune(sessionsRoot: File, proceed: () -> Boolean = { true }) {
        val sessions = (sessionsRoot.listFiles { f -> f.isDirectory } ?: return)
            .sortedByDescending { it.name.toLongOrNull() ?: it.lastModified() }
        var keptBytes = 0L
        sessions.forEachIndexed { index, session ->
            if (!proceed()) return
            val frames = (session.listFiles { f -> f.isFile && f.name.endsWith(".png", true) } ?: return@forEachIndexed)
                .sortedWith(compareByDescending<File> { it.lastModified() }.thenByDescending { it.name })
            val kept = if (index >= KEEP_SESSIONS) emptyList() else {
                frames.filter { DIAGNOSTIC.matches(it.name) } + if (completed(session)) emptyList() else frames.take(KEEP_FAILED_FRAMES)
            }.distinct().filter { frame ->
                keptBytes += frame.length()
                keptBytes <= MAX_FRAME_BYTES
            }
            (frames.toList() - kept.toSet()).forEach(File::delete)
        }
    }

    private fun completed(session: File): Boolean = runCatching {
        COMPLETED.containsMatchIn(File(session, "run-record.json").readText())
    }.getOrDefault(false)

    /** Cuts at a line start so the log stays one parsable JSON object per line. */
    fun trimLog(log: File) {
        if (!log.isFile || log.length() <= MAX_LOG_BYTES) return
        val bytes = log.readBytes()
        val from = bytes.size - KEEP_LOG_BYTES.toInt()
        val lineStart = (from until bytes.size).firstOrNull { bytes[it] == '\n'.code.toByte() }?.plus(1) ?: return
        log.writeBytes(bytes.copyOfRange(lineStart, bytes.size))
    }
}
