package com.ynk.virtualdisplay.automation

import org.junit.Assert.*
import org.junit.Test
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

// Different package from the legacy class embedded in PlanBudgetLedgerTest; no duplicate JVM name.
class PlanRunCoordinatorTest {
    private fun key() = "coordinator-test-${UUID.randomUUID()}"
    private fun accepted(value: PlanRunCoordinator.Admission) = value as PlanRunCoordinator.Admission.Accepted

    /** All workers are waiting before the latch opens: this exercises contention, not serial calls. */
    private fun <T> race(count: Int = 24, action: (Int) -> T): List<T> {
        val pool = Executors.newFixedThreadPool(count)
        val ready = CountDownLatch(count)
        val start = CountDownLatch(1)
        try {
            val futures = (0 until count).map { index ->
                pool.submit(Callable {
                    ready.countDown()
                    check(start.await(10, TimeUnit.SECONDS)) { "start latch timed out" }
                    action(index)
                })
            }
            assertTrue("workers must all be ready", ready.await(10, TimeUnit.SECONDS))
            start.countDown()
            return futures.map { it.get(10, TimeUnit.SECONDS) }
        } finally {
            start.countDown()
            pool.shutdownNow()
            assertTrue("workers must terminate", pool.awaitTermination(10, TimeUnit.SECONDS))
        }
    }

    @Test fun multipleInstancesOnOneDeviceHaveExactlyOneOwnerUnderContention() {
        val device = key()
        val results = race { index -> PlanRunCoordinator(device).admit("req-$index", "run-$index") }
        val grants = results.filterIsInstance<PlanRunCoordinator.Admission.Accepted>()
        assertEquals(1, grants.size)
        assertTrue(grants.single().shouldExecute)
        assertEquals(23, results.filterIsInstance<PlanRunCoordinator.Admission.Rejected>().size)
        val observer = PlanRunCoordinator(device)
        assertEquals(grants.single().runId, observer.active)
        assertTrue(observer.isActive(grants.single().runId))
        observer.finish(grants.single().runId)
    }

    @Test fun simultaneousRedeliveryIssuesOnlyOneExecutionGrantAcrossInstances() {
        val device = key()
        val results = race { index -> accepted(PlanRunCoordinator(device).admit("same-request", "candidate-$index")) }
        assertEquals(1, results.count { it.shouldExecute })
        assertEquals(23, results.count { it.duplicateRequest })
        assertEquals(1, results.map { it.runId }.distinct().size)
        PlanRunCoordinator(device).finish(results.first().runId)
    }

    @Test fun differentDevicesAllowConcurrentOwnersAndScopeReceiptsByDevice() {
        val devices = List(24) { key() }
        val results = race { index -> accepted(PlanRunCoordinator(devices[index]).admit("same-request", "same-run")) }
        assertTrue(results.all { it.shouldExecute })
        devices.forEach { device ->
            assertEquals("same-run", PlanRunCoordinator(device).active)
            PlanRunCoordinator(device).finish("same-run")
        }
    }

