package com.ynk.virtualdisplay.ui.data

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Read-only projection of what the automation layer already wrote to disk.
 *
 * Sources (all written by the verified business layer, never by the UI):
 *  - external `pet_mvp_session/<epochMs>/`: run-record.json, runtime-state.json, verdict.json,
 *    round-<n>.json, and the `round-*.png` evidence frames
 *  - internal `files/pet_mvp.log`: one JSON object per line, appended by PetAutomationService
 *  - internal `files/pet-events/<sessionId>.jsonl`: the durable session event journal
 *
 * Every parser is defensive: a missing or unreadable field stays absent (null / empty), and is shown
 * as "—"/"未知" by the UI. Nothing here invents a value or a completed outcome.
 */

/** One `ObservedValue.fields()` blob, kept in its four distinct states. */
data class ObservedField(
    val status: String,
    val value: Int? = null,
    val observedAt: Long? = null,
    val confidence: Double? = null,
    val evidence: String? = null,
    val source: String? = null,
    val reason: String? = null,
    val previousValue: Int? = null,
    val previousObservedAt: Long? = null,
) {
    val isKnown get() = status == "KNOWN"
    val isStale get() = status == "STALE"
    val isUnsupported get() = status == "UNSUPPORTED"
    /** The only number this field may ever display; null when there is nothing to show. */
    val displayValue: Int? get() = when {
        isKnown -> value
        isStale -> previousValue
        else -> null
    }
    val displayObservedAt: Long? get() = if (isStale) previousObservedAt else observedAt

    companion object {
        fun parse(o: JSONObject?): ObservedField? {
            if (o == null) return null
            val status = o.optString("status").takeIf { it.isNotEmpty() } ?: return null
            val prev = o.optJSONObject("previous")
            return ObservedField(
                status = status,
                value = o.intOrNull("value"),
                observedAt = o.longOrNull("observedAt"),
                confidence = if (o.has("confidence")) o.optDouble("confidence") else null,
                evidence = o.optString("evidence").takeIf { it.isNotEmpty() && it != "null" },
                source = o.optString("source").takeIf { it.isNotEmpty() && it != "null" },
                reason = o.optString("reason").takeIf { it.isNotEmpty() && it != "null" },
                previousValue = prev?.intOrNull("value"),
                previousObservedAt = prev?.longOrNull("observedAt"),
            )
        }
    }
}

/** `care` counters written by PetCareFlow / PetSessionController. */
data class CareStats(
    val feedCount: Int,
    val cleanCount: Int,
    val feedDurationMs: Long,
    val cleanDurationMs: Long,
) {
    val total get() = feedCount + cleanCount

    companion object {
        fun parse(o: JSONObject?): CareStats? {
            if (o == null) return null
            return CareStats(
                feedCount = o.optInt("feed_count"),
                cleanCount = o.optInt("clean_count"),
                feedDurationMs = o.optLong("feed_duration_ms"),
                cleanDurationMs = o.optLong("clean_duration_ms"),
            )
        }
    }
}

/** A before/ready/after health snapshot from `healthJson`. */
data class HealthSnapshot(val stamina: Int?, val cleanliness: Int?, val mood: Int?) {
    companion object {
        fun parse(o: JSONObject?): HealthSnapshot? {
            if (o == null) return null
            val moodObj = o.optJSONObject("mood")
            return HealthSnapshot(
                stamina = o.intOrNull("stamina"),
                cleanliness = o.intOrNull("cleanliness"),
                mood = moodObj?.let { ObservedField.parse(it)?.displayValue },
            )
        }
    }
}

/** One `round-<n>.json`. */
data class RoundRecord(
    val round: Int,
    val before: HealthSnapshot?,
    val ready: HealthSnapshot?,
    val after: HealthSnapshot?,
    val care: CareStats?,
    val grade: String?,
    val encouragementSent: Int?,
    val courseMs: Long?,
)

