package com.ynk.virtualdisplay.ui.screens

import com.ynk.virtualdisplay.automation.ManagedRuntimeState
import com.ynk.virtualdisplay.automation.ManualPause
import com.ynk.virtualdisplay.automation.ManualPauseState
import com.ynk.virtualdisplay.automation.ObservedValue
import com.ynk.virtualdisplay.automation.SessionRunState
import com.ynk.virtualdisplay.automation.QuietWindow
import com.ynk.virtualdisplay.automation.QuietWindowPolicy
import com.ynk.virtualdisplay.automation.QuietWindowText
import com.ynk.virtualdisplay.automation.at
import com.ynk.virtualdisplay.data.model.ShizukuState

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
 *  2. Control affordances come from REAL capabilities only. The automation layer exposes START,
 *     STOP and a manual pause that only takes effect between actions; PAUSED / RESUMING driven by
 *     SessionRecovery are reported as the automatic recovery that they are.
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
            meta = "上次 ${timeFormatter(aged.previous.observedAt)}",
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
     * Reference labels: 蟹在歇着 (nothing running), 蟹在忙 (a live session), 已暂停 (a manual pause
     * or auto-recovery). NOT_STARTED / a settled historical snapshot read as 蟹在歇着 on the home screen because
     * no managed batch is running right now; the exact lifecycle text is still available on the card.
     */
    fun of(view: ManagedRuntimeView, legacyActive: Boolean): StatusPill = when {
        !legacyActive && view.lifecycle == ManagedLifecycle.RUNNING -> StatusPill("启动中", StatusPillTone.NEUTRAL)
        view.lifecycle == ManagedLifecycle.RUNNING -> StatusPill("蟹在忙", StatusPillTone.ACTIVE)
        view.lifecycle == ManagedLifecycle.PAUSED -> StatusPill("已暂停", StatusPillTone.ATTENTION)
        view.lifecycle == ManagedLifecycle.RESUMING -> StatusPill("恢复中", StatusPillTone.ATTENTION)
        view.lifecycle == ManagedLifecycle.COMPLETED -> StatusPill("蟹啦，搞定", StatusPillTone.SUCCESS)
        view.lifecycle == ManagedLifecycle.FAILED -> StatusPill("失败", StatusPillTone.FAILURE)
        view.lifecycle == ManagedLifecycle.STOPPED -> StatusPill("已停止", StatusPillTone.NEUTRAL)
        else -> StatusPill("蟹在歇着", StatusPillTone.NEUTRAL)
    }
}

/**
 * The footer control model. Derived from real capabilities, never from what the mock-up drew.
 *
 * The business layer supports START (a bounded batch), STOP and a manual pause ([ManualPause]). The
 * manual pause only takes effect at the next boundary between actions, so it is offered only for a
 * session that has one, and never while the automatic recovery (SessionRecovery) owns PAUSED /
 * RESUMING.
 */
data class ManagedControls(
    val startEnabled: Boolean,
    val stopEnabled: Boolean,
    /** 暂停 can be pressed right now. */
    val pauseEnabled: Boolean,
    /** The pause button is shown: a boundary is still ahead, or a manual pause is requested or held. */
    val manualPauseAvailable: Boolean,
    /** A manual pause is requested or held, so the same button withdraws or continues it. */
    val resumeEnabled: Boolean,
    /** 暂停 / 取消暂停 / 继续. */
    val pauseButtonText: String,
    /** True when PAUSED/RESUMING is being driven automatically by SessionRecovery. */
    val automaticRecoveryActive: Boolean,
    /** Why a shown pause button cannot be pressed now; null when it can. */
    val pauseUnavailableReason: String?,
    /** Shown while a pause request waits for its boundary. A held pause is explained by the paused banner. */
    val manualPauseNote: String?,
    /** True when the primary footer is the single 开始托管 button (idle / settled). */
    val showStartPrimary: Boolean,
    /** Real, human-readable reason the start button is disabled; null when start is possible. */
    val blockerNote: String? = null,
    /** The batch size selector is only meaningful while idle and startable. */
    val batchRoundsSelectable: Boolean = false,
)

