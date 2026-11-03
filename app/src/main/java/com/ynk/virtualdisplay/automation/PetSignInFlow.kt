package com.ynk.virtualdisplay.automation

import android.graphics.PointF
import kotlinx.coroutines.delay

sealed interface SignInOutcome {
    data class Completed(val reward: SignInParser.SignInRewardResult) : SignInOutcome
    object AlreadyDone : SignInOutcome
    data class Skipped(val reason: String) : SignInOutcome
    data class Blocked(val reason: String) : SignInOutcome
    data class Failed(val reason: String) : SignInOutcome
}

class PetSignInFlow(
    private val flow: PetFlowContext,
    private val lineReader: suspend () -> List<TextLine>,
    private val tap: suspend (PointF, Long) -> Unit,
    private val pageSettleMs: Long = 1200L,
    private val onBeforeClaim: suspend () -> Unit = {},
) {
    var phase: String = "IDLE"; private set

    suspend fun run(): SignInOutcome = with(flow) {
        flow.state = "SIGN_IN"
        phase = "CHECKING_ROOM"
        emit("sign-in-start")

        // 1. 读取当前房间画面
        val roomLines = lineReader()

        // 2. 检查是否已经签到
        if (SignInParser.isAlreadySignedIn(roomLines)) {
            phase = "ALREADY_DONE"
            emit("sign-in-already-done")
            return@with SignInOutcome.AlreadyDone
        }

        // 3. 定位签到入口
        val entryPoint = SignInParser.locateSignInEntry(roomLines)
        if (entryPoint == null) {
            phase = "SKIPPED"
            emit("sign-in-entry-missing")
            return@with SignInOutcome.Skipped("SIGN_IN_ENTRY_NOT_FOUND")
        }

        // 4. 点击签到入口
        phase = "OPENING_DIALOG"
        tap(entryPoint, pageSettleMs)
        delay(pageSettleMs)

        // 5. 检查是否已展示签到弹窗
        val dialogLines = lineReader()
        if (SignInParser.isAlreadySignedIn(dialogLines)) {
            phase = "ALREADY_DONE"
            val dismiss = SignInParser.locateDismissButton(dialogLines) ?: PointF(640f, 360f)
            tap(dismiss, pageSettleMs)
            emit("sign-in-dialog-already-done")
            return@with SignInOutcome.AlreadyDone
        }

        if (!SignInParser.isSignInDialog(dialogLines)) {
            phase = "SKIPPED"
            val dismiss = SignInParser.locateDismissButton(dialogLines) ?: PointF(640f, 360f)
            tap(dismiss, pageSettleMs)
            emit("sign-in-dialog-unconfirmed")
            return@with SignInOutcome.Skipped("SIGN_IN_DIALOG_NOT_CONFIRMED")
        }

        // 6. 定位并点击领取/签到按钮
        val claimButton = SignInParser.locateClaimButton(dialogLines)
        if (claimButton != null) {
            phase = "CLAIMING"
            onBeforeClaim()
            tap(claimButton, pageSettleMs)
            delay(pageSettleMs)
        }

        // 7. 读取并确认奖励
        val rewardLines = lineReader()
        val reward = SignInParser.parseSignInReward(rewardLines)
        emit("sign-in-reward", result = "coins=" + (reward.coinAward ?: 0) + ";items=" + reward.items.joinToString(","))

        // 8. 关闭弹窗回到房间
        val dismissButton = SignInParser.locateDismissButton(rewardLines) ?: PointF(640f, 360f)
        tap(dismissButton, pageSettleMs)
        delay(pageSettleMs)

        phase = "COMPLETED"
        return@with SignInOutcome.Completed(reward)
    }
}
