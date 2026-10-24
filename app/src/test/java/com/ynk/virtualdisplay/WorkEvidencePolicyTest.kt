package com.ynk.virtualdisplay

import com.ynk.virtualdisplay.automation.*
import org.junit.Assert.*
import org.junit.Test

class WorkEvidencePolicyTest {
    private fun job(confidence: RewardConfidence = RewardConfidence.VERIFIED,
        fee: Int? = 0, evidence: String? = "measured zero entry fee") =
        WorkCatalog.WorkCatalogEntry("work:test/job", "test", "job", 10, 68, 10, 5, 2,
            confidence, "payroll evidence", entryFeeCoins = fee, entryFeeEvidence = evidence)

    @Test fun `zero spending grant alone does not establish the job entry fee`() {
        assertFalse(WorkEvidencePolicy.allowsUnknownBaseline(job(fee = null), 0))
        assertFalse(WorkEvidencePolicy.allowsUnknownBaseline(job(evidence = null), 0))
        assertFalse(WorkEvidencePolicy.allowsUnknownBaseline(job(fee = 1), 0))
    }

    @Test fun `only verified evidenced zero fee and zero spending authorisation waive baseline`() {
        assertTrue(WorkEvidencePolicy.allowsUnknownBaseline(job(), 0))
        assertFalse(WorkEvidencePolicy.allowsUnknownBaseline(job(), 1))
        assertFalse(WorkEvidencePolicy.allowsUnknownBaseline(job(RewardConfidence.PAGE_READING), 0))
        assertFalse(WorkEvidencePolicy.allowsUnknownBaseline(null, 0))
    }

    @Test fun `production whitelist contains only the verified zero entry job`() {
        assertEquals(setOf("work:竹影武馆/帮武馆跑腿"), WorkCatalog().verifiedZeroEntryIds())
    }
}
