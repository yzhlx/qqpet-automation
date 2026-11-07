package com.ynk.virtualdisplay.automation

/**
 * STR-15 的方案级失败冷却判定（纯函数，JVM 可测）。
 *
 * 规则：同一 planId 最近一次以 FAILED 或 UNCONFIRMED 结束的 run，
 * 在其 endedAtMs 之后 failureCooldownMinutes 内拒绝重开。
 * STOPPED（用户主动停）与 COMPLETED 不触发冷却；
 * 没有 endedAtMs 的记录不参与判定（不把未知当作失败证据）。
 */
object FailureCooldownPolicy {

    data class Snapshot(
        val planId: String,
        val state: RunState,
        val endedAtMs: Long?,
    )

    sealed interface Decision {
        object Allowed : Decision
        data class Cooling(val remainingMinutes: Long, val lastStopReasonState: RunState) : Decision
    }

    fun evaluate(
        history: List<Snapshot>,
        planId: String,
        failureCooldownMinutes: Int?,
        nowMs: Long,
    ): Decision {
        if (failureCooldownMinutes == null) return Decision.Allowed
        val lastFailure = history
            .filter { it.planId == planId && it.endedAtMs != null }
            .filter { it.state == RunState.FAILED || it.state == RunState.UNCONFIRMED }
            .maxByOrNull { it.endedAtMs!! }
            ?: return Decision.Allowed
        val cooldownMs = failureCooldownMinutes * 60_000L
        val elapsed = nowMs - lastFailure.endedAtMs!!
        if (elapsed < 0 || elapsed >= cooldownMs) return Decision.Allowed
        val remaining = ((cooldownMs - elapsed) / 60_000L).coerceAtLeast(1)
        return Decision.Cooling(remaining, lastFailure.state)
    }
}
