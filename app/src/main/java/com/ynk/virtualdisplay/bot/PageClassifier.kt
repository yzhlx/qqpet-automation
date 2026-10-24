package com.ynk.virtualdisplay.bot

/**
 * Turns template scores into one [Page]. Pure, so it is tested on recorded scores and frames.
 *
 * Order matters where two probes can match at once: popups sit on top of the page under them,
 * and the learning panel is drawn over the outdoor map, so both are checked before what they
 * cover. The order otherwise follows the earlier PetNavigation.initialise, which held on device.
 */
object PageClassifier {
    const val THRESHOLD = 0.97

    /** Probe names (see [ProbeSet]) checked in priority order. */
    private val order = listOf(
        "qq_host_search" to Page.QQ_HOST,
        "care_upgrade" to Page.CARE_UPGRADE_POPUP,
        "upgrade" to Page.UPGRADE_POPUP,
        "result" to Page.RESULT,
        "learning" to Page.LEARNING,
        "food_scene" to Page.FOOD_SCENE,
        "bath" to Page.BATH_SCENE,
        "health" to Page.HEALTH_PANEL,
        "course_detail" to Page.COURSE_DETAIL,
        "course_list" to Page.COURSE_LIST,
        "academy" to Page.ACADEMY,
        "room" to Page.ROOM,
        "outdoor" to Page.OUTDOOR,
    )

    /** Every probe name the classifier reads; the device scorer must provide each one. */
    val probeNames: List<String> = order.map { it.first }

    /**
     * @param scores best template score per probe name; a missing name counts as no match.
     * @param isTown whether OCR recognised a career-town page; only consulted when no template matched.
     */
    fun classify(scores: Map<String, Double>, isTown: () -> Boolean = { false }): Page {
        for ((name, page) in order) {
            if ((scores[name] ?: 0.0) >= THRESHOLD) return page
        }
        return if (isTown()) Page.TOWN else Page.UNKNOWN
    }
}
