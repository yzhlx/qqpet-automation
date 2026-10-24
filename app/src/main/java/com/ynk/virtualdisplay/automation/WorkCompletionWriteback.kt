package com.ynk.virtualdisplay.automation

import java.io.File
import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * One durable, already-recorded fact about a finished standalone WORK session, read back from the
 * session journal and the session directory. Nothing here performs input or spends anything: it is
 * only the evidence a [WorkCompletionWritebackPolicy] decision is allowed to look at.
 */
data class WorkCompletionEvidenceRow(
    val sequence: Long,
    val wallTimeMs: Long,
    val kind: String,
    val action: String?,
    val reason: String?,
    val result: String?,
    val evidence: List<String> = emptyList(),
)

data class WorkCompletionAppLogRow(
    val timestampMs: Long,
    val state: String?,
    val action: String?,
    val result: String?,
    val stopReason: String?,
)

/** The terminal verdict document of the same session (`pet_mvp_session/<ts>/verdict.json`). */
data class WorkCompletionVerdict(
    val sessionId: String,
    val verdict: String,
    val mode: String,
    val stopReason: String?,
    val terminalState: String,
    val courseStarts: Int,
    val workOptionId: String,
    val workMinutes: Int,
    val energyCost: Int,
    val cleanlinessCost: Int,
)

/**
 * Evidence-only write-back for the ONE defect shape proven on device (defect D1, 2026-10-24):
 * a standalone WORK session whose shift really completed and really paid (same-payroll-page positive
 * award, payroll page dismissed to a known page, terminal verdict WORK_PASS) but whose budget attempt
 * was left UNCONFIRMED_RETAINED because the old `runWorkOnly` never reported the completion.
 *
 * The policy only ever answers "is this attempt allowed to be marked COMPLETED now". It cannot refund,
 * cannot change minutes, cannot replay input and cannot touch any other attempt. Every check below is
 * a refusal by default; a single missing or mismatched fact rejects the whole write-back.
 */
object WorkCompletionWritebackPolicy {
    /** The sweep reason the stuck attempt carries; anything else is a different story and is refused. */
    const val SWEEP_REASON = "SESSION_ENDED_WITHOUT_COMPLETION_PROOF"
    const val TARGET_SESSION_ID = "d5bf86e8-9c57-4369-b311-f758c94023ca"
    const val TARGET_WORK_OPTION = "work:竹影武馆/帮武馆跑腿"
    const val TARGET_AWARD_COINS = 68
    const val TARGET_WORK_MINUTES = 10

    data class Proof(
        val awardCoins: Int,
        val awardSequence: Long,
        val dismissReason: String,
        val dismissSequence: Long,
        val dismissResultSource: String,
        val dismissLogTimestampMs: Long?,
        val dismissLogDeltaMs: Long?,
    )

    const val MAX_APP_LOG_SKEW_MS = 1_000L

