package com.ynk.virtualdisplay.automation

/** The spending cap is permission, not evidence that a job has no entry fee. */
object WorkEvidencePolicy {
    fun allowsUnknownBaseline(entry: WorkCatalog.WorkCatalogEntry?, maxCoinsSpent: Int): Boolean =
        maxCoinsSpent == 0 && entry != null && entry.confidence == RewardConfidence.VERIFIED &&
            entry.entryFeeCoins == 0 && !entry.entryFeeEvidence.isNullOrBlank()
}
