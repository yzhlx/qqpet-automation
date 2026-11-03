package com.ynk.virtualdisplay

import com.ynk.virtualdisplay.automation.RewardConfidence
import com.ynk.virtualdisplay.automation.WorkCatalog
import org.junit.Assert.*
import org.junit.Test

class WorkCandidateSwitchTest {

    @Test
    fun candidatesForPlacesPrimaryFirst() {
        val catalog = WorkCatalog()
        val primaryId = "work:竹影武馆/帮武馆跑腿"
        val candidates = catalog.candidatesFor(primaryId)

        assertTrue(candidates.isNotEmpty())
        assertEquals(primaryId, candidates.first().id)
    }

    @Test
    fun candidatesForDoesNotDuplicatePrimary() {
        val catalog = WorkCatalog()
        val primaryId = "work:竹影武馆/帮武馆跑腿"
        val candidates = catalog.candidatesFor(primaryId)

        val ids = candidates.map { it.id }
        assertEquals(ids.distinct().size, ids.size)
    }

    @Test
    fun candidatesForIncludesOtherVerifiedJobs() {
        val catalog = WorkCatalog()
        val primaryId = "work:竹影武馆/帮武馆跑腿"
        val candidates = catalog.candidatesFor(primaryId)

        // Other non-unknown candidate should follow
        assertTrue("应包含备选岗位供失败回退", candidates.size >= 2)
        assertTrue(candidates.none { it.confidence == RewardConfidence.UNKNOWN })
    }

    @Test
    fun unknownPrimaryStillProvidesSafeFallbacks() {
        val catalog = WorkCatalog()
        val candidates = catalog.candidatesFor("work:non_existent")

        assertTrue("未识别岗位时应回退到目录中已确认的岗位", candidates.isNotEmpty())
        assertTrue(candidates.all { it.confidence != RewardConfidence.UNKNOWN })
    }
}