/** The consolidated `run-record.json` of one finished session. */
data class SessionRunRecord(
    val sessionId: String?,
    val terminalState: String,
    val runner: String?,
    val rounds: Int,
    val completedRounds: Int,
    val pauseCount: Int,
    val startedAtMs: Long?,
    val runtimeMs: Long,
    val stopReason: String?,
    val moodPolicy: String?,
    val energy: ObservedField?,
    val cleanliness: ObservedField?,
    val mood: ObservedField?,
    val care: CareStats?,
    val encouragementSent: Int?,
    val courseStarts: Int?,
    val requestedRounds: Int?,
) {
    val succeeded get() = terminalState == "COMPLETED"

    companion object {
        /** Returns null when the JSON is absent or has no terminal state — never a synthetic record. */
        fun parse(text: String): SessionRunRecord? = runCatching {
            val o = JSONObject(text)
            val terminal = o.optString("terminalState").takeIf { it.isNotEmpty() } ?: return null
            SessionRunRecord(
                sessionId = o.optString("sessionId").takeIf { it.isNotEmpty() && it != "null" },
                terminalState = terminal,
                runner = o.optString("runner").takeIf { it.isNotEmpty() && it != "null" },
                rounds = o.optInt("rounds"),
                completedRounds = o.optInt("completedRounds"),
                pauseCount = o.optInt("pauseCount"),
                startedAtMs = o.optLongOrNull("startedAt"),
                runtimeMs = o.optLong("runtimeMs"),
                stopReason = o.optString("stopReason").takeIf { it.isNotEmpty() && it != "null" },
                moodPolicy = o.optString("moodPolicy").takeIf { it.isNotEmpty() && it != "null" },
                energy = ObservedField.parse(o.optJSONObject("energy")),
                cleanliness = ObservedField.parse(o.optJSONObject("cleanliness")),
                mood = ObservedField.parse(o.optJSONObject("mood")),
                care = CareStats.parse(o.optJSONObject("care")),
                encouragementSent = o.intOrNull("encouragementSent"),
                courseStarts = o.intOrNull("courseStarts"),
                requestedRounds = o.intOrNull("requestedRounds"),
            )
        }.getOrNull()
    }
}

/** A session directory plus everything read from it. */
data class SessionEvidence(
    val directory: File,
    val record: SessionRunRecord?,
    val rounds: List<RoundRecord>,
    val screenshots: List<File>,
    val runtimeStateJson: String?,
) {
    val id: String get() = directory.name
}

/** One parsed line of `files/pet_mvp.log`. */
data class LogEntry(
    val timestamp: String,
    val state: String,
    val action: String,
    val result: String,
    val reason: String?,
    val score: Double?,
) {
    companion object {
        fun parse(line: String): LogEntry? = runCatching {
            val o = JSONObject(line)
            val ts = o.optString("timestamp").takeIf { it.isNotEmpty() } ?: return null
            LogEntry(
                timestamp = ts,
                state = o.optString("state"),
                action = o.optString("action"),
                result = o.optString("result"),
                reason = o.optString("stopReason").takeIf { it.isNotEmpty() && it != "null" },
                score = if (o.has("matchScore") && !o.isNull("matchScore")) o.optDouble("matchScore") else null,
            )
        }.getOrNull()
    }
}

/**
 * Reads evidence off disk. All calls are cheap, synchronous file reads; call them from a background
 * dispatcher (the screens use `withContext(Dispatchers.IO)`).
 */
class PetEvidenceRepository(private val context: Context) {

    private val externalRoot: File?
        get() = context.getExternalFilesDir(null)?.let { File(it, "pet_mvp_session") }

    private val logFile: File get() = File(context.filesDir, "pet_mvp.log")

    private val eventsDir: File get() = File(context.filesDir, "pet-events")

    /** Newest-first session directories. Empty when the app has never completed a run. */
    fun sessionDirectories(limit: Int = 30): List<File> {
        val root = externalRoot ?: return emptyList()
        val dirs = root.listFiles { f -> f.isDirectory } ?: return emptyList()
        return dirs.sortedByDescending { it.name.toLongOrNull() ?: it.lastModified() }.take(limit)
    }

    /** Everything readable about one session directory. */
    fun read(directory: File): SessionEvidence {
        val record = File(directory, "run-record.json").takeIf { it.isFile }
            ?.let { runCatching { SessionRunRecord.parse(it.readText()) }.getOrNull() }
        val roundFiles = directory.listFiles { f -> f.isFile && ROUND_FILE.matches(f.name) } ?: emptyArray()
        val rounds = roundFiles.mapNotNull { f -> runCatching { parseRound(f.readText(), f.name) }.getOrNull() }
            .sortedBy { it.round }
        val shots = directory.listFiles { f -> f.isFile && f.name.endsWith(".png", true) }
            ?.sortedBy { it.name } ?: emptyList()
        val runtime = File(directory, "runtime-state.json").takeIf { it.isFile }
            ?.let { runCatching { it.readText() }.getOrNull() }
        return SessionEvidence(directory, record, rounds, shots, runtime)
    }

