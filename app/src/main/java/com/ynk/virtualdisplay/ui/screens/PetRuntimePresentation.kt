package com.ynk.virtualdisplay.ui.screens

import com.ynk.virtualdisplay.automation.ManagedRuntimeState
import com.ynk.virtualdisplay.automation.ObservedValue
import com.ynk.virtualdisplay.automation.PetRuntime
import com.ynk.virtualdisplay.automation.SessionRunState

/**
 * Read-only presentation mapping for the managed session snapshot.
 *
 * This file is deliberately pure JVM code: no Android, Compose or automation-side-effect imports,
 * so the mapping is unit-testable and can never send input, publish state or repair anything.
 * Only [com.ynk.virtualdisplay.automation.PetManagedRuntime] publishes; this layer only reads.
 *
 * Trust rules enforced here:
 *  - A snapshot is only "current" when the service is running *and* that snapshot is in an active
 *    state. A settled (COMPLETED/FAILED/STOPPED) snapshot that is still on screen while a new
 *    service runs is a startup transition of the previous session, never a current result.
 *  - Duration for a settled state is the value published at finalization; only active states are
 *    recomputed from the monotonic clock.
 *  - KNOWN/UNKNOWN/STALE/UNSUPPORTED stay distinct, and a reading is only "current" when it belongs
 *    to the current session and is fresh.
 *  - This layer never decides course readiness. It only reports whether the readings shown are
 *    current observations; the authoritative >=90 policy lives in the automation layer.
 */
enum class ManagedLifecycle { NOT_STARTED, RUNNING, PAUSED, RESUMING, COMPLETED, STOPPED, FAILED }

enum class ManagedLifecycleTone { NEUTRAL, ACTIVE, ATTENTION, SUCCESS, FAILURE }

/** Ownership of the shown snapshot relative to the service that is running right now. */
enum class ManagedSessionPhase {
    /** No snapshot exists and nothing is running. */
    NOT_STARTED,
    /** The service is running but the new session has not published its active snapshot yet. */
    STARTING,
    /** The snapshot belongs to the session running right now. */
    CURRENT,
    /** The shown snapshot is a settled result of an earlier session. */
    HISTORICAL,
}

/** Mirrors [ObservedValue]'s four states so the UI can keep them visibly distinct. */
enum class HealthDisplayStatus { KNOWN, UNKNOWN, STALE, UNSUPPORTED }

data class HealthDisplayLine(
    val label: String,
    val status: HealthDisplayStatus,
    val valueText: String,
    val detail: String,
    /** True only for a fresh Known reading owned by the current session. */
    val currentReading: Boolean,
)

data class ManagedRuntimeView(
    val lifecycle: ManagedLifecycle,
    val lifecycleText: String,
    val tone: ManagedLifecycleTone,
    /** True only for a verified completed batch; paused/resuming/failed/stopped are never success. */
    val successful: Boolean,
    val sessionPhase: ManagedSessionPhase,
    /** The snapshot belongs to the session the service is running right now. */
    val sessionIsCurrent: Boolean,
    /** The snapshot is a settled result of an earlier session and must be shown as history. */
    val sessionIsHistorical: Boolean,
    /** The service restarts a session while the previous settled snapshot is still displayed. */
    val sessionIsStartingTransition: Boolean,
    val sessionLabelText: String,
    /** Legacy PetRuntime status text, always shown so early start errors stay visible. */
    val legacyStatusText: String,
    val legacyActive: Boolean,
    val flowText: String,
    val stepText: String,
    val roundText: String,
    val completedText: String,
    val durationText: String,
    val pauseText: String,
    val nextStepText: String,
    /** States only which readings are current observations; never a course-readiness verdict. */
    val confirmedReadingsText: String,
    val health: List<HealthDisplayLine>,
) {
    /**
     * Parses the "轮次：N/M" line back into a pair for headline composition. Null when the snapshot
     * carries no round information at all, so the caller never prints a fabricated "第 0 节".
     */
    val roundOfTotal: Pair<Int, Int>?
        get() {
            val body = roundText.substringAfter('：', roundText)
            val parts = body.split('/')
            if (parts.size != 2) return null
            val current = parts[0].trim().toIntOrNull() ?: return null
            val total = parts[1].trim().toIntOrNull() ?: return null
            return if (total <= 0) null else current to total
        }

    /** Bare "MM:SS"/"H:MM:SS" duration without the "运行时长：" label, for inline sentences. */
    val durationValue: String get() = durationText.substringAfter('：', durationText)

    /** Bare "N/M" or "N 节" completion figure without the "已完成：" label. */
    val completedValue: String get() = completedText.substringAfter('：', completedText)
}

object ManagedRuntimePresentation {
    /** Same freshness window the controller applies to the managed snapshot. */
    const val HEALTH_MAX_AGE_MS: Long = 60_000L

    private val TERMINAL_STATES = setOf(SessionRunState.COMPLETED, SessionRunState.FAILED, SessionRunState.STOPPED)
    private val ACTIVE_STATES = setOf(SessionRunState.RUNNING, SessionRunState.PAUSED, SessionRunState.RESUMING)

