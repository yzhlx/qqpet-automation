package com.ynk.virtualdisplay.ui.screens

import com.ynk.virtualdisplay.automation.ManagedRuntimeState
import com.ynk.virtualdisplay.automation.ObservedValue
import com.ynk.virtualdisplay.automation.SessionRunState
import com.ynk.virtualdisplay.automation.at

/**
 * Pure presentation layer for the v0.1 managed UI. No Android, Compose or automation *side-effect*
 * imports, so every rule below is unit-testable and can never send input or publish state. The only
 * automation imports here are the read-only value types and the existing [at] freshness primitive —
 * freshness is reused, never reimplemented, and always with [ManagedRuntimePresentation.HEALTH_MAX_AGE_MS].
 *
 * Two honesty rules are enforced:
 *  1. The four observation states (KNOWN / UNKNOWN / STALE / UNSUPPORTED) stay visibly distinct.
 *     A missing reading reads as "—", never 0; a stale reading shows its previous number labelled
 *     as expired; a historical reading is never dressed up as a live one.
 *  2. Control affordances come from REAL capabilities only. The automation layer exposes just
 *     START and STOP. There is no manual pause/resume API, so pause is never an operable button;
 *     PAUSED / RESUMING are reported as the automatic recovery (SessionRecovery) that they are.
 */

/** One of the three top health stats, kept distinct across the four observation states. */
enum class HealthStatStatus { KNOWN, UNKNOWN, STALE, UNSUPPORTED }

data class HealthStat(
    val name: String,
    /** Big number line: "≈96" when there is a number to show, "—" when there is none. */
    val valueText: String,
    /** Small meta line: confirmation time, or why there is no usable value. */
    val meta: String,
    val status: HealthStatStatus,
    /** True only for a fresh Known reading owned by the session running right now. */
    val current: Boolean,
)

object HealthStatPresentation {
    /**
     * Builds the 3 top stats from the raw snapshot values. [belongsToCurrentSession] must be the
     * [ManagedRuntimeView.sessionIsCurrent] flag: a KNOWN reading from an earlier session is shown
     * as a past reading, never as "confirmed now". [timeFormatter] turns a wall-clock instant into a
     * display string (the UI passes a local HH:MM formatter; tests pass a fixed one).
     */
    fun stats(
        managed: ManagedRuntimeState,
        wallNowMs: Long,
        belongsToCurrentSession: Boolean,
        timeFormatter: (Long) -> String,
    ): List<HealthStat> = listOf(
        one("体力", managed.energy, wallNowMs, belongsToCurrentSession, timeFormatter),
        one("清洁", managed.cleanliness, wallNowMs, belongsToCurrentSession, timeFormatter),
        one("心情", managed.mood, wallNowMs, belongsToCurrentSession, timeFormatter),
    )

    private fun one(
        name: String,
        raw: ObservedValue<Int>,
        wallNowMs: Long,
        current: Boolean,
        timeFormatter: (Long) -> String,
    ): HealthStat = when (val aged = raw.at(wallNowMs, ManagedRuntimePresentation.HEALTH_MAX_AGE_MS)) {
        is ObservedValue.Known -> HealthStat(
            name = name,
            valueText = "≈${aged.value}",
            meta = if (current) "${timeFormatter(aged.observedAt)} 确认" else "上次 ${timeFormatter(aged.observedAt)}",
            status = HealthStatStatus.KNOWN,
            current = current,
        )
        is ObservedValue.Unknown ->
            HealthStat(name, "—", "未确认", HealthStatStatus.UNKNOWN, false)
        is ObservedValue.Stale -> HealthStat(
            name = name,
            valueText = "≈${aged.previous.value}",
            meta = "已过期 · ${timeFormatter(aged.previous.observedAt)}",
            status = HealthStatStatus.STALE,
            current = false,
        )
        is ObservedValue.Unsupported ->
            HealthStat(name, "—", "不支持", HealthStatStatus.UNSUPPORTED, false)
    }
}

/** The pill in the status card: text + tone, derived only from the real lifecycle. */
enum class StatusPillTone { NEUTRAL, ACTIVE, ATTENTION, SUCCESS, FAILURE }

data class StatusPill(val text: String, val tone: StatusPillTone)

object StatusPillPresentation {
    /**
     * Reference labels: 空闲 (nothing running), 托管中 (a live session), 已暂停 (auto-recovery
     * paused). NOT_STARTED / a settled historical snapshot read as 空闲 on the home screen because
     * no managed batch is running right now; the exact lifecycle text is still available on the card.
     */
    fun of(view: ManagedRuntimeView, legacyActive: Boolean): StatusPill = when {
        !legacyActive && view.lifecycle == ManagedLifecycle.RUNNING -> StatusPill("启动中", StatusPillTone.NEUTRAL)
        view.lifecycle == ManagedLifecycle.RUNNING -> StatusPill("托管中", StatusPillTone.ACTIVE)
        view.lifecycle == ManagedLifecycle.PAUSED -> StatusPill("已暂停", StatusPillTone.ATTENTION)
        view.lifecycle == ManagedLifecycle.RESUMING -> StatusPill("恢复中", StatusPillTone.ATTENTION)
        view.lifecycle == ManagedLifecycle.COMPLETED -> StatusPill("已完成", StatusPillTone.SUCCESS)
        view.lifecycle == ManagedLifecycle.FAILED -> StatusPill("失败", StatusPillTone.FAILURE)
        view.lifecycle == ManagedLifecycle.STOPPED -> StatusPill("已停止", StatusPillTone.NEUTRAL)
        else -> StatusPill("空闲", StatusPillTone.NEUTRAL)
    }
}

