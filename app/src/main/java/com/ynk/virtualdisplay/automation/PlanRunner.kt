package com.ynk.virtualdisplay.automation

/**
 * Serial runner over a selected plan: fixed WORK→CARE→STUDY order, one step at a time.
 *
 * It is deliberately small. It owns three things the individual executors cannot:
 *  - the ORDER (the plan's steps are already sorted and validated; the runner just walks them);
 *  - STOP-ON-FAILURE: a Blocked/Failed/Unconfirmed/Cancelled step leaves every later step PENDING;
 *  - COUNTERS from persisted completions, so a step whose cleanup failed after a confirmed award
 *    still shows its completed count while the overall run stays UNCONFIRMED.
 *
 * It performs no input itself. Every consuming input is behind [StepExecutionContext], which the
 * executor must call through; the runner only sequences and decides terminal state.
 */
class PlanRunner(
    private val store: RunStore,
    private val preflight: PlanPreflight,
    private val executors: Map<PlanStepType, StepExecutor>,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val checkAuthority: () -> Unit = {},
) {
    /** Provide the run a fresh store has to persist into before it starts; production passes AtomicFile. */
    suspend fun run(request: RunRequest, runId: String): RunSnapshot {
        val plan = request.planSnapshot
        store.load(runId)?.let { existing ->
            require(PlanRunJsonCodec.sameRequest(existing.request, request)) { "RUN_REQUEST_CHANGED" }
            if (existing.state in setOf(RunState.COMPLETED, RunState.STOPPED, RunState.FAILED, RunState.UNCONFIRMED)) {
                return existing
            }
            val blocked=existing.copy(state=RunState.UNCONFIRMED,
                counts=store.countsOf(runId,existing.request.planSnapshot),
                stopReason="PERSISTED_RUN_REQUIRES_OBSERVATION_NO_REPLAY",
                endedAtMs=maxOf(nowMs(),existing.startedAtMs ?: 0L))
            store.save(blocked)
            return blocked
        }
        var snapshot = RunSnapshot(runId, request, state = RunState.PREFLIGHT, startedAtMs = nowMs())
        store.save(snapshot)

        val pre = try {
            checkAuthority()
            preflight.check(plan)
        } catch (t: Throwable) {
            val cancelled = t is java.util.concurrent.CancellationException
            store.save(snapshot.copy(state = if(cancelled) RunState.STOPPED else RunState.UNCONFIRMED,
                stopReason = t.message ?: t.javaClass.simpleName, endedAtMs = nowMs()))
            if(cancelled) throw t
            return checkNotNull(store.load(runId))
        }
        if (pre is PlanPreflight.Result.Blocked) {
            snapshot = snapshot.copy(state = RunState.FAILED, stopReason = pre.reason, endedAtMs = nowMs())
            store.save(snapshot); return snapshot
        }
        if (pre is PlanPreflight.Result.NeedsObservation) {
            snapshot = snapshot.copy(state = RunState.UNCONFIRMED,
                stopReason = "PREFLIGHT_NEEDS_OBSERVATION:" + pre.missing.joinToString(","), endedAtMs = nowMs())
            store.save(snapshot); return snapshot
        }

        snapshot = snapshot.copy(state = RunState.RUNNING)
        store.save(snapshot)

        for ((index, step) in plan.steps.withIndex()) {
            val executor = executors[step.type]
                ?: return stop(snapshot, index, StepState.BLOCKED, "NO_EXECUTOR_FOR:${step.type}")
            val ctx = ExecutingContext(store, runId, plan, step, index, nowMs)
            val outcome = try {
                checkAuthority()
                executor.execute(step, ctx)
            } catch (t: Throwable) {
                val reason = t.message ?: t.javaClass.simpleName
                store.attempts(runId).filter { it.inputPending }.forEach {
                    store.persistUnconfirmed(it.attemptId, reason)
                }
                if(t is java.util.concurrent.CancellationException) {
                    val stopped = stop(snapshot, index, StepState.CANCELLED, reason)
                    store.save(stopped.copy(counts = store.countsOf(runId, plan)))
                    throw t
                }
                StepOutcome.Unconfirmed(reason)
            }
            val stepState = when (outcome) {
                is StepOutcome.Completed -> StepState.COMPLETED
                is StepOutcome.Skipped -> StepState.SKIPPED
                is StepOutcome.Blocked -> StepState.BLOCKED
                is StepOutcome.Unconfirmed -> StepState.UNCONFIRMED
                is StepOutcome.Failed -> StepState.FAILED
            }
            val reason = when (outcome) {
                is StepOutcome.Blocked -> outcome.reason
                is StepOutcome.Unconfirmed -> outcome.reason
                is StepOutcome.Failed -> outcome.reason
                is StepOutcome.Skipped -> outcome.reason.name
                is StepOutcome.Completed -> null
            }
            val evidence = when (outcome) {
                is StepOutcome.Completed -> outcome.evidenceIds
                is StepOutcome.Skipped -> outcome.evidenceIds
                else -> emptyList()
            }
            val updated = snapshot.steps.toMutableList().also { list ->
                list[index] = list[index].copy(state = stepState, reason = reason, evidenceIds = evidence,
                    startedAtMs = list[index].startedAtMs ?: nowMs(), endedAtMs = nowMs())
            }
            // Counts come from the persisted attempts the executor recorded, never from step states.
            val counts = store.countsOf(runId, plan)
            val terminal = stepState !in setOf(StepState.COMPLETED, StepState.SKIPPED)
            snapshot = snapshot.copy(steps = updated, counts = counts,
                state = if (terminal) terminalState(stepState) else RunState.RUNNING,
                stopReason = if (terminal) reason else snapshot.stopReason,
                endedAtMs = if (terminal) nowMs() else snapshot.endedAtMs)
            store.save(snapshot)
            if (terminal) return snapshot
        }
        snapshot = snapshot.copy(state = RunState.COMPLETED, endedAtMs = nowMs())
        store.save(snapshot)
        return snapshot
    }

    private fun stop(snapshot: RunSnapshot, index: Int, stepState: StepState, reason: String): RunSnapshot {
        val updated = snapshot.steps.toMutableList().also {
            it[index] = it[index].copy(state = stepState, reason = reason, endedAtMs = nowMs())
        }
        val next = snapshot.copy(steps = updated, state = terminalState(stepState), stopReason = reason, endedAtMs = nowMs())
        store.save(next); return next
    }

    private fun terminalState(stepState: StepState): RunState = when (stepState) {
        StepState.BLOCKED, StepState.FAILED -> RunState.FAILED
        StepState.UNCONFIRMED -> RunState.UNCONFIRMED
        StepState.CANCELLED -> RunState.STOPPED
        else -> RunState.RUNNING
    }
}