    /** Extracts the award + dismiss pair from the session journal rows of [sessionId]. */
    fun proofFromRows(
        sessionId: String,
        rows: List<WorkCompletionEvidenceRow>,
        appLogRows: List<WorkCompletionAppLogRow> = emptyList(),
    ): Proof {
        val awards = rows.filter {
            it.kind == "ERROR" && it.action == "work-reward-payroll-award" && it.reason != null &&
                it.reason.startsWith("award=")
        }
        require(awards.size == 1) { "WORK_WRITEBACK_AWARD_EVENT_MISSING_OR_AMBIGUOUS:$sessionId:${awards.size}" }
        val award = awards.single()
        val awardCoins = award.reason!!.removePrefix("award=").substringBefore('|').toIntOrNull()
            ?: error("WORK_WRITEBACK_AWARD_NOT_AN_INTEGER:$sessionId")
        require(awardCoins == TARGET_AWARD_COINS) { "WORK_WRITEBACK_AWARD_MISMATCH:$sessionId:$awardCoins" }

        val dismisses = rows.filter {
            it.kind == "ERROR" && it.action == "work-completion-dismiss" &&
                it.reason == "PAYROLL_RETURNED_OUTDOOR"
        }
        require(dismisses.size == 1) { "WORK_WRITEBACK_DISMISS_EVENT_MISSING_OR_AMBIGUOUS:$sessionId:${dismisses.size}" }
        val dismiss = dismisses.single()
        val (resultSource, corroboratingLog) = when (dismiss.result) {
            "PASS" -> "EVENT_JOURNAL" to null
            null -> {
                // Old writers omitted payload.result. Backfill only from the exact logger call paired
                // with this journal row: same state/action/outcome/reason, uniquely within 1 s before it.
                val expectedAction = "round-1:${dismiss.action}"
                val matches = appLogRows.filter { log ->
                    val delta = dismiss.wallTimeMs - log.timestampMs
                    log.state == "WAIT_COMPLETION" && log.action == expectedAction &&
                        log.result == "PASS" && log.stopReason == dismiss.reason &&
                        delta in 0L..MAX_APP_LOG_SKEW_MS
                }
                require(matches.size == 1) {
                    "WORK_WRITEBACK_DISMISS_LOG_CORROBORATION_MISSING_OR_AMBIGUOUS:$sessionId:${matches.size}"
                }
                "APP_LOG_CORROBORATION" to matches.single()
            }
            else -> error("WORK_WRITEBACK_DISMISS_RESULT_NOT_PASS:$sessionId:${dismiss.result}")
        }
        require(dismiss.sequence > award.sequence) { "WORK_WRITEBACK_DISMISS_BEFORE_AWARD:$sessionId" }
        val logDelta = corroboratingLog?.let { dismiss.wallTimeMs - it.timestampMs }
        return Proof(awardCoins, award.sequence, dismiss.reason!!, dismiss.sequence, resultSource,
            corroboratingLog?.timestampMs, logDelta)
    }

    /** The full gate. Throws with a specific reason when the write-back must not happen. */
    fun requireEligible(
        sessionId: String,
        attempt: BudgetAttempt,
        unresolvedOthers: List<String>,
        rows: List<WorkCompletionEvidenceRow>,
        verdict: WorkCompletionVerdict,
        auditAlreadyExists: Boolean,
        evidenceDirectory: String,
        appLogRows: List<WorkCompletionAppLogRow> = emptyList(),
    ): Proof {
        require(!auditAlreadyExists) { "WORK_WRITEBACK_AUDIT_ALREADY_PREPARED_REQUIRES_REVIEW:$sessionId" }
        require(sessionId == TARGET_SESSION_ID) { "WORK_WRITEBACK_SESSION_NOT_ALLOWLISTED:$sessionId" }
        require(attempt.attemptId == AttemptId(sessionId, "work", 1).value) {
            "WORK_WRITEBACK_ATTEMPT_IDENTITY_MISMATCH:$sessionId"
        }
        require(attempt.status == ReserveStatus.UNCONFIRMED_RETAINED) {
            "WORK_WRITEBACK_ATTEMPT_NOT_RETAINED:$sessionId"
        }
        require(attempt.reason == SWEEP_REASON) { "WORK_WRITEBACK_ATTEMPT_REASON_MISMATCH:$sessionId" }
        require(attempt.minutes == TARGET_WORK_MINUTES) { "WORK_WRITEBACK_ATTEMPT_MINUTES_MISMATCH:$sessionId" }
        require(unresolvedOthers.isEmpty()) { "WORK_WRITEBACK_OTHER_PENDING:${unresolvedOthers.joinToString(",")}" }
        val proof = proofFromRows(sessionId, rows, appLogRows)
        // Both proof events must point at the SAME session directory the verdict was read from, so a
        // journal of one session can never be combined with the verdict document of another. Paths are
        // compared canonical so a separator or case difference on Windows never mis-matches.
        val expectedDirectory = runCatching { File(evidenceDirectory).canonicalFile.absolutePath }.getOrNull()
        rows.filter { it.sequence == proof.awardSequence || it.sequence == proof.dismissSequence }.forEach {
            require(expectedDirectory != null && it.evidence.size == 1 &&
                it.evidence.map { path -> File(path).canonicalFile.absolutePath } == listOf(expectedDirectory)) {
                "WORK_WRITEBACK_EVIDENCE_DIRECTORY_MISMATCH:${it.sequence}"
            }
        }
        require(verdict.sessionId == sessionId) { "WORK_WRITEBACK_VERDICT_SESSION_MISMATCH:$sessionId" }
        require(verdict.verdict == "WORK_PASS" && verdict.mode == "work") {
            "WORK_WRITEBACK_VERDICT_NOT_WORK_PASS:$sessionId"
        }
        require(verdict.stopReason == null && verdict.terminalState == "COMPLETED" && verdict.courseStarts == 0) {
            "WORK_WRITEBACK_RUN_NOT_TERMINALLY_COMPLETED:$sessionId"
        }
        require(verdict.workOptionId == TARGET_WORK_OPTION && verdict.workMinutes == attempt.minutes &&
            verdict.workMinutes == TARGET_WORK_MINUTES && verdict.energyCost == 5 && verdict.cleanlinessCost == 2) {
            "WORK_WRITEBACK_WORK_ONLY_RECORD_MISMATCH:$sessionId"
        }
        return proof
    }

