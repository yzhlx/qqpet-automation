package com.ynk.virtualdisplay.automation

/**
 * 中断之后结果未知的动作：可能已经扣费、已经开课，也可能已经领取了奖励。
 *
 * 判定口径只有一条：账本里这条尝试的结果没有被证实。它要么停在
 * [ReserveStatus.UNCONFIRMED_RETAINED]（输入已经发出，结局始终没读到），要么被中断恢复标成
 * [ReserveStatus.RECONCILED_STOPPED] 并且原因里写着 OUTCOME_REMAINS_UNKNOWN（等待时间过去了，
 * 但游戏里到底做没做仍然不知道）。两种都按「未知」处理：不当作成功，也不当作没发生，
 * 更不能当作没发生而再来一次。
 *
 * 只有会花钱或会领奖的动作需要这道闸门：学习要交学费，签到／信箱／福袋要领奖。
 * 打工不交学费也不领奖，它的中断由带证据的补写路径处理（WorkCompletionWriteback），不在这里拦。
 */
data class PendingAction(
    val attemptId: String,
    val stepType: PlanStepType,
    val minutes: Int,
    val day: String,
    val reason: String,
) {
    init {
        require(attemptId.isNotBlank()) { "PENDING_ACTION_ATTEMPT_BLANK" }
        require(minutes >= 0) { "PENDING_ACTION_MINUTES_INVALID" }
        require(day.isNotBlank()) { "PENDING_ACTION_DAY_BLANK" }
    }
}

/** 一步现在能不能做：照做，或者结果未知、要用户先确认。 */
sealed interface PendingActionDecision {
    /** 没有待确认的动作：照常执行。 */
    data object Proceed : PendingActionDecision
    /** 结果未知：不能擅自再花一次钱，交给用户确认。 */
    data class NeedsConfirmation(val actions: List<PendingAction>) : PendingActionDecision
}

object PendingActionGuard {
    /** 需要确认的动作：会扣费或会领奖。 */
    val guardedTypes: Set<PlanStepType> = setOf(
        PlanStepType.STUDY, PlanStepType.SIGN_IN, PlanStepType.MAILBOX, PlanStepType.LUCKY_BAG)

    /** 中断恢复写在原因里的标记：结果仍然是未知。 */
    const val UNKNOWN_MARK = "OUTCOME_REMAINS_UNKNOWN"
    /** 用户确认「已经做完」。 */
    const val USER_DONE_MARK = "USER_CONFIRMED_DONE"
    /** 用户确认「没有发生」。 */
    const val USER_NOT_DONE_MARK = "USER_CONFIRMED_NOT_DONE"

    /**
     * 从尝试号里取出动作类型。旧会话的步骤号是 study/work/care，新方案的是 <planId>:study，
     * 两种都取最后一段冒号之后的名字比对。
     */
    fun stepTypeOf(attemptId: String): PlanStepType? {
        val stepId = runCatching { WorkCompletionWritebackPolicy.splitAttemptId(attemptId).second }
            .getOrNull() ?: return null
        val tail = stepId.substringAfterLast(':')
        return PlanStepType.entries.firstOrNull { it.name.equals(tail, ignoreCase = true) }
    }

    /** 这条尝试的结果是不是仍然未知。 */
    fun isOutcomeUnknown(attempt: BudgetAttempt): Boolean {
        val reason = attempt.reason ?: ""
        return when (attempt.status) {
            ReserveStatus.UNCONFIRMED_RETAINED -> true
            ReserveStatus.RECONCILED_STOPPED ->
                reason.contains(UNKNOWN_MARK) && !reason.contains(USER_NOT_DONE_MARK)
            else -> false
        }
    }

    /** 结果未知的尝试：中断之后可能被重复执行的那些。 */
    fun pendingActions(attempts: Collection<BudgetAttempt>): List<PendingAction> =
        attempts.mapNotNull { attempt ->
            if (!isOutcomeUnknown(attempt)) return@mapNotNull null
            val type = stepTypeOf(attempt.attemptId) ?: return@mapNotNull null
            if (type !in guardedTypes) return@mapNotNull null
            PendingAction(attempt.attemptId, type, attempt.minutes, attempt.day, attempt.reason ?: "")
        }.sortedBy { it.attemptId }

    /** 用户确认「已经做完」的动作数量：学习用来少上一节，领取类用来不再重复领。 */
    fun confirmedDoneCount(attempts: Collection<BudgetAttempt>, day: String, type: PlanStepType): Int =
        attempts.count { it.status == ReserveStatus.COMPLETED && it.day == day &&
            (it.reason ?: "").contains(USER_DONE_MARK) && stepTypeOf(it.attemptId) == type }

    /** 这一步现在能不能做。 */
    fun decide(actions: List<PendingAction>, type: PlanStepType): PendingActionDecision {
        val pending = actions.filter { it.stepType == type }
        return if (pending.isEmpty()) PendingActionDecision.Proceed
        else PendingActionDecision.NeedsConfirmation(pending)
    }
}
