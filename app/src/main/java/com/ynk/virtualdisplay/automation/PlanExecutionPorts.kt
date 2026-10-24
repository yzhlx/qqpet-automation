package com.ynk.virtualdisplay.automation

/**
 * Ports/adapters boundary for the serial plan runner.
 *
 * Everything here is PURE Kotlin (no Android types) so the runner and its budget/idempotency rules
 * are unit-testable on the JVM. Production wires real adapters in later increments; the runner never
 * reaches into a flow, a navigation, or a capture directly.
 */

/** One step's result, as the executor reports it. */
sealed interface StepOutcome {
    data class Completed(val evidenceIds: List<String> = emptyList()) : StepOutcome
    data class Skipped(val reason: SkipReason, val evidenceIds: List<String> = emptyList()) : StepOutcome
    data class Blocked(val reason: String) : StepOutcome
    data class Unconfirmed(val reason: String) : StepOutcome
    data class Failed(val reason: String) : StepOutcome
}

/** Result of the pre-consumption course check for a plan that contains a STUDY step. */
sealed interface StudyPreflight {
    data class Ok(val courseId: String, val name: String, val durationMinutes: Int, val tuitionCoins: Int) : StudyPreflight
    data class Rejected(val reason: String) : StudyPreflight
}

/** Read-only resource feasibility for the whole selected suffix. Never authorizes a spend itself. */
sealed interface ResourcePreflight {
    data class Ready(
        val snapshotId: String,
        val skipConditionalWork: Boolean,
        val careAlreadySatisfied: Boolean,
    ) : ResourcePreflight
    data class NeedsObservation(val missing: List<String>) : ResourcePreflight
    data class Insufficient(val reason: String) : ResourcePreflight
}

/**
 * The persistence + budget boundary a real consuming input must cross.
 *
 * [beforeConsumingInput] is called SYNCHRONOUSLY immediately before an input that can spend money,
 * a consumable or game minutes. It must persist the intent first and throw (so the caller sends no
 * input) if that write fails. The runner never calls a start/pay tap outside this boundary.
 */
interface StepExecutionContext {
    /** Persist intent + reserve minutes for one attempt. Must be idempotent per attemptId. */
    fun beforeConsumingInput(iteration: Int, minutes: Int)
    /** The input was positively confirmed to have started (e.g. 正在学习 appeared). */
    fun startConfirmed(iteration: Int)
    /** The step's own completion proof landed; only now may the counter move. */
    fun completed(iteration: Int, minutes: Int)
    /** The outcome could not be proven; the attempt stays input-pending and is never replayed. */
    fun unconfirmed(iteration: Int, reason: String)
}

/** One selected step's executor. STUDY loops rounds internally; WORK is one shift; CARE one pass. */
fun interface StepExecutor {
    suspend fun execute(step: PlanStep, context: StepExecutionContext): StepOutcome
}

/** Course dependency, called ONLY when the plan contains a STUDY step. */
fun interface CourseDependencyPort {
    suspend fun preflight(courseId: String): StudyPreflight
}

/** Read-only resource decision over the current observed state; may not spend. */
fun interface ResourcePreflightPort {
    suspend fun observe(plan: PlanV2): ResourcePreflight
}