    /**
     * Parses the length-prefixed [AttemptId.value] (`"<runLen>:<runId><stepLen>:<stepId>:<iteration>"`)
     * back into its parts, so the observation task can refuse anything that is not a session's own
     * first work attempt.
     */
    fun splitAttemptId(value: String): Triple<String, String, Int> {
        val first = value.indexOf(':')
        require(first > 0) { "WORK_WRITEBACK_ATTEMPT_ID_MALFORMED" }
        val runLen = value.substring(0, first).toIntOrNull() ?: error("WORK_WRITEBACK_ATTEMPT_ID_MALFORMED")
        require(value.length > first + 1 + runLen) { "WORK_WRITEBACK_ATTEMPT_ID_MALFORMED" }
        val runId = value.substring(first + 1, first + 1 + runLen)
        val second = value.indexOf(':', first + 1 + runLen)
        require(second > first + 1 + runLen) { "WORK_WRITEBACK_ATTEMPT_ID_MALFORMED" }
        val stepLen = value.substring(first + 1 + runLen, second).toIntOrNull()
            ?: error("WORK_WRITEBACK_ATTEMPT_ID_MALFORMED")
        require(value.length > second + 1 + stepLen) { "WORK_WRITEBACK_ATTEMPT_ID_MALFORMED" }
        val stepId = value.substring(second + 1, second + 1 + stepLen)
        val iteration = value.substring(second + 1 + stepLen).removePrefix(":").toIntOrNull()
            ?: error("WORK_WRITEBACK_ATTEMPT_ID_MALFORMED")
        return Triple(runId, stepId, iteration)
    }

    /** The audit note appended to the attempt reason; minutes and day never change. */
    fun auditNote(proof: Proof, atMs: Long) =
        "COMPLETION_WRITTEN_BACK;at=$atMs;award=${proof.awardCoins};awardSeq=${proof.awardSequence};" +
            "dismiss=${proof.dismissReason};dismissSeq=${proof.dismissSequence};" +
            "no-refund;no-replay;minutes-unchanged"

    /**
     * Finds the single session directory whose `verdict.json` names [sessionId], so the journal rows
     * and the terminal verdict always come from the SAME session and never from two runs combined.
     */
    fun locateSessionDirectory(sessionsRoot: File, sessionId: String): File {
        val children = sessionsRoot.listFiles() ?: error("WORK_WRITEBACK_SESSIONS_UNREADABLE")
        val matches = children.filter { directory ->
            directory.isDirectory && runCatching {
                LegacySessionEvidence.readVerdict(directory, sessionId).sessionId == sessionId
            }.getOrDefault(false)
        }
        check(matches.size == 1) { "WORK_WRITEBACK_SESSION_DIRECTORY_MISSING_OR_AMBIGUOUS:$sessionId" }
        return matches.single()
    }
}

/** Reads the durable journal and verdict of ONE session with the strict JSON parser; never rewrites them. */
object LegacySessionEvidence {
    private const val MAX_DOCUMENT_BYTES = 1_000_000L
    private const val MAX_JOURNAL_BYTES = 50_000_000L
    private const val MAX_APP_LOG_BYTES = 16_000_000L

