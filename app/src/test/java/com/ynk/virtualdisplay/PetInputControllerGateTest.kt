package com.ynk.virtualdisplay

import android.content.Context
import android.view.InputEvent
import android.view.Surface
import com.ynk.virtualdisplay.automation.ActiveCapturedFrame
import com.ynk.virtualdisplay.automation.AutomationFacts
import com.ynk.virtualdisplay.automation.EventClock
import com.ynk.virtualdisplay.automation.FrameLivenessResult
import com.ynk.virtualdisplay.automation.FrameStatus
import com.ynk.virtualdisplay.automation.InjectAttemptLedger
import com.ynk.virtualdisplay.automation.ObservationSource
import com.ynk.virtualdisplay.automation.ObservedValue
import com.ynk.virtualdisplay.automation.PetInputController
import com.ynk.virtualdisplay.automation.releaseAttemptedDown
import com.ynk.virtualdisplay.data.repository.ConnectionStatus
import com.ynk.virtualdisplay.data.repository.IDisplayRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The refusal half of [PetInputController]: what the controller does when the start-of-action
 * freshness barrier does not authorise the action.
 *
 * Every assertion here is deliberately about the ABSENCE of a side effect - no pending-action
 * registration, no injected DOWN, no INPUT_SENT record - because that is the property that keeps an
 * unauthorised tap from reaching the pet display. Only refusal paths are exercised, which is why
 * this can be a plain JVM test: a refusal is raised before any MotionEvent is constructed.
 */
class PetInputControllerGateTest {
    private class MutableClock(var mono: Long = 10_000L) : EventClock {
        override fun wallTimeMs() = 1_000L
        override fun monotonicMs() = mono
        override val bootId = ObservedValue.Known("boot-fixture", 1_000L, 1.0, "fixture", ObservationSource.SYSTEM)
    }

    /** Only the two display-state flows and the injection counter matter for a refusal. */
    private class FakeRepository(dir: File) : IDisplayRepository {
        override val automationEventsDirectory: File = dir
        override suspend fun captureDisplay(displayId: Int): Result<ActiveCapturedFrame> =
            Result.failure(IllegalStateException("CAPTURE_NOT_USED_IN_REFUSAL_TEST"))
        override val connectionStatus = MutableStateFlow(ConnectionStatus.CONNECTED)
        override val managedDisplayIds = MutableStateFlow(setOf(4))
        override fun bindService(context: Context) = Unit
        override fun unbindService() = Unit
        override suspend fun createDisplay(name: String, width: Int, height: Int, dpi: Int) = Result.success(4)
        override suspend fun releaseDisplay(displayId: Int) = Result.success(Unit)
        override suspend fun setDisplaySurface(displayId: Int, surface: Surface?) = Result.success(Unit)
        override suspend fun resizeDisplay(displayId: Int, width: Int, height: Int, dpi: Int) = Result.success(Unit)
        override suspend fun launchApp(packageName: String, displayId: Int) = Result.success(0)
        override suspend fun launchHome(displayId: Int) = Result.success(0)
        override suspend fun injectInput(event: InputEvent) = Result.success(true)
        /** Counts every event that would have reached the target display; must stay 0. */
        var injected = 0
        override suspend fun injectInputWithDisplayId(event: InputEvent, displayId: Int): Result<Boolean> {
            injected++
            return Result.success(true)
        }
        override fun isShizukuAvailable() = true
        override fun destroyService() = Unit
    }

    private class Fixture {
        val dir: File = Files.createTempDirectory("gate-fixture").toFile()
        val clock = MutableClock()
        val facts = AutomationFacts(dir, 4, clock)
        val repository = FakeRepository(dir)
        val controller = PetInputController(repository, 4, 720, 1600, facts)
        var pending = 0
        init { facts.markActionPending = { pending++ } }
        /** The journal file is created on first append, so "no file" means "no event". */
        private fun journalLines(): List<String> =
            if (facts.events.file.exists()) facts.events.file.readLines() else emptyList()
        fun inputSentCount() = journalLines().count { it.contains("\"kind\":\"INPUT_SENT\"") }
        fun refusals() = journalLines().filter { it.contains("INPUT_FRESHNESS") }
        fun close() = dir.deleteRecursively()
    }

