package com.ynk.virtualdisplay

import com.ynk.virtualdisplay.automation.RecoveryPageClassification
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class RecoveryPageClassificationTest {
    @Test fun trustedLearningRecoversFromPositivePanelEvidenceWhenOldPixelsMiss(): Unit = runBlocking {
        // Observed regression: the current running-class panel scores 0.703 < 0.97.
        var reads = 0
        val page = RecoveryPageClassification.classify(
            trustedPage = "LEARNING", trustedPageMatches = false,
            healthVisible = false, ambiguousCourse = false, resultVisible = false,
            learningEvidence = { reads++; true },
        )
        assertEquals("LEARNING", page)
        assertEquals(1, reads)
    }

    @Test fun missingOrNegativeEvidenceLeavesUnmatchedLearningUnknown(): Unit = runBlocking {
        assertNull(RecoveryPageClassification.classify("LEARNING", false, false, false, false))
        assertNull(RecoveryPageClassification.classify("LEARNING", false, false, false, false) { false })
    }

    @Test fun unrelatedTrustedPagesNeverConsultLearningText(): Unit = runBlocking {
        for (trusted in listOf("room", "outdoor", "course_list", "course_detail", "result", "HEALTH", "learning")) {
            assertNull(RecoveryPageClassification.classify(trusted, false, false, false, false) {
                fail("Learning text must not admit an unrelated page: $trusted"); true
            })
        }
    }

    @Test fun healthOverlayCannotBeAdmittedByLearningTextOrBackgroundPixels(): Unit = runBlocking {
        assertEquals("HEALTH", RecoveryPageClassification.classify("LEARNING", true, true, false, false) {
            fail("Health overlay must block the learning evidence callback"); true
        })
    }

    @Test fun ambiguousCourseCannotBeAdmittedByLearningText(): Unit = runBlocking {
        assertNull(RecoveryPageClassification.classify("course_detail", true, false, true, false) {
            fail("Ambiguous course must block the learning evidence callback"); true
        })
    }

    @Test fun normalResultTransitionIsNotHiddenByLearningText(): Unit = runBlocking {
        assertEquals("result", RecoveryPageClassification.classify("LEARNING", false, false, false, true) {
            fail("Result proof must precede the new learning evidence"); true
        })
    }

    @Test fun matchingPixelsPreserveLegacyClassificationWithoutCallingText(): Unit = runBlocking {
        for (trusted in listOf("LEARNING", "room", "outdoor", "health", "HEALTH", "course_detail")) {
            assertEquals(trusted, RecoveryPageClassification.classify(trusted, true, false, false, false) {
                fail("Existing pixel proof must remain sufficient"); true
            })
        }
        assertEquals("health", RecoveryPageClassification.classify("health", true, true, false, false))
        assertEquals("result", RecoveryPageClassification.classify("room", false, false, false, true))
    }

    @Test fun callbackCancellationAndFailureAreNotConvertedIntoPageEvidence(): Unit = runBlocking {
        val cancelled = kotlinx.coroutines.CancellationException("caller cancelled OCR")
        val failed = IllegalStateException("OCR unavailable")
        for (failure in listOf(cancelled, failed)) {
            try {
                RecoveryPageClassification.classify("LEARNING", false, false, false, false) { throw failure }
                fail("Evidence failure must propagate")
            } catch (actual: Exception) {
                assertSame(failure, actual)
            }
        }
    }

    private class Store : com.ynk.virtualdisplay.automation.CheckpointStore {
        var checkpoint: com.ynk.virtualdisplay.automation.SessionCheckpoint? = null
        override fun save(checkpoint: com.ynk.virtualdisplay.automation.SessionCheckpoint) { this.checkpoint = checkpoint }
        override fun load() = checkpoint
    }

    @Test fun strategyResultResumesThroughRealRecoveryAndPreservesCheckpoint(): Unit = runBlocking {
        val store = Store()
        var resumed = 0
        val recovery = com.ynk.virtualdisplay.automation.SessionRecovery("session", store, { 1000L }, { 1000L },
            { _, _, _ -> }, {}, {}, { resumed++ })
        recovery.confirm(1, "WAIT_RESULT", "LEARNING")
        recovery.recover("capture interrupted", { false }) {
            val page = RecoveryPageClassification.classify(it.trustedPage, false, false, false, false) { true }
            com.ynk.virtualdisplay.automation.RecoveryObservation(true, true, page)
        }
        assertEquals(1, resumed)
        assertEquals("LEARNING", store.checkpoint!!.trustedPage)
        assertEquals("WAIT_RESULT", store.checkpoint!!.phase)
    }

    @Test fun normalResultResumesOnlyAfterExistingReconcileApproves(): Unit = runBlocking {
        val store = Store()
        var reconciled = 0
        var resumed = 0
        val recovery = com.ynk.virtualdisplay.automation.SessionRecovery("session", store, { 1000L }, { 1000L },
            { _, _, _ -> }, {}, {}, { resumed++ }, reconcile = { checkpoint, page ->
                reconciled++
                checkpoint.trustedPage == "LEARNING" && checkpoint.phase == "WAIT_RESULT" && page == "result"
            })
        recovery.confirm(1, "WAIT_RESULT", "LEARNING")
        recovery.recover("capture interrupted", { false }) {
            val page = RecoveryPageClassification.classify(it.trustedPage, false, false, false, true) {
                fail("Result must go to reconciliation, never through learning text"); true
            }
            com.ynk.virtualdisplay.automation.RecoveryObservation(true, true, page)
        }
        assertEquals(1, reconciled)
        assertEquals(1, resumed)
        assertEquals("result", store.checkpoint!!.trustedPage)
    }

    @Test fun callbackCancellationStopsRecoveryAndPropagatesWithoutRetry(): Unit = runBlocking {
        val store = Store()
        val cancelled = kotlinx.coroutines.CancellationException("cancelled panel observation")
        var reads = 0
        var resumed = 0
        val recovery = com.ynk.virtualdisplay.automation.SessionRecovery("session", store, { 1000L }, { 1000L },
            { _, _, _ -> }, {}, {}, { resumed++ })
        recovery.confirm(1, "WAIT_RESULT", "LEARNING")
        val failure = runCatching {
            recovery.recover("capture interrupted", { false }) {
                val page = RecoveryPageClassification.classify(it.trustedPage, false, false, false, false) {
                    reads++; throw cancelled
                }
                com.ynk.virtualdisplay.automation.RecoveryObservation(true, true, page)
            }
        }.exceptionOrNull()
        // With assertions on (Gradle's test runner), coroutine stack-trace recovery rethrows a copy
        // of the exception, so compare type and message rather than identity.
        assertTrue(failure is kotlinx.coroutines.CancellationException)
        assertEquals(cancelled.message, failure?.message)
        assertEquals(1, reads)
        assertEquals(0, resumed)
        assertEquals(com.ynk.virtualdisplay.automation.SessionRunState.STOPPED, recovery.state)
    }

    @Test fun normalResultStillRequiresReconciliationAndInvalidFrameCannotResume(): Unit = runBlocking {
        // Text classification must not bypass either the existing result attribution gate or
        // frame-validity gate. In particular, this change does not fix INVALID_BLACK_FRAME.
        for (invalidFrame in listOf(false, true)) {
            val store = Store()
            var resumed = 0
            val recovery = com.ynk.virtualdisplay.automation.SessionRecovery("session", store, { 1000L }, { 1000L },
                { _, _, _ -> }, {}, {}, { resumed++ }, maxAttempts = 1)
            recovery.confirm(1, "WAIT_RESULT", "LEARNING")
            val failure = runCatching {
                recovery.recover("capture interrupted", { false }) {
                    val page = RecoveryPageClassification.classify(it.trustedPage, false, false, false, !invalidFrame) { true }
                    com.ynk.virtualdisplay.automation.RecoveryObservation(true, !invalidFrame, page)
                }
            }.exceptionOrNull()
            assertNotNull(failure)
            assertEquals(0, resumed)
            assertEquals(com.ynk.virtualdisplay.automation.SessionRunState.STOPPED, recovery.state)
        }
    }
}
