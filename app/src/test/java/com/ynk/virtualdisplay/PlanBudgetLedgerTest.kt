package com.ynk.virtualdisplay

import com.ynk.virtualdisplay.automation.*
import org.junit.Assert.*
import org.junit.Test

class InMemoryBudgetStore(initial: BudgetLedgerState = BudgetLedgerState("2026-10-21", 30, emptyMap())) : BudgetLedgerStore {
    private var state = initial
    var failWrites = false
    override fun read() = state
    override fun write(state: BudgetLedgerState) {
        if (failWrites) throw IllegalStateException("BUDGET_WRITE_FAILED")
        this.state = state
    }
}

class PlanBudgetLedgerTest {
    @Test fun reserveIsIdempotentPerAttempt() {
        val store = InMemoryBudgetStore()
        val ledger = PlanBudgetLedger(store) { "2026-10-21" }
        val first = ledger.reserve("run:s:1", 30)
        assertTrue(first.newlyCharged)
        val again = ledger.reserve("run:s:1", 30)
        assertFalse("a retried reserve must not charge twice", again.newlyCharged)
        assertEquals(30, again.attempt.minutes)
    }

    @Test fun unconfirmedDispatchKeepsItsMinutes() {
        val store = InMemoryBudgetStore()
        val ledger = PlanBudgetLedger(store) { "2026-10-21" }
        ledger.reserve("run:s:1", 30)
        ledger.unconfirmed("run:s:1", "CAPTURE_LOST")
        val attempt = ledger.attempt("run:s:1")!!
        assertEquals(ReserveStatus.UNCONFIRMED_RETAINED, attempt.status)
        assertEquals(30, attempt.minutes)
    }

    @Test fun completedAttemptIsFinalAndNeverRegresses() {
        val store = InMemoryBudgetStore()
        val ledger = PlanBudgetLedger(store) { "2026-10-21" }
        ledger.reserve("run:s:1", 30)
        ledger.completed("run:s:1")
        ledger.unconfirmed("run:s:1", "late") // must be ignored on a completed attempt
        assertEquals(ReserveStatus.COMPLETED, ledger.attempt("run:s:1")!!.status)
    }

    @Test fun crossMidnightCompletionKeepsTheOriginalReserveDay() {
        val store = InMemoryBudgetStore()
        var day = "2026-10-21"
        val ledger = PlanBudgetLedger(store) { day }
        ledger.reserve("run:s:1", 30)
        day = "2026-10-22"
        // Completing after midnight must not move the reservation to a new day.
        ledger.completed("run:s:1")
        assertEquals("2026-10-21", ledger.attempt("run:s:1")!!.day)
    }

    @Test fun legacyMinutesArePreservedNotRecomputedFromAttempts() {
        val store = InMemoryBudgetStore(BudgetLedgerState("2026-10-21", 120, emptyMap()))
        val ledger = PlanBudgetLedger(store) { "2026-10-21" }
        ledger.reserve("run:s:1", 30)
        // The daily used count the app already showed is unchanged by booking an attempt.
        assertEquals(120, store.read().legacyUsedMinutes)
    }

    @Test fun failedReserveWriteChargesNothing() {
        val store = InMemoryBudgetStore().apply { failWrites = true }
        val ledger = PlanBudgetLedger(store) { "2026-10-21" }
        var threw = false
        try { ledger.reserve("run:s:1", 30) } catch (_: IllegalStateException) { threw = true }
        assertTrue(threw)
        assertNull(store.read().attempts["run:s:1"])
    }
}

class PersistentPlanBudgetTest {
    @get:org.junit.Rule val temporary = org.junit.rules.TemporaryFolder()

    @Test fun malformedLegacyDayCannotSilentlyBecomeAZeroMinuteToday() {
        val file = java.io.File(temporary.root, "budget.bin")
        val ledger = PlanBudgetLedger(PersistentBudgetStore(file,
            legacy = { LegacyBudgetUsage("not-a-date", 120) })) { "2026-10-21" }
        try {
            ledger.usedMinutes()
            fail("invalid legacy day must not authorize a fresh budget")
        } catch (_: IllegalArgumentException) { }
        assertFalse(file.exists())
    }

