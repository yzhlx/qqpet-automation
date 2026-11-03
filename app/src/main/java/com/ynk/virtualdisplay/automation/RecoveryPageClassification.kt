package com.ynk.virtualdisplay.automation

/** Recovery classification only; callers supply evidence from one valid current frame. */
object RecoveryPageClassification {
    suspend fun classify(
        trustedPage: String,
        trustedPageMatches: Boolean,
        healthVisible: Boolean,
        ambiguousCourse: Boolean,
        resultVisible: Boolean,
        learningEvidence: (suspend () -> Boolean)? = null,
    ): String? {
        val overlayMismatch = healthVisible && trustedPage != "health" && trustedPage != "HEALTH"
        // Preserve health-overlay and ambiguity guards before considering extra evidence.
        // A real result wins over text: the normal LEARNING -> result transition must still
        // go through SessionRecovery's reconcile check rather than masquerade as LEARNING.
        return when {
            overlayMismatch -> "HEALTH"
            ambiguousCourse -> null
            trustedPageMatches -> trustedPage
            resultVisible -> "result"
            trustedPage == "LEARNING" && learningEvidence?.invoke() == true -> trustedPage
            else -> null
        }
    }
}