    private fun assertRefused(fixture: Fixture, marker: String) {
        val failure = runBlocking { runCatching { fixture.controller.tap(360f, 800f, 0L) } }.exceptionOrNull()
        assertNotNull("the action must be refused", failure)
        assertTrue("expected $marker, got '${failure?.message}'", failure?.message?.contains(marker) == true)
        assertEquals("a refused action must not be registered as pending", 0, fixture.pending)
        assertEquals("a refused action must not deliver the DOWN", 0, fixture.repository.injected)
        assertEquals("a refused action must not journal INPUT_SENT", 0, fixture.inputSentCount())
    }

    @Test fun anActionBeforeAnyFrameIsRefusedAndTouchesNothing() {
        val fixture = Fixture()
        try {
            assertRefused(fixture, "FRAME_NOT_TRUSTED")
        } finally { fixture.close() }
    }

    @Test fun anUntimedFrameIsTrustedForLivenessYetCanNotAuthoriseAnAction() {
        val fixture = Fixture()
        try {
            // No capture receipt: trusted for liveness, but not a timestamp an action may rely on.
            fixture.facts.frame(FrameLivenessResult(FrameStatus.VALID), "TEST_UNKNOWN_RECEIPT")
            assertTrue("an untimed VALID frame still opens the trust barrier", fixture.facts.frames.inputAllowed)
            assertRefused(fixture, "FRAME_FRESHNESS_UNKNOWN")
            assertTrue("the refusal must be journaled", fixture.refusals().any { it.contains("FRAME_FRESHNESS_UNKNOWN") })
        } finally { fixture.close() }
    }

    @Test fun reValidatingTheSameUntimedFrameDoesNotRefreshItIntoAnAuthorisingOne() {
        val fixture = Fixture()
        try {
            // Re-validating a stale buffer is a liveness observation, never a new capture receipt.
            repeat(3) {
                fixture.facts.frame(FrameLivenessResult(FrameStatus.VALID), "REVALIDATED_CACHE")
                fixture.clock.mono += 100L
            }
            assertRefused(fixture, "FRAME_FRESHNESS_UNKNOWN")
            assertTrue(fixture.facts.frames.inputAllowed)
        } finally { fixture.close() }
    }

    @Test fun anAuthorisingFrameThatAgedOutIsRefusedRatherThanTrusted() {
        val fixture = Fixture()
        try {
            fixture.facts.frame(FrameLivenessResult(FrameStatus.VALID, "fixture", fixture.clock.mono), "TEST")
            fixture.clock.mono += 5_001L
            assertRefused(fixture, "FRAME_TOO_OLD")
        } finally { fixture.close() }
    }

    @Test fun aRefusedActionLeavesTheNextActionNoAttestationToInherit() {
        val fixture = Fixture()
        try {
            fixture.facts.frame(FrameLivenessResult(FrameStatus.VALID, "fixture", fixture.clock.mono), "TEST")
            fixture.clock.mono += 5_001L
            assertRefused(fixture, "FRAME_TOO_OLD")
            // A later action may bypass the controller entirely; it must still record no attestation.
            fixture.facts.input("CONTINUOUS_DRAG", mapOf("points" to 130))
            val sent = fixture.facts.events.file.readLines().filter { it.contains("\"kind\":\"INPUT_SENT\"") }
            assertEquals(1, sent.size)
            assertTrue("an unattested action must not inherit a withdrawn attestation",
                sent[0].contains("\"authorizingFrameAgeMs\":null"))
        } finally { fixture.close() }
    }

    // ---- an UNACKNOWLEDGED inject call is not evidence that nothing reached the display ----
    //
    // The tap/gesture body itself constructs MotionEvents, which a plain JVM test can not build, so
    // the two decisions that guard the batch budget and the pressed display are extracted into the
    // Android-free [InjectAttemptLedger] and [releaseAttemptedDown]. These tests execute the real
    // rule with a fake transport that applies an event and only THEN refuses to acknowledge it.

