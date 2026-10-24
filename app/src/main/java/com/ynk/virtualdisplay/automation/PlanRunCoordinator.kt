package com.ynk.virtualdisplay.automation

import java.util.concurrent.ConcurrentHashMap

/**
 * One input owner per device in this JVM, shared by every coordinator instance. HEALTH_CHECK must
 * use the same device key and admission path as consuming runs; it has no lock bypass.
 *
 * This is NOT a cross-process lock or a durable request journal. Production must inject a gate
 * backed by persisted unresolved/non-terminal runs before admitting after restart. The no-argument
 * constructor remains source-compatible, but its default gate only offers in-process exclusion.
 */
class PlanRunCoordinator(
    val deviceKey: String = DEFAULT_DEVICE_KEY,
    private val pendingGate: PendingGate = PendingGate { null },
) {
    /**
     * Returns a persisted unresolved run id, or null only after durable state has been checked.
     * Called while the device's admission monitor is held. Implementations must be read-only and
     * must not call back into the coordinator. A read failure rejects admission (fails closed).
     * All production entry points for a device must use the same durable gate.
     */
    fun interface PendingGate {
        fun blockingRunId(deviceKey: String): String?
    }

    sealed interface Admission {
        /**
         * A duplicate is a receipt for the ORIGINAL run, never a new execution grant. Callers must
         * dispatch input only when [shouldExecute] is true (checking Accepted's type alone is unsafe).
         * Kept as Accepted for compatibility with the existing request receipt API.
         */
        data class Accepted(val runId: String, val duplicateRequest: Boolean) : Admission {
            val shouldExecute: Boolean get() = !duplicateRequest
        }
        data class Rejected(val reason: String) : Admission
    }

    private class DeviceState {
        var activeRunId: String? = null
        var stopRequested = false
        val requestToRun = mutableMapOf<String, String>()
        val usedRunIds = mutableSetOf<String>()
    }

    companion object {
        const val DEFAULT_DEVICE_KEY = "default-device"
        // Entries and receipts intentionally survive finish for this process's lifetime. Removing
        // an idle entry could split two coordinators onto different locks and replay old requests.
        private val devices = ConcurrentHashMap<String, DeviceState>()
    }

    init {
        require(deviceKey.isNotBlank()) { "RUN_DEVICE_KEY_BLANK" }
    }

    private val device = devices.computeIfAbsent(deviceKey) { DeviceState() }

    /** Includes a stopping run until its owner confirms terminal cleanup with finish. */
    val active: String? get() = synchronized(device) { device.activeRunId }

    /**
     * Atomic claim or original receipt. Duplicate receipts never change ownership, including after
     * terminal completion or while a different run owns the device. Rejected requests are retryable.
     */
    enum class ObservationTask { LEGACY_ZERO_MINUTE_CARE_RECONCILIATION, COMMITTED_NON_STUDY_FINALIZATION,
        RETAINED_WORK_COMPLETION_WRITEBACK }

    fun admitObservation(requestId:String,runId:String,task:ObservationTask):Admission =
        admitInternal("observation:${task.name}:$requestId",runId,checkDurable=false)

    fun admit(requestId: String, runId: String): Admission = admitInternal(requestId,runId,checkDurable=true)

    private fun admitInternal(requestId:String,runId:String,checkDurable:Boolean):Admission {
        require(requestId.isNotBlank()) { "RUN_REQUEST_ID_BLANK" }
        require(runId.isNotBlank()) { "RUN_ID_BLANK" }
        return synchronized(device) {
            device.requestToRun[requestId]?.let { return@synchronized Admission.Accepted(it, true) }
            device.activeRunId?.let { return@synchronized Admission.Rejected("RUN_ALREADY_ACTIVE:$it") }
            // A run id is never recycled: an old finish/stop callback must not affect a new owner.
            if (runId in device.usedRunIds) {
                return@synchronized Admission.Rejected("RUN_ID_ALREADY_USED:$runId")
            }
            val pending = try {
                if(checkDurable) pendingGate.blockingRunId(deviceKey) else null
            } catch (e: Exception) {
                if (e is InterruptedException) Thread.currentThread().interrupt()
                return@synchronized Admission.Rejected("PENDING_GATE_FAILED:${e.javaClass.simpleName}")
            }
            if (pending != null) {
                return@synchronized Admission.Rejected("PENDING_RUN_UNRESOLVED:$pending")
            }
            device.activeRunId = runId
            device.stopRequested = false
            device.requestToRun[requestId] = runId
            device.usedRunIds += runId
            Admission.Accepted(runId, false)
        }
    }

    /**
     * Request cancellation without handing the device to another controller. The owner must revoke
     * input, settle pending dispatch and finish terminal cleanup before calling [finish]. Stale ids
     * cannot stop a different owner. Repeated stops of the current owner are idempotent.
     */
    fun stop(runId: String): Boolean = synchronized(device) {
        if (device.activeRunId != runId) false else {
            device.stopRequested = true
            true
        }
    }

    fun isStopRequested(runId: String): Boolean = synchronized(device) {
        device.activeRunId == runId && device.stopRequested
    }

    /** Release only after terminal cleanup; request receipts remain non-executable forever in JVM. */
    fun finish(runId: String) {
        synchronized(device) {
            if (device.activeRunId == runId) {
                device.activeRunId = null
                device.stopRequested = false
            }
        }
    }

    fun isActive(runId: String): Boolean = synchronized(device) { device.activeRunId == runId }
}
