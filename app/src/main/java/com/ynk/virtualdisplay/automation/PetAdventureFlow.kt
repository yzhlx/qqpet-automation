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

    companion object {
        /** Readings of the 冒险 label taken before the tap; two consecutive ones must agree. */
        const val ENTRY_READS = 4
        const val ENTRY_READ_GAP_MS = 500L
        /** Gap between reads while the pet is out; each read is an OCR pass of its own. */
        const val WATCH_POLL_MS = 2000L
        private const val PROGRESS_LOG_STEP_SEC = 10
        private val COIN_IN_EVENT = Regex("""金币\s*[+＋]?\s*(\d+)""")
    }

    /** 执行一次完整的冒险闭环 */
    suspend fun run(
        mode: AdventureMode = AdventureMode.NEARBY_WALK,
        settleWaitTimeoutMs: Long = AdventureWatch.UNSEEN_TIMEOUT_MS,
        onBeforeStart: suspend () -> Unit = {},
    ): AdventureOutcome = with(flow) {
        flow.state = "ADVENTURE"
        this@PetAdventureFlow.phase = AdventurePhase.NAVIGATING_OUTDOOR
        emit("adventure-start", result = "mode=${mode.label}")

        try {
            // 1. 进入户外地图
            navigation.enterOutdoor("adventure-outdoor")
            this@PetAdventureFlow.phase = AdventurePhase.ENTERING_ADVENTURE

            // 2. 定位「冒险」建筑入口：两次独立读数一致才点（地图刚打开时镜头可能还在动）
            var entryPoint: PointF? = null
            var previous: PointF? = null
            var lastLines: List<TextLine> = emptyList()
            for (read in 1..ENTRY_READS) {
                lastLines = lineReader()
                // An adventure already under way replaces the 冒险 label with a "冒险中" bubble and
                // opens the bottom panel; a tap on that bubble is not an entry and a second run
                // cannot start until the pet is back.
                if (read == 1 && AdventureParser.readProgress(lastLines).inProgress) {
                    this@PetAdventureFlow.phase = AdventurePhase.BLOCKED
                    emit("adventure-already-running", result = "lines=${lastLines.joinToString(";") { it.text }}")
                    navigation.walkBackToRoom("adventure-already-running")
                    return@with AdventureOutcome.Blocked("ADVENTURE_ALREADY_RUNNING")
                }
                val located = AdventureParser.locateAdventureEntry(lastLines)
                if (located != null && previous != null && AdventureParser.sameEntry(previous, located)) {
                    entryPoint = located
                    break
                }
                previous = located
                if (read < ENTRY_READS) delay(ENTRY_READ_GAP_MS)
            }
            if (entryPoint == null) {
                this@PetAdventureFlow.phase = AdventurePhase.BLOCKED
                emit("adventure-entry-unconfirmed", result = "lines=${lastLines.joinToString(";") { it.text }}")
                navigation.walkBackToRoom("adventure-entry-missing")
                return@with AdventureOutcome.Blocked("ADVENTURE_ENTRY_NOT_FOUND")
            }
            emit("adventure-entry-located", result = "tap=${entryPoint.x.toInt()},${entryPoint.y.toInt()}")

            // 3. 点击进入冒险
            tap(entryPoint, pageSettleMs)
            delay(pageSettleMs)

            // 4. 确认冒险页面
            val pageLines = lineReader()
            if (!AdventureParser.isAdventurePage(pageLines)) {
                this@PetAdventureFlow.phase = AdventurePhase.BLOCKED
                emit("adventure-page-unconfirmed", result = "lines=${pageLines.joinToString(";") { it.text }}")
                // 不知道落在了哪一页：按页面实际样子一级级退回房间（职业小镇也能退）
                navigation.walkBackToRoom("adventure-page-unconfirmed")
                return@with AdventureOutcome.Blocked("ADVENTURE_PAGE_NOT_CONFIRMED")
            }
            this@PetAdventureFlow.phase = AdventurePhase.PAGE_CONFIRMED

            // 5. 检查是否达到上限或疲劳限制
            val limit = AdventureParser.detectLimitOrCooldown(pageLines)
            if (limit != null) {
                this@PetAdventureFlow.phase = AdventurePhase.BLOCKED
                emit("adventure-blocked-limit", result = "reason=$limit")
                navigation.walkBackToRoom("adventure-limited")
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
                navigation.walkBackToRoom("adventure-start-missing")
                return@with AdventureOutcome.Blocked("ADVENTURE_START_BUTTON_NOT_FOUND")
            }

            this@PetAdventureFlow.phase = AdventurePhase.START_REQUESTED
            onBeforeStart()
            tap(startPoint, pageSettleMs)
            val startedAt = clock()
            this@PetAdventureFlow.phase = AdventurePhase.IN_PROGRESS
            emit("adventure-started", result = "mode=${mode.label} startedAt=$startedAt")

            // 8. 等宠物回来。实机（2026-11-07）：冒险没有结算弹窗；地图下方是「正在冒险 / 剩余 mm:ss /
            //    召回」面板，倒计时走完后宠物自己回房间。所以看面板：见过它、又连续两次读不到才算结束。
            this@PetAdventureFlow.phase = AdventurePhase.WAITING_RESULT
            val watch = AdventureWatch(startedAt, unseenTimeoutMs = settleWaitTimeoutMs)
            var verdict: AdventureWatch.Verdict = AdventureWatch.Verdict.Waiting
            var watchLines: List<TextLine> = emptyList()
            var loggedRemaining: Int? = null
            while (verdict === AdventureWatch.Verdict.Waiting) {
                delay(WATCH_POLL_MS)
                watchLines = lineReader()
                verdict = watch.observe(clock(), watchLines)
                // The pet's log text jitters between OCR passes; write a line on the first sighting and then
                // about every PROGRESS_LOG_STEP_SEC of countdown, not on every changed spelling.
                val remaining = watch.lastRemainingSec
                if (watch.sawProgress && (loggedRemaining == null || (remaining != null && loggedRemaining - remaining >= PROGRESS_LOG_STEP_SEC))) {
                    loggedRemaining = remaining ?: loggedRemaining ?: 0
                    emit("adventure-progress", result = "remaining=$remaining events=${watch.events.joinToString("|")}")
                }
            }
            if (verdict is AdventureWatch.Verdict.Settled) {
                // 旧版界面的结算弹窗：点掉确定/收下
                AdventureParser.locateDismissButton(watchLines)?.let { tap(it, pageSettleMs); delay(1000L) }
            }

            // 9. 按画面实际样子回房：房间里不再点返回（那会退出小程序），地图上才点箭头
            navigation.walkBackToRoom("adventure-finish")

            return@with when (val v = verdict) {
                is AdventureWatch.Verdict.Finished -> {
                    this@PetAdventureFlow.phase = AdventurePhase.RESULT_CONFIRMED
                    val coins = v.events.firstNotNullOfOrNull { COIN_IN_EVENT.find(it)?.groupValues?.get(1)?.toIntOrNull() }
                    emit("adventure-completed", result = "summary=${AdventureParser.summarise(v.events)} coins=$coins events=${v.events.joinToString("|")}")
                    this@PetAdventureFlow.phase = AdventurePhase.FINISHED
                    AdventureOutcome.Completed(
                        mode = mode,
                        eventSummary = AdventureParser.summarise(v.events),
                        coinAward = coins,
                        itemsAwarded = AdventureParser.foundItems(v.events),
                    )
                }
                is AdventureWatch.Verdict.Settled -> {
                    this@PetAdventureFlow.phase = AdventurePhase.RESULT_CONFIRMED
                    emit("adventure-completed", result = "summary=${v.result.summary} coins=${v.result.coinAward}")
                    this@PetAdventureFlow.phase = AdventurePhase.FINISHED
                    AdventureOutcome.Completed(mode, v.result.summary, v.result.coinAward, v.result.items)
                }
                is AdventureWatch.Verdict.TooLong -> {
                    // 诗和远方要 2 小时：不守着，宠物在游戏里继续跑，结果之后在游戏里看
                    emit("adventure-result-pending", result = "remainingSec=${v.remainingSec}")
                    AdventureOutcome.Started(mode, startedAt, v.remainingSec / 60 + 1)
                }
                else -> {
                    this@PetAdventureFlow.phase = AdventurePhase.FAILED
                    emit("adventure-timeout", result = "sawPanel=${watch.sawProgress} remaining=${watch.lastRemainingSec}")
                    AdventureOutcome.Failed("ADVENTURE_SETTLEMENT_TIMEOUT")
                }
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
