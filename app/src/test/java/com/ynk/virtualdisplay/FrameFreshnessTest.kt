package com.ynk.virtualdisplay

import com.ynk.virtualdisplay.automation.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

/**
 * The start-of-action freshness contract. Pure JVM: it exercises the gate directly, because the
 * Android input injector (MotionEvent / Bitmap / Context) is unavailable in local unit tests.
 */
class FrameFreshnessTest {
    private fun fresh(atMs: Long) = FrameLivenessResult(FrameStatus.VALID, "fixture", atMs)

    private fun rejected(block: () -> Unit): String =
        runCatching(block).exceptionOrNull()?.message
            ?: fail("expected the freshness gate to refuse this action").toString()

    private fun assertRefused(marker: String, block: () -> Unit) =
        assertTrue("expected $marker, got '${rejected(block)}'", rejected(block).contains(marker))

    // ---- the 5000 ms budget itself ----

    @Test fun frameAtExactlyTheBudgetIsAccepted() {
        val gate = FrameTrustGate(3)
        gate.observe(fresh(1000L))
        gate.requireFreshInput(6000L, 5000L)
        assertEquals(5000L, gate.trustedFrameAgeMs(6000L)!!)
    }

    @Test fun ageOneMillisecondOverBudgetIsRefused() {
        val gate = FrameTrustGate(3)
        gate.observe(fresh(1000L))
        assertRefused("FRAME_TOO_OLD") { gate.requireFreshInput(6001L, 5000L) }
        assertTrue(rejected { gate.requireFreshInput(6001L, 5000L) }.contains("5001"))
    }

    @Test fun defaultBudgetIsFiveSeconds() {
        val gate = FrameTrustGate(3)
        gate.observe(fresh(100L))
        gate.requireFreshInput(5100L)
        assertRefused("FRAME_TOO_OLD") { gate.requireFreshInput(5101L) }
    }

    @Test fun absurdBudgetIsRejectedRatherThanTreatedAsUnbounded() {
        val gate = FrameTrustGate(3)
        gate.observe(fresh(1000L))
        assertRefused("FRAME_FRESHNESS_BUDGET_INVALID") { gate.requireFreshInput(2000L, 0L) }
    }

    // ---- fail-closed cases ----

    @Test fun noAcceptedFrameRefusesAnAction() {
        val gate = FrameTrustGate(3)
        assertRefused("FRAME_NOT_TRUSTED") { gate.requireFreshInput(1000L) }
        assertNull(gate.trustedFrameAgeMs(1000L))
    }

    @Test fun unknownReceiptIsRefusedEvenThoughTheFrameIsTrusted() {
        val gate = FrameTrustGate(3)
        // A trusted-but-untimed frame (the historical one-argument form) must not be assumed fresh.
        gate.observe(FrameLivenessResult(FrameStatus.VALID))
        gate.requireInputAllowed()
        assertNull(gate.trustedFrameAgeMs(1000L))
        assertRefused("FRAME_FRESHNESS_UNKNOWN") { gate.requireFreshInput(1000L) }
    }

    @Test fun aRolledBackClockIsUnknownRatherThanNegativeAndFresh() {
        val gate = FrameTrustGate(3)
        gate.observe(fresh(5000L))
        assertRefused("CLOCK_ROLLBACK") { gate.requireFreshInput(4999L) }
    }

    @Test fun anInvalidFrameWithdrawsTheFreshnessOfTheAcceptedOne() {
        val gate = FrameTrustGate(3)
        gate.observe(fresh(1000L))
        gate.requireFreshInput(6000L)
        gate.observe(FrameLivenessResult(FrameStatus.INVALID_BLACK_FRAME))
        assertRefused("FRAME_NOT_TRUSTED") { gate.requireFreshInput(1001L) }
        assertNull(gate.trustedFrameAgeMs(1001L))
    }

    @Test fun aLatchedStopCanNotBeBypassedByAnyFrame() {
        val gate = FrameTrustGate(3)
        repeat(3) { gate.observe(FrameLivenessResult(FrameStatus.CAPTURE_FAILED)) }
        assertTrue(gate.stopped)
        gate.observe(fresh(9000L))
        assertRefused("FRAME_NOT_TRUSTED") { gate.requireFreshInput(9000L) }
    }