    /** Applies the event to its (fake) display, then fails the acknowledgement. */
    private class SideEffectThenFailingTransport {
        val applied = mutableListOf<String>()
        var failWith: Throwable? = null
        var returnFalse = true
        fun inject(action: String): Boolean {
            applied += action
            failWith?.let { throw it }
            return !returnFalse
        }
    }

    @Test fun anInjectCallIsRecordedAsAnAttemptBeforeItCanFailOrRefuseItsAck() {
        val ledger = InjectAttemptLedger()
        val transport = SideEffectThenFailingTransport()
        // The transport applies the DOWN and then answers false: it may already have pressed the pet.
        val accepted = runBlocking {
            runCatching {
                ledger.attempt(isDown = true, rejected = "ACTION_DOWN rejected") { transport.inject("DOWN") }
            }.exceptionOrNull()
        }
        assertNotNull("a false ack must be refused", accepted)
        assertEquals("the side effect did happen", listOf("DOWN"), transport.applied)
        assertEquals("the attempt is on record even though it was refused", 1, ledger.attempts)
        assertEquals("a refused ack is not a confirmation", 0, ledger.confirmedInScope())
        assertTrue("a possibly-applied DOWN must be releasable", ledger.downAttempted)

        // A throwing transport behaves the same way.
        val throwing = InjectAttemptLedger()
        transport.failWith = IllegalStateException("TRANSPORT_FAULT")
        assertNotNull(runBlocking {
            runCatching {
                throwing.attempt(isDown = true, rejected = "ACTION_DOWN rejected") { transport.inject("DOWN") }
            }.exceptionOrNull()
        })
        assertEquals(1, throwing.attempts)
        assertTrue(throwing.downAttempted)
    }

    @Test fun anAttemptTotalIsMonotonicSoACallerCanProveWhetherItsOwnActionStartedOne() {
        val ledger = InjectAttemptLedger()
        val before = ledger.attempts
        // A guard refusal or a construction failure starts no inject call at all.
        assertEquals("nothing was started", before, ledger.attempts)
        ledger.beginScope()
        assertTrue("a fresh scope must not inherit the previous action's DOWN",
            !ledger.downAttempted)
        runBlocking { ledger.attempt(isDown = false, rejected = "REJECTED") { true } }
        assertEquals(1, ledger.attempts)
        assertEquals(1, ledger.confirmedInScope())
        // A later scope reports only its own acknowledgements, while the total never regresses.
        ledger.beginScope()
        assertEquals(0, ledger.confirmedInScope())
        assertEquals(1, ledger.attempts)
    }

    @Test fun aRecordedDownIsReleasedOnItsOwnDisplayEvenWhenItsAckFailed() {
        val released = mutableListOf<Int>()
        val failures = mutableListOf<String>()
        val ledger = InjectAttemptLedger()
        val transport = SideEffectThenFailingTransport()
        runBlocking {
            runCatching { ledger.attempt(isDown = true, rejected = "ACTION_DOWN rejected") { transport.inject("DOWN") } }
            // The release is driven by the ATTEMPT, never by the acknowledgement.
            releaseAttemptedDown(ledger.downAttempted, displayId = 4,
                recordFailure = { id, reason, _ -> failures += "$id:$reason" }) { id -> released += id }
        }
        assertEquals("no acknowledgement was ever received", 0, ledger.confirmedInScope())
        assertEquals("the DOWN reached the display, so its release must too", listOf(4), released)
        assertTrue("a successful release is not a failure: $failures", failures.isEmpty())
    }

    @Test fun aGuardRefusalBeforeTheInjectionReleasesNothing() {
        val released = mutableListOf<Int>()
        // downAttempted=false: the freshness/pause/display guard or the construction refused it.
        runBlocking { releaseAttemptedDown(false, displayId = 4, recordFailure = { _, _, _ -> }) { id -> released += id } }
        assertTrue("nothing was injected, so no release may be sent: $released", released.isEmpty())
    }