/**
 * The footer control model. Derived from real capabilities, never from what the mock-up drew.
 *
 * The business layer only supports START (a bounded 1..3 round batch) and STOP. There is no manual
 * pause/resume entry point, so [pauseEnabled] and [manualPauseAvailable] are always false: the UI
 * must show pause as automatic recovery only, never as a button the user can press.
 */
data class ManagedControls(
    val startEnabled: Boolean,
    val stopEnabled: Boolean,
    val pauseEnabled: Boolean,
    /** False for the whole of v0.1: no manual pause API exists in the automation layer. */
    val manualPauseAvailable: Boolean,
    /** True when PAUSED/RESUMING is being driven automatically by SessionRecovery. */
    val automaticRecoveryActive: Boolean,
    /** Human explanation for why the pause control is not operable. */
    val pauseUnavailableReason: String,
    /** True when the primary footer is the single 开始托管 button (idle / settled). */
    val showStartPrimary: Boolean,
    /** Real, human-readable reason the start button is disabled; null when start is possible. */
    val blockerNote: String? = null,
    /** The batch size selector is only meaningful while idle and startable. */
    val batchRoundsSelectable: Boolean = false,
)

object ManagedControlsPresentation {
    private const val PAUSE_REASON =
        "v0.1 未开放手动暂停：检测到异常时会自动暂停并尝试恢复，期间不发送任何输入。"

    /**
     * @param legacyActive whether the foreground service is running a session right now
     * @param runState the managed snapshot lifecycle state
     * @param canStart environment gate: Shizuku ready, a managed 720×1600@320 display selected and
     *   calibrated, and the display is currently managed by the live Shizuku service. The caller
     *   computes this from real sources (PetAutomationService.verifyDisplay is the hard gate).
     * @param profileBlockers the profile calibration errors that would make the service refuse to
     *   start. Shown verbatim so the user is never told "ready" by a UI that is more permissive than
     *   the real gate.
     * @param displayReady whether a 720×1600 virtual display is currently available and managed.
     */
    fun of(
        legacyActive: Boolean,
        runState: SessionRunState,
        canStart: Boolean,
        profileBlockers: List<String> = emptyList(),
        displayReady: Boolean = true,
    ): ManagedControls {
        val blocker = when {
            canStart -> null
            profileBlockers.isNotEmpty() -> "尚未校准：${profileBlockers.first()}"
            !displayReady -> "未找到已托管的 720×1600 虚拟屏，请先在「设置 › 虚拟屏控制台」创建。"
            else -> "Shizuku 未就绪或虚拟屏未被托管，暂不能开始。"
        }
        return ManagedControls(
            startEnabled = canStart && !legacyActive,
            stopEnabled = legacyActive,
            pauseEnabled = false,
            manualPauseAvailable = false,
            automaticRecoveryActive = legacyActive &&
                (runState == SessionRunState.PAUSED || runState == SessionRunState.RESUMING),
            pauseUnavailableReason = PAUSE_REASON,
            showStartPrimary = !legacyActive,
            blockerNote = if (legacyActive) null else blocker,
            batchRoundsSelectable = !legacyActive && canStart,
        )
    }
}

/**
 * Progress for the current-task card. Only rounds are an authoritative source, so the bar is
 * completedRounds/totalRounds; a per-course percentage of the current lesson is never invented.
 */
data class RoundProgress(
    val total: Int,
    val completed: Int,
    /** 0..1 fraction of finished rounds; 0 when nothing is running. */
    val fraction: Float,
    val percentText: String,
    val roundsText: String,
)

object RoundProgressPresentation {
    fun of(totalRounds: Int, completedRounds: Int): RoundProgress {
        val total = if (totalRounds <= 0) 0 else totalRounds
        val done = if (total == 0) completedRounds.coerceAtLeast(0) else completedRounds.coerceIn(0, total)
        val fraction = if (total <= 0) 0f else done.toFloat() / total.toFloat()
        val percent = (fraction * 100f).toInt().coerceIn(0, 100)
        return RoundProgress(
            total = total,
            completed = done,
            fraction = fraction,
            percentText = if (total <= 0) "—" else "$percent%",
            roundsText = if (total <= 0) "未开始" else "$done / $total",
        )
    }
}

/**
 * One-row summary of the most recent finished batch, for the idle home card. It is built ONLY from a
 * persisted run record (see [com.ynk.virtualdisplay.ui.data.SessionRunRecord]); when there is no
 * record every field is null/empty and the card says so instead of inventing "上次运行".
 */
data class LastRunSummary(
    val present: Boolean,
    val finishedLabel: String,
    val courses: Int,
    val careCount: Int,
    val minutes: Int,
    val screenshots: Int,
)