    /**
     * The verdict document carries no session id, so identity comes from the run-record in the same
     * directory (it names sessionId and terminalState). Both must agree with [sessionId].
     */
    fun readVerdict(directory: File, sessionId: String): WorkCompletionVerdict {
        val record = LegacySettlementJson.objectAt(readBounded(File(directory, "run-record.json"), MAX_DOCUMENT_BYTES))
        check(record.string("sessionId") == sessionId) { "WORK_WRITEBACK_RECORD_SESSION_MISMATCH:$sessionId" }
        val value = LegacySettlementJson.objectAt(readBounded(File(directory, "verdict.json"), MAX_DOCUMENT_BYTES))
        val work = LegacySettlementJson.objectAt(readBounded(File(directory, "work-only.json"), MAX_DOCUMENT_BYTES))
        return WorkCompletionVerdict(
            sessionId = sessionId,
            verdict = value.string("verdict"),
            mode = value.string("mode"),
            stopReason = value["stopReason"] as? String,
            terminalState = record.string("terminalState"),
            courseStarts = record.long("courseStarts").toInt(),
            workOptionId = work.string("option"),
            workMinutes = work.long("minutes").toInt(),
            energyCost = work.long("energyCost").toInt(),
            cleanlinessCost = work.long("cleanlinessCost").toInt(),
        )
    }

    fun readJournal(journal: File, sessionId: String): List<WorkCompletionEvidenceRow> {
        check(journal.isFile && journal.length() in 1..MAX_JOURNAL_BYTES) {
            "WORK_WRITEBACK_JOURNAL_MISSING_OR_TOO_LARGE:${journal.name}"
        }
        val bytes = journal.readBytes()
        check(bytes.size <= MAX_JOURNAL_BYTES) { "WORK_WRITEBACK_JOURNAL_TOO_LARGE" }
        val text = Charsets.UTF_8.newDecoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .decode(java.nio.ByteBuffer.wrap(bytes)).toString()
        check(text.endsWith("\n")) { "WORK_WRITEBACK_JOURNAL_INCOMPLETE" }
        val rows = mutableListOf<WorkCompletionEvidenceRow>()
        var sequence = 0L
        text.lineSequence().filter { it.isNotBlank() }.forEach { line ->
            val event = LegacySettlementJson.objectAt(line)
            check(event.string("sessionId") == sessionId && event.long("sequence") == ++sequence) {
                "WORK_WRITEBACK_JOURNAL_IDENTITY_OR_SEQUENCE"
            }
            val payload = if (event["payload"] is Map<*, *>) event.obj("payload") else emptyMap()
            rows += WorkCompletionEvidenceRow(
                sequence = sequence,
                wallTimeMs = event.long("wallTimeMs"),
                kind = event.string("kind"),
                action = payload["action"] as? String,
                reason = payload["reason"] as? String,
                result = payload["result"] as? String,
                evidence = if (event["evidence"] is List<*>) {
                    @Suppress("UNCHECKED_CAST") (event["evidence"] as List<Any?>).mapNotNull { it as? String }
                } else emptyList(),
            )
        }
        return rows
    }

    fun readAppLog(file: File): List<WorkCompletionAppLogRow> {
        val text = readBounded(file, MAX_APP_LOG_BYTES)
        check(text.endsWith("\n")) { "WORK_WRITEBACK_APP_LOG_INCOMPLETE" }
        val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).apply { isLenient = false }
        return text.lineSequence().filter { it.isNotBlank() }.map { line ->
            val row = LegacySettlementJson.objectAt(line)
            val timestamp = row.string("timestamp")
            val position = ParsePosition(0)
            val parsed = formatter.parse(timestamp, position)
            check(parsed != null && position.index == timestamp.length) {
                "WORK_WRITEBACK_APP_LOG_TIMESTAMP_INVALID"
            }
            WorkCompletionAppLogRow(
                timestampMs = checkNotNull(parsed).time,
                state = row["state"] as? String,
                action = row["action"] as? String,
                result = row["result"] as? String,
                stopReason = row["stopReason"] as? String,
            )
        }.toList()
    }

    private fun readBounded(file: File, max: Long): String {
        check(file.isFile && file.length() in 1..max) { "WORK_WRITEBACK_FILE_MISSING_OR_TOO_LARGE:${file.name}" }
        val bytes = file.readBytes()
        check(bytes.size <= max) { "WORK_WRITEBACK_FILE_TOO_LARGE" }
        return Charsets.UTF_8.newDecoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .decode(java.nio.ByteBuffer.wrap(bytes)).toString()
    }
}

/**
 * Runs the evidence-only write-back over real files and the authoritative ledger. No Android types,
 * no capture, no input: the whole operation is read evidence -> write audit -> one ledger commit.
 * A crash between the audit and the commit leaves an explicit prepared audit and the retained
 * attempt, which the policy then refuses for review; the attempt can never be rewritten twice.
 */
