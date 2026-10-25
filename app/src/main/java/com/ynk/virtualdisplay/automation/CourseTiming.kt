package com.ynk.virtualdisplay.automation

/**
 * How many minutes one lesson of the plan's course charges against the shared daily budget, read
 * off this account's own scanned list instead of the verified-course table.
 *
 * [SessionPlan.courseDurationMinutes] trusts only the closed-loop table, which holds exactly one
 * course. That keeps the default path honest but leaves every scanned course unrunnable: a
 * 30-minute lesson stops with `PLAN_COURSE_DURATION_UNKNOWN` even though the scan did read its
 * duration. This is the second way to the same number, for courses the scan has seen: the course
 * must appear in a complete, fresh catalog as an unlocked row, and its minutes must fit inside one
 * day's hard stop — anything else fails closed, because charging a guess would let the budget
 * guard pass a lesson the game then bills differently.
 *
 * A resolved duration is still a list reading. Before a lesson may start the detail page has to
 * repeat the same course and the same minutes; [confirmDetail] is that gate, kept pure so the
 * caller can run it on whatever it just read off the page.
 */
data class CourseTiming(
    /** Catalog id of the course, the same string as [SessionPlan.courseId]. */
    val id: String,
    /** The course name as the catalog read it; the detail page must show this again. */
    val name: String,
    /** Minutes one lesson charges against the shared daily budget. */
    val durationMinutes: Int,
) {
    init {
        require(durationMinutes in MIN_DURATION_MINUTES..MAX_DURATION_MINUTES) {
            "COURSE_DURATION_OUT_OF_RANGE:$durationMinutes"
        }
    }

    /**
     * The last check before 开始上课: the detail page must repeat the same name and the same
     * minutes. A different name means the wrong course opened; different minutes mean the page no
     * longer says what the scan read. Either stops the session instead of starting a lesson the
     * budget would have priced wrong. [courseName] is trimmed like every other OCR line, the way
     * [CourseRowLocator.titleMatches] compares a page title.
     */
    fun confirmDetail(courseName: String, minutes: Int) {
        check(courseName.trim() == name) { "COURSE_DETAIL_NAME_MISMATCH:$courseName" }
        check(minutes == durationMinutes) { "COURSE_DETAIL_MINUTES_MISMATCH:$minutes!=$durationMinutes" }
    }

    companion object {
        const val MIN_DURATION_MINUTES = 1

        /** A lesson longer than a whole day's hard stop could never pass the budget guard anyway. */
        const val MAX_DURATION_MINUTES = DailyBudgetStore.HARD_STOP_MINUTES

        /**
         * Reads [plan]'s course out of [catalog] as budget minutes. The catalog is this
         * account's own list, so its gates are the account's gates: no catalog, an incomplete
         * sweep, or one older than the plan allows authorises nothing; a course absent from a
         * fresh catalog does not exist for this account; a locked row can never be picked.
         * Completeness means the scanner reached both boundaries. A sweep that hits its screen
         * cap fails instead of replacing the previous catalog.
         */
        fun resolve(plan: SessionPlan, catalog: CourseCatalog?, nowMs: Long): CourseTiming {
            checkNotNull(catalog) { "COURSE_TIMING_CATALOG_MISSING" }
            check(catalog.complete) { "COURSE_TIMING_CATALOG_INCOMPLETE" }
            check(catalog.isFresh(nowMs)) { "COURSE_TIMING_CATALOG_STALE" }
            val entry = catalog.find(plan.courseId) ?: error("COURSE_NOT_FOUND:${plan.courseName}")
            check(entry.unlocked) { "COURSE_LOCKED:${plan.courseName}" }
            val minutes = entry.durationMinutes ?: error("COURSE_DURATION_MISSING:${plan.courseId}")
            return CourseTiming(id = entry.id, name = entry.name, durationMinutes = minutes)
        }
    }
}