    @Test fun blockedGateOnOneDeviceDoesNotSerializeOtherDevices() {
        val gateEntered = CountDownLatch(1)
        val releaseGate = CountDownLatch(1)
        val pool = Executors.newSingleThreadExecutor()
        val firstDevice = key()
        try {
            val first = pool.submit(Callable {
                PlanRunCoordinator(firstDevice, PlanRunCoordinator.PendingGate {
                    gateEntered.countDown()
                    check(releaseGate.await(10, TimeUnit.SECONDS))
                    null
                }).admit("req", "run")
            })
            assertTrue(gateEntered.await(10, TimeUnit.SECONDS))
            val other = PlanRunCoordinator(key())
            assertTrue(accepted(other.admit("req", "run")).shouldExecute)
            other.finish("run")
            releaseGate.countDown()
            assertTrue(accepted(first.get(10, TimeUnit.SECONDS)).shouldExecute)
            PlanRunCoordinator(firstDevice).finish("run")
        } finally {
            releaseGate.countDown()
            pool.shutdownNow()
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS))
        }
    }

    @Test fun stopKeepsOwnershipUntilTerminalFinishAndStaleCallbacksCannotReleaseNextRun() {
        val device = key()
        val first = PlanRunCoordinator(device)
        val second = PlanRunCoordinator(device)
        assertTrue(accepted(first.admit("req1", "run1")).shouldExecute)
        assertFalse(second.stop("unrelated"))
        second.finish("unrelated")
        assertTrue(second.stop("run1"))
        assertTrue(first.stop("run1"))
        assertTrue(first.isStopRequested("run1"))
        assertTrue(second.isActive("run1"))
        assertTrue(second.admit("req2", "run2") is PlanRunCoordinator.Admission.Rejected)
        first.finish("run1")
        assertNull(second.active)
        assertFalse(first.isStopRequested("run1"))
        assertTrue(accepted(second.admit("req2", "run2")).shouldExecute)
        first.finish("run1")
        assertFalse(first.stop("run1"))
        assertEquals("run2", second.active)
        second.finish("run2")
    }

    @Test fun terminalRequestReceiptNeverReexecutesOrClaimsAnotherOwnersChannel() {
        val device = key()
        val c = PlanRunCoordinator(device)
        c.admit("old-request", "old-run")
        c.finish("old-run")
        val idleReceipt = accepted(PlanRunCoordinator(device).admit("old-request", "new-candidate"))
        assertTrue(idleReceipt.duplicateRequest)
        assertFalse(idleReceipt.shouldExecute)
        assertEquals("old-run", idleReceipt.runId)
        assertNull(c.active)
        c.admit("next-request", "next-run")
        val busyReceipt = accepted(PlanRunCoordinator(device).admit("old-request", "other-candidate"))
        assertFalse(busyReceipt.shouldExecute)
        assertEquals("next-run", c.active)
        c.finish("old-run")
        assertEquals("next-run", c.active)
        c.finish("next-run")
    }

    @Test fun runIdsCannotBeRecycledForDifferentRequestsAfterFinish() {
        val c = PlanRunCoordinator(key())
        c.admit("req1", "run")
        c.finish("run")
        assertEquals(PlanRunCoordinator.Admission.Rejected("RUN_ID_ALREADY_USED:run"), c.admit("req2", "run"))
        assertTrue(accepted(c.admit("req2", "new-run")).shouldExecute)
        c.finish("new-run")
    }

    @Test fun readOnlyHealthCheckUsesTheSameDeviceLockInBothDirections() {
        val device = key()
        val work = PlanRunCoordinator(device)
        val health = PlanRunCoordinator(device)
        work.admit("work-request", "work-run")
        assertTrue(health.admit("health-request", "health-run") is PlanRunCoordinator.Admission.Rejected)
        work.finish("work-run")
        assertTrue(accepted(health.admit("health-request", "health-run")).shouldExecute)
        assertTrue(work.admit("study-request", "study-run") is PlanRunCoordinator.Admission.Rejected)
        assertFalse(accepted(work.admit("health-request", "second-health-run")).shouldExecute)
        health.finish("health-run")
    }

    @Test fun persistedPendingGateRejectsRestartWithoutRecordingOrReplayingTheRequest() {
        val device = key()
        val pending = AtomicReference<String?>("persisted-input-pending-run")
        val gate = PlanRunCoordinator.PendingGate { target ->
            assertEquals(device, target)
            pending.get()
        }
        // Fresh coordinator has no local owner; durable pending evidence still blocks it.
        val restarted = PlanRunCoordinator(device, gate)
        assertEquals(PlanRunCoordinator.Admission.Rejected("PENDING_RUN_UNRESOLVED:persisted-input-pending-run"),
            restarted.admit("new-request", "new-run"))
        assertNull(restarted.active)
        assertTrue(PlanRunCoordinator(device, gate).admit("health-request", "health-run") is PlanRunCoordinator.Admission.Rejected)
        restarted.finish("persisted-input-pending-run") // memory cleanup cannot clear durable evidence
        assertTrue(restarted.admit("new-request", "new-run") is PlanRunCoordinator.Admission.Rejected)
        pending.set(null) // explicit outside reconciliation, never automatic replay
        val grant = accepted(restarted.admit("new-request", "new-run"))
        assertTrue(grant.shouldExecute)
        restarted.finish("new-run")
    }

    @Test fun gateReadFailureFailsClosedAndRejectedRequestRemainsRetryable() {
        val device = key()
        val failed = PlanRunCoordinator(device, PlanRunCoordinator.PendingGate { error("disk read failed") })
        assertEquals(PlanRunCoordinator.Admission.Rejected("PENDING_GATE_FAILED:IllegalStateException"),
            failed.admit("req", "run"))
        assertNull(failed.active)
        val reconciled = PlanRunCoordinator(device, PlanRunCoordinator.PendingGate { null })
        assertTrue(accepted(reconciled.admit("req", "run")).shouldExecute)
        reconciled.finish("run")
    }

    @Test fun competingStopAndAdmissionCannotGrantASecondControllerBeforeFinish() {
        val device = key()
        val owner = PlanRunCoordinator(device)
        owner.admit("owner-request", "owner-run")
        val results = race { index ->
            val c = PlanRunCoordinator(device)
            if (index % 2 == 0) {
                c.stop("owner-run")
                null
            } else c.admit("candidate-request-$index", "candidate-run-$index")
        }
        assertTrue(results.filterNotNull().all { it is PlanRunCoordinator.Admission.Rejected })
        assertTrue(owner.isStopRequested("owner-run"))
        assertEquals("owner-run", owner.active)
        owner.finish("owner-run")
    }

    @Test fun concurrentFinishAndReadmissionStillCreatesExactlyOneNewOwner() {
        val device = key()
        PlanRunCoordinator(device).admit("old-request", "old-run")
        val results = race { index ->
            val c = PlanRunCoordinator(device)
            c.finish("old-run")
            c.admit("new-request-$index", "new-run-$index")
        }
        val grants = results.filterIsInstance<PlanRunCoordinator.Admission.Accepted>()
        assertEquals(1, grants.size)
        assertEquals(grants.single().runId, PlanRunCoordinator(device).active)
        PlanRunCoordinator(device).finish(grants.single().runId)
    }

    @Test fun blankIdentifiersAreRejectedBeforeStateMutation() {
        try { PlanRunCoordinator(" "); fail("blank device") } catch (_: IllegalArgumentException) {}
        val c = PlanRunCoordinator(key())
        try { c.admit(" ", "run"); fail("blank request") } catch (_: IllegalArgumentException) {}
        try { c.admit("request", " "); fail("blank run") } catch (_: IllegalArgumentException) {}
        assertNull(c.active)
    }
}