    fun isTerminal(runState: SessionRunState): Boolean = runState in TERMINAL_STATES

    fun isActive(runState: SessionRunState): Boolean = runState in ACTIVE_STATES

    fun of(managed: ManagedRuntimeState, legacy: PetRuntime.Snapshot, wallNowMs: Long, elapsedNowMs: Long): ManagedRuntimeView {
        val aged = managed.at(wallNowMs, elapsedNowMs)
        val lifecycle = lifecycleOf(aged.runState)
        val phase = sessionPhase(managed, legacy)
        val currentSession = phase == ManagedSessionPhase.CURRENT
        val health = listOf(
            healthLine("体力", aged.energy, currentSession),
            healthLine("清洁", aged.cleanliness, currentSession),
            healthLine("心情", aged.mood, currentSession),
        )
        return ManagedRuntimeView(
            lifecycle = lifecycle,
            lifecycleText = lifecycleText(lifecycle),
            tone = toneOf(lifecycle),
            successful = lifecycle == ManagedLifecycle.COMPLETED,
            sessionPhase = phase,
            sessionIsCurrent = phase == ManagedSessionPhase.CURRENT,
            sessionIsHistorical = phase == ManagedSessionPhase.HISTORICAL,
            sessionIsStartingTransition = phase == ManagedSessionPhase.STARTING,
            sessionLabelText = sessionLabel(phase, managed.sessionId),
            legacyStatusText = legacy.status,
            legacyActive = legacy.running,
            flowText = aged.currentFlow,
            stepText = aged.currentStep,
            roundText = if (managed.totalRounds <= 0) "轮次：未开始" else "轮次：${managed.round}/${managed.totalRounds}",
            completedText = if (managed.totalRounds <= 0) "已完成：${managed.completedRounds} 节"
                else "已完成：${managed.completedRounds}/${managed.totalRounds}",
            durationText = durationText(managed, elapsedNowMs),
            pauseText = pauseText(managed.pauseCount, managed.pauseReason),
            nextStepText = managed.nextStep,
            confirmedReadingsText = confirmedReadingsText(aged, currentSession),
            health = health,
        )
    }

    /**
     * Ownership is decided by the snapshot state, never by [PetRuntime.Snapshot.running] alone.
     * A settled snapshot seen while a fresh service runs is the previous session's result during
     * the new session's startup window.
     */
    fun sessionPhase(managed: ManagedRuntimeState, legacy: PetRuntime.Snapshot): ManagedSessionPhase {
        val hasSnapshot = managed.sessionId != null || managed.runState != SessionRunState.IDLE
        if (!hasSnapshot) return if (legacy.running) ManagedSessionPhase.STARTING else ManagedSessionPhase.NOT_STARTED
        if (!legacy.running) return ManagedSessionPhase.HISTORICAL
        return if (isActive(managed.runState)) ManagedSessionPhase.CURRENT else ManagedSessionPhase.STARTING
    }

    fun lifecycleOf(runState: SessionRunState): ManagedLifecycle = when (runState) {
        SessionRunState.IDLE -> ManagedLifecycle.NOT_STARTED
        SessionRunState.RUNNING -> ManagedLifecycle.RUNNING
        SessionRunState.PAUSED -> ManagedLifecycle.PAUSED
        SessionRunState.RESUMING -> ManagedLifecycle.RESUMING
        SessionRunState.COMPLETED -> ManagedLifecycle.COMPLETED
        SessionRunState.STOPPED -> ManagedLifecycle.STOPPED
        SessionRunState.FAILED -> ManagedLifecycle.FAILED
    }

    fun lifecycleText(lifecycle: ManagedLifecycle): String = when (lifecycle) {
        ManagedLifecycle.NOT_STARTED -> "未开始"
        ManagedLifecycle.RUNNING -> "运行中"
        ManagedLifecycle.PAUSED -> "已暂停"
        ManagedLifecycle.RESUMING -> "恢复中"
        ManagedLifecycle.COMPLETED -> "已完成"
        ManagedLifecycle.STOPPED -> "已停止"
        ManagedLifecycle.FAILED -> "失败"
    }

    /** Only a verified completion reads as success. Paused, resuming, failed and stopped never do. */
    fun toneOf(lifecycle: ManagedLifecycle): ManagedLifecycleTone = when (lifecycle) {
        ManagedLifecycle.COMPLETED -> ManagedLifecycleTone.SUCCESS
        ManagedLifecycle.RUNNING, ManagedLifecycle.RESUMING -> ManagedLifecycleTone.ACTIVE
        ManagedLifecycle.PAUSED -> ManagedLifecycleTone.ATTENTION
        ManagedLifecycle.FAILED -> ManagedLifecycleTone.FAILURE
        ManagedLifecycle.NOT_STARTED, ManagedLifecycle.STOPPED -> ManagedLifecycleTone.NEUTRAL
    }