/**
 * The context one step sees. It forwards to the store so the runner owns no input state, and it
 * derives the attempt id from (runId, stepId, iteration). Every consuming input MUST go through
 * [beforeConsumingInput]; if that write fails it throws and the executor sends nothing.
 */
internal class ExecutingContext(
    private val store: RunStore,
    private val runId: String,
    private val plan: PlanV2,
    private val step: PlanStep,
    private val index: Int,
    private val nowMs: () -> Long,
) : StepExecutionContext {
    private fun attemptId(iteration: Int) = AttemptId(runId, step.stepId, iteration)

    override fun beforeConsumingInput(iteration: Int, minutes: Int) {
        val id = attemptId(iteration)
        val existing = store.attempt(id)
        // A previously unconfirmed attempt for this iteration must NOT be replayed.
        if (existing != null) error("ATTEMPT_ALREADY_EXISTS_NO_REPLAY:${id.value}")
        require(iteration <= if(step is StudyStep) step.rounds else 1) { "ATTEMPT_ITERATION_OUT_OF_RANGE" }
        require(if(step is CareStep) minutes == 0 else minutes > 0) { "ATTEMPT_DURATION_INVALID" }
        store.persistIntent(ActivityAttempt(id, minutes, AttemptState.INPUT_PENDING,
            inputPending = true, inputDispatchedAtMs = nowMs()))
    }

    override fun startConfirmed(iteration: Int) {
        store.persistStartConfirmed(attemptId(iteration), nowMs())
    }

    override fun completed(iteration: Int, minutes: Int) {
        val attempt = checkNotNull(store.attempt(attemptId(iteration))) { "ATTEMPT_NOT_RESERVED" }
        require(attempt.durationMinutes == minutes) { "ATTEMPT_COMPLETION_DURATION_MISMATCH" }
        store.persistCompleted(attemptId(iteration), nowMs())
    }

    override fun unconfirmed(iteration: Int, reason: String) {
        store.persistUnconfirmed(attemptId(iteration), reason)
    }
}
