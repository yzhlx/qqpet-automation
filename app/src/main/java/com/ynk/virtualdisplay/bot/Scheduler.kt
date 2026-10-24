package com.ynk.virtualdisplay.bot

/** What the person asks the bot to do in one run. */
data class BotConfig(
    /** Null takes the course the academy panel opens on. */
    val course: String? = null,
    val courseMinutes: Int = 10,
    val lessons: Int = 1,
    val energyTarget: Int = 70,
    val cleanTarget: Int = 70,
    /** Work a shift when a lesson does not start for lack of coins. */
    val workWhenShort: Boolean = true,
    val job: Job = Job.RUN_ERRANDS,
    /** The game's own daily cap on lesson and work minutes. */
    val dailyMinutes: Int = 480,
) {
    init {
        require(lessons in 1..10)
        require(courseMinutes in 1..480)
        require(energyTarget in 0..100 && cleanTarget in 0..100)
        require(dailyMinutes in 1..480)
    }
}

/** Minutes of lessons and work already spent today. */
interface DailyLedger {
    fun minutesUsed(): Int
    fun add(minutes: Int)
}

enum class RunStop { DONE, DAILY_LIMIT, NOT_ENOUGH_COINS, COURSE_NOT_FOUND, TOO_MANY_FAILURES }

data class RunReport(val lessons: Int, val shifts: Int, val coinsEarned: Int, val stop: RunStop)

/**
 * The bot's whole decision logic: before each lesson, care; then try the lesson; when it does not
 * start, work one shift and try once more. Each action ends in the room, so after any failure the
 * next attempt simply starts again from there; only repeated failures end the run.
 */
class Scheduler(
    private val care: suspend (energyTarget: Int, cleanTarget: Int) -> CareResult,
    private val study: suspend (course: String?, minutes: Int) -> StudyResult,
    private val work: suspend (Job) -> WorkResult,
    private val goHome: suspend () -> Unit,
    private val ledger: DailyLedger,
    private val log: (String) -> Unit = {},
    private val maxFailures: Int = 3,
) {
    suspend fun run(config: BotConfig): RunReport {
        var lessons = 0
        var shifts = 0
        var earned = 0
        var failures = 0
        fun report(stop: RunStop) = RunReport(lessons, shifts, earned, stop).also { log("结束：$it") }
        fun left() = config.dailyMinutes - ledger.minutesUsed()

        var workedForThisLesson = false
        while (lessons < config.lessons) {
            if (failures >= maxFailures) return report(RunStop.TOO_MANY_FAILURES)
            if (left() < config.courseMinutes) return report(RunStop.DAILY_LIMIT)
            try {
                val cared = care(config.energyTarget, config.cleanTarget)
                log("护理：${cared.stop} 体力/清洁=${cared.health} 喂${cared.feeds} 洗${cared.cleans} 花${cared.coinsSpent}币")
                val result = study(config.course, config.courseMinutes)
                log("上课：${result.stop}")
                when (result.stop) {
                    StudyStop.COMPLETED, StudyStop.NO_RESULT -> {
                        // A lesson that started used its minutes, report card or not.
                        ledger.add(config.courseMinutes)
                        lessons++
                        workedForThisLesson = false
                    }
                    StudyStop.NOT_STARTED -> {
                        if (!config.workWhenShort || workedForThisLesson) return report(RunStop.NOT_ENOUGH_COINS)
                        if (left() < config.job.minutes + config.courseMinutes) return report(RunStop.DAILY_LIMIT)
                        workedForThisLesson = true
                        // A shift costs energy and cleanliness of its own; care covers it at the targets.
                        care(maxOf(config.energyTarget, config.job.energyCost), maxOf(config.cleanTarget, config.job.cleanCost))
                        val shift = work(config.job)
                        log("打工：${shift.stop} 工资=${shift.coinsEarned}")
                        if (shift.stop == WorkStop.COMPLETED || shift.stop == WorkStop.NO_PAYROLL) {
                            ledger.add(config.job.minutes)
                            shifts++
                            earned += shift.coinsEarned ?: 0
                        } else failures++
                    }
                    StudyStop.COURSE_NOT_FOUND -> return report(RunStop.COURSE_NOT_FOUND)
                    StudyStop.NO_COURSE_LIST -> failures++
                }
            } catch (e: NavigationFailed) {
                log("迷路：${e.message}")
                failures++
                runCatching { goHome() }
            }
        }
        return report(RunStop.DONE)
    }
}

/** Wires the real actions into a [Scheduler]. */
fun Scheduler(care: CareAction, study: StudyAction, work: WorkAction, navigator: Navigator,
    ledger: DailyLedger, log: (String) -> Unit = {}) =
    Scheduler(care::run, study::run, work::run, { navigator.goHome() }, ledger, log)

class MemoryLedger(private var used: Int = 0) : DailyLedger {
    override fun minutesUsed() = used
    override fun add(minutes: Int) { used += minutes }
}
