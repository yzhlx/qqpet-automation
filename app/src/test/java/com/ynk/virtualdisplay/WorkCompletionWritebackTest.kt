package com.ynk.virtualdisplay

import com.ynk.virtualdisplay.automation.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Evidence-only completion write-back for the retained standalone-WORK attempt (defect D1).
 *
 * All facts come from real files on disk (journal + run-record + verdict + a real
 * [PersistentBudgetStore]); the policy must refuse every shape that is not the proven one.
 */
class WorkCompletionWritebackTest {

    @get:Rule val tmp = TemporaryFolder()

    private val sessionId = "d5bf86e8-9c57-4369-b311-f758c94023ca"
    private val attemptId = AttemptId(sessionId, "work", 1).value

    private class Store(dir: File) : BudgetLedgerStore {
        val delegate = PersistentBudgetStore(File(dir, PersistentBudgetStore.FILE_NAME),
            legacy = { LegacyBudgetUsage(null, 0) }, today = { "2026-10-24" })
        override fun read() = delegate.read()
        override fun write(state: BudgetLedgerState) = delegate.write(state)
    }

    private fun fixture(award: Boolean = true, dismiss: Boolean = true, verdict: String = "WORK_PASS",
                        stopReason: String? = null, awardCoins: Int = 68, workMinutes: Int = 10,
                        workOption: String = WorkCompletionWritebackPolicy.TARGET_WORK_OPTION,
                        terminal: String = "COMPLETED", courseStarts: Int = 0,
                        dismissResult: String? = "PASS", appLogCopies: Int = 0,
                        appLogTimestampMs: Long = 2L, appLogResult: String = "PASS",
                        appLogReason: String = "PAYROLL_RETURNED_OUTDOOR",
                         aliasFilesDir: Boolean = false, writebackRootRelativePath: String? = null): Triple<WorkCompletionWritebackRunner, Store, File> {
        val canonicalFilesDir = tmp.newFolder("files")
        val aliasParent = if (aliasFilesDir) tmp.newFolder("files-alias") else null
        val filesDir = aliasParent?.let { File(it, "../files") } ?: canonicalFilesDir
        val external = tmp.newFolder("external")
        val events = tmp.newFolder("events")
        val sessionDir = File(external, "pet_mvp_session/1792782592359")
        sessionDir.mkdirs()
        // JSON strings must escape backslashes; on Windows the absolute path carries them.
        val sessionPath = sessionDir.absolutePath.replace("\\", "\\\\")
        File(sessionDir, "run-record.json").writeText(
            """{"sessionId":"$sessionId","terminalState":"$terminal","runner":"PetSessionController",
                |"rounds":1,"completedRounds":0,"pauseCount":0,"startedAt":1792782592359,"runtimeMs":631201,
                |"stopReason":${stopReason?.let { "\"$it\"" } ?: "null"},"courseStarts":$courseStarts,"requestedRounds":0}
            """.trimMargin().replace("\n", ""))
        File(sessionDir, "work-only.json").writeText(
            """{"option":"$workOption","minutes":$workMinutes,"energyCost":5,"cleanlinessCost":2,"records":"[]"}""")
        File(sessionDir, "verdict.json").writeText(
            """{"verdict":"$verdict","mode":"work","completed":0,"requested":1,"stopReason":
                |${stopReason?.let { "\"$it\"" } ?: "null"},"captureStatistics":{}}
            """.trimMargin().replace("\n", ""))
        val lines = mutableListOf(
            """{"eventId":"a","sessionId":"$sessionId","sequence":1,"wallTimeMs":1,"monotonicMs":1,
               |"bootId":{"status":"KNOWN","value":"b"},"source":"APP_AUTOMATION","kind":"SESSION_STATE",
               |"payload":{"state":"RUNNING"},"evidence":[]}""".trimMargin().replace("\n", ""))
        if (award) lines += """{"eventId":"b","sessionId":"$sessionId","sequence":2,"wallTimeMs":2,"monotonicMs":2,
               |"bootId":{"status":"KNOWN","value":"b"},"source":"APP_AUTOMATION","kind":"ERROR",
               |"payload":{"state":"WAIT_COMPLETION","action":"work-reward-payroll-award",
               |"reason":"award=$awardCoins|balanceDelta=unread"},"evidence":["$sessionPath"]}
            """.trimMargin().replace("\n", "")
        val dismissResultField = dismissResult?.let { ",\"result\":\"$it\"" }.orEmpty()
        if (dismiss) lines += """{"eventId":"c","sessionId":"$sessionId","sequence":3,"wallTimeMs":3,"monotonicMs":3,
               |"bootId":{"status":"KNOWN","value":"b"},"source":"APP_AUTOMATION","kind":"ERROR",
               |"payload":{"state":"WAIT_COMPLETION","action":"work-completion-dismiss",
               |"reason":"PAYROLL_RETURNED_OUTDOOR"$dismissResultField},"evidence":["$sessionPath"]}
            """.trimMargin().replace("\n", "")
        File(events, "$sessionId.jsonl").writeText(lines.joinToString("\n") + "\n")
        if (appLogCopies > 0) {
            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date(appLogTimestampMs))
            val appLogLine = """{"timestamp":"$timestamp","state":"WAIT_COMPLETION","matchScore":null,"action":"round-1:work-completion-dismiss","result":"$appLogResult","stopReason":"$appLogReason"}"""
            File(filesDir, "pet_mvp.log").writeText(List(appLogCopies) { appLogLine }.joinToString("\n") + "\n")
        }
        val store = Store(filesDir)
        val writebackRoot = writebackRootRelativePath?.let { File(filesDir, it) }
            ?: File(filesDir, "work-writebacks")
        val runner = WorkCompletionWritebackRunner(filesDir, external, events, store, writebackRoot)
        return Triple(runner, store, filesDir)
    }

    private fun seedRetained(store: Store) {
        val ledger = PlanBudgetLedger(store)
        ledger.reserve(attemptId, 10, 480)
        ledger.startConfirmed(attemptId)
        ledger.unconfirmed(attemptId, WorkCompletionWritebackPolicy.SWEEP_REASON)
    }

    @Test fun aProvenRetainedWorkAttemptIsCompletedWithAnAudit() {
        val (runner, store, _) = fixture()
        seedRetained(store)
        assertTrue(runner.isRetainedWorkAttempt(attemptId))
        val before = store.read().attempts.getValue(attemptId)
        val after = runner.run(attemptId, 1792800000000L)
        assertEquals(ReserveStatus.COMPLETED, after.status)
        assertEquals(before.minutes, after.minutes)
        assertEquals(before.day, after.day)
        assertTrue((after.reason ?: "").contains("COMPLETION_WRITTEN_BACK"))
        assertTrue((after.reason ?: "").contains("no-refund;no-replay;minutes-unchanged"))
        assertTrue(runner.auditFile(sessionId).isFile)
        val audit = runner.auditFile(sessionId).readText()
        assertTrue(audit.contains("\"terminalState\":\"COMPLETED\""))
        assertTrue(audit.contains("\"courseStarts\":0"))
        assertTrue(audit.contains("\"workMinutes\":10"))
        assertTrue(audit.contains("work-only.json"))
        assertTrue(audit.contains("run-record.json"))
        assertFalse(runner.isRetainedWorkAttempt(attemptId))
    }

    @Test fun aCanonicalFilesDirectoryAliasIsAccepted() {
        val (runner, store, filesDir) = fixture(aliasFilesDir = true)
        assertTrue(filesDir.canonicalFile != filesDir.absoluteFile)
        seedRetained(store)
        val after = runner.run(attemptId, 1L)
        assertEquals(ReserveStatus.COMPLETED, after.status)
        assertTrue(runner.auditFile(sessionId).canonicalFile.isFile)
    }

    @Test fun anOutOfRootAuditParentIsRefusedBeforeAnyDirectoryIsCreated() {
        val (runner, store, filesDir) = fixture(writebackRootRelativePath = "../outside")
        seedRetained(store)
        val before = store.read().attempts.getValue(attemptId)
        val outside = File(filesDir.parentFile, "outside")
        assertFalse(outside.exists())
        val failure = runCatching { runner.run(attemptId, 1L) }
        assertEquals("WORK_WRITEBACK_AUDIT_PATH_INVALID", failure.exceptionOrNull()?.message)
        val after = store.read().attempts.getValue(attemptId)
        assertEquals(before.status, after.status)
        assertEquals(before.minutes, after.minutes)
        assertEquals(before.day, after.day)
        assertEquals(before.limitMinutes, after.limitMinutes)
        assertEquals(before.reason, after.reason)
        assertFalse(outside.exists())
        assertFalse(runner.auditFile(sessionId).exists())
    }


    @Test fun aDifferentPayrollAwardIsRefused() {
        val (runner, store, _) = fixture(awardCoins = 67)
        seedRetained(store)
        assertTrue(runCatching { runner.run(attemptId, 1L) }.isFailure)
        assertEquals(ReserveStatus.UNCONFIRMED_RETAINED, store.read().attempts.getValue(attemptId).status)
    }

    @Test fun aSymlinkedWritebackRootIsRefusedBeforeCreatingTheSessionDirectory() {
        val (runner, store, filesDir) = fixture()
        seedRetained(store)
        val outside = tmp.newFolder("outside-root")
        val writebackRoot = File(filesDir, "work-writebacks")
        createSymbolicLinkOrSkip(writebackRoot, outside)
        val before = store.read().attempts.getValue(attemptId)
        try {
            assertTrue(runCatching { runner.run(attemptId, 1L) }.isFailure)
            val after = store.read().attempts.getValue(attemptId)
            assertEquals(before.status, after.status)
            assertEquals(before.minutes, after.minutes)
            assertEquals(before.day, after.day)
            assertEquals(before.limitMinutes, after.limitMinutes)
            assertEquals(before.reason, after.reason)
            assertTrue(outside.listFiles().orEmpty().isEmpty())
            assertFalse(runner.auditFile(sessionId).exists())
        } finally {
            java.nio.file.Files.deleteIfExists(writebackRoot.toPath())
        }
    }

    @Test fun aSymlinkedSessionDirectoryIsRefusedBeforeWritingTheAudit() {
        val (runner, store, filesDir) = fixture()
        seedRetained(store)
        val outside = tmp.newFolder("outside-session")
        val writebackRoot = File(filesDir, "work-writebacks")
        assertTrue(writebackRoot.mkdir())
        val sessionDirectory = File(writebackRoot, sessionId)
        createSymbolicLinkOrSkip(sessionDirectory, outside)
        val before = store.read().attempts.getValue(attemptId)
        try {
            assertTrue(runCatching { runner.run(attemptId, 1L) }.isFailure)
            val after = store.read().attempts.getValue(attemptId)
            assertEquals(before.status, after.status)
            assertEquals(before.minutes, after.minutes)
            assertEquals(before.day, after.day)
            assertEquals(before.limitMinutes, after.limitMinutes)
            assertEquals(before.reason, after.reason)
            assertTrue(outside.listFiles().orEmpty().isEmpty())
            assertFalse(runner.auditFile(sessionId).exists())
        } finally {
            java.nio.file.Files.deleteIfExists(sessionDirectory.toPath())
        }
    }

    @Test fun workOnlyCompletionMinutesMustMatchAttempt() {
        val (runner, store, _) = fixture(workMinutes = 9)
        seedRetained(store)
        assertTrue(runCatching { runner.run(attemptId, 1L) }.isFailure)
        assertEquals(ReserveStatus.UNCONFIRMED_RETAINED, store.read().attempts.getValue(attemptId).status)
    }

    @Test fun workOnlyCompletionOptionMustMatchVerifiedWork() {
        val (runner, store, _) = fixture(workOption = "work:other")
        seedRetained(store)
        assertTrue(runCatching { runner.run(attemptId, 1L) }.isFailure)
        assertEquals(ReserveStatus.UNCONFIRMED_RETAINED, store.read().attempts.getValue(attemptId).status)
    }

    @Test fun terminalRunMustBeCompleted() {
        val (runner, store, _) = fixture(terminal = "STOPPED")
        seedRetained(store)
        assertTrue(runCatching { runner.run(attemptId, 1L) }.isFailure)
        assertEquals(ReserveStatus.UNCONFIRMED_RETAINED, store.read().attempts.getValue(attemptId).status)
    }

    @Test fun terminalRunMustHaveNoCourseStarts() {
        val (runner, store, _) = fixture(courseStarts = 1)
        seedRetained(store)
        assertTrue(runCatching { runner.run(attemptId, 1L) }.isFailure)
        assertEquals(ReserveStatus.UNCONFIRMED_RETAINED, store.read().attempts.getValue(attemptId).status)
    }

    @Test fun aRunWithoutTheAwardEventIsRefused() {
        val (runner, store, _) = fixture(award = false)
        seedRetained(store)
        val failed = runCatching { runner.run(attemptId, 1L) }
        assertTrue(failed.exceptionOrNull() is IllegalStateException)
        assertEquals(ReserveStatus.UNCONFIRMED_RETAINED, store.read().attempts.getValue(attemptId).status)
    }

    @Test fun aRunWithoutTheDismissEventIsRefused() {
        val (runner, store, _) = fixture(dismiss = false)
        seedRetained(store)
        assertTrue(runCatching { runner.run(attemptId, 1L) }.isFailure)
        assertEquals(ReserveStatus.UNCONFIRMED_RETAINED, store.read().attempts.getValue(attemptId).status)
    }

    @Test fun aMissingJournalDismissResultAcceptsOneCorrelatedPassLog() {
        val (runner, store, _) = fixture(dismissResult = null, appLogCopies = 1)
        seedRetained(store)
        val after = runner.run(attemptId, 1L)
        assertEquals(ReserveStatus.COMPLETED, after.status)
        val audit = runner.auditFile(sessionId).readText()
        assertTrue(audit.contains("\"dismissResultSource\":\"APP_LOG_CORROBORATION\""))
        assertTrue(audit.contains("\"dismissLogDeltaMs\":1"))
    }

    @Test fun aMissingJournalDismissResultWithoutLogIsRefused() {
        val (runner, store, _) = fixture(dismissResult = null)
        seedRetained(store)
        assertTrue(runCatching { runner.run(attemptId, 1L) }.isFailure)
        assertEquals(ReserveStatus.UNCONFIRMED_RETAINED, store.read().attempts.getValue(attemptId).status)
    }

    @Test fun ambiguousPassLogCannotBackfillDismissResult() {
        val (runner, store, _) = fixture(dismissResult = null, appLogCopies = 2)
        seedRetained(store)
        val failure = runCatching { runner.run(attemptId, 1L) }
        assertTrue(failure.exceptionOrNull()?.message?.contains("CORROBORATION_MISSING_OR_AMBIGUOUS") == true)
        assertEquals(ReserveStatus.UNCONFIRMED_RETAINED, store.read().attempts.getValue(attemptId).status)
    }

    @Test fun anAppLogAfterTheEventCannotBackfillDismissResult() {
        val (runner, store, _) = fixture(dismissResult = null, appLogCopies = 1, appLogTimestampMs = 2_000L)
        seedRetained(store)
        assertTrue(runCatching { runner.run(attemptId, 1L) }.isFailure)
        assertEquals(ReserveStatus.UNCONFIRMED_RETAINED, store.read().attempts.getValue(attemptId).status)
    }

    @Test fun anAppLogWithAnotherReasonCannotBackfillDismissResult() {
        val (runner, store, _) = fixture(dismissResult = null, appLogCopies = 1,
            appLogReason = "PAYROLL_RETURNED_ROOM")
        seedRetained(store)
        assertTrue(runCatching { runner.run(attemptId, 1L) }.isFailure)
        assertEquals(ReserveStatus.UNCONFIRMED_RETAINED, store.read().attempts.getValue(attemptId).status)
    }

    @Test fun aPassLogCannotOverrideANonPassJournalResult() {
        val (runner, store, _) = fixture(dismissResult = "OBSERVED", appLogCopies = 1)
        seedRetained(store)
        val failure = runCatching { runner.run(attemptId, 1L) }
        assertTrue(failure.exceptionOrNull()?.message?.contains("DISMISS_RESULT_NOT_PASS") == true)
        assertEquals(ReserveStatus.UNCONFIRMED_RETAINED, store.read().attempts.getValue(attemptId).status)
    }

    @Test fun aNonWorkPassVerdictIsRefused() {
        val (runner, store, _) = fixture(verdict = "STOPPED", stopReason = "WORK_ENERGY_INSUFFICIENT")
        seedRetained(store)
        assertTrue(runCatching { runner.run(attemptId, 1L) }.isFailure)
        assertEquals(ReserveStatus.UNCONFIRMED_RETAINED, store.read().attempts.getValue(attemptId).status)
    }

    @Test fun aSecondRunOverAPreparedAuditIsRefusedForReview() {
        val (runner, store, _) = fixture()
        seedRetained(store)
        runner.run(attemptId, 1L)
        // Simulate a crash after the audit but before the commit: put the attempt back to retained.
        val state = store.read()
        val attempt = state.attempts.getValue(attemptId)
        store.write(state.copy(attempts = state.attempts + (attemptId to
            attempt.copy(status = ReserveStatus.UNCONFIRMED_RETAINED,
                reason = WorkCompletionWritebackPolicy.SWEEP_REASON))))
        val failed = runCatching { runner.run(attemptId, 2L) }
        assertTrue(failed.exceptionOrNull()?.message?.contains("AUDIT_ALREADY_PREPARED") == true)
        assertEquals(ReserveStatus.UNCONFIRMED_RETAINED, store.read().attempts.getValue(attemptId).status)
    }

    @Test fun anotherUnresolvedAttemptRefusesTheWriteback() {
        val (runner, store, _) = fixture()
        seedRetained(store)
        val ledger = PlanBudgetLedger(store)
        val careId = AttemptId(sessionId, "care", 1).value
        ledger.reserve(careId, 0, 480)
        ledger.unconfirmed(careId, "CARE_INVENTORY_UNCONFIRMED")
        assertTrue(runCatching { runner.run(attemptId, 1L) }.isFailure)
        assertEquals(ReserveStatus.UNCONFIRMED_RETAINED, store.read().attempts.getValue(attemptId).status)
    }

    @Test fun aCompletedAttemptIsNeverRewritten() {
        val (runner, store, _) = fixture()
        val ledger = PlanBudgetLedger(store)
        ledger.reserve(attemptId, 10, 480)
        ledger.startConfirmed(attemptId)
        ledger.completed(attemptId)
        assertFalse(runner.isRetainedWorkAttempt(attemptId))
        assertTrue(runCatching { runner.run(attemptId, 1L) }.isFailure)
        assertEquals(ReserveStatus.COMPLETED, store.read().attempts.getValue(attemptId).status)
        // The ledger commit refuses a non-retained attempt even if called directly.
        assertTrue(runCatching { ledger.completedWithAudit(attemptId, "x") }.isFailure)
    }

    private fun createSymbolicLinkOrSkip(link: File, target: File) {
        try {
            java.nio.file.Files.createSymbolicLink(link.toPath(), target.toPath())
        } catch (unsupported: UnsupportedOperationException) {
            org.junit.Assume.assumeTrue("Symbolic links are unsupported: ${unsupported.message}", false)
        } catch (failure: java.nio.file.FileSystemException) {
            val message = "${failure.reason.orEmpty()} ${failure.message.orEmpty()}"
            val unsupported = message.contains("privilege", ignoreCase = true) ||
                message.contains("not supported", ignoreCase = true) ||
                message.contains("unsupported", ignoreCase = true)
            if (unsupported) {
                org.junit.Assume.assumeTrue("Symbolic links are unsupported: $message", false)
            }
            throw failure
        }
    }
}