    @Test fun aFailedReleaseIsRecordedSeparatelyAndNeverReportedAsDone() {
        val failures = mutableListOf<String>()
        val halted = mutableListOf<String>()
        val ledger = InjectAttemptLedger()
        val thrown = assertThrows(IllegalStateException::class.java) {
            runBlocking {
                ledger.attempt(isDown = true, rejected = "rejected") { true }
                releaseAttemptedDown(ledger.downAttempted, displayId = 4,
                    recordFailure = { id, reason, cause -> failures += "$id:$reason:${cause.message}" },
                    halt = { halted += it }) {
                    error("UP transport refused")
                }
            }
        }
        assertEquals("UP transport refused", thrown.message)
        assertEquals(listOf("4:ACTION_UP_CLEANUP_FAILED:UP transport refused"), failures)
        assertEquals("an unconfirmed release must halt the session", listOf("ACTION_UP_CLEANUP_FAILED"), halted)
    }

    @Test fun anUpRefusedByTheTransportHaltFailsTheActionInsteadOfBeingSwallowed() {
        val failures = mutableListOf<String>()
        val halted = mutableListOf<String>()
        val ledger = InjectAttemptLedger()
        // Mirrors the real release body: deliver(up) rejects a false ack, which propagates out of the
        // release lambda exactly like this.
        val thrown = assertThrows(IllegalStateException::class.java) {
            runBlocking {
                ledger.attempt(isDown = true, rejected = "rejected") { true }
                releaseAttemptedDown(ledger.downAttempted, displayId = 4,
                    recordFailure = { id, reason, _ -> failures += "$id:$reason" },
                    halt = { halted += it }) { id ->
                    check(id == 4 && false) { "ACTION_UP rejected" }
                }
            }
        }
        assertEquals("ACTION_UP rejected", thrown.message)
        assertEquals(listOf("4:ACTION_UP_CLEANUP_FAILED"), failures)
        assertEquals(listOf("ACTION_UP_CLEANUP_FAILED"), halted)
    }

    @Test fun anUpThatCouldNotEvenBeConstructedFailsTheActionAndHalts() {
        val failures = mutableListOf<String>()
        val halted = mutableListOf<String>()
        val ledger = InjectAttemptLedger()
        // The UP MotionEvent is built INSIDE the release lambda, so a construction failure is a
        // release failure: it must halt and fail, never be read as a completed cleanup.
        val thrown = assertThrows(IllegalStateException::class.java) {
            runBlocking {
                ledger.attempt(isDown = true, rejected = "rejected") { true }
                releaseAttemptedDown(ledger.downAttempted, displayId = 4,
                    recordFailure = { id, reason, _ -> failures += "$id:$reason" },
                    halt = { halted += it }) { error("UP event construction failed") }
            }
        }
        assertEquals("UP event construction failed", thrown.message)
        assertEquals(listOf("4:ACTION_UP_CLEANUP_FAILED"), failures)
        assertEquals(listOf("ACTION_UP_CLEANUP_FAILED"), halted)
    }

    @Test fun anUnconfirmedUpHaltsTheSessionSoNoLaterDownCanBeSent() {
        val fixture = Fixture()
        try {
            // A fresh, authorising frame: without the halt this would open a new action.
            fun authorise() {
                fixture.facts.frame(FrameLivenessResult(FrameStatus.VALID, "fixture", fixture.clock.mono), "TEST")
            }
            authorise()
            assertNull(runBlocking { runCatching { fixture.facts.requireFreshInput() }.exceptionOrNull() })

            fixture.facts.haltInput("ACTION_UP_CLEANUP_FAILED")
            // The frame barrier is deliberately still satisfied: the halt is what refuses, and it is
            // checked before every other guard so a fresh frame can not outrank it.
            authorise()
            assertTrue(fixture.facts.frames.inputAllowed)
            val refused = runBlocking { runCatching { fixture.facts.requireFreshInput() }.exceptionOrNull() }
            assertNotNull(refused)
            assertTrue("expected the halt marker, got '${refused?.message}'",
                refused?.message?.contains("SESSION_INPUT_HALTED_BY_UNCONFIRMED_UP:ACTION_UP_CLEANUP_FAILED") == true)
            val trusted = runBlocking { runCatching { fixture.facts.requireInput() }.exceptionOrNull() }
            assertTrue("the plain trust gate must refuse too",
                trusted?.message?.contains("SESSION_INPUT_HALTED_BY_UNCONFIRMED_UP") == true)
        } finally { fixture.close() }
    }

