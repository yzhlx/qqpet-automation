package com.ynk.virtualdisplay.automation

import android.graphics.Bitmap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.io.File

class PetStateMachine(
    private val capture: HeadlessDisplayCapture,
    private val input: PetInputController,
    private val matcher: TemplateMatcher,
    private val profile: PetProfile,
    private val evidence: File,
    private val logger: (String, Double?, String, String, String?) -> Unit,
) {
    enum class State { RESULT, COURSE_LIST, COURSE_DETAIL, LEARNING, STOPPED_ERROR }
    data class CycleResult(val ok: Boolean, val finalState: State, val message: String)
    private var frameIndex = 0

    suspend fun runOneCycle(): CycleResult = try {
        verify(State.RESULT, "continue", "before")
        input.tap(profile.continueButton.x, profile.continueButton.y)
        delay(profile.pageSettleMs)
        verify(State.COURSE_LIST, "continue", "after")
        verify(State.COURSE_LIST, "course", "before")
        input.tap(profile.courseButton.x, profile.courseButton.y)
        delay(profile.pageSettleMs)
        verify(State.COURSE_DETAIL, "course", "after")
        verify(State.COURSE_DETAIL, "start", "before")
        input.tap(profile.startButton.x, profile.startButton.y)
        delay(profile.pageSettleMs)
        verify(State.LEARNING, "start", "after")
        capture.facts.events.append(EventSource.GAME_OBSERVATION,EventKind.COURSE_STARTED,
            mapOf("course" to "PHYSICAL_10_MIN"),listOf(evidence.absolutePath))
        CycleResult(true, State.LEARNING, "course started")
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Throwable) {
        capture.facts.events.append(EventSource.APP_AUTOMATION,EventKind.ERROR,mapOf("reason" to failure.toString()))
        logger(State.STOPPED_ERROR.name, null, "none", "STOP", failure.message)
        CycleResult(false, State.STOPPED_ERROR, failure.message ?: failure.javaClass.simpleName)
    }

    suspend fun verify(state: State, action: String, phase: String) {
        val frame = capture.awaitFrame()
        try {
            evidence.mkdirs()
            File(evidence, "%03d_%s_%s_%s.png".format(frameIndex++, state, action, phase))
                .outputStream().use { check(frame.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            val probes = when (state) {
                State.RESULT -> listOf(profile.resultProbe)
                State.COURSE_LIST -> listOf(profile.courseListProbe)
                State.COURSE_DETAIL -> listOf(profile.courseDetailProbe)
                State.LEARNING -> profile.learningProbes
                State.STOPPED_ERROR -> error("Cannot verify STOPPED_ERROR")
            }
            val best = probes.map { it to matcher.match(frame, it, profile.matchThreshold) }
                .maxBy { it.second.score }
            val matched = best.second.matched
            val reason = if (matched) null else "$state mismatch: ${best.first.name}"
            logger(state.name, best.second.score, "$action:$phase", if (matched) "PASS" else "FAIL", reason)
            check(matched) { reason!! }
            if(state==State.LEARNING) capture.facts.events.append(EventSource.GAME_OBSERVATION,EventKind.LEARNING_CONFIRMED,
                mapOf("score" to best.second.score,"phase" to phase),listOf(evidence.absolutePath))
        } finally { frame.recycle() }
    }
}
