package com.ynk.virtualdisplay.automation

/**
 * STR-11：账号可核对岗位的收益比较。
 *
 * 只把「现场读过且 7 天内」的岗位当作账号已开放的证据；目录里的 PAGE_READING /
 * VERIFIED 数值只是参考，不能证明这个账号现在能接这单。
 *
 * 排名只在可执行（VERIFIED）且现场读过的岗位之间进行，避免推荐一个还不能选的岗位；
 * 读不到时调用方应退回安全默认岗位。
 */
object WorkYieldComparison {
    data class Row(
        val optionId: String,
        val venue: String,
        val optionLabel: String,
        val durationMinutes: Int,
        val coinReward: Int,
        val workScoreReward: Int,
        val energyCost: Int,
        val cleanlinessCost: Int,
        val live: Boolean,
        val selectable: Boolean,
        val entryFeeKnown: Boolean,
        val readAtMs: Long?,
        val coinsPerMinute: Double,
        val workScorePerMinute: Double,
        val energyPerMinute: Double,
        val cleanlinessPerMinute: Double,
    )

    data class Summary(
        val rows: List<Row>,
        val liveRows: List<Row>,
        val selectableLiveRows: List<Row>,
        val bestCoins: Row?,
        val bestWorkScore: Row?,
        val leastEnergy: Row?,
    ) {
        val hasLive: Boolean get() = liveRows.isNotEmpty()
    }

    fun compare(
        entries: List<WorkCatalog.WorkCatalogEntry>,
        readings: Map<String, WorkReading>,
        nowMs: Long,
    ): Summary {
        val rows = entries.filter { it.confidence != RewardConfidence.UNKNOWN }.map { entry ->
            val reading = readings[entry.id]?.takeIf {
                it.readAtMs > 0 && nowMs >= it.readAtMs &&
                    nowMs - it.readAtMs <= SessionPlan.CATALOG_MAX_AGE_MS && it.durationMinutes > 0
            }
            val duration = reading?.durationMinutes ?: entry.durationMinutes
            val coins = reading?.coinReward ?: entry.coinReward
            val score = reading?.workScoreReward ?: entry.workScoreReward
            val energy = reading?.energyCost ?: entry.energyCost
            val clean = reading?.cleanlinessCost ?: entry.cleanlinessCost
            Row(
                optionId = entry.id,
                venue = entry.venue,
                optionLabel = entry.optionLabel,
                durationMinutes = duration,
                coinReward = coins,
                workScoreReward = score,
                energyCost = energy,
                cleanlinessCost = clean,
                live = reading != null,
                selectable = entry.confidence == RewardConfidence.VERIFIED,
                entryFeeKnown = entry.entryFeeCoins != null,
                readAtMs = reading?.readAtMs,
                coinsPerMinute = coins.toDouble() / duration,
                workScorePerMinute = score.toDouble() / duration,
                energyPerMinute = energy.toDouble() / duration,
                cleanlinessPerMinute = clean.toDouble() / duration,
            )
        }
        val live = rows.filter { it.live }
        val selectable = live.filter { it.selectable }
        return Summary(
            rows = rows.sortedWith(compareByDescending<Row> { it.live }.thenBy { it.durationMinutes }),
            liveRows = live,
            selectableLiveRows = selectable,
            bestCoins = selectable.maxByOrNull { it.coinsPerMinute },
            bestWorkScore = selectable.maxByOrNull { it.workScorePerMinute },
            leastEnergy = selectable.minByOrNull { it.energyPerMinute },
        )
    }
}
