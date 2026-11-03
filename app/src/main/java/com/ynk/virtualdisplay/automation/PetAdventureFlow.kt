package com.ynk.virtualdisplay.automation

import android.graphics.PointF
import kotlinx.coroutines.delay

/**
 * 基础自动冒险闭环流程（CORE-11）：
 * 1. 经户外地图定位「冒险」入口
 * 2. 识别并确认进入冒险大厅页面
 * 3. 检查体力、疲劳与今日次数限制，受阻时安全停止并不乱点
 * 4. 选择目标模式（默认「附近走走」），点击出发
 * 5. 等待奇遇/事件结算弹窗，确认到账奖励并安全关闭
 * 6. 安全返回房间，避免滞留户外或报错死循环
 */
class PetAdventureFlow(
    private val flow: PetFlowContext,
    private val navigation: PetNavigation,
    private val lineReader: suspend () -> List<TextLine>,
    private val tap: suspend (PointF, Long) -> Unit,
    private val pageSettleMs: Long = 1200L,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    var phase: AdventurePhase = AdventurePhase.IDLE; private set

    /** 执行一次完整的冒险闭环 */
    suspend fun run(
        mode: AdventureMode = AdventureMode.NEARBY_WALK,
        settleWaitTimeoutMs: Long = 45_000L,
        onBeforeStart: suspend () -> Unit = {},
    ): AdventureOutcome = with(flow) {
        flow.state = "ADVENTURE"
        this@PetAdventureFlow.phase = AdventurePhase.NAVIGATING_OUTDOOR
        emit("adventure-start", result = "mode=${mode.label}")

        try {
            // 1. 进入户外地图
            navigation.enterOutdoor("adventure-outdoor")
            this@PetAdventureFlow.phase = AdventurePhase.ENTERING_ADVENTURE

            // 2. 定位「冒险」建筑入口
            val outdoorLines = lineReader()
            val entryPoint = AdventureParser.locateAdventureEntry(outdoorLines)
            if (entryPoint == null) {
                this@PetAdventureFlow.phase = AdventurePhase.BLOCKED
                emit("adventure-entry-unconfirmed", result = "lines=${outdoorLines.joinToString(";") { it.text }}")
                navigation.returnHomeFromMap("adventure-entry-missing")
                return@with AdventureOutcome.Blocked("ADVENTURE_ENTRY_NOT_FOUND")
            }

            // 3. 点击进入冒险
            tap(entryPoint, pageSettleMs)
            delay(pageSettleMs)

            // 4. 确认冒险页面
            val pageLines = lineReader()
            if (!AdventureParser.isAdventurePage(pageLines)) {
                this@PetAdventureFlow.phase = AdventurePhase.BLOCKED
                emit("adventure-page-unconfirmed", result = "lines=${pageLines.joinToString(";") { it.text }}")
                // 点击左上角返回户外再回房
                tap(PointF(80f, 160f), pageSettleMs)
                navigation.returnHomeFromMap("adventure-page-unconfirmed")
                return@with AdventureOutcome.Blocked("ADVENTURE_PAGE_NOT_CONFIRMED")
            }
            this@PetAdventureFlow.phase = AdventurePhase.PAGE_CONFIRMED

            // 5. 检查是否达到上限或疲劳限制
            val limit = AdventureParser.detectLimitOrCooldown(pageLines)
            if (limit != null) {
                this@PetAdventureFlow.phase = AdventurePhase.BLOCKED
                emit("adventure-blocked-limit", result = "reason=$limit")
                tap(PointF(80f, 160f), pageSettleMs)
                navigation.returnHomeFromMap("adventure-limited")
                return@with AdventureOutcome.Blocked("ADVENTURE_LIMITED:$limit")
            }

            // 6. 切换目标模式（若存在对应标签）
            val modePoint = AdventureParser.locateModeOption(pageLines, mode)
            if (modePoint != null) {
                tap(modePoint, pageSettleMs)
                delay(800L)
            }

            // 7. 定位并点击出发/开始冒险
            val updatedLines = lineReader()
            val startPoint = AdventureParser.locateStartButton(updatedLines)
            if (startPoint == null) {
                this@PetAdventureFlow.phase = AdventurePhase.BLOCKED
                emit("adventure-start-button-missing", result = "lines=${updatedLines.joinToString(";") { it.text }}")
                tap(PointF(80f, 160f), pageSettleMs)
                navigation.returnHomeFromMap("adventure-start-missing")
                return@with AdventureOutcome.Blocked("ADVENTURE_START_BUTTON_NOT_FOUND")
            }

            this@PetAdventureFlow.phase = AdventurePhase.START_REQUESTED
            onBeforeStart()
            tap(startPoint, pageSettleMs)
            val startedAt = clock()
            this@PetAdventureFlow.phase = AdventurePhase.IN_PROGRESS
            emit("adventure-started", result = "mode=${mode.label} startedAt=$startedAt")

            // 8. 等待奇遇结算与结果确认
            this@PetAdventureFlow.phase = AdventurePhase.WAITING_RESULT
            val deadline = startedAt + settleWaitTimeoutMs
            var settlementResult: AdventureParser.SettlementResult? = null

            while (clock() < deadline) {
                delay(2000L)
                val checkLines = lineReader()
                val parsed = AdventureParser.parseSettlement(checkLines)
                if (parsed != null) {
                    settlementResult = parsed
                    // 点击弹窗的确定/收下按钮
                    val dismissPoint = AdventureParser.locateDismissButton(checkLines)
                    if (dismissPoint != null) {
                        tap(dismissPoint, pageSettleMs)
                        delay(1000L)
                    }
                    break
                }
            }

            // 9. 安全退出并回房
            tap(PointF(80f, 160f), pageSettleMs)
            delay(1000L)
            navigation.returnHomeFromMap("adventure-finish")

            if (settlementResult != null) {
                this@PetAdventureFlow.phase = AdventurePhase.RESULT_CONFIRMED
                emit("adventure-completed", result = "summary=${settlementResult.summary} coins=${settlementResult.coinAward}")
                this@PetAdventureFlow.phase = AdventurePhase.FINISHED
                return@with AdventureOutcome.Completed(
                    mode = mode,
                    eventSummary = settlementResult.summary,
                    coinAward = settlementResult.coinAward,
                    itemsAwarded = settlementResult.items,
                )
            } else {
                this@PetAdventureFlow.phase = AdventurePhase.FAILED
                emit("adventure-timeout", result = "timeoutMs=$settleWaitTimeoutMs")
                return@with AdventureOutcome.Failed("ADVENTURE_SETTLEMENT_TIMEOUT")
            }
        } catch (t: Throwable) {
            this@PetAdventureFlow.phase = AdventurePhase.FAILED
            emit("adventure-exception", result = t.message ?: "UNKNOWN_ERROR")
            try {
                navigation.returnHomeFromPage(flow.room, "adventure-exception-home")
            } catch (_: Throwable) {}
            throw t
        }
    }
}
