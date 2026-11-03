package com.ynk.virtualdisplay.automation

import android.graphics.PointF
import kotlinx.coroutines.delay

sealed interface LuckyBagOutcome {
    data class Completed(val reward: LuckyBagParser.LuckyBagRewardResult) : LuckyBagOutcome
    object AlreadyDone : LuckyBagOutcome
    data class Cooldown(val secondsRemaining: Long) : LuckyBagOutcome
    data class Skipped(val reason: String) : LuckyBagOutcome
    data class Blocked(val reason: String) : LuckyBagOutcome
    data class Failed(val reason: String) : LuckyBagOutcome
}

class PetLuckyBagFlow(
    private val flow: PetFlowContext,
    private val lineReader: suspend () -> List<TextLine>,
    private val tap: suspend (PointF, Long) -> Unit,
    private val pageSettleMs: Long = 1200L,
    private val onBeforeClaim: suspend () -> Unit = {},
) {
    var phase: String = "IDLE"; private set

    suspend fun run(): LuckyBagOutcome = with(flow) {
        flow.state = "LUCKY_BAG"
        phase = "CHECKING_ROOM"
        emit("lucky-bag-start")

        // 1. 读取房间画面
        val roomLines = lineReader()

        // 2. 定位福袋入口
        val entryPoint = LuckyBagParser.locateLuckyBagEntry(roomLines)
        if (entryPoint == null) {
            phase = "SKIPPED"
            emit("lucky-bag-entry-missing")
            return@with LuckyBagOutcome.Skipped("LUCKY_BAG_ENTRY_NOT_FOUND")
        }

        // 3. 点击福袋入口
        phase = "OPENING_DIALOG"
        emit("lucky-bag-entry-tap", result = "x=${entryPoint.x.toInt()},y=${entryPoint.y.toInt()}")
        tap(entryPoint, pageSettleMs)

        // 4. 读取弹窗画面并校验
        val dialogLines = lineReader()
        if (!LuckyBagParser.isLuckyBagDialog(dialogLines)) {
            phase = "FAILED"
            emit("lucky-bag-dialog-missing")
            safeDismiss(dialogLines)
            return@with LuckyBagOutcome.Failed("LUCKY_BAG_DIALOG_NOT_FOUND")
        }

        // 5. 检查是否已达今日上限
        if (LuckyBagParser.isLimitReached(dialogLines)) {
            phase = "LIMIT_REACHED"
            emit("lucky-bag-limit-reached")
            safeDismiss(dialogLines)
            return@with LuckyBagOutcome.AlreadyDone
        }

        // 6. 检查是否在冷却中
        val cooldownSec = LuckyBagParser.parseCooldownSeconds(dialogLines)
        if (cooldownSec != null && cooldownSec > 0) {
            phase = "COOLDOWN"
            emit("lucky-bag-cooldown", result = "remainingSeconds=$cooldownSec")
            safeDismiss(dialogLines)
            return@with LuckyBagOutcome.Cooldown(cooldownSec)
        }

        // 7. 定位开福袋/领取按钮
        val claimPoint = LuckyBagParser.locateClaimButton(dialogLines)
        if (claimPoint == null) {
            phase = "SKIPPED"
            emit("lucky-bag-claim-missing")
            safeDismiss(dialogLines)
            return@with LuckyBagOutcome.Skipped("LUCKY_BAG_CLAIM_BUTTON_NOT_FOUND")
        }

        // 8. 授权门禁
        phase = "CLAIMING"
        onBeforeClaim()

        // 9. 点击开福袋
        emit("lucky-bag-claim-tap", result = "x=${claimPoint.x.toInt()},y=${claimPoint.y.toInt()}")
        tap(claimPoint, pageSettleMs)

        // 10. 读取结算奖励
        val rewardLines = lineReader()
        val reward = LuckyBagParser.parseLuckyBagReward(rewardLines)

        // 11. 安全关闭弹窗回房
        safeDismiss(rewardLines)
        phase = "COMPLETED"
        emit("lucky-bag-completed", result = "coins=${reward.coinAward ?: 0},items=${reward.items.size}")
        return@with LuckyBagOutcome.Completed(reward)
    }

    private suspend fun safeDismiss(lines: List<TextLine>) {
        val dismissPoint = LuckyBagParser.locateDismissButton(lines)
        if (dismissPoint != null) {
            tap(dismissPoint, pageSettleMs)
        } else {
            flow.input.tap(650f, 180f)
            delay(pageSettleMs)
        }
    }
}