    @Test fun crossMidnightUpdatesOldAttemptWithoutChargingTheNewDay() {
        val file = java.io.File(temporary.root, "budget.bin")
        var day = "2026-10-21"
        val ledger = PlanBudgetLedger(PersistentBudgetStore(file,
            legacy = { LegacyBudgetUsage(day, 100) })) { day }
        ledger.reserve("old", 10, 480)
        day = "2026-10-22"
        assertEquals(0, ledger.usedMinutes())
        ledger.reserve("new", 30, 480)
        ledger.unconfirmed("old", "CAPTURE_LOST")
        assertEquals(30, ledger.usedMinutes())
        ledger.completed("old")
        ledger.addLegacyMinutes(20)
        val reopened = PlanBudgetLedger(PersistentBudgetStore(file)) { day }
        assertEquals(110, reopened.usedMinutes("2026-10-21"))
        assertEquals(50, reopened.usedMinutes())
        assertEquals("2026-10-21", reopened.attempt("old")!!.day)
        assertEquals(ReserveStatus.COMPLETED, reopened.attempt("old")!!.status)
        assertFalse(reopened.reserve("old", 10, 480).newlyCharged)
        assertEquals(50, reopened.usedMinutes())
    }

    @Test fun changedAttemptParametersAndInvalidCapsCannotCreateNewCharges() {
        val ledger = PlanBudgetLedger(PersistentBudgetStore(java.io.File(temporary.root, "budget.bin"),
            legacy = { LegacyBudgetUsage("2026-10-21", 0) })) { "2026-10-21" }
        ledger.reserve("same", 30, 100)
        for ((minutes, limit) in listOf(31 to 100, 30 to 101)) {
            try {
                ledger.reserve("same", minutes, limit)
                fail("changed attempt parameters must be rejected")
            } catch (e: IllegalStateException) {
                assertTrue(e.message!!.contains("BUDGET_ATTEMPT_CONFLICT"))
            }
        }
        for (limit in listOf(0, 481)) {
            try {
                ledger.reserve("bad-$limit", 1, limit)
                fail("invalid cap must be rejected")
            } catch (_: IllegalArgumentException) { }
        }
        assertEquals(30, ledger.usedMinutes())
    }

    @Test fun uncertainAndCompletedReservationsSurviveFileReopeningWithoutAnotherCharge() {
        val file = java.io.File(temporary.root, "budget.bin")
        val ledger = PlanBudgetLedger(PersistentBudgetStore(file,
            legacy = { LegacyBudgetUsage("2026-10-21", 20) })) { "2026-10-21" }
        ledger.reserve("s", 30)
        ledger.unconfirmed("s", "CAPTURE_LOST")
        val reopened = PlanBudgetLedger(PersistentBudgetStore(file)) { "2026-10-21" }
        assertEquals(50, reopened.usedMinutes())
        assertEquals("CAPTURE_LOST", reopened.attempt("s")!!.reason)
        assertFalse(reopened.reserve("s", 30).newlyCharged)
        reopened.startConfirmed("s")
        reopened.completed("s")
        reopened.completed("s")
        reopened.unconfirmed("s", "late cleanup failure")
        assertEquals(50, PlanBudgetLedger(PersistentBudgetStore(file)) { "2026-10-21" }.usedMinutes())
        assertEquals(ReserveStatus.COMPLETED, reopened.attempt("s")!!.status)
    }

