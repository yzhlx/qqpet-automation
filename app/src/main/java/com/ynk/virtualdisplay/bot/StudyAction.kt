package com.ynk.virtualdisplay.bot

enum class StudyStop {
    COMPLETED,
    /** Could not get from the room to the course list. */
    NO_COURSE_LIST,
    COURSE_NOT_FOUND,
    /** "去上课" was tapped but no lesson started (usually not enough coins). */
    NOT_STARTED,
    /** The lesson started but no report card appeared in time. */
    NO_RESULT,
}

data class StudyResult(val stop: StudyStop, val highFives: Int = 0)

/**
 * Takes one lesson: room -> outdoor -> academy -> course -> 去上课, gives the high-five, waits for
 * the report card and walks home. A lesson that started is never abandoned: if anything goes wrong
 * after the start, the bot keeps waiting for the report card rather than tapping away.
 */
class StudyAction(
    private val eyes: Eyes,
    private val hands: Hands,
    private val gauges: Gauges,
    private val navigator: Navigator,
    private val pollMs: Long = 15_000L,
) {
    suspend fun run(course: String?, durationMinutes: Int): StudyResult {
        require(durationMinutes in 1..480)
        navigator.goHome()
        if (!openCourseList()) return home(StudyResult(StudyStop.NO_COURSE_LIST))
        if (!selectCourse(course)) return home(StudyResult(StudyStop.COURSE_NOT_FOUND))
        hands.tap(Layout.startCourse)
        if (!await(Page.LEARNING, tries = 15)) return home(StudyResult(StudyStop.NOT_STARTED))
        val highFives = encourage()
        if (!awaitResult(durationMinutes)) return home(StudyResult(StudyStop.NO_RESULT, highFives))
        return home(StudyResult(StudyStop.COMPLETED, highFives))
    }

    private suspend fun home(result: StudyResult): StudyResult {
        navigator.goHome()
        return result
    }

    /** Room to the open course panel. The panel can already be open when the academy loads. */
    private suspend fun openCourseList(): Boolean {
        val exit = gauges.findText("出门") ?: return false
        hands.tap(exit)
        if (!await(Page.OUTDOOR)) return false
        hands.tap(Layout.enterAcademy)
        repeat(10) {
            hands.pause(Layout.SETTLE_MS / 3)
            when (eyes.look().page) {
                Page.COURSE_LIST, Page.COURSE_DETAIL -> return true
                Page.ACADEMY -> {
                    val label = gauges.findText("初级学园") ?: return@repeat
                    hands.tap(label)
                    return await(Page.COURSE_LIST) || await(Page.COURSE_DETAIL, tries = 1)
                }
                else -> {}
            }
        }
        return false
    }

    /** Null picks the default course, which the panel opens on. Others are found by name, page by page. */
    private suspend fun selectCourse(course: String?): Boolean {
        if (course == null) {
            hands.tap(Layout.defaultCourse)
            return await(Page.COURSE_DETAIL)
        }
        repeat(MAX_COURSE_PAGES) {
            val card = gauges.findText(course)
            if (card != null) {
                hands.tap(card)
                return await(Page.COURSE_DETAIL) && gauges.findText(course) != null
            }
            hands.drag(Layout.courseFling, 40L)
            hands.pause(Layout.SETTLE_MS)
        }
        return false
    }

    /** Taps the high-five a few times once its cue shows, within the lesson's first minute. */
    private suspend fun encourage(): Int {
        repeat(30) {
            if (gauges.highFiveVisible()) {
                repeat(HIGH_FIVE_TAPS) {
                    hands.tap(Layout.highFive)
                    hands.pause(300)
                }
                return HIGH_FIVE_TAPS
            }
            hands.pause(2_000)
        }
        return 0
    }

    private suspend fun awaitResult(durationMinutes: Int): Boolean {
        val polls = ((durationMinutes * 60_000L + RESULT_GRACE_MS) / pollMs).toInt() + 1
        repeat(polls) {
            hands.pause(pollMs)
            if (eyes.look().page == Page.RESULT) return true
        }
        return false
    }

    private suspend fun await(page: Page, tries: Int = 10): Boolean {
        repeat(tries) {
            hands.pause(Layout.SETTLE_MS / 3)
            if (eyes.look().page == page) return true
        }
        return false
    }

    companion object {
        const val MAX_COURSE_PAGES = 6
        const val HIGH_FIVE_TAPS = 10
        const val RESULT_GRACE_MS = 120_000L
    }
}
