package com.ynk.virtualdisplay.automation

import android.graphics.PointF
import kotlinx.coroutines.delay

sealed interface MailboxOutcome {
    data class Completed(val reward: MailboxParser.MailboxRewardResult) : MailboxOutcome
    object AlreadyDone : MailboxOutcome
    data class Skipped(val reason: String) : MailboxOutcome
    data class Blocked(val reason: String) : MailboxOutcome
    data class Failed(val reason: String) : MailboxOutcome
    /** 点了领取，但信箱既没变空也没读到奖励：未知，不能算领到。 */
    data class Unconfirmed(val reason: String) : MailboxOutcome
}

class PetMailboxFlow(
    private val flow: PetFlowContext,
    private val lineReader: suspend () -> List<TextLine>,
    private val tap: suspend (PointF, Long) -> Unit,
    private val pageSettleMs: Long = 1200L,
    private val onBeforeClaim: suspend () -> Unit = {},
) {
    var phase: String = "IDLE"; private set

    suspend fun run(): MailboxOutcome = with(flow) {
        flow.state = "MAILBOX"
        phase = "CHECKING_ROOM"
        emit("mailbox-start")

        // 1. 读取当前房间画面
        val roomLines = lineReader()

        // 2. 定位信箱入口
        val entryPoint = MailboxParser.locateMailboxEntry(roomLines)
        if (entryPoint == null) {
            phase = "SKIPPED"
            emit("mailbox-entry-missing")
            return@with MailboxOutcome.Skipped("MAILBOX_ENTRY_NOT_FOUND")
        }

        // 3. 点击信箱入口打开信箱弹窗
        phase = "OPENING_DIALOG"
        tap(entryPoint, pageSettleMs)
        delay(pageSettleMs)

        // 4. 检查信箱弹窗
        val dialogLines = lineReader()
        if (MailboxParser.isEmptyMailbox(dialogLines)) {
            phase = "ALREADY_DONE"
            dismissIfLocated(dialogLines)
            emit("mailbox-empty")
            return@with MailboxOutcome.AlreadyDone
        }

        if (!MailboxParser.isMailboxDialog(dialogLines)) {
            phase = "SKIPPED"
            dismissIfLocated(dialogLines)
            emit("mailbox-dialog-unconfirmed")
            return@with MailboxOutcome.Skipped("MAILBOX_DIALOG_NOT_CONFIRMED")
        }

        // 5. 优先定位「一键领取」按钮，若无则定位单个「领取」
        val claimButton = MailboxParser.locateClaimAllButton(dialogLines)
            ?: MailboxParser.locateClaimButton(dialogLines)

        if (claimButton == null) {
            phase = "ALREADY_DONE"
            dismissIfLocated(dialogLines)
            emit("mailbox-no-claimable")
            return@with MailboxOutcome.AlreadyDone
        }

        // 6. 点击领取奖励
        phase = "CLAIMING"
        onBeforeClaim()
        tap(claimButton, pageSettleMs)
        delay(pageSettleMs)

        // 7. 读取并提取奖励结果
        val rewardLines = lineReader()
        val reward = MailboxParser.parseMailboxReward(rewardLines)
        emit("mailbox-reward", result = "coins=" + (reward.coinAward ?: 0) + ";items=" + reward.items.joinToString(","))

        // 8. 页面没有领取证据就是未知；关闭按钮同样不猜坐标。
        val confirmed = MailboxParser.claimConfirmed(rewardLines, reward)
        dismissIfLocated(rewardLines)
        if (!confirmed) {
            phase = "UNCONFIRMED"
            emit("mailbox-claim-unconfirmed")
            return@with MailboxOutcome.Unconfirmed("MAILBOX_CLAIM_UNCONFIRMED")
        }

        phase = "COMPLETED"
        return@with MailboxOutcome.Completed(reward)
    }

    /** 有「关闭／确定」才点；找不到就不猜坐标，交给上层按未知处理。 */
    private suspend fun dismissIfLocated(lines: List<TextLine>): Boolean {
        val point = MailboxParser.locateDismissButton(lines) ?: return false
        tap(point, pageSettleMs)
        delay(pageSettleMs)
        return true
    }
}
