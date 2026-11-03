package com.ynk.virtualdisplay.automation

/**
 * 冒险模式定义（CORE-11）：
 * - NEARBY_WALK: 附近走走（短时探索、随机奇遇事件）
 * - POETRY_AND_DISTANT: 诗和远方（旅行、明信片与风景收藏）
 */
enum class AdventureMode(val label: String, val defaultDurationMinutes: Int) {
    NEARBY_WALK("附近走走", 5),
    POETRY_AND_DISTANT("诗和远方", 30);

    companion object {
        fun fromLabel(text: String?): AdventureMode? =
            entries.firstOrNull { it.label == text || it.name.equals(text, ignoreCase = true) }
    }
}

/** 冒险状态机阶段 */
enum class AdventurePhase {
    IDLE,
    NAVIGATING_OUTDOOR,
    ENTERING_ADVENTURE,
    PAGE_CONFIRMED,
    START_REQUESTED,
    IN_PROGRESS,
    WAITING_RESULT,
    RESULT_CONFIRMED,
    FINISHED,
    BLOCKED,
    FAILED,
    INTERRUPTED
}

/** 冒险执行结果 */
sealed interface AdventureOutcome {
    /** 冒险已开始 */
    data class Started(
        val mode: AdventureMode,
        val startedAtMs: Long,
        val expectedDurationMinutes: Int,
    ) : AdventureOutcome

    /** 冒险已完成并取得结算 */
    data class Completed(
        val mode: AdventureMode,
        val eventSummary: String,
        val coinAward: Int? = null,
        val itemsAwarded: List<String> = emptyList(),
    ) : AdventureOutcome

    /** 冒险受阻（例如体力不足、到达每日上限、页面未识别） */
    data class Blocked(val reason: String) : AdventureOutcome

    /** 冒险执行失败 */
    data class Failed(val reason: String) : AdventureOutcome

    /** 用户主动暂停或中断 */
    data class Interrupted(val reason: String) : AdventureOutcome
}
