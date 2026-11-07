package com.ynk.virtualdisplay.automation

import android.graphics.PointF
import kotlinx.coroutines.delay

sealed interface SignInOutcome {
    data class Completed(val reward: SignInParser.SignInRewardResult) : SignInOutcome
    object AlreadyDone : SignInOutcome
    data class Skipped(val reason: String) : SignInOutcome
    data class Blocked(val reason: String) : SignInOutcome
    data class Failed(val reason: String) : SignInOutcome
    /** 点了领取，但页面上读不到任何结果：未知，不能算领到。 */
    data class Unconfirmed(val reason: String) : SignInOutcome
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
            dismissIfLocated(dialogLines)
            emit("sign-in-dialog-already-done")
            return@with SignInOutcome.AlreadyDone
        }

        if (!SignInParser.isSignInDialog(dialogLines)) {
            phase = "SKIPPED"
            dismissIfLocated(dialogLines)
            emit("sign-in-dialog-unconfirmed")
            return@with SignInOutcome.Skipped("SIGN_IN_DIALOG_NOT_CONFIRMED")
        }

        // 6. 没有领取按钮就什么都没点：什么也没发生，不能报成功。
        val claimButton = SignInParser.locateClaimButton(dialogLines)
        if (claimButton == null) {
            phase = "SKIPPED"
            dismissIfLocated(dialogLines)
            emit("sign-in-claim-missing")
            return@with SignInOutcome.Skipped("SIGN_IN_CLAIM_BUTTON_NOT_FOUND")
        }

        phase = "CLAIMING"
        onBeforeClaim()
        tap(claimButton, pageSettleMs)
        delay(pageSettleMs)

        // 7. 读奖励；页面上没有领取证据就是未知，交回计划记成待确认。
        val rewardLines = lineReader()
        val reward = SignInParser.parseSignInReward(rewardLines)
        emit("sign-in-reward", result = "coins=" + (reward.coinAward ?: 0) + ";items=" + reward.items.joinToString(","))
        val confirmed = SignInParser.claimConfirmed(rewardLines, reward)
        dismissIfLocated(rewardLines)
        if (!confirmed) {
            phase = "UNCONFIRMED"
            emit("sign-in-claim-unconfirmed")
            return@with SignInOutcome.Unconfirmed("SIGN_IN_CLAIM_UNCONFIRMED")
        }

        phase = "COMPLETED"
        return@with SignInOutcome.Completed(reward)
    }

    /** 有「关闭／我知道了」才点；找不到就不猜坐标，交给上层按未知处理。 */
    private suspend fun dismissIfLocated(lines: List<TextLine>): Boolean {
        val point = SignInParser.locateDismissButton(lines) ?: return false
        tap(point, pageSettleMs)
        delay(pageSettleMs)
        return true
    }
}
