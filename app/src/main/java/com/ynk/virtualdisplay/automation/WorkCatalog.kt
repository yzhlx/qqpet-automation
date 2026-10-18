package com.ynk.virtualdisplay.automation

/**
 * The P5.1 career catalog, in code.
 *
 * Three levels stay separate: career -> venue-stage -> single work option. Rewards hang on the
 * OPTION, so two options in one venue are two independent entries and are never merged.
 *
 * Every entry carries its own [RewardConfidence], and an UNKNOWN entry is a hole rather than a zero:
 * the planner must not schedule it and the flow must not run it.
 */
class WorkCatalog(private val entries: List<WorkCatalogEntry> = defaultEntries()) {

    data class WorkCatalogEntry(
        val id: String,
        val venue: String,
        val optionLabel: String,
        val durationMinutes: Int,
        val coinReward: Int,
        val workScoreReward: Int,
        val energyCost: Int,
        val cleanlinessCost: Int,
        val confidence: RewardConfidence,
        val evidence: String,
        val caveat: String? = null,
    )

    fun find(venue: String, optionId: String): WorkCatalogEntry? =
        entries.firstOrNull { it.venue == venue && it.id == optionId }

    fun byVenue(venue: String): List<WorkCatalogEntry> = entries.filter { it.venue == venue }

    fun all(): List<WorkCatalogEntry> = entries

    /**
     * Compares a freshly re-read page row against the catalog entry.
     *
     * Returns the list of field-level differences (empty means the page agrees with the catalog).
     * An UNKNOWN catalog reward is reported as a difference rather than treated as agreement, so a
     * caller can never mistake "we have no data" for "the page matches".
     */
    fun compare(entry: WorkCatalogEntry, page: WorkOption): List<String> {
        val diffs = mutableListOf<String>()
        if (entry.confidence == RewardConfidence.UNKNOWN) {
            diffs += "目录中该收益未采集（${entry.evidence}）"
            return diffs
        }
        if (page.durationMinutes != entry.durationMinutes) {
            diffs += "时长 页面${page.durationMinutes} vs 目录${entry.durationMinutes}"
        }
        if (page.pageCoinReward != entry.coinReward) {
            diffs += "金币 页面${page.pageCoinReward} vs 目录${entry.coinReward}"
        }
        if (page.pageWorkScoreReward != entry.workScoreReward) {
            diffs += "工分 页面${page.pageWorkScoreReward} vs 目录${entry.workScoreReward}"
        }
        if (page.pageEnergyCost != entry.energyCost) {
            diffs += "体力 页面${page.pageEnergyCost} vs 目录${entry.energyCost}"
        }
        if (page.pageCleanlinessCost != entry.cleanlinessCost) {
            diffs += "清洁 页面${page.pageCleanlinessCost} vs 目录${entry.cleanlinessCost}"
        }
        return diffs
    }

    companion object {
        /**
         * P5.1 measured values, 2026-10-12, on display 4.
         *
         * 帮武馆跑腿 is VERIFIED: one real closed-loop session confirmed +68 coins / +10 工分 /
         * 体力 -5 (P5.1 §5). 陪练基础课 and 替摊主看摊 are PAGE_READING: the row was read after
         * selecting the option card but never closed-loop verified. 街巷跑腿 is UNKNOWN: QQ rate
         * limiting blocked collection, so there is no number to record.
         */
        fun defaultEntries(): List<WorkCatalogEntry> = listOf(
            WorkCatalogEntry(
                id = "work:竹影武馆/帮武馆跑腿",
                venue = "竹影武馆",
                optionLabel = "帮武馆跑腿",
                durationMinutes = 10,
                coinReward = 68,
                workScoreReward = 10,
                energyCost = 5,
                cleanlinessCost = 2,
                confidence = RewardConfidence.VERIFIED,
                evidence = "docs/P5-01-career-discovery.md §5 真实打工闭环 2026-10-12 19:18",
            ),
            WorkCatalogEntry(
                id = "work:竹影武馆/陪练基础课",
                venue = "竹影武馆",
                optionLabel = "陪练基础课",
                durationMinutes = 45,
                coinReward = 278,
                workScoreReward = 54,
                energyCost = 10,
                cleanlinessCost = 4,
                confidence = RewardConfidence.PAGE_READING,
                evidence = "docs/P5-01-career-discovery.md §3.1 选中选项卡片后 zoom OCR",
                caveat = "45 分钟收益尚未用真实打工闭环验证；清洁数字 OCR 不确定",
            ),
            WorkCatalogEntry(
                id = "work:风铃旅社/替摊主看摊",
                venue = "风铃旅社",
                optionLabel = "替摊主看摊",
                durationMinutes = 10,
                coinReward = 40,
                workScoreReward = 10,
                energyCost = 5,
                cleanlinessCost = 2,
                confidence = RewardConfidence.PAGE_READING,
                evidence = "docs/P5-01-career-discovery.md §3.2 详情页奖励行",
            ),
            WorkCatalogEntry(
                id = "work:风铃旅社/街巷跑腿",
                venue = "风铃旅社",
                optionLabel = "街巷跑腿",
                durationMinutes = 45,
                coinReward = 0,
                workScoreReward = 0,
                energyCost = 0,
                cleanlinessCost = 0,
                confidence = RewardConfidence.UNKNOWN,
                evidence = "docs/P5-01-career-discovery.md §8.1 QQ 限流阻塞，未采集",
                caveat = "收益未采集；不得外推为 0 或任何其他数值",
            ),
        )
    }
}