object ManagedControlsPresentation {
    private const val RECOVERING_REASON = "正在自动恢复，恢复后才能暂停。"
    private const val REQUESTED_NOTE = "做完当前这一步就暂停，正在上的课或正在打的工不会被打断。"

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
     * @param pausable whether a boundary between actions is still ahead (ManagedRuntimeState.pauseBoundariesLeft > 0).
     * @param manualPause the current [ManualPause] state.
     */
    fun of(
        legacyActive: Boolean,
        runState: SessionRunState,
        canStart: Boolean,
        profileBlockers: List<String> = emptyList(),
        displayReady: Boolean = true,
        pausable: Boolean = false,
        manualPause: ManualPauseState = ManualPauseState.NONE,
    ): ManagedControls {
        val blocker = when {
            canStart -> null
            profileBlockers.isNotEmpty() -> "尚未校准：${profileBlockers.first()}"
            !displayReady -> "未找到已托管的 720×1600 虚拟屏，请先在「设置 › 虚拟屏控制台」创建。"
            else -> "Shizuku 未就绪或虚拟屏未被托管，暂不能开始。"
        }
        val manualActive = legacyActive && manualPause != ManualPauseState.NONE
        val manualHeld = legacyActive && manualPause == ManualPauseState.HELD
        val recovering = legacyActive && !manualHeld &&
            (runState == SessionRunState.PAUSED || runState == SessionRunState.RESUMING)
        return ManagedControls(
            startEnabled = canStart && !legacyActive,
            stopEnabled = legacyActive,
            pauseEnabled = legacyActive && pausable && !manualActive && !recovering,
            manualPauseAvailable = legacyActive && (pausable || manualActive),
            resumeEnabled = manualActive,
            pauseButtonText = when {
                manualHeld -> "继续"
                manualActive -> "取消暂停"
                else -> "暂停"
            },
            automaticRecoveryActive = recovering,
            pauseUnavailableReason = if (legacyActive && pausable && !manualActive && recovering) RECOVERING_REASON else null,
            manualPauseNote = if (manualActive && !manualHeld) REQUESTED_NOTE else null,
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

/** The four stages of one lesson. Steps outside a lesson have no stage, so no track is drawn for them. */
object StudyStepTrack {
    val LABELS = listOf("检查状态", "开始上课", "等成绩", "读取课后状态")

    fun indexOf(step: String): Int? = when (step.uppercase()) {
        "INITIAL_NAVIGATION", "CHECK_STATE", "VERIFY_READY", "RECOVER_IF_NEEDED" -> 0
        "SELECT_COURSE", "CLEAR_PREVIOUS_RESULT", "START_COURSE" -> 1
        "HIGH_FIVE", "WAIT_HIGH_FIVE_WINDOW", "WAIT_RESULT" -> 2
        "READ_POST_STATE", "NEXT_ROUND" -> 3
        else -> null
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

/**
 * STR-17：夜间暂停在主页上的那一行字。
 *
 * 只在真的设置了窗口时出现；窗口外也保留一行，让用户知道「现在为什么能开始」，
 * 不必进设置去猜。判定复用 [QuietWindowPolicy]，这里只负责措辞。
 */
object QuietWindowPresentation {
    fun statusLine(window: QuietWindow, nowMinute: Int): String? {
        if (!window.enabled || window.startMinute == window.endMinute) return null
        return if (QuietWindowPolicy.isQuiet(nowMinute, window)) {
            "夜间暂停中 · " + QuietWindowText.opensAt(window) + " 再开始"
        } else {
            "夜间暂停 " + window.startText() + "–" + window.endText() + " · 现在可以开始"
        }
    }

    fun isQuietNow(window: QuietWindow, nowMinute: Int): Boolean =
        QuietWindowPolicy.isQuiet(nowMinute, window)
}


/**
 * 首页检查卡里 Shizuku 第一行的说法与下一步。
 *
 * 「服务没启动」和「服务在跑但没授权」是两种完全不同的阻塞，恢复动作也不同：
 * 前者让用户点启动，后者让用户在 Shizuku 里勾选蟹啦。都没确认时不冒充已连接。
 */
data class ShizukuSetupHint(
    val stateLabel: String,
    val actionLabel: String?,
    val message: String?,
)

fun shizukuSetupHint(state: ShizukuState): ShizukuSetupHint = when (state) {
    ShizukuState.PermissionDenied -> ShizukuSetupHint(
        stateLabel = "未授权",
        actionLabel = "去授权",
        message = "Shizuku 已在运行，但还没允许蟹啦使用。打开 Shizuku →「已授权应用」→ 勾选蟹啦；" +
            "若弹出授权窗口请选「始终允许」。不用重新配对，方案与进度已保存。",
    )
    ShizukuState.NotRunning -> ShizukuSetupHint(
        stateLabel = "未连接",
        actionLabel = "去恢复",
        message = "Shizuku 已停止：打开 Shizuku 点「启动」即可恢复，通常无需重新配对；" +
            "若系统清除了无线调试授权，按 Shizuku 页面提示重新配对。方案与进度已保存。",
    )
    ShizukuState.Ready -> ShizukuSetupHint("已连接", null, null)
    ShizukuState.Checking -> ShizukuSetupHint("检查中…", null, null)
}

/**
 * 第 2、3 步都要等第 1 步就绪。用户提前点「一键创建」时，必须一句话说明为什么没反应、
 * 下一步点哪里：静默失败会让人以为按钮坏了，也可能把「未知」误当成已完成。
 */
fun stepBlockedMessage(state: ShizukuState): String? = when (state) {
    ShizukuState.PermissionDenied ->
        "先完成第 1 步：Shizuku 还没允许蟹啦使用。点上面的「去授权」，在弹窗里选「始终允许」。"
    ShizukuState.NotRunning ->
        "先完成第 1 步：Shizuku 服务没在运行。点上面的「去恢复」，在 Shizuku 里点「启动」。"
    ShizukuState.Checking -> "正在检查 Shizuku 状态，稍等一秒再点。"
    ShizukuState.Ready -> null
}
