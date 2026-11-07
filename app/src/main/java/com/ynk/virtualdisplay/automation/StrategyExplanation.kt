package com.ynk.virtualdisplay.automation

/**
 * STR-16 策略解释：把本轮「为什么做这个动作、为什么没做其他动作」变成可以核对的事实。
 *
 * 纯函数，无 Android 依赖，JVM 可测。输入只有两件已经确认的事：
 *  - 方案里勾选的动作（[PlanV2.steps]）；
 *  - 每个动作在本轮的真实结果（[RunSnapshot.steps] 的状态与原因）。
 *
 * 未知不当作成功：
 *  - 方案没勾选的动作记为 NOT_SELECTED，不会被写成已完成；
 *  - 勾选了却因为前一步失败而没跑到的动作记为 NOT_EXECUTED，同样不算完成；
 *  - 预检就把这一轮挡住、一步都没跑时，勾选过的动作全部记为「本轮没有执行」。
 */
object StrategyExplanation {

    /** 一个动作在本轮的真实结果。 */
    enum class Outcome { COMPLETED, SKIPPED, BLOCKED, FAILED, UNCONFIRMED, NOT_SELECTED, NOT_EXECUTED }

    /**
     * 一个动作的说明事实：中文名 + 真实结果 + 原因（原因码或说明短语）。
     *
     * [transferredTo] 是 STR-01/STR-02 的转移：本轮这个动作确定失败后，改做了哪个动作。
     */
    data class Action(val label: String, val outcome: Outcome, val reason: String? = null,
        val transferredTo: String? = null)

    /** 勾选过但一步都没轮到：预检就把这一轮挡住了。 */
    const val NOT_STARTED = "本轮没有开始执行"

    /** 勾选过但没轮到：前一步没有完成，方案按顺序停下。 */
    const val STOPPED_BY_PREVIOUS = "前一步没有完成"

    /** 动作类型的中文名，与方案配置页、运行页用词一致。 */
    fun labelOf(type: PlanStepType): String = when (type) {
        PlanStepType.SIGN_IN -> "每日签到"
        PlanStepType.MAILBOX -> "信箱待领"
        PlanStepType.LUCKY_BAG -> "福袋任务"
        PlanStepType.WORK -> "打工"
        PlanStepType.CARE -> "护理"
        PlanStepType.STUDY -> "学习"
        PlanStepType.ADVENTURE -> "冒险"
    }

    /**
     * 方案 + 本轮执行结果 → 逐个动作的说明。
     *
     * 顺序：先按方案的执行顺序列出勾选过的动作，再列出方案没勾选的动作。
     * [snapshot] 为 null 表示本轮还没有任何执行记录，这时勾选过的动作都记为「本轮没有执行」。
     */
    fun actionsOf(plan: PlanV2, snapshot: RunSnapshot?): List<Action> {
        val executions = snapshot?.steps.orEmpty()
        val transfers = snapshot?.transfers.orEmpty()
        val anyStarted = executions.any { it.state != StepState.PENDING }
        val chosen = plan.steps.mapIndexed { index, step ->
            val execution = executions.getOrNull(index)
            when {
                execution == null -> Action(labelOf(step.type), Outcome.NOT_EXECUTED, NOT_STARTED)
                execution.state == StepState.PENDING -> Action(labelOf(step.type), Outcome.NOT_EXECUTED,
                    if (anyStarted) STOPPED_BY_PREVIOUS else NOT_STARTED)
                execution.state == StepState.CANCELLED -> Action(labelOf(step.type), Outcome.NOT_EXECUTED, execution.reason)
                else -> Action(labelOf(step.type), outcomeOf(execution.state), execution.reason,
                    transfers.firstOrNull { it.stoppedStepId == step.stepId }?.let { labelOf(it.resumedType) })
            }
        }
        val chosenTypes = plan.steps.map { it.type }
        val notChosen = PlanStepType.values().filter { it !in chosenTypes }
            .map { Action(labelOf(it), Outcome.NOT_SELECTED, null) }
        return chosen + notChosen
    }

    private fun outcomeOf(state: StepState): Outcome = when (state) {
        StepState.COMPLETED -> Outcome.COMPLETED
        StepState.SKIPPED -> Outcome.SKIPPED
        StepState.BLOCKED -> Outcome.BLOCKED
        StepState.FAILED -> Outcome.FAILED
        StepState.UNCONFIRMED -> Outcome.UNCONFIRMED
        StepState.PENDING, StepState.RUNNING, StepState.CANCELLED -> Outcome.NOT_EXECUTED
    }
}
