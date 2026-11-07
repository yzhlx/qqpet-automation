package com.ynk.virtualdisplay.automation

import java.io.File
import java.io.IOException
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FileRunStoreTest {
    @get:Rule val temporary = TemporaryFolder(File("../.build-tmp/file-run-store-tests").apply { mkdirs() })
    private fun directory() = temporary.newFolder()
    private fun snapshot(id: String = "run-1"): RunSnapshot {
        val plan = PlanV2(planId = "plan:中文", name = "文本\"\\\n\t😀", dailyBudgetMinutes = 480,
            steps = listOf(WorkStep("w", "work:跑腿"), CareStep("c", 90, 100), StudyStep("s", "study:田径", 2)),
            executionProfile = ExecutionProfile.LEGACY_MANAGED, legacySource = "{\"old\":true}")
        return RunSnapshot(id, RunRequest("req-1", plan, "serial", 4, RunOrigin.LEGACY, Long.MAX_VALUE - 10),
            RunState.RUNNING, startedAtMs = Long.MAX_VALUE - 9)
    }
    private fun intent(run: String = "run-1", step: String = "s", iteration: Int = 1, minutes: Int = 30) =
        ActivityAttempt(AttemptId(run, step, iteration), minutes, AttemptState.INPUT_PENDING)
    private fun rejects(block: () -> Unit) {
        try { block(); fail("Expected failure") } catch (e: AssertionError) { throw e } catch (_: Exception) {}
    }

    @Test fun snapshotAndAllPlanFieldsRoundTripThroughRealJsonFile() {
        val dir = directory(); val first = snapshot()
        FileRunStore(dir).save(first)
        val second = FileRunStore(dir).load("run-1")!!
        assertEquals(first.request, second.request)
        assertEquals(first.runId, second.runId)
        assertEquals(first.state, second.state)
        assertEquals(first.startedAtMs, second.startedAtMs)
        assertEquals(first.counts, second.counts)
        assertEquals(first.steps.map { it.stepId }, second.steps.map { it.stepId })
        assertTrue(File(dir, "run-1.json").readText().startsWith("{\"schemaVersion\":2,"))
    }

    @Test fun intentDispatchConfirmationAndCompletionSurviveNewInstances() {
        val dir = directory(); val store = FileRunStore(dir); store.save(snapshot())
        val a = intent(); store.persistIntent(a)
        assertEquals(a, FileRunStore(dir).attempt(a.attemptId))
        FileRunStore(dir).persistDispatched(a.attemptId, 20)
        FileRunStore(dir).persistStartConfirmed(a.attemptId, 30)
        FileRunStore(dir).persistCompleted(a.attemptId, 40)
        val completed = FileRunStore(dir).attempt(a.attemptId)!!
        assertEquals(AttemptState.COMPLETED, completed.state)
        assertFalse(completed.inputPending)
        assertEquals(20L, completed.inputDispatchedAtMs)
        assertEquals(30L, completed.startConfirmedAtMs)
        assertEquals(40L, completed.completionConfirmedAtMs)
        assertEquals(1, FileRunStore(dir).countsOf("run-1", snapshot().request.planSnapshot).studyRoundsCompleted)
        FileRunStore(dir).persistCompleted(a.attemptId, 999)
        assertEquals(completed, FileRunStore(dir).attempt(a.attemptId))
        rejects { FileRunStore(dir).persistIntent(a) }
    }

    @Test fun pendingAndReasonAreReadOnlyAcrossRestartWithoutDispatch() {
        val dir = directory(); val store = FileRunStore(dir); store.save(snapshot()); val a = intent()
        store.persistIntent(a); store.persistUnconfirmed(a.attemptId, "断连\n保留")
        val bytes = File(dir, "run-1.json").readBytes()
        val restarted = FileRunStore(dir)
        assertTrue(restarted.hasPendingInput("run-1"))
        assertNull(restarted.attempt(a.attemptId)!!.inputDispatchedAtMs)
        assertEquals("断连\n保留", restarted.unconfirmedReason(a.attemptId))
        assertArrayEquals(bytes, File(dir, "run-1.json").readBytes())
        assertEquals(0, restarted.countsOf("run-1", snapshot().request.planSnapshot).studyRoundsCompleted)
    }

    @Test fun finalSnapshotStepTimesEvidenceReasonAndCountsRoundTripWithoutLosingAttempts() {
        val dir = directory(); val store = FileRunStore(dir); val s = snapshot(); store.save(s)
        val work = intent(step = "w", minutes = 10); store.persistIntent(work); store.persistCompleted(work.attemptId, 8)
        val final = s.copy(state = RunState.UNCONFIRMED, counts = RunCounts(1, 0, 0), endedAtMs = Long.MAX_VALUE,
            stopReason = "CLEANUP_UNKNOWN", steps = s.steps.mapIndexed { index, e -> when (index) {
                0 -> e.copy(state = StepState.COMPLETED, startedAtMs = 1, endedAtMs = 8, evidenceIds = listOf("工资😀"))
                1 -> e.copy(state = StepState.UNCONFIRMED, reason = "UNKNOWN", startedAtMs = 9, endedAtMs = 10)
                else -> e
            } })
        store.save(final)
        val read = FileRunStore(dir).load("run-1")!!
        assertEquals(final.counts, read.counts); assertEquals(final.endedAtMs, read.endedAtMs)
        assertEquals(final.stopReason, read.stopReason)
        final.steps.zip(read.steps).forEach { (a, b) ->
            assertEquals(a.state, b.state); assertEquals(a.reason, b.reason); assertEquals(a.startedAtMs, b.startedAtMs)
            assertEquals(a.endedAtMs, b.endedAtMs); assertEquals(a.evidenceIds, b.evidenceIds)
        }
        assertEquals(AttemptState.COMPLETED, FileRunStore(dir).attempt(work.attemptId)!!.state)
    }

    @Test fun actualCommitFailurePreservesOriginalBytesAndPendingFlags() {
        val dir = directory(); val good = FileRunStore(dir); good.save(snapshot())
        val a = intent(); good.persistIntent(a)
        val original = File(dir, "run-1.json").readBytes()
        val broken = FileRunStore(dir, beforeCommit = { throw IOException("injected rename failure") })
        rejects { broken.persistDispatched(a.attemptId, 20) }
        rejects { broken.save(snapshot().copy(stopReason = "NEW")) }
        assertArrayEquals(original, File(dir, "run-1.json").readBytes())
        assertEquals(a, FileRunStore(dir).attempt(a.attemptId))
        assertEquals(listOf("run-1.json"), dir.list()!!.toList())
    }

    @Test fun corruptionIsRejectedAndNeverOverwrittenWithDefaults() {
        val dir = directory(); val store = FileRunStore(dir); store.save(snapshot())
        val f = File(dir, "run-1.json"); f.writeText("{broken")
        rejects { FileRunStore(dir).load("run-1") }
        rejects { store.save(snapshot()) }
        assertEquals("{broken", f.readText())
    }

    @Test fun unknownStoreAndPlanSchemasAreRejectedWithoutRewriting() {
        val dir = directory(); val store = FileRunStore(dir); store.save(snapshot())
        val f = File(dir, "run-1.json"); val original = f.readText()
        for (bad in listOf(original.replaceFirst("\"schemaVersion\":2", "\"schemaVersion\":99"),
            original.replace("\"schemaVersion\":2,\"planId\"", "\"schemaVersion\":99,\"planId\""))) {
            f.writeText(bad); rejects { store.load("run-1") }; assertEquals(bad, f.readText())
        }
    }

    @Test fun missingRunReturnsNullButInvalidRunIdsNeverAccessFiles() {
        val dir = directory(); val store = FileRunStore(dir)
        assertNull(store.load("missing"))
        for (id in listOf("../outside", "a/b", "a\\b", ".", "..", "C:escape", "a.json", "", "x".repeat(129))) {
            rejects { store.load(id) }; rejects { store.save(snapshot(id)) }
        }
        assertEquals(0, dir.list()!!.size)
    }

    @Test fun unknownStepWrongIterationAndZeroWorkDurationCannotBePersisted() {
        val dir = directory(); val store = FileRunStore(dir); store.save(snapshot())
        rejects { store.persistIntent(intent(step = "unknown")) }
        rejects { store.persistIntent(intent(step = "w", iteration = 2)) }
        rejects { store.persistIntent(intent(iteration = 3)) }
        rejects { store.persistIntent(intent(step = "w", minutes = 0)) }
        store.persistIntent(intent(step = "c", minutes = 0))
        assertEquals(1, store.attempts("run-1").size)
    }

    @Test fun duplicateIntentAndRepeatedObservationsDoNotRewriteOrRegressCompletion() {
        val dir = directory(); val store = FileRunStore(dir); store.save(snapshot()); val a = intent()
        store.persistIntent(a); val original = File(dir, "run-1.json").readBytes()
        FileRunStore(dir).persistIntent(a)
        assertArrayEquals(original, File(dir, "run-1.json").readBytes())
        rejects { store.persistIntent(a.copy(durationMinutes = 10)) }
        store.persistCompleted(a.attemptId, 10)
        rejects { store.persistUnconfirmed(a.attemptId, "late") }
        rejects { store.persistDispatched(a.attemptId, 10) }
        assertEquals(AttemptState.COMPLETED, store.attempt(a.attemptId)!!.state)
    }

    @Test fun strictJsonRejectsDuplicatesTrailingGarbageFloatsAndMissingFields() {
        val dir = directory(); val store = FileRunStore(dir); store.save(snapshot())
        val f = File(dir, "run-1.json"); val original = f.readText()
        val invalid = listOf(original + "false", original.replaceFirst("\"schemaVersion\":2", "\"schemaVersion\":2,\"schemaVersion\":2"),
            original.replaceFirst("\"displayId\":4", "\"displayId\":4.0"),
            original.replaceFirst("\"displayId\":4,", ""), original.replaceFirst("\"displayId\":4", "\"displayId\":04"))
        invalid.forEach { text -> f.writeText(text); rejects { store.load("run-1") }; assertEquals(text, f.readText()) }
    }

    @Test fun invalidUtf8AndMismatchedEmbeddedIdAreRejected() {
        val dir = directory(); val store = FileRunStore(dir); store.save(snapshot())
        val f = File(dir, "run-1.json"); val original = f.readText()
        f.writeBytes(byteArrayOf(0xc3.toByte(), 0x28)); rejects { store.load("run-1") }
        f.writeText(original.replace("\"runId\":\"run-1\"", "\"runId\":\"other\"")); rejects { store.load("run-1") }
    }

    @Test fun twoAdaptersReadModifyWriteWithoutClobberingEachOthersAttempts() {
        val dir = directory(); val a = FileRunStore(dir); val b = FileRunStore(dir); a.save(snapshot())
        a.persistIntent(intent(iteration = 1)); b.persistIntent(intent(iteration = 2))
        a.persistStartConfirmed(intent().attemptId, 5)
        assertEquals(2, b.attempts("run-1").size)
        assertEquals(AttemptState.START_CONFIRMED, b.attempt(intent().attemptId)!!.state)
        assertTrue(b.hasPendingInput("run-1"))
    }
}
