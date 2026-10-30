package com.ynk.virtualdisplay.automation

import android.content.Context
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File

/**
 * What one user-configured 托管 session should do. Before this existed the session was a fixed
 * batch: always 蹦蹦跳跳体能课, always the 90/90 gate, always the game's full 480-minute allowance,
 * and the only choice was 1/3/10 rounds. Every field here replaces one of those constants.
 *
 * Pure data with no Android types in [SessionPlan] itself, so validation is testable on the JVM.
 * The defaults reproduce the historical behaviour exactly, which is what keeps a missing config
 * file from changing what an existing install does.
 *
 * The bounds are not arbitrary:
 *  - a threshold below [MIN_THRESHOLD] leaves the in-game start button grey (measured: a lesson
 *    will not start), so allowing it would only produce sessions that fail after spending care;
 *  - [dailyBudgetMinutes] may only be tightened below [DailyBudgetStore.HARD_STOP_MINUTES], never
 *    raised, because the game itself stops paying full rewards past that point;
 *  - [rounds] stays inside the range `PetSessionController` and `PetAutomationService` already accept.
 */
data class SessionPlan(
    /** Id from the scanned course catalog, e.g. "study:蹦蹦跳跳体能课". */
    val courseId: String = DEFAULT_COURSE_ID,
    /** Lessons to run. Only meaningful for a 托管 session; 健康检查 and 护理 ignore it. */
    val rounds: Int = DEFAULT_ROUNDS,
    /** 体力 required before a lesson may start. */
    val energyThreshold: Int = DEFAULT_THRESHOLD,
    /** 清洁 required before a lesson may start. */
    val cleanlinessThreshold: Int = DEFAULT_THRESHOLD,
    /**
     * Minutes of the shared daily allowance this install will spend before it refuses another
     * lesson. Capped by the game's own hard stop.
     */
    val dailyBudgetMinutes: Int = DailyBudgetStore.HARD_STOP_MINUTES,
    /** When true, a lesson whose tuition the pet cannot afford is preceded by one work shift. */
    val workBeforeStudy: Boolean = false,
    /** Catalog id of the shift to run. Only consulted when [workBeforeStudy] is set. */
    val workOptionId: String? = DEFAULT_WORK_OPTION_ID,
) {
    init {
        require(courseId.isNotBlank()) { "PLAN_COURSE_BLANK" }
        require(rounds in ROUNDS_RANGE) { "PLAN_ROUNDS_OUT_OF_RANGE:$rounds" }
        require(energyThreshold in THRESHOLD_RANGE) { "PLAN_ENERGY_THRESHOLD_OUT_OF_RANGE:$energyThreshold" }
        require(cleanlinessThreshold in THRESHOLD_RANGE) { "PLAN_CLEANLINESS_THRESHOLD_OUT_OF_RANGE:$cleanlinessThreshold" }
        require(dailyBudgetMinutes in BUDGET_RANGE) { "PLAN_BUDGET_OUT_OF_RANGE:$dailyBudgetMinutes" }
        require(!workBeforeStudy || !workOptionId.isNullOrBlank()) { "PLAN_WORK_OPTION_MISSING" }
    }

    /** The human-readable course name carried inside [courseId]. */
    val courseName: String get() = courseId.substringAfter(':', courseId)

    /**
     * True when this course's lesson length was measured on device. Both the budget guard and the
     * UI read this one rule: a 托管 session can only start on a course whose minutes it can charge.
     */
    val hasKnownDuration: Boolean get() = hasMeasuredDuration(courseId)

    /**
     * Minutes one lesson of [courseId] charges against the shared daily budget. Only the
     * historically verified course has a known duration; a scanned course carries its own and the
     * caller passes it in. Charging a guess would let the budget guard pass a lesson the game
     * then bills differently, so an unknown course fails closed instead.
     */
    fun courseDurationMinutes(): Int =
        KNOWN_COURSE_MINUTES[courseId] ?: error("PLAN_COURSE_DURATION_UNKNOWN:$courseId")

    fun toJson(): JSONObject = JSONObject()
        .put("courseId", courseId)
        .put("rounds", rounds)
        .put("energyThreshold", energyThreshold)
        .put("cleanlinessThreshold", cleanlinessThreshold)
        .put("dailyBudgetMinutes", dailyBudgetMinutes)
        .put("workBeforeStudy", workBeforeStudy)
        .put("workOptionId", workOptionId ?: JSONObject.NULL)

    companion object {
        const val DEFAULT_COURSE_ID = "study:蹦蹦跳跳体能课"
        const val DEFAULT_WORK_OPTION_ID = "work:竹影武馆/帮武馆跑腿"
        const val DEFAULT_ROUNDS = 1
        const val DEFAULT_THRESHOLD = 90
        const val MIN_THRESHOLD = 60
        const val MAX_THRESHOLD = 100
        const val MIN_ROUNDS = 1
        const val MAX_ROUNDS = 10

        /** How long a scanned course catalog stays usable before 托管 refuses to trust it. */
        const val CATALOG_MAX_AGE_MS = 7L * 24L * 60L * 60L * 1000L

        /**
         * Durations confirmed by a real completed lesson. The scan records a duration per course
         * too, but until that reading is closed-loop verified the budget guard trusts only this.
         */
        val KNOWN_COURSE_MINUTES: Map<String, Int> = mapOf(DEFAULT_COURSE_ID to 10)

        /** The single rule behind [SessionPlan.hasKnownDuration], usable before a plan is built. */
        fun hasMeasuredDuration(courseId: String): Boolean = KNOWN_COURSE_MINUTES.containsKey(courseId)

        /** The ranges the plan sheet offers. Same source as the [SessionPlan] checks above. */
        val ROUNDS_RANGE = MIN_ROUNDS..MAX_ROUNDS
        val THRESHOLD_RANGE = MIN_THRESHOLD..MAX_THRESHOLD
        val BUDGET_RANGE = 1..DailyBudgetStore.HARD_STOP_MINUTES

        /**
         * One numeric field of the plan sheet. Text that is empty, unparsable or outside [range] is
         * not a value yet: "6" on the way to "65" must never be committed, because the plan's own
         * range check would throw and take the composition down with it.
         */
        fun parseIntOrNull(raw: String, range: IntRange): Int? =
            raw.toIntOrNull()?.takeIf { it in range }

        /** A missing or older file decodes as the defaults, so an upgrade changes nothing. */
        fun fromJson(json: JSONObject): SessionPlan = SessionPlan(
            courseId = json.optString("courseId", DEFAULT_COURSE_ID),
            rounds = json.optInt("rounds", DEFAULT_ROUNDS),
            energyThreshold = json.optInt("energyThreshold", DEFAULT_THRESHOLD),
            cleanlinessThreshold = json.optInt("cleanlinessThreshold", DEFAULT_THRESHOLD),
            dailyBudgetMinutes = json.optInt("dailyBudgetMinutes", DailyBudgetStore.HARD_STOP_MINUTES),
            workBeforeStudy = json.optBoolean("workBeforeStudy", false),
            workOptionId = if (json.has("workOptionId") && !json.isNull("workOptionId")) json.getString("workOptionId") else null,
        )
    }
}

/**
 * The one on-disk copy of [SessionPlan]. Atomic because a killed write must never leave a
 * half-saved plan that the next session would treat as the user's intent.
 */
class SessionPlanStore(context: Context) {
    private val atomic = AtomicFile(File(context.filesDir, FILE_NAME))

    fun load(): SessionPlan {
        if (!atomic.baseFile.exists()) return SessionPlan()
        return SessionPlan.fromJson(JSONObject(atomic.readFully().toString(Charsets.UTF_8)))
    }

    fun save(plan: SessionPlan) {
        val stream = atomic.startWrite()
        try {
            stream.write(plan.toJson().toString().toByteArray(Charsets.UTF_8))
            atomic.finishWrite(stream)
        } catch (t: Exception) {
            atomic.failWrite(stream)
            throw t
        }
    }

    companion object {
        const val FILE_NAME = "session-plan.json"
    }
}
