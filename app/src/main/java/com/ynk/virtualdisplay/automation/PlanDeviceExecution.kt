package com.ynk.virtualdisplay.automation

interface PlanAttemptBudget {
    fun canAfford(minutes: Int, limit: Int): Boolean
    fun reserve(id: String, minutes: Int, limit: Int): Boolean
    fun started(id: String)
    fun completed(id: String)
    fun unconfirmed(id: String, reason: String)
}

/** One identity for both journals. File intent precedes reservation and input. */
class PlanBudgetExecutionContext(
    private val delegate: StepExecutionContext,
    private val runId: String,
    private val step: PlanStep,
    private val limit: Int,
    private val budget: PlanAttemptBudget,
    private val checkAuthority: () -> Unit,
) : StepExecutionContext {
    private val reserved = mutableMapOf<Int, Int>()
    private val done = mutableSetOf<Int>()
    private fun id(iteration: Int) = AttemptId(runId, step.stepId, iteration).value
    override fun beforeConsumingInput(iteration: Int, minutes: Int) {
        checkAuthority()
        if (iteration in reserved) {
            check(step is CareStep && iteration !in done && minutes == 0) { "ATTEMPT_ALREADY_RESERVED_NO_REPLAY" }
            return
        }
        delegate.beforeConsumingInput(iteration, minutes)
        check(budget.reserve(id(iteration), minutes, limit)) { "BUDGET_ATTEMPT_ALREADY_RESERVED_NO_REPLAY" }
        reserved[iteration] = minutes
        checkAuthority()
    }
    override fun startConfirmed(iteration: Int) {
        check(iteration in reserved) { "ATTEMPT_NOT_RESERVED" }
        delegate.startConfirmed(iteration)
        budget.started(id(iteration))
    }
    override fun completed(iteration: Int, minutes: Int) {
        check(reserved[iteration] == minutes && iteration !in done) { "ATTEMPT_COMPLETION_MISMATCH" }
        delegate.completed(iteration, minutes)
        done += iteration
        budget.completed(id(iteration))
    }
    override fun unconfirmed(iteration: Int, reason: String) {
        if (iteration in done) return
        delegate.unconfirmed(iteration, reason)
        if (iteration in reserved) budget.unconfirmed(id(iteration), reason)
    }
    fun retainPending(reason: String) {
        reserved.keys.filter { it !in done }.forEach { unconfirmed(it, reason) }
    }
}

/** Sole selected-plan composition; Android supplies actual preflight and flow functions. */
class PlanDeviceExecution(
    private val store: RunStore,
    private val budget: PlanAttemptBudget,
    private val preflight: PlanPreflight,
    private val work: suspend (WorkStep, StepExecutionContext) -> StepOutcome,
    private val care: suspend (CareStep, StepExecutionContext) -> StepOutcome,
    private val study: suspend (StudyStep, StepExecutionContext) -> StepOutcome,
    private val checkAuthority: () -> Unit,
) {
    suspend fun run(request: RunRequest, runId: String): RunSnapshot {
        fun executor(type: PlanStepType) = StepExecutor { step, context ->
            val guarded = PlanBudgetExecutionContext(context, runId, step,
                request.planSnapshot.dailyBudgetMinutes, budget, checkAuthority)
            try {
                checkAuthority()
                when (type) {
                    PlanStepType.WORK -> work(step as WorkStep, guarded)
                    PlanStepType.CARE -> care(step as CareStep, guarded)
                    PlanStepType.STUDY -> study(step as StudyStep, guarded)
                }
            } finally {
                guarded.retainPending("STEP_ENDED_WITHOUT_COMPLETION_PROOF")
            }
        }
        return PlanRunner(store, preflight, PlanStepType.entries.associateWith(::executor),
            checkAuthority = checkAuthority).run(request, runId)
    }
}

object PlanRunPendingGate {
    fun findRequest(directory: java.io.File, requestId: String): RunSnapshot? {
        if(!directory.exists()) return null
        val store=FileRunStore(directory)
        return (directory.listFiles() ?: error("RUN_STORE_DIRECTORY_UNREADABLE"))
            .filter {it.name.endsWith(".json")}.map {
                checkNotNull(store.load(it.name.removeSuffix(".json")))
            }.firstOrNull {it.request.requestId==requestId}
    }
    fun blockingRunId(directory: java.io.File): String? = pendingRunIds(directory).firstOrNull()
    fun pendingRunIds(directory:java.io.File):List<String> {
        if (!directory.exists()) return emptyList()
        val files = directory.listFiles() ?: error("RUN_STORE_DIRECTORY_UNREADABLE")
        val store = FileRunStore(directory)
        return files.filter {it.name.endsWith(".json")}.sortedBy {it.name}.mapNotNull {file ->
            val run=store.load(file.name.removeSuffix(".json")) ?: error("RUN_STORE_RECORD_MISSING")
            if(run.state in setOf(RunState.CREATED,RunState.PREFLIGHT,RunState.RUNNING,RunState.UNCONFIRMED) ||
                store.hasPendingInput(run.runId)) run.runId else null
        }
    }
}
