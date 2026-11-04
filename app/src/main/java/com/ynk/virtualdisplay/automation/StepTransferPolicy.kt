package com.ynk.virtualdisplay.automation

/**
 * STR-01/STR-02：优先动作走不通时，按规则转到后面的动作，而不是让整轮就此停下。
 *
 * 纯函数，无 Android 依赖，JVM 可测。规则只看四件事：
 *  - 用户是否打开了「受阻时转移」；
 *  - 受阻的结果是不是「已经确定失败」（BLOCKED/FAILED）；
 *  - 方案里后面还剩什么动作；
 *  - 本轮已经转移过几次。
 *
 * 转移目标就是方案里下一个还没执行的动作：用户自己排的顺序就是规则，代码不替用户另排一套。
 * 结果无法确认（UNCONFIRMED）或用户主动停掉（CANCELLED）时绝不转移：
 * 那两种情况下动作可能已经在游戏里发生，再往下做可能重复扣费或重复领取。
 */
object StepTransferPolicy {

    /** 一次运行最多转移一次：第二次受阻说明整体状态有问题，继续试只是空转。 */
    const val MAX_TRANSFERS_PER_RUN = 1

    /** 转移或不转移的决定。[rule] 是可核对的原因码，不编造解释。 */
    sealed interface Decision {
        /** 转到 [target] 这个动作继续做。 */
        data class Transfer(val target: PlanStepType, val rule: String) : Decision
        /** 不转移，本轮就此停下。[rule] 说明为什么不转移。 */
        data class Stop(val rule: String) : Decision
    }

    /**
     * @param enabled 方案是否打开了「受阻时转移」
     * @param stoppedState 停住那一步的结果
     * @param stoppedType 停住那一步的动作类型
     * @param remainingTypes 方案里后面还没执行的动作类型，按方案顺序
     * @param transfersUsed 本轮已经转移过几次
     */
    fun decide(
        enabled: Boolean,
        stoppedState: StepState,
        stoppedType: PlanStepType,
        remainingTypes: List<PlanStepType>,
        transfersUsed: Int,
    ): Decision {
        if (!enabled) return Decision.Stop("TRANSFER_DISABLED")
        if (stoppedState != StepState.BLOCKED && stoppedState != StepState.FAILED) {
            return Decision.Stop("TRANSFER_NOT_ALLOWED_FOR_STATE:" + stoppedState.name)
        }
        if (transfersUsed >= MAX_TRANSFERS_PER_RUN) return Decision.Stop("TRANSFER_ALREADY_USED")
        val target = remainingTypes.firstOrNull() ?: return Decision.Stop("TRANSFER_NO_TARGET")
        return Decision.Transfer(target, "TRANSFER_AFTER_" + stoppedType.name)
    }
}