class WorkCompletionWritebackRunner(
    private val filesDir: File,
    private val externalFilesDir: File,
    private val eventsDirectory: File,
    private val store: BudgetLedgerStore,
    private val writebackRoot: File = File(filesDir, "work-writebacks"),
) {
    fun auditFile(sessionId: String): File {
        require(sessionId.matches(Regex("[A-Za-z0-9_-]{1,128}"))) { "WORK_WRITEBACK_SESSION_ID_INVALID" }
        return File(File(writebackRoot, sessionId), "writeback.json")
    }

    /** True when the stuck attempt looks like the D1 shape (retained sweep marker on a work attempt). */
    fun isRetainedWorkAttempt(attemptId: String): Boolean {
        val attempt = store.read().attempts[attemptId] ?: return false
        val parts = runCatching { WorkCompletionWritebackPolicy.splitAttemptId(attempt.attemptId) }.getOrNull()
            ?: return false
        return attempt.status == ReserveStatus.UNCONFIRMED_RETAINED &&
            attempt.reason == WorkCompletionWritebackPolicy.SWEEP_REASON &&
            parts.first == WorkCompletionWritebackPolicy.TARGET_SESSION_ID &&
            parts.second == "work" && parts.third == 1
    }

    fun run(attemptId: String, nowMs: Long, checkAuthority: () -> Unit = {}): BudgetAttempt {
        val state = store.read()
        val attempt = state.attempts[attemptId] ?: error("BUDGET_ATTEMPT_UNKNOWN:$attemptId")
        val (sessionId, stepId, iteration) = WorkCompletionWritebackPolicy.splitAttemptId(attempt.attemptId)
        check(stepId == "work" && iteration == 1) { "WORK_WRITEBACK_ATTEMPT_STEP_NOT_WORK:$attemptId" }
        // No V2 run may sit pending either: the write-back must not race a live session.
        check(PlanRunPendingGate.blockingRunId(File(filesDir, "plan-runs")) == null) {
            "WORK_WRITEBACK_V2_RUN_PENDING"
        }
        val unresolvedOthers = state.attempts.values
            .filter { it.status !in setOf(ReserveStatus.COMPLETED, ReserveStatus.RECONCILED_STOPPED) &&
                it.attemptId != attemptId }
            .map { it.attemptId }
        val directory = WorkCompletionWritebackPolicy.locateSessionDirectory(
            File(externalFilesDir, "pet_mvp_session"), sessionId)
        val rows = LegacySessionEvidence.readJournal(File(eventsDirectory, "$sessionId.jsonl"), sessionId)
        val needsDismissCorroboration = rows.any {
            it.action == "work-completion-dismiss" && it.reason == "PAYROLL_RETURNED_OUTDOOR" && it.result == null
        }
        val appLogRows = if (needsDismissCorroboration) {
            LegacySessionEvidence.readAppLog(File(filesDir, "pet_mvp.log"))
        } else emptyList()
        val verdict = LegacySessionEvidence.readVerdict(directory, sessionId)
        val audit = auditFile(sessionId)
        // A prepared-but-uncommitted audit from an earlier crash must be reviewed by a human: the
        // policy refuses outright when the audit file already exists.
        val proof = WorkCompletionWritebackPolicy.requireEligible(sessionId, attempt, unresolvedOthers, rows, verdict,
            auditAlreadyExists = audit.exists(), evidenceDirectory = directory.absolutePath, appLogRows = appLogRows)
        checkAuthority()
        val note = WorkCompletionWritebackPolicy.auditNote(proof, nowMs)
        val auditText = EventJson.encode(mapOf(
            "schemaVersion" to 1, "action" to "WRITEBACK_RETAINED_WORK_COMPLETION", "sessionId" to sessionId,
            "attemptId" to attemptId, "at" to nowMs, "minutesBefore" to attempt.minutes,
            "statusBefore" to attempt.status.name, "reasonBefore" to attempt.reason,
            "awardCoins" to proof.awardCoins, "awardSequence" to proof.awardSequence,
            "dismissReason" to proof.dismissReason, "dismissSequence" to proof.dismissSequence,
            "dismissResult" to "PASS", "dismissResultSource" to proof.dismissResultSource,
            "dismissLogTimestampMs" to proof.dismissLogTimestampMs,
            "dismissLogDeltaMs" to proof.dismissLogDeltaMs,
            "verdict" to verdict.verdict, "mode" to verdict.mode, "stopReason" to verdict.stopReason,
            "terminalState" to verdict.terminalState, "courseStarts" to verdict.courseStarts,
            "workOptionId" to verdict.workOptionId, "workMinutes" to verdict.workMinutes,
            "energyCost" to verdict.energyCost, "cleanlinessCost" to verdict.cleanlinessCost,
            "journal" to File(eventsDirectory, "$sessionId.jsonl").absolutePath,
            "runRecord" to File(directory, "run-record.json").absolutePath,
            "workOnlyRecord" to File(directory, "work-only.json").absolutePath,
            "verdictFile" to File(directory, "verdict.json").absolutePath,
            "verdictDirectory" to directory.absolutePath,
            "note" to "Evidence-only bookkeeping. No input, no replay, no refund, minutes unchanged."))
        val parent = checkNotNull(audit.parentFile)
        validateAuditPath(parent)
        createAuditDirectory(writebackRoot)
        validateAuditPath(parent)
        createAuditDirectory(parent)
        validateAuditPath(parent)
        writeOnce(audit, auditText.toByteArray(Charsets.UTF_8)) { validateAuditPath(parent) }
        // A crash here leaves the prepared audit + the retained attempt; the rerun refuses for review.
        checkAuthority()
        val ledger = PlanBudgetLedger(store)
        ledger.completedWithAudit(attemptId, note)
        val after = checkNotNull(ledger.attempt(attemptId)) { "WORK_WRITEBACK_ATTEMPT_MISSING_AFTER_COMMIT" }
        check(after.status == ReserveStatus.COMPLETED && after.minutes == attempt.minutes &&
            after.day == attempt.day && after.limitMinutes == attempt.limitMinutes &&
            (after.reason ?: "").startsWith(attempt.reason ?: "") && after.reason!!.contains(note)) {
            "WORK_WRITEBACK_COMMIT_CHANGED_OTHER_FIELDS"
        }
        return after
    }

    private fun validateAuditPath(parent: File) {
        val canonicalFilesRoot = filesDir.canonicalFile.toPath()
        val canonicalWritebackRoot = writebackRoot.canonicalFile.toPath()
        val canonicalParent = parent.canonicalFile.toPath()
        val canonicalAudit = File(parent, "writeback.json").canonicalFile.toPath()
        check(canonicalWritebackRoot.startsWith(canonicalFilesRoot) &&
            canonicalParent.startsWith(canonicalFilesRoot) &&
            canonicalParent.parent == canonicalWritebackRoot &&
            canonicalAudit.parent == canonicalParent &&
            canonicalAudit.startsWith(canonicalFilesRoot) &&
            !java.nio.file.Files.isSymbolicLink(writebackRoot.toPath()) &&
            !java.nio.file.Files.isSymbolicLink(parent.toPath())) {
            "WORK_WRITEBACK_AUDIT_PATH_INVALID"
        }
    }

    private fun createAuditDirectory(directory: File) {
        if (!directory.exists()) {
            try {
                java.nio.file.Files.createDirectory(directory.toPath())
            } catch (_: java.nio.file.FileAlreadyExistsException) {
                // A concurrent creator is acceptable only if the revalidation sees a safe directory.
            }
        }
        check(directory.isDirectory) { "WORK_WRITEBACK_AUDIT_DIRECTORY_FAILED" }
    }

    private fun writeOnce(target: File, bytes: ByteArray, validateParent: () -> Unit) {
        validateParent()
        val targetPath = target.toPath()
        check(!target.exists() && !java.nio.file.Files.isSymbolicLink(targetPath)) {
            "WORK_WRITEBACK_AUDIT_EXISTS"
        }
        val temp = File.createTempFile(".writeback-", ".tmp", target.parentFile)
        try {
            java.io.FileOutputStream(temp).use { it.write(bytes); it.fd.sync() }
            validateParent()
            check(!target.exists() && !java.nio.file.Files.isSymbolicLink(targetPath)) {
                "WORK_WRITEBACK_AUDIT_EXISTS"
            }
            java.nio.file.Files.move(temp.toPath(), targetPath,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE)
        } finally { java.nio.file.Files.deleteIfExists(temp.toPath()) }
    }
}