    /** The most recent session that actually produced a run record, or null. */
    fun latestSession(): SessionEvidence? =
        sessionDirectories().asSequence().map { read(it) }.firstOrNull { it.record != null }

    /**
     * Locates the evidence directory of the session running right now by matching the sessionId the
     * controller published into `runtime-state.json`. Returns null when nothing matches — the caller
     * then shows no live evidence instead of guessing a directory.
     */
    fun liveSessionDirectory(managedSessionId: String?): File? {
        if (managedSessionId.isNullOrBlank()) return null
        return sessionDirectories(limit = 6).firstOrNull { dir ->
            val text = File(dir, "runtime-state.json").takeIf { it.isFile }
                ?.let { runCatching { it.readText() }.getOrNull() } ?: return@firstOrNull false
            runCatching { JSONObject(text).optString("sessionId") == managedSessionId }.getOrDefault(false)
        }
    }

    /** Sessions whose record's local start date is today, newest first. */
    fun todaySessions(nowMs: Long = System.currentTimeMillis()): List<SessionEvidence> {
        val day = dayKey(nowMs)
        return sessionDirectories().map { read(it) }
            .filter { ev -> ev.record?.startedAtMs?.let { dayKey(it) == day } ?: false }
    }

    /** Tail of `files/pet_mvp.log`, newest last, parsed defensively. */
    fun logEntries(limit: Int = 400): List<LogEntry> {
        val file = logFile
        if (!file.isFile) return emptyList()
        val lines = runCatching { file.readLines() }.getOrElse { return emptyList() }
        return lines.asSequence()
            .filter { it.isNotBlank() }
            .mapNotNull { LogEntry.parse(it) }
            .toList()
            .takeLast(limit)
    }

    /** The durable event journal of one session, as raw JSON lines (may be empty). */
    fun eventJournal(sessionId: String?): List<String> {
        if (sessionId.isNullOrBlank()) return emptyList()
        val file = File(eventsDir, "$sessionId.jsonl")
        if (!file.isFile) return emptyList()
        return runCatching { file.readLines().filter { it.isNotBlank() } }.getOrElse { emptyList() }
    }

    /** Frames of one round, in filename order, for the evidence film strip. */
    fun screenshotsFor(directory: File?, round: Int?): List<File> {
        if (directory == null) return emptyList()
        val all = directory.listFiles { f -> f.isFile && f.name.endsWith(".png", true) } ?: return emptyList()
        val filtered = if (round == null) all.toList() else all.filter { it.name.startsWith("round-$round-") }
        return filtered.sortedBy { it.name }
    }

    companion object {
        private val ROUND_FILE = Regex("round-(\\d+)\\.json")

        fun dayKey(ms: Long): String =
            SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(ms))

        fun parseRound(text: String, name: String): RoundRecord? = runCatching {
            val o = JSONObject(text)
            val round = o.optInt("round", name.filter { it.isDigit() }.toIntOrNull() ?: 0)
            val result = o.optJSONObject("result")
            RoundRecord(
                round = round,
                before = HealthSnapshot.parse(o.optJSONObject("before")),
                ready = HealthSnapshot.parse(o.optJSONObject("ready")),
                after = HealthSnapshot.parse(o.optJSONObject("after")),
                care = CareStats.parse(o.optJSONObject("care")),
                grade = result?.optString("grade")?.takeIf { it.isNotEmpty() && it != "null" },
                encouragementSent = o.intOrNull("appEncouragementSent"),
                courseMs = result?.longOrNull("course_ms"),
            )
        }.getOrNull()

        /** A log line's "HH:MM" part, or the whole string when it does not look like a timestamp. */
        fun timeOf(timestamp: String): String {
            val m = Regex("(\\d{2}:\\d{2})").find(timestamp)
            return m?.groupValues?.get(1) ?: timestamp
        }
    }
}

private fun JSONObject.intOrNull(key: String): Int? =
    if (has(key) && !isNull(key)) optInt(key) else null

private fun JSONObject.longOrNull(key: String): Long? =
    if (has(key) && !isNull(key)) optLong(key) else null

private fun JSONObject.optLongOrNull(key: String): Long? =
    if (has(key) && !isNull(key)) optLong(key) else null
