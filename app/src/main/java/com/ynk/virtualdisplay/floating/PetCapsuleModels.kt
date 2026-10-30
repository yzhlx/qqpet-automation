package com.ynk.virtualdisplay.floating

import com.ynk.virtualdisplay.automation.ManagedRuntimeState
import com.ynk.virtualdisplay.automation.PetRuntime
import com.ynk.virtualdisplay.automation.SessionRunState
import com.ynk.virtualdisplay.ui.screens.ManagedRuntimePresentation

enum class CapsuleVisualState {
    RUNNING,
    WAITING,
    PAUSED,
    RESUMING,
    COMPLETED,
    FAILED,
    STOPPED,
}

data class CapsuleUiModel(
    val visible: Boolean,
    val visual: CapsuleVisualState,
    val title: String,
    val statusText: String,
    val durationText: String,
    val flowText: String,
    val stepText: String,
    val roundText: String,
    val nextStepText: String,
    val canStop: Boolean,
)

/**
 * Where the cross-app bubble belongs.
 *
 * The bubble exists for OTHER apps. While QQPet itself is in the foreground the in-app PiP already
 * shows the same preview, so a floating bubble on top of it would be a second, redundant preview.
 * The answer is symmetric on purpose: the state that keeps the bubble away is also the state that
 * takes it off screen, so returning to the app can never leave a stale system window behind.
 */
object CapsuleBubblePolicy {
    fun shouldAttach(appForeground: Boolean, expanded: Boolean): Boolean = !appForeground && !expanded
}

object CapsuleUiMapper {
    fun from(managed: ManagedRuntimeState, legacy: PetRuntime.Snapshot, wallNowMs: Long, elapsedNowMs: Long): CapsuleUiModel {
        val view = ManagedRuntimePresentation.of(managed, legacy, wallNowMs, elapsedNowMs)
        val active = legacy.running || ManagedRuntimePresentation.isActive(managed.runState)
        val terminalVisible = managed.runState in setOf(
            SessionRunState.COMPLETED, SessionRunState.FAILED, SessionRunState.STOPPED,
        ) && managed.sessionId != null
        val visual = when (managed.runState) {
            // WAITING only when the controller itself publishes a waiting next-step
            // (e.g. "等待成绩"), so no state is invented here.
            SessionRunState.RUNNING -> if (managed.nextStep.contains("等待")) {
                CapsuleVisualState.WAITING
            } else {
                CapsuleVisualState.RUNNING
            }
            SessionRunState.PAUSED -> CapsuleVisualState.PAUSED
            SessionRunState.RESUMING -> CapsuleVisualState.RESUMING
            SessionRunState.COMPLETED -> CapsuleVisualState.COMPLETED
            SessionRunState.FAILED -> CapsuleVisualState.FAILED
            SessionRunState.STOPPED -> CapsuleVisualState.STOPPED
            SessionRunState.IDLE -> if (legacy.running) CapsuleVisualState.RUNNING else CapsuleVisualState.STOPPED
        }
        return CapsuleUiModel(
            visible = active || terminalVisible,
            visual = visual,
            title = "QQ宠物托管",
            statusText = view.lifecycleText,
            durationText = view.durationText.removePrefix("运行时长："),
            flowText = view.flowText,
            stepText = view.stepText,
            roundText = view.roundText,
            nextStepText = view.nextStepText,
            canStop = legacy.running || ManagedRuntimePresentation.isActive(managed.runState),
        )
    }
}