    @Test fun failedAtomicWriteLeavesThePreviouslyReservedBudgetReadable() {
        val file = java.io.File(temporary.root, "budget.bin")
        val ledger = PlanBudgetLedger(PersistentBudgetStore(file,
            legacy = { LegacyBudgetUsage("2026-10-21", 20) })) { "2026-10-21" }
        ledger.reserve("kept", 30)
        var inputs = 0
        try {
            // DataOutput's bounded UTF field rejects this before the atomic file replacement.
            ledger.reserve("x".repeat(70_000), 10)
            inputs++
            fail("a failed durable write must stop input")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("BUDGET_WRITE_FAILED"))
        }
        val reopened = PlanBudgetLedger(PersistentBudgetStore(file)) { "2026-10-21" }
        assertEquals(0, inputs)
        assertEquals(50, reopened.usedMinutes())
        assertNotNull(reopened.attempt("kept"))
        assertNull(reopened.attempt("x".repeat(70_000)))
    }

    @Test fun corruptRecordNeverFallsBackToLegacyOrAnEmptyBudget() {
        val file = java.io.File(temporary.root, "broken.bin")
        file.writeBytes(byteArrayOf(1, 2, 3))
        var legacyReads = 0
        val store = PersistentBudgetStore(file, legacy = {
            legacyReads++
            LegacyBudgetUsage("2026-10-21", 0)
        })
        try {
            store.read()
            fail("corrupt authoritative state must stop input")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("BUDGET_RECORD_INVALID"))
        }
        assertEquals(0, legacyReads)
        assertArrayEquals(byteArrayOf(1, 2, 3), file.readBytes())
    }

    @Test fun concurrentOldAndKeyedStartsCannotSpendPastOneSharedCap() {
        val file = java.io.File(temporary.root, "budget.bin")
        val old = DailyBudgetStore(PersistentBudgetStore(file,
            legacy = { LegacyBudgetUsage("2026-10-21", 20) })) { "2026-10-21" }
        val keyed = PlanBudgetLedger(PersistentBudgetStore(file,
            legacy = { LegacyBudgetUsage("2026-10-21", 20) })) { "2026-10-21" }
        val workers = java.util.concurrent.Executors.newFixedThreadPool(6)
        val start = java.util.concurrent.CountDownLatch(1)
        try {
            val outcomes = (1..20).map { index -> workers.submit(java.util.concurrent.Callable {
                start.await()
                try {
                    if (index % 2 == 0) old.reserveActivity(10, 60)
                    else keyed.reserve("attempt-$index", 10, 60)
                    true
                } catch (e: IllegalStateException) {
                    if (!e.message.orEmpty().contains("DAILY_BUDGET_LIMIT")) throw e
                    false
                }
            }) }
            start.countDown()
            assertEquals(4, outcomes.count { it.get(20, java.util.concurrent.TimeUnit.SECONDS) })
            assertEquals(60, old.usedMinutes())
            assertEquals(60, keyed.usedMinutes())
            assertEquals(60, PlanBudgetLedger(PersistentBudgetStore(file)) { "2026-10-21" }.usedMinutes())
        } finally { workers.shutdownNow() }
    }

    @Test fun concurrentDuplicateReceiptsChargeExactlyOnce() {
        val file = java.io.File(temporary.root, "budget.bin")
        val first = PlanBudgetLedger(PersistentBudgetStore(file,
            legacy = { LegacyBudgetUsage("2026-10-21", 0) })) { "2026-10-21" }
        val second = PlanBudgetLedger(PersistentBudgetStore(file)) { "2026-10-21" }
        val workers = java.util.concurrent.Executors.newFixedThreadPool(6)
        try {
            val results = (1..20).map { index -> workers.submit(java.util.concurrent.Callable {
                (if (index % 2 == 0) first else second).reserve("same", 30).newlyCharged
            }) }
            assertEquals(1, results.count { it.get(20, java.util.concurrent.TimeUnit.SECONDS) })
            assertEquals(30, first.usedMinutes())
        } finally { workers.shutdownNow() }
    }

    @Test fun careInputCanBeJournaledWithZeroGameMinutesEvenAtTheDailyCap() {
        val ledger = PlanBudgetLedger(PersistentBudgetStore(java.io.File(temporary.root, "budget.bin"),
            legacy = { LegacyBudgetUsage("2026-10-21", 480) })) { "2026-10-21" }
        ledger.reserve("care-input", 0, 1)
        ledger.completed("care-input")
        assertEquals(480, ledger.usedMinutes())
        assertEquals(0, ledger.attempt("care-input")!!.minutes)
    }

    @Test fun oldDailyApisAndAttemptApisUseTheSamePersistedTotal() {
        val file = java.io.File(temporary.root, "budget.bin")
        val daily = DailyBudgetStore(PersistentBudgetStore(file,
            legacy = { LegacyBudgetUsage("2026-10-21", 30) })) { "2026-10-21" }
        daily.reserveAttempt("a", 10, 100)
        daily.reserveActivity(20, 100)
        daily.addCourse(10)
        assertEquals(70, daily.usedMinutes())
        assertFalse(daily.canAfford(31, 100))
        val reopened = DailyBudgetStore(PersistentBudgetStore(file)) { "2026-10-21" }
        assertEquals(70, reopened.usedMinutes())
        reopened.completed("a")
        assertEquals(70, daily.usedMinutes())
        daily.resetToday()
        assertEquals("reset must retain keyed possibly spent minutes", 10, reopened.usedMinutes())
        assertEquals(ReserveStatus.COMPLETED, reopened.attempt("a")!!.status)
    }

    @Test fun existingMinutesAndReservationsShareOneHardLimitBeforeInput() {
        val file = java.io.File(temporary.root, "budget.bin")
        val ledger = PlanBudgetLedger(PersistentBudgetStore(file,
            legacy = { LegacyBudgetUsage("2026-10-21", 440) })) { "2026-10-21" }
        ledger.reserve("first", 30, 480)
        var consumingInputs = 0
        try {
            ledger.reserve("over", 11, 480)
            consumingInputs++
            fail("must refuse before consuming input")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message!!.contains("DAILY_BUDGET_LIMIT"))
        }
        assertEquals(0, consumingInputs)
        assertNull(ledger.attempt("over"))
        assertEquals(470, ledger.usedMinutes())
        ledger.reserve("exact", 10, 480)
        assertEquals(480, ledger.usedMinutes())
        assertEquals(480, PlanBudgetLedger(PersistentBudgetStore(file)) { "2026-10-21" }.usedMinutes())
    }

    @Test fun aNewStoreInstanceReadsReservedMinutesAndImportsLegacyOnlyOnce() {
        val file = java.io.File(temporary.root, "budget.bin")
        var legacyReads = 0
        val first = PlanBudgetLedger(PersistentBudgetStore(file, legacy = {
            legacyReads++
            LegacyBudgetUsage("2026-10-21", 120)
        })) { "2026-10-21" }
        assertEquals(120, first.usedMinutes())
        first.reserve("run/工作:1", 30, 480)
        val reopened = PlanBudgetLedger(PersistentBudgetStore(file, legacy = {
            legacyReads++
            LegacyBudgetUsage("2026-10-21", 150)
        })) { "2026-10-21" }
        assertEquals(150, reopened.usedMinutes())
        assertFalse(reopened.reserve("run/工作:1", 30, 480).newlyCharged)
        assertEquals(150, reopened.usedMinutes())
        assertEquals(1, legacyReads)
        assertEquals(120, PersistentBudgetStore(file).read().legacyUsedMinutes)
    }
}

class PlanRunCoordinatorTest {
    @Test fun oneLiveRunAtATime() {
        val c = PlanRunCoordinator(deviceKey="legacy-fixture-${java.util.UUID.randomUUID()}")
        assertTrue(c.admit("req1", "run1") is PlanRunCoordinator.Admission.Accepted)
        val second = c.admit("req2", "run2")
        assertTrue(second is PlanRunCoordinator.Admission.Rejected)
        c.finish("run1")
        assertTrue(c.admit("req2", "run2") is PlanRunCoordinator.Admission.Accepted)
    }

    @Test fun sameRequestIdIsIdempotent() {
        val c = PlanRunCoordinator(deviceKey="legacy-fixture-${java.util.UUID.randomUUID()}")
        val a = c.admit("req1", "run1") as PlanRunCoordinator.Admission.Accepted
        assertFalse(a.duplicateRequest)
        val b = c.admit("req1", "run2") as PlanRunCoordinator.Admission.Accepted
        assertTrue(b.duplicateRequest)
        assertEquals("run1", b.runId)
    }
}