    // ---- no self-renewal: the trap this change exists to avoid ----

    @Test fun reobservingTheSameFrameDoesNotRenewIt() {
        val gate = FrameTrustGate(3)
        val frame = fresh(1000L)
        gate.observe(frame)
        gate.requireFreshInput(6000L)
        // Looking at the same accepted frame again, much later, must not make it look young.
        gate.observe(frame)
        assertEquals(19000L, gate.trustedFrameAgeMs(20000L)!!)
        assertRefused("FRAME_TOO_OLD") { gate.requireFreshInput(20000L) }
    }

    @Test fun aRefusalChangesNoStateAndSpendsNothing() {
        val gate = FrameTrustGate(3)
        gate.observe(fresh(1000L))
        repeat(5) { assertRefused("FRAME_TOO_OLD") { gate.requireFreshInput(90000L) } }
        assertEquals(0, gate.consecutiveFailures)
        assertFalse(gate.stopped)
        assertTrue(gate.inputAllowed)
        // The frame is still the same frame: still old, and never silently renewed by the refusals.
        assertEquals(89000L, gate.trustedFrameAgeMs(90000L)!!)
    }

    @Test fun onlyAGenuinelyNewFrameReAuthorisesAnAction() {
        val gate = FrameTrustGate(3)
        gate.observe(fresh(1000L))
        assertRefused("FRAME_TOO_OLD") { gate.requireFreshInput(20000L) }
        gate.observe(fresh(19000L))
        gate.requireFreshInput(20000L)
        assertEquals(1000L, gate.trustedFrameAgeMs(20000L)!!)
    }

    @Test fun recoveryRearmClearsFreshnessAndNeedsARealNewFrame() {
        val gate = FrameTrustGate(3)
        gate.observe(fresh(1000L))
        gate.rearmForRecovery()
        assertRefused("FRAME_NOT_TRUSTED") { gate.requireFreshInput(1001L) }
        gate.observe(fresh(7000L))
        gate.requireFreshInput(7000L)
    }

    // ---- the backward-compatible trust barrier is unchanged ----

    @Test fun theTrustBarrierStillAcceptsAnUntimedValidFrame() {
        val gate = FrameTrustGate(3)
        gate.observe(FrameLivenessResult(FrameStatus.VALID))
        gate.requireInputAllowed()
        assertTrue(gate.inputAllowed)
        assertFalse(gate.stopped)
    }

    // ---- the refused action leaves no trace on the input journal ----

    private class MutableClock(var mono: Long = 10_000L) : EventClock {
        override fun wallTimeMs() = 1_000L
        override fun monotonicMs() = mono
        override val bootId = ObservedValue.Known("boot-fixture", 1_000L, 1.0, "fixture", ObservationSource.SYSTEM)
    }

    @Test fun aRefusedActionSendsNoInputEventAndJournaledRefusalIsRecorded() {
        val dir = Files.createTempDirectory("fresh-refusal").toFile()
        try {
            val clock = MutableClock()
            val facts = AutomationFacts(dir, 4, clock)
            facts.frame(FrameLivenessResult(FrameStatus.VALID, "fixture", clock.mono), "TEST")
            clock.mono += 5_001L // the authorising frame is now one millisecond over budget
            assertRefused("FRAME_TOO_OLD") { facts.requireFreshInput() }
            // Nothing was delivered, so the action must not claim an authorising frame.
            assertEquals(0, facts.events.file.readLines().count { it.contains("\"kind\":\"INPUT_SENT\"") })
            val refusal = facts.events.file.readLines().firstOrNull { it.contains("INPUT_FRESHNESS") }
            assertNotNull("the refusal must be journaled, not silent", refusal)
            assertTrue(refusal!!.contains("FRAME_TOO_OLD"))
        } finally { dir.deleteRecursively() }
    }

