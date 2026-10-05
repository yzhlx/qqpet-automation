package com.ynk.virtualdisplay.automation

import android.graphics.Bitmap
import android.util.Log
import kotlinx.coroutines.delay

class PetStateMachine(
    private val capture: HeadlessDisplayCapture,
    private val input: PetInputController,
    private val matcher: TemplateMatcher,
    private val profile: PetProfile,
    private val logger: (String) -> Unit,
) {
    enum class State {
        CHECK_FINISHED, OPEN_SCHOOL, VERIFY_SCHOOL, SELECT_COURSE, VERIFY_COURSE,
        START_COURSE, VERIFY_LEARNING, COMPLETED, STOPPED_ERROR
    }

    data class CycleResult(val ok: Boolean, val finalState: State, val message: String)

    suspend fun runOneCycle(): CycleResult = try {
        verify(State.CHECK_FINISHED, profile.finishedProbe)
        logger("transition CHECK_FINISHED -> OPEN_SCHOOL")
        input.tap(profile.schoolButton.x, profile.schoolButton.y)
        delay(profile.pageSettleMs)

        verify(State.VERIFY_SCHOOL, profile.schoolProbe)
        logger("transition VERIFY_SCHOOL -> SELECT_COURSE")
        input.tap(profile.courseButton.x, profile.courseButton.y)
        delay(profile.pageSettleMs)

        verify(State.VERIFY_COURSE, profile.courseProbe)
        logger("transition VERIFY_COURSE -> START_COURSE")
        input.tap(profile.startButton.x, profile.startButton.y)
        delay(profile.pageSettleMs)

        verify(State.VERIFY_LEARNING, profile.learningProbe)
        logger("transition VERIFY_LEARNING -> COMPLETED")
        CycleResult(true, State.COMPLETED, "short course started")
    } catch (t: Throwable) {
        Log.e("PetStateMachine", "fail closed", t)
        logger("STOPPED_ERROR: ${t.javaClass.simpleName}: ${t.message}")
        CycleResult(false, State.STOPPED_ERROR, t.message ?: t.javaClass.simpleName)
    }

    private suspend fun verify(state: State, probe: PetProfile.Probe) {
        val bitmap: Bitmap = capture.awaitFrame()
        try {
            val result = matcher.match(bitmap, probe, profile.matchThreshold)
            logger("verify $state probe=${probe.name} score=${"%.4f".format(result.score)}")
            check(result.matched) { "$state mismatch (${probe.name}, score=${"%.4f".format(result.score)})" }
        } finally { bitmap.recycle() }
    }
}
