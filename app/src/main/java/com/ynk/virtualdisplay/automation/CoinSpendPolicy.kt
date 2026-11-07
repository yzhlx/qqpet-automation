package com.ynk.virtualdisplay.automation

/**
 * STR-13 的金币底线与每日学习净支出上限判定（纯函数，JVM 可测）。
 *
 * 输入全部为已确认读数：课程页学费、课程页金币余额、方案剩余轮次。
 * 任一字段为 null 表示该门禁未启用，不参与判定。
 * 未知读数由调用方在进入本函数前拦截（沿用既有 COURSE_COINS_INSUFFICIENT 路径），
 * 这里不把未知当作通过。
 */
object CoinSpendPolicy {

    sealed interface Decision {
        object Allowed : Decision
        data class Rejected(val reason: String) : Decision
    }

    /**
     * @param balanceCoins 课程页读出的当前金币余额
     * @param tuitionCoins 单节课学费
     * @param remainingRounds 本方案剩余学习轮次
     * @param minCoinFloor 用户配置的金币底线（扣完全部学费后余额不得低于该值）；null 关闭
     * @param maxDailyNetSpend 用户配置的每日净支出上限；null 关闭
     * @param alreadySpentToday 今日已确认净支出（来自当日历史记录）；无可靠记录时为 0
     */
    fun evaluate(
        balanceCoins: Int,
        tuitionCoins: Int,
        remainingRounds: Int,
        minCoinFloor: Int?,
        maxDailyNetSpend: Int?,
        alreadySpentToday: Int,
    ): Decision {
        require(balanceCoins >= 0 && tuitionCoins >= 0 && remainingRounds in 0..10 && alreadySpentToday >= 0) {
            "COIN_SPEND_POLICY_INPUT_INVALID"
        }
        return evaluatePlanned(balanceCoins, tuitionCoins.toLong() * remainingRounds,
            minCoinFloor, maxDailyNetSpend, alreadySpentToday)
    }

    /**
     * STR-09：按真实学费合计判断，而不是「单节课学费 × 节数」。
     *
     * 均衡轮换里每一节的学费可能不同（10 分钟课与 30 分钟课价格不同），
     * 所以闸门要拿到真实合计，不能拿最贵那门乘节数，也不能拿最便宜那门乘节数。
     */
    fun evaluatePlanned(
        balanceCoins: Int,
        plannedSpendCoins: Long,
        minCoinFloor: Int?,
        maxDailyNetSpend: Int?,
        alreadySpentToday: Int,
    ): Decision {
        require(balanceCoins >= 0 && plannedSpendCoins >= 0 && alreadySpentToday >= 0) {
            "COIN_SPEND_POLICY_INPUT_INVALID"
        }
        val plannedSpend = plannedSpendCoins

        if (maxDailyNetSpend != null) {
            val projectedTotal = alreadySpentToday.toLong() + plannedSpend
            if (projectedTotal > maxDailyNetSpend) {
                return Decision.Rejected(
                    "COIN_SPEND_DAILY_CAP_EXCEEDED:spent=" + alreadySpentToday +
                        " planned=" + plannedSpend + " cap=" + maxDailyNetSpend
                )
            }
        }

        if (minCoinFloor != null) {
            val after = balanceCoins.toLong() - plannedSpend
            if (after < minCoinFloor) {
                return Decision.Rejected(
                    "COIN_FLOOR_BREACH:balance=" + balanceCoins +
                        " planned=" + plannedSpend + " floor=" + minCoinFloor
                )
            }
        }

        return Decision.Allowed
    }
}