    @Test fun anAuthorisedActionCarriesTheAttestedFrameAge() {
        val dir = Files.createTempDirectory("fresh-attest").toFile()
        try {
            val clock = MutableClock()
            val facts = AutomationFacts(dir, 4, clock)
            facts.frame(FrameLivenessResult(FrameStatus.VALID, "fixture", clock.mono), "TEST")
            clock.mono += 1_200L
            facts.requireFreshInput()
            facts.input("TAP", mapOf("x" to 1f))
            val sent = facts.events.file.readLines().first { it.contains("\"kind\":\"INPUT_SENT\"") }
            assertTrue("INPUT_SENT must attest the age of its authorising frame", sent.contains("\"authorizingFrameAgeMs\":1200"))
            assertTrue(sent.contains("\"freshnessBudgetMs\":5000"))
        } finally { dir.deleteRecursively() }
    }

    @Test fun anActionThatBypassedTheGateCanNotInheritAnEarlierAttestation() {
        val dir = Files.createTempDirectory("fresh-inherit").toFile()
        try {
            val clock = MutableClock()
            val facts = AutomationFacts(dir, 4, clock)
            facts.frame(FrameLivenessResult(FrameStatus.VALID, "fixture", clock.mono), "TEST")
            facts.requireFreshInput()
            facts.input("TAP", mapOf("x" to 1f))
            // A second action that never passed the gate must not re-use the first action's age.
            facts.input("CONTINUOUS_DRAG", mapOf("points" to 130))
            val sent = facts.events.file.readLines().filter { it.contains("\"kind\":\"INPUT_SENT\"") }
            assertEquals(2, sent.size)
            assertTrue(sent[1].contains("\"authorizingFrameAgeMs\":null"))
        } finally { dir.deleteRecursively() }
    }

    // ---- a frame with no capture receipt is trusted for liveness but authorises nothing ----

    @Test fun anUntimedFrameOpensTheTrustBarrierYetRefusesANewAction() {
        val dir = Files.createTempDirectory("fresh-untimed").toFile()
        try {
            val clock = MutableClock()
            val facts = AutomationFacts(dir, 4, clock)
            facts.frame(FrameLivenessResult(FrameStatus.VALID), "TEST_UNKNOWN_RECEIPT")
            // Liveness still passes: an untimed frame is a real observation of a live display.
            facts.requireInput()
            assertTrue(facts.frames.inputAllowed)
            // The freshness barrier is what refuses it, and it must say so rather than assume fresh.
            assertRefused("FRAME_FRESHNESS_UNKNOWN") { facts.requireFreshInput() }
            facts.input("TAP", mapOf("x" to 1f))
            val sent = facts.events.file.readLines().first { it.contains("\"kind\":\"INPUT_SENT\"") }
            assertTrue("an action with no authorising frame must attest null",
                sent.contains("\"authorizingFrameAgeMs\":null"))
        } finally { dir.deleteRecursively() }
    }

    @Test fun reObservingTheSameUntimedFrameNeverStampsItWithTheCurrentClock() {
        val dir = Files.createTempDirectory("fresh-restamp").toFile()
        try {
            val clock = MutableClock()
            val facts = AutomationFacts(dir, 4, clock)
            // Re-validating a queued buffer or a cache entry is not a new capture receipt: the age of
            // the accepted frame must stay unknown however many times it is looked at.
            repeat(4) {
                facts.frame(FrameLivenessResult(FrameStatus.VALID), "REVALIDATED_CACHE")
                clock.mono += 250L
                assertRefused("FRAME_FRESHNESS_UNKNOWN") { facts.requireFreshInput() }
            }
            val observations = facts.events.file.readLines().filter { it.contains("\"kind\":\"FRAME_VALID\"") }
            assertEquals(4, observations.size)
            observations.forEach {
                assertTrue("an untimed frame must stay un-stamped: $it", it.contains("\"frameObservedAtMs\":0"))
                assertTrue("and be disclosed as such: $it", it.contains("\"receiptSource\":\"UNKNOWN\""))
            }
        } finally { dir.deleteRecursively() }
    }

    @Test fun anInvalidFrameIsDisclosedAsHavingNoReceiptToState() {
        val dir = Files.createTempDirectory("fresh-invalid-receipt").toFile()
        try {
            val facts = AutomationFacts(dir, 4, MutableClock())
            facts.frame(FrameLivenessResult(FrameStatus.INVALID_BLACK_FRAME), "TEST")
            val invalid = facts.events.file.readLines().first { it.contains("\"kind\":\"FRAME_INVALID\"") }
            // Not "UNKNOWN": an invalid frame never had a receipt instant to be unknown about.
            assertTrue(invalid.contains("\"receiptSource\":\"NOT_APPLICABLE\""))
        } finally { dir.deleteRecursively() }
    }