    @Test fun noRecoveryPathQuietlyClearsTheUnconfirmedUpHalt() {
        val fixture = Fixture()
        try {
            fixture.facts.frame(FrameLivenessResult(FrameStatus.VALID, "fixture", fixture.clock.mono), "TEST")
            fixture.facts.haltInput("ACTION_UP_CLEANUP_FAILED")
            // Every ordinary un-pause route is attempted, then a brand-new frame is offered.
            assertThrows(IllegalStateException::class.java) { fixture.facts.rearmCapture() }
            fixture.facts.beginAction()
            runCatching { fixture.facts.resumeInput() }
            fixture.facts.frame(FrameLivenessResult(FrameStatus.VALID, "fixture", fixture.clock.mono), "TEST")
            assertEquals("ACTION_UP_CLEANUP_FAILED", fixture.facts.inputHalted)
            val refused = runBlocking { runCatching { fixture.facts.requireFreshInput() }.exceptionOrNull() }
            assertTrue("expected the halt to survive recovery, got '${refused?.message}'",
                refused?.message?.contains("SESSION_INPUT_HALTED_BY_UNCONFIRMED_UP") == true)
        } finally { fixture.close() }
    }

    @Test fun anOriginalBusinessFailureIsNotMaskedByItsUnconfirmedRelease() {
        val failures = mutableListOf<String>()
        val halted = mutableListOf<String>()
        val ledger = InjectAttemptLedger()
        // The business body already failed; its unconfirmed release must not replace that cause.
        val thrown = assertThrows(IllegalStateException::class.java) {
            runBlocking {
                ledger.attempt(isDown = true, rejected = "rejected") { true }
                val primary = IllegalStateException("BUSINESS_BODY_FAILED")
                try {
                    releaseAttemptedDown(ledger.downAttempted, displayId = 4,
                        recordFailure = { id, reason, _ -> failures += "$id:$reason" },
                        halt = { halted += it }) { error("UP transport refused") }
                }
                catch(upFailure: Throwable) {
                    primary.addSuppressed(upFailure)
                    throw primary
                }
            }
        }
        assertEquals("the original cause must survive", "BUSINESS_BODY_FAILED", thrown.message)
        assertEquals("and the release failure must be attached, not dropped",
            "UP transport refused", thrown.suppressed.single().message)
        assertEquals(listOf("4:ACTION_UP_CLEANUP_FAILED"), failures)
        assertEquals(listOf("ACTION_UP_CLEANUP_FAILED"), halted)
    }

    @Test fun aReleaseThatFailsBecauseItWasCancelledIsAlsoOnRecord() {
        val failures = mutableListOf<String>()
        val halted = mutableListOf<String>()
        val ledger = InjectAttemptLedger()
        val cancelled = assertThrows(CancellationException::class.java) {
            runBlocking {
                ledger.attempt(isDown = true, rejected = "rejected") { true }
                releaseAttemptedDown(ledger.downAttempted, displayId = 4,
                    recordFailure = { id, reason, _ -> failures += "$id:$reason" },
                    halt = { halted += it }) { throw CancellationException("scope torn down") }
            }
        }
        assertEquals("scope torn down", cancelled.message)
        assertEquals(listOf("4:ACTION_UP_CLEANUP_CANCELLED"), failures)
        assertEquals("a cancelled release halts the session too", listOf("ACTION_UP_CLEANUP_CANCELLED"), halted)
    }
}