    /**
     * Settled states show the duration that was published when the session settled; a later screen
     * frame must never inflate it. Only active states follow the monotonic clock.
     */
    fun displayedRuntimeMs(managed: ManagedRuntimeState, elapsedNowMs: Long): Long {
        if (!isActive(managed.runState)) return managed.runtimeMs
        val startedAt = managed.startedElapsedMs ?: return managed.runtimeMs
        return (elapsedNowMs - startedAt).coerceAtLeast(0)
    }

    fun durationText(managed: ManagedRuntimeState, elapsedNowMs: Long): String {
        val neverStarted = managed.startedElapsedMs == null && managed.startedAt == null &&
            managed.runState == SessionRunState.IDLE
        if (neverStarted) return "运行时长：未启动"
        return "运行时长：${formatDuration(displayedRuntimeMs(managed, elapsedNowMs))}"
    }

    fun formatDuration(ms: Long): String {
        val safe = if (ms < 0) 0 else ms
        val totalSeconds = safe / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds)
        else "%02d:%02d".format(minutes, seconds)
    }

    fun pauseText(pauseCount: Int, pauseReason: String?): String =
        if (pauseReason == null) "暂停：$pauseCount 次" else "暂停：$pauseCount 次 · 原因：$pauseReason"

    /**
     * Wall-clock instant after which no Known reading in this snapshot can still be current.
     * Null when the snapshot holds no Known reading at all.
     */
    fun nextHealthExpiryAtMs(state: ManagedRuntimeState): Long? {
        fun expiry(value: ObservedValue<Int>): Long? =
            (value as? ObservedValue.Known<*>)?.let { it.observedAt + HEALTH_MAX_AGE_MS }
        return listOf(expiry(state.energy), expiry(state.cleanliness), expiry(state.mood))
            .filterNotNull().minOrNull()
    }

    /**
     * True while the screen must keep re-evaluating freshness for the snapshot it shows, including
     * after the service stopped, so a reading that was Known on the last frame cannot stay "current"
     * forever. A wall clock that sits behind the observation instant is bounded to one freshness
     * window instead of ticking indefinitely.
     */
    fun needsFreshnessTicker(state: ManagedRuntimeState, runningNow: Boolean, wallNowMs: Long): Boolean {
        if (runningNow) return true
        val deadline = nextHealthExpiryAtMs(state) ?: return false
        val remaining = deadline - wallNowMs
        return remaining in 0..HEALTH_MAX_AGE_MS
    }

    /**
     * Reports only whether the displayed readings are current observations of the session that is
     * running. It intentionally makes no readiness claim: the authoritative >=90 judgement lives in
     * PetStudyHealthPolicy and is never re-implemented here.
     */
    fun confirmedReadingsText(state: ManagedRuntimeState, currentSession: Boolean): String {
        if (!currentSession) return "当前读数未确认（显示的不是本会话正在观测的读数）"
        val energy = state.energy is ObservedValue.Known
        val cleanliness = state.cleanliness is ObservedValue.Known
        return when {
            energy && cleanliness -> "当前读数已确认（仅指体力/清洁为本会话最新观测，不代表开课就绪）"
            !energy && !cleanliness -> "当前读数未确认（体力与清洁都不是本会话最新有效读数）"
            !energy -> "当前读数未确认（体力不是本会话最新有效读数）"
            else -> "当前读数未确认（清洁不是本会话最新有效读数）"
        }
    }

    fun healthLine(label: String, value: ObservedValue<Int>, belongsToCurrentSession: Boolean): HealthDisplayLine = when (value) {
        is ObservedValue.Known -> if (belongsToCurrentSession)
            HealthDisplayLine(label, HealthDisplayStatus.KNOWN, "${value.value}（当前读数）",
                "置信度 ${(value.confidence * 100).toInt()}% · ${value.source.name} · ${value.evidence}", true)
        else
            HealthDisplayLine(label, HealthDisplayStatus.KNOWN, "上一会话读数 ${value.value}（非本会话）",
                "置信度 ${(value.confidence * 100).toInt()}% · ${value.source.name} · ${value.evidence}", false)
        is ObservedValue.Unknown -> HealthDisplayLine(label, HealthDisplayStatus.UNKNOWN, "未知（未观测到有效读数）",
            "${value.reason} · ${value.evidence}", false)
        is ObservedValue.Stale -> HealthDisplayLine(label, HealthDisplayStatus.STALE,
            "历史读数 ${value.previous.value}（已过期，非当前值）", "${value.reason} · 观测于 ${value.previous.observedAt}", false)
        is ObservedValue.Unsupported -> HealthDisplayLine(label, HealthDisplayStatus.UNSUPPORTED, "不支持读取",
            value.reason, false)
    }

    private fun sessionLabel(phase: ManagedSessionPhase, sessionId: String?): String {
        val id = sessionId?.let { " · ${it.take(8)}" } ?: ""
        return when (phase) {
            ManagedSessionPhase.CURRENT -> "当前会话$id"
            ManagedSessionPhase.STARTING -> "启动过渡（新会话尚未发布状态，显示的是上一次已结束会话的终态）$id"
            ManagedSessionPhase.HISTORICAL -> "上一会话（历史读数，不代表当前状态）$id"
            ManagedSessionPhase.NOT_STARTED -> "尚未运行托管会话"
        }
    }
}
