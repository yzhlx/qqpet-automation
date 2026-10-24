package com.ynk.virtualdisplay

import com.ynk.virtualdisplay.automation.DeviceWorkResourceReader
import com.ynk.virtualdisplay.automation.ObservedValue
import com.ynk.virtualdisplay.automation.ObservationSource
import com.ynk.virtualdisplay.automation.RewardRecord
import com.ynk.virtualdisplay.automation.RewardRecordKind
import com.ynk.virtualdisplay.automation.at
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/**
 * The before-side balance injection contract.
 *
 * Measured 2026-10-20: the work detail page's top bar shows 力量/智力/魅力 only - no coin balance
 * line exists there, so the pre-start snapshot taken on that page can never read coins. The course
 * detail page (visited by the controller immediately before the work run) DOES state the balance
 * ("消耗 22（当前 1）…"). The controller passes that reading in, and exactly ONE snapshot - the
 * before-start one - may reuse it. Every other snapshot must come from its own frame, above all
 * "after-completion": a payout must be a difference between two independently read balances, and an
 * after-side that reuses the before number would book a delta of 0 as if it were a measurement.
 */
class DeviceWorkResourceReaderTest {

    private fun reader(
        observed: suspend (String) -> RewardRecord = { at ->
            RewardRecord(RewardRecordKind.OBSERVED_DELTA, at,
                ObservedValue.Unknown("COIN_BALANCE_UNREAD", 1000L, at),
                ObservedValue.Unknown("WORK_SCORE_NOT_A_CUMULATIVE_BALANCE", 1000L, at),
                ObservedValue.Unknown("ENERGY_READ_BY_CARE_FLOW", 1000L, at),
                evidence = "frame")
        },
    ): DeviceWorkResourceReader = DeviceWorkResourceReader(
        // PetFlowContext cannot exist on the JVM; energy stays Unknown on the tested paths.
        care = null,
        readObserved = observed,
        clock = { 1000L },
    )

    private val knownBalance: ObservedValue<Int> =
        ObservedValue.Known(1, 1000L, 1.0, "course-detail-consumption-row", ObservationSource.DIGIT_RECOGNITION)

    @Test
    fun `aBeforeStartSnapshotReusesTheCoursePageBalanceWhenTheFrameHasNoBalanceLine`() = runBlocking {
        val r = reader()
        r.setCoursePageBalance(knownBalance)
        val before = r.snapshot("before-start")
        assertTrue("before coins must come from the course page, got ${before.coins}",
            before.coins is ObservedValue.Known)
        assertEquals(1, (before.coins as ObservedValue.Known).value)
    }

    @Test
    fun `anAfterSnapshotNeverReusesTheBeforeBalance`() = runBlocking {
        val r = reader()
        r.setCoursePageBalance(knownBalance)
        val after = r.snapshot("after-completion")
        assertTrue("after coins must come off its own frame, got ${after.coins}",
            after.coins is ObservedValue.Unknown)
    }

    @Test
    fun `withoutAnInjectedBalanceTheBeforeStartSnapshotStaysUnread`() = runBlocking {
        val r = reader()
        val before = r.snapshot("before-start")
        assertTrue(before.coins is ObservedValue.Unknown)
    }

    @Test
    fun `aFrameThatDoesReadABalanceWinsOverTheInjectedOne`() = runBlocking {
        // If the frame itself ever carries a balance line (the DEVICE-UNCERTAIN case), the frame
        // reading is the truth and the injection must not override it.
        val r = reader(observed = { at ->
            RewardRecord(RewardRecordKind.OBSERVED_DELTA, at,
                ObservedValue.Known(76, 1000L, 1.0, "frame", ObservationSource.DIGIT_RECOGNITION),
                ObservedValue.Unknown("x", 1000L, at),
                ObservedValue.Unknown("x", 1000L, at), evidence = "frame")
        })
        r.setCoursePageBalance(knownBalance)
        val before = r.snapshot("before-start")
        assertEquals(76, (before.coins as ObservedValue.Known).value)
    }

    @Test
    fun `anInjectedUnknownBalanceDoesNotMaskAnUnreadFrame`() = runBlocking {
        val r = reader()
        r.setCoursePageBalance(ObservedValue.Unknown("x", 1000L, "no-reading"))
        val before = r.snapshot("before-start")
        assertTrue(before.coins is ObservedValue.Unknown)
    }

    @Test
    fun `theBeforeStartInjectionAppliesOnlyToThatNamedSnapshot`() = runBlocking {
        val r = reader()
        r.setCoursePageBalance(knownBalance)
        assertTrue("the injection must not leak into other before-named snapshots",
            r.snapshot("before-start-recovered").coins is ObservedValue.Unknown)
    }

    @Test
    fun `workStartEnergyKeepsACarePanelKnownOlderThanSixtySeconds`() {
        // Mirror PetSessionController: room panel read as work-prepared-health, then outdoor →
        // town → venue → option. That walk can exceed the 60s study-health gate; energy is unchanged
        // by UI navigation, so the Known must still authorise the start (else WORK_ENERGY_UNREADABLE).
        val observedAt = 1_000L
        val now = observedAt + 61_000L
        val panel = ObservedValue.Known(24, observedAt, 1.0, "work-prepared-health",
            ObservationSource.DIGIT_RECOGNITION)
        val r = DeviceWorkResourceReader(
            care = null,
            readObserved = { at ->
                RewardRecord(RewardRecordKind.OBSERVED_DELTA, at,
                    ObservedValue.Unknown("COIN_BALANCE_UNREAD", now, at),
                    ObservedValue.Unknown("WORK_SCORE_NOT_A_CUMULATIVE_BALANCE", now, at),
                    ObservedValue.Unknown("ENERGY_READ_BY_CARE_FLOW", now, at),
                    evidence = "frame")
            },
            clock = { now },
            energySource = { panel },
        )
        val energy = r.readEnergy()
        assertTrue("energy must stay Known for work start, got $energy", energy is ObservedValue.Known)
        assertEquals(24, (energy as ObservedValue.Known).value)
        // Contrast: the study-health 60s gate would have made this Stale.
        assertTrue(panel.at(now, 60_000) is ObservedValue.Stale)
    }
}