    @Test fun aStampedFrameIsDisclosedAsTransportReceipted() {
        val dir = Files.createTempDirectory("fresh-stamped").toFile()
        try {
            val facts = AutomationFacts(dir, 4, MutableClock())
            facts.frame(FrameLivenessResult(FrameStatus.VALID, "fixture", 7_000L), "TEST")
            val valid = facts.events.file.readLines().first { it.contains("\"kind\":\"FRAME_VALID\"") }
            assertTrue(valid.contains("\"receiptSource\":\"TRANSPORT\""))
            assertTrue(valid.contains("\"frameObservedAtMs\":7000"))
        } finally { dir.deleteRecursively() }
    }

    // ---- an incomplete gesture withdraws the attestation it was granted ----

    @Test fun aGestureAbortedMidWayCanNotAuthoriseTheNextAction() {
        val dir = Files.createTempDirectory("fresh-abort").toFile()
        try {
            val clock = MutableClock()
            val facts = AutomationFacts(dir, 4, clock)
            facts.frame(FrameLivenessResult(FrameStatus.VALID, "fixture", clock.mono), "TEST")
            // A gesture starts, is authorised, then is stopped by a refused MOVE before it can
            // record its own INPUT_SENT - the shape PetInputController.drag() leaves behind.
            facts.beginAction()
            facts.requireFreshInput()
            facts.endAction(delivered = false)
            facts.input("CONTINUOUS_DRAG", mapOf("points" to 130))
            val sent = facts.events.file.readLines().first { it.contains("\"kind\":\"INPUT_SENT\"") }
            assertTrue("an aborted gesture must not leave its attestation for a later input",
                sent.contains("\"authorizingFrameAgeMs\":null"))
        } finally { dir.deleteRecursively() }
    }

    @Test fun aDeliveredGestureKeepsItsOwnAttestationForItsOwnRecord() {
        val dir = Files.createTempDirectory("fresh-delivered").toFile()
        try {
            val clock = MutableClock()
            val facts = AutomationFacts(dir, 4, clock)
            facts.frame(FrameLivenessResult(FrameStatus.VALID, "fixture", clock.mono), "TEST")
            clock.mono += 900L
            facts.beginAction()
            facts.requireFreshInput()
            facts.input("CONTINUOUS_DRAG", mapOf("points" to 130))
            facts.endAction(delivered = true)
            val sent = facts.events.file.readLines().first { it.contains("\"kind\":\"INPUT_SENT\"") }
            // Its own record carries the age attested when the gesture was authorised.
            assertTrue(sent.contains("\"authorizingFrameAgeMs\":900"))
            // ...but only for its own record: the next action starts from nothing and, because it
            // never passed the gate, may claim no authorising frame at all.
            facts.beginAction()
            facts.input("TAP", mapOf("x" to 1f))
            val after = facts.events.file.readLines().filter { it.contains("\"kind\":\"INPUT_SENT\"") }
            assertEquals(2, after.size)
            assertTrue(after[1].contains("\"authorizingFrameAgeMs\":null"))
        } finally { dir.deleteRecursively() }
    }

    @Test fun pausingOrRearmingWithdrawsAnOutstandingAttestation() {
        val dir = Files.createTempDirectory("fresh-withdraw").toFile()
        try {
            val clock = MutableClock()
            val facts = AutomationFacts(dir, 4, clock)
            facts.frame(FrameLivenessResult(FrameStatus.VALID, "fixture", clock.mono), "TEST")
            facts.requireFreshInput()
            // Recovery intervenes between the check and the input: the grant must not survive it.
            facts.inhibitInput()
            facts.input("TAP", mapOf("x" to 1f))
            val sent = facts.events.file.readLines().first { it.contains("\"kind\":\"INPUT_SENT\"") }
            assertTrue(sent.contains("\"authorizingFrameAgeMs\":null"))
        } finally { dir.deleteRecursively() }
    }
}
