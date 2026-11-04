package com.ynk.virtualdisplay

import com.ynk.virtualdisplay.automation.RewardConfidence
import com.ynk.virtualdisplay.automation.SessionPlan
import com.ynk.virtualdisplay.automation.WorkCatalog
import com.ynk.virtualdisplay.automation.WorkReading
import com.ynk.virtualdisplay.automation.WorkYieldComparison
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkYieldComparisonTest {
    private val now = 1_700_000_000_000L

    private fun entry(
        id: String,
        venue: String = "竹影武馆",
        label: String = id,
        minutes: Int = 10,
        coins: Int = 68,
        score: Int = 10,
        energy: Int = 5,
        clean: Int = 2,
        confidence: RewardConfidence = RewardConfidence.VERIFIED,
        entryFee: Int? = 0,
    ) = WorkCatalog.WorkCatalogEntry(
        id = id, venue = venue, optionLabel = label, durationMinutes = minutes,
        coinReward = coins, workScoreReward = score, energyCost = energy,
        cleanlinessCost = clean, confidence = confidence, evidence = "test",
        entryFeeCoins = entryFee)

    private fun reading(
        id: String,
        venue: String = "竹影武馆",
        label: String = id,
        minutes: Int = 10,
        coins: Int = 68,
        score: Int = 10,
        energy: Int = 5,
        clean: Int = 2,
        at: Long = 1_700_000_000_000L,
    ) = WorkReading(id, venue, label, minutes, coins, score, energy, clean, at)

    @Test fun no_readings_leaves_every_job_unknown() {
        val summary = WorkYieldComparison.compare(
            listOf(entry("work:a"), entry("work:b")), emptyMap(), now)
        assertFalse(summary.hasLive)
        assertTrue(summary.liveRows.isEmpty())
        assertNull(summary.bestCoins)
        assertNull(summary.bestWorkScore)
        assertNull(summary.leastEnergy)
        assertEquals(listOf("work:a", "work:b"), summary.rows.map { it.optionId })
    }

    @Test fun stale_reading_is_not_live_and_does_not_rank() {
        val stale = reading("work:a", at = now - SessionPlan.CATALOG_MAX_AGE_MS - 1)
        val summary = WorkYieldComparison.compare(
            listOf(entry("work:a")), mapOf("work:a" to stale), now)
        assertFalse(summary.hasLive)
        assertNull(summary.bestCoins)
        assertNull(summary.rows.single().readAtMs)
        assertEquals(68, summary.rows.single().coinReward)
    }

    @Test fun reading_at_the_age_limit_still_counts_as_live() {
        val edge = reading("work:a", at = now - SessionPlan.CATALOG_MAX_AGE_MS)
        val summary = WorkYieldComparison.compare(
            listOf(entry("work:a")), mapOf("work:a" to edge), now)
        assertTrue(summary.hasLive)
        assertEquals(edge.readAtMs, summary.rows.single().readAtMs)
    }

    @Test fun page_reading_confidence_is_live_but_never_selectable() {
        val summary = WorkYieldComparison.compare(
            listOf(entry("work:a", confidence = RewardConfidence.PAGE_READING, entryFee = null)),
            mapOf("work:a" to reading("work:a")), now)
        assertTrue(summary.hasLive)
        assertTrue(summary.selectableLiveRows.isEmpty())
        assertNull(summary.bestCoins)
        assertNull(summary.bestWorkScore)
        assertNull(summary.leastEnergy)
        assertFalse(summary.rows.single().entryFeeKnown)
    }

    @Test fun ranking_uses_per_minute_values_among_selectable_live_jobs() {
        val summary = WorkYieldComparison.compare(
            listOf(
                entry("work:a", label = "跑腿", minutes = 10, coins = 68, score = 10, energy = 5),
                entry("work:b", label = "陪练", minutes = 45, coins = 278, score = 54, energy = 10),
            ),
            mapOf(
                "work:a" to reading("work:a", label = "跑腿", minutes = 10, coins = 68, score = 10, energy = 5),
                "work:b" to reading("work:b", label = "陪练", minutes = 45, coins = 278, score = 54, energy = 10),
            ),
            now,
        )
        assertEquals("work:a", summary.bestCoins?.optionId)
        assertEquals("work:b", summary.bestWorkScore?.optionId)
        assertEquals(6.8, summary.bestCoins?.coinsPerMinute ?: -1.0, 0.0001)
        assertEquals(1.2, summary.bestWorkScore?.workScorePerMinute ?: -1.0, 0.0001)
        assertEquals("work:b", summary.leastEnergy?.optionId)
    }

    @Test fun live_page_numbers_override_the_catalog_numbers() {
        val summary = WorkYieldComparison.compare(
            listOf(entry("work:a", coins = 68, score = 10)),
            mapOf("work:a" to reading("work:a", coins = 71, score = 11)),
            now,
        )
        val row = summary.rows.single()
        assertTrue(row.live)
        assertEquals(71, row.coinReward)
        assertEquals(11, row.workScoreReward)
    }

    @Test fun unknown_catalog_entries_never_appear() {
        val summary = WorkYieldComparison.compare(
            listOf(
                entry("work:a"),
                entry("work:u", confidence = RewardConfidence.UNKNOWN, coins = 0, score = 0, energy = 0, clean = 0),
            ),
            mapOf("work:u" to reading("work:u")), now)
        assertEquals(listOf("work:a"), summary.rows.map { it.optionId })
        assertTrue(summary.liveRows.none { it.optionId == "work:u" })
    }

    @Test fun live_rows_are_ordered_before_catalog_only_rows() {
        val summary = WorkYieldComparison.compare(
            listOf(entry("work:a", minutes = 45), entry("work:b", minutes = 10)),
            mapOf("work:b" to reading("work:b", minutes = 10)),
            now,
        )
        assertEquals(listOf("work:b", "work:a"), summary.rows.map { it.optionId })
    }
}
