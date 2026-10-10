package com.ynk.virtualdisplay

import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ynk.virtualdisplay.automation.*
import com.ynk.virtualdisplay.data.repository.ConnectionStatus
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * t18 — TEST-ONLY single-round (rounds=1) live acceptance entry.
 *
 * This is the missing single-round harness. It replaces nothing: [PetManagedSessionTest] and
 * [PetExperimentalSessionTest] stay pinned to the explicit three-round experiment and are never
 * invoked from here. The production service already supported a single round
 * (`PetAutomationService.startIntent(..., rounds = 1)`, `rounds in 1..3`); only the test harness
 * was hard-coded to 3. No production file is touched by this class.
 *
 * Contract of this entry:
 *  - explicit per-run authorization: `single_regression_authorized=yes`
 *  - explicit `rounds=1` argument; anything else refuses to run
 *  - non-zero target display; the default/physical display is never touched
 *  - exactly ONE real service start, requesting exactly ONE round; no retry, no re-open
 *  - no simulated readings, no forced pause/resume, no forced POWER, no extra consumables
 *  - a real new sessionId is observed and bound to this run
 *  - PASS requires one real completed round AND a device-observed course result
 *  - anything not observed is recorded as UNKNOWN, never inferred
 *
 * Run (device action is the captain's call, not this class's):
 *   adb shell am instrument -w \
 *     -e single_regression_authorized yes -e rounds 1 -e display_id 4 \
 *     com.ynk.virtualdisplay.test/androidx.test.runner.AndroidJUnitRunner
 */
@RunWith(AndroidJUnit4::class)
class PetSingleRoundAcceptanceTest {

    private val requestedRounds = 1

    @Test fun authorizedSingleRoundServiceRegression(): Unit = runBlocking {
        val args = InstrumentationRegistry.getArguments()

        // --- explicit authorization + single-round + non-zero display guards -------------------
        assumeTrue("Single-round live run requires explicit authorization",
            args.getString("single_regression_authorized") == "yes")
        require(args.getString("rounds") == "1") {
            "Single-round entry refuses rounds=${args.getString("rounds")}; only an explicit rounds=1 is accepted"
        }
        val displayId = args.getString("display_id")?.toIntOrNull() ?: -1
        require(displayId > 0) { "A non-zero target display_id is required, got=$displayId" }

        val inst = InstrumentationRegistry.getInstrumentation()
        val app = inst.targetContext.applicationContext as MyApplication
        val repo = app.displayRepository

        fun shell(cmd: String) = inst.uiAutomation.executeShellCommand(cmd).use {
            ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().readText()
        }

        val dir = File(app.getExternalFilesDir(null),
            "pet_single_round/${System.currentTimeMillis()}").apply { mkdirs() }

        // Anything this run did not actually observe is listed here. It is only populated by a
        // missing real observation, never by an assumption. A pause is deliberately NOT required,
        // so an absent pause is not an unresolved item.
        val unresolved = linkedSetOf<String>()

        var observedSessionId: String? = null
        var started = false
        var physicalTouchesAtStart = -1
        val states = mutableListOf<String>()
        val stateLog = mutableListOf<JSONObject>()

        // Real M0 physical-screen touch counter, installed before any automation could run.
        shell("am start --display 0 -f 0x18000000 -n com.ynk.virtualdisplay/.automation.GateTestActivity")
        withTimeout(5000) { while (!GateTestActivity.touches.containsKey(0)) delay(100) }
        physicalTouchesAtStart = GateTestActivity.touches.getValue(0).get()

        // A real pre-start page/frame reference, captured before the service exists.
        val preStart = JSONObject()
            .put("capturedAtWallMs", System.currentTimeMillis())
            .put("capturedAtElapsedMs", SystemClock.elapsedRealtime())
            .put("displayId", displayId)
        try {
            preStart.put("managedDisplayIds", JSONArray(repo.managedDisplayIds.value.toList()))
            preStart.put("connectionStatus", repo.connectionStatus.value.name)
            preStart.put("targetDisplayDumpSectionChars", targetDisplaySectionLength(::shell, displayId))
        } catch (t: Throwable) {
            preStart.put("probeError", t.toString())
        }
        File(dir, "pre-start.json").writeText(preStart.toString())

        fun guard() {
            check(android.os.Build.MODEL == "FOA-AL00") { "DEVICE_MODEL_CHANGED" }
            val qq = app.packageManager.getPackageInfo("com.tencent.mobileqq", 0)
            check(qq.versionName == "9.3.70" && qq.longVersionCode == 16410L) { "QQ_VERSION_CHANGED" }
            check(repo.managedDisplayIds.value.contains(displayId)) { "DISPLAY_NOT_MANAGED" }
            check(repo.connectionStatus.value == ConnectionStatus.CONNECTED) { "DISPLAY_DISCONNECTED" }
            val stats = shell("dumpsys activity activities")
            val section = stats.substringAfter("Display: mDisplayId=$displayId ", "")
                .substringBefore("Display: mDisplayId=")
            check(section.contains("com.tencent.mobileqq")) { "QQ_NOT_ON_TARGET_DISPLAY" }
            check(GateTestActivity.touches.getValue(0).get() == physicalTouchesAtStart) {
                "PHYSICAL_TOUCH_DETECTED"
            }
        }

        try {
            withTimeout(15000) { repo.connectionStatus.first { it == ConnectionStatus.CONNECTED } }
            withTimeout(15000) { repo.managedDisplayIds.first { displayId in it } }
            guard()

            // --- exactly one start, exactly one requested round ---------------------------------
            withContext(Dispatchers.Main) {
                app.startForegroundService(PetAutomationService.startIntent(app, displayId, requestedRounds))
            }
            started = true

            // --- observe the real sessionId published by the controller -------------------------
            withTimeout(20_000L) {
                while (PetManagedRuntime.state.value.sessionId == null) {
                    val s = PetRuntime.state.value
                    check(!s.status.startsWith("会话异常")) { s.status }
                    delay(100)
                }
            }
            observedSessionId = PetManagedRuntime.state.value.sessionId

            // --- observe the real run to its own terminal state (never forced) ------------------
            withTimeout(30 * 60 * 1000L) {
                while (true) {
                    val st = PetManagedRuntime.state.value
                    if (st.sessionId != null && st.sessionId == observedSessionId) {
                        if (states.lastOrNull() != st.runState.name) {
                            states.add(st.runState.name)
                            stateLog.add(JSONObject()
                                .put("atWallMs", System.currentTimeMillis())
                                .put("atElapsedMs", SystemClock.elapsedRealtime())
                                .put("state", st.runState.name)
                                .put("step", st.currentStep)
                                .put("flow", st.currentFlow)
                                .put("round", st.round)
                                .put("completedRounds", st.completedRounds)
                                .put("pauseReason", st.pauseReason ?: JSONObject.NULL))
                        }
                        File(dir, "runtime-observe.json").writeText(JSONObject()
                            .put("sessionId", st.sessionId).put("state", st.runState.name)
                            .put("step", st.currentStep).put("round", st.round)
                            .put("completedRounds", st.completedRounds)
                            .put("nextStep", st.nextStep)
                            .put("pauseReason", st.pauseReason ?: JSONObject.NULL)
                            .put("energy", st.energy.fields()).put("cleanliness", st.cleanliness.fields())
                            .put("mood", st.mood.fields())
                            .put("physicalDelta", GateTestActivity.touches.getValue(0).get() - physicalTouchesAtStart)
                            .toString())
                        // Fail closed the moment real physical input appears.
                        check(GateTestActivity.touches.getValue(0).get() == physicalTouchesAtStart) {
                            "PHYSICAL_TOUCH_DETECTED"
                        }
                        if (st.runState == SessionRunState.COMPLETED ||
                            st.runState == SessionRunState.FAILED ||
                            st.runState == SessionRunState.STOPPED) break
                    }
                    delay(250)
                }
            }

            val terminal = PetManagedRuntime.state.value
            val sid = observedSessionId!!
            val evidenceDir = newestSessionEvidenceDir(app)
            val eventFile = File(app.filesDir, "pet-events/$sid.jsonl")

            // --- bind the new sessionId to its own real evidence ---------------------------------
            val binding = JSONObject()
                .put("sessionId", sid)
                .put("requestedRounds", requestedRounds)
                .put("terminalState", terminal.runState.name)
                .put("completedRounds", terminal.completedRounds)
                .put("pauseReason", terminal.pauseReason ?: JSONObject.NULL)
                .put("eventFile", eventFile.absolutePath)
                .put("eventFileExists", eventFile.isFile)
                .put("evidenceDir", evidenceDir?.absolutePath ?: JSONObject.NULL)
                .put("physicalDelta", GateTestActivity.touches.getValue(0).get() - physicalTouchesAtStart)
                .put("states", JSONArray(states))
            File(dir, "session-binding.json").writeText(binding.toString())

            // --- count real recorded events; INPUT_SENT is never treated as a game result --------
            val events = if (eventFile.isFile) eventFile.readLines().mapNotNull {
                runCatching { JSONObject(it) }.getOrNull()
            } else emptyList()
            if (!eventFile.isFile) unresolved.add("sessionEventStream")

            fun kindOf(k: String) = events.filter { it.optString("kind") == k }
            val courseStarts = kindOf("COURSE_STARTED")
            val learningConfirmed = kindOf("LEARNING_CONFIRMED")
            val inputSent = kindOf("INPUT_SENT")
            val careBudget = kindOf("CARE_BUDGET")
            val errors = kindOf("ERROR")

            val ownedStartSeq = courseStarts.minOfOrNull { it.optLong("sequence") } ?: -1L
            val ownedInputs = inputSent.filter { it.optLong("sequence") > ownedStartSeq }
            val targetInputs = ownedInputs.filter {
                it.optJSONObject("payload")?.optInt("displayId") == displayId
            }
            if (courseStarts.isEmpty()) unresolved.add("courseStart")

            // Course "end + result confirmed" must come from real device observation, never from
            // INPUT_SENT and never from a cached earlier COMPLETED.
            val roundRecord = evidenceDir?.let { File(it, "round-$requestedRounds.json") }
            var resultConfirmed = false
            val resultEvidence = JSONObject()
            if (roundRecord != null && roundRecord.isFile) {
                val rr = runCatching { JSONObject(roundRecord.readText()) }.getOrNull()
                val result = rr?.optJSONObject("result")
                resultConfirmed = result != null && result.has("course_ms")
                resultEvidence.put("roundRecord", roundRecord.absolutePath)
                    .put("resultPresent", result != null)
                    .put("courseMs", result?.opt("course_ms") ?: JSONObject.NULL)
                    .put("grade", result?.opt("grade") ?: JSONObject.NULL)
                    .put("highFiveSent", result?.opt("high_five_sent_count") ?: JSONObject.NULL)
            } else unresolved.add("roundRecord")
            if (!resultConfirmed) unresolved.add("courseResultConfirmation")

            // Result frame saved by the flow itself, if the result page really matched.
            val resultFrames = evidenceDir?.listFiles()
                ?.filter { it.name.contains("result", ignoreCase = true) && it.name.endsWith(".png") }
                ?: emptyList()
            if (resultFrames.isEmpty()) unresolved.add("resultFrame")
            resultEvidence.put("resultFrames", JSONArray(resultFrames.map { it.name }))

            val runRecord = evidenceDir?.let { File(it, "run-record.json") }
            if (runRecord != null && runRecord.isFile) {
                val r = runCatching { JSONObject(runRecord.readText()) }.getOrNull()
                resultEvidence.put("runRecordTerminalState", r?.opt("terminalState") ?: JSONObject.NULL)
                    .put("runRecordCompletedRounds", r?.opt("completedRounds") ?: JSONObject.NULL)
                    .put("runRecordCourseStarts", r?.opt("courseStarts") ?: JSONObject.NULL)
                    .put("runRecordSessionId", r?.opt("sessionId") ?: JSONObject.NULL)
                    .put("care", r?.opt("care") ?: JSONObject.NULL)
            } else unresolved.add("runRecord")
            File(dir, "result-confirmation.json").writeText(resultEvidence.toString())

            // --- health/care ledger: record what was actually read; absence stays UNKNOWN --------
            val careLedger = JSONObject()
                .put("careBudgetEvents", careBudget.size)
                .put("lastCareBudget", careBudget.lastOrNull()?.optJSONObject("payload") ?: JSONObject.NULL)
                .put("finalEnergy", terminal.energy.fields())
                .put("finalCleanliness", terminal.cleanliness.fields())
                .put("finalMood", terminal.mood.fields())
                .put("energyObserved", terminal.energy is ObservedValue.Known)
                .put("cleanlinessObserved", terminal.cleanliness is ObservedValue.Known)
                .put("moodObserved", terminal.mood is ObservedValue.Known)
                .put("note", "Consumables are only spent if the real health reading is below threshold; " +
                    "a threshold-met round legitimately spends none and is not a failure.")
            File(dir, "care-ledger.json").writeText(careLedger.toString())

            // --- verdict --------------------------------------------------------------------------
            val oneRealRound = terminal.runState == SessionRunState.COMPLETED &&
                terminal.completedRounds == 1 && courseStarts.size == 1
            val pass = oneRealRound && resultConfirmed &&
                GateTestActivity.touches.getValue(0).get() == physicalTouchesAtStart

            val verdict = JSONObject()
                .put("verdict", if (pass) "PASS" else "NOT_PASS")
                .put("sessionId", sid)
                .put("requestedRounds", requestedRounds)
                .put("completedRounds", terminal.completedRounds)
                .put("terminalState", terminal.runState.name)
                .put("courseStarts", courseStarts.size)
                .put("learningConfirmed", learningConfirmed.size)
                .put("appInputsAfterStart", ownedInputs.size)
                .put("appInputsOnTargetDisplay", targetInputs.size)
                .put("careBudgetEvents", careBudget.size)
                .put("errorEvents", errors.size)
                .put("resultConfirmed", resultConfirmed)
                .put("resultFrameCount", resultFrames.size)
                .put("physicalTouchesBefore", physicalTouchesAtStart)
                .put("physicalTouchesAfter", GateTestActivity.touches.getValue(0).get())
                .put("unresolved", JSONArray(unresolved.toList()))
                .put("states", JSONArray(states))
                .put("stateLog", JSONArray(stateLog))
                .put("note", "InputSent counters are transport evidence only and are never treated as a game result.")
            File(dir, "verdict.json").writeText(verdict.toString())

            // Copy the real event stream verbatim, when it exists, for offline inspection.
            if (eventFile.isFile) eventFile.copyTo(File(dir, "session-events.jsonl"), overwrite = true)
            File(dir, "state-transitions.json").writeText(JSONArray(stateLog).toString())

            assertEquals("Session must end COMPLETED for a single-round pass, was ${terminal.runState.name} " +
                "(${terminal.pauseReason})", SessionRunState.COMPLETED, terminal.runState)
            assertEquals("Exactly one requested round must be completed", 1, terminal.completedRounds)
            assertEquals("Exactly one real course start may be owned", 1, courseStarts.size)
            assertTrue("A device-observed course result is required", resultConfirmed)
            assertEquals("No physical-screen touch may occur", physicalTouchesAtStart,
                GateTestActivity.touches.getValue(0).get())
            println("SINGLE_ROUND_ACCEPTANCE $dir")
        } catch (t: Throwable) {
            // Preserve the actual failure reason; never claim completion and never auto-retry.
            val why = JSONObject()
                .put("verdict", "STOPPED")
                .put("sessionId", observedSessionId ?: JSONObject.NULL)
                .put("requestedRounds", requestedRounds)
                .put("started", started)
                .put("completedRounds", PetManagedRuntime.state.value.completedRounds)
                .put("terminalState", PetManagedRuntime.state.value.runState.name)
                .put("error", t.toString())
                .put("states", JSONArray(states))
                .put("stateLog", JSONArray(stateLog))
                .put("unresolved", JSONArray(unresolved.toList()))
            runCatching { File(dir, "verdict.json").writeText(why.toString()) }
            throw t
        } finally {
            // Clean up only the session this class started; finalizeStop() republishes STOPPED.
            if (started) {
                runCatching {
                    withContext(Dispatchers.Main) {
                        app.startService(PetAutomationService.stopIntent(app))
                    }
                }
                withTimeoutOrNull(15_000L) {
                    while (PetManagedRuntime.state.value.runState != SessionRunState.STOPPED &&
                        PetManagedRuntime.state.value.runState != SessionRunState.COMPLETED) delay(200)
                }
            }
        }
    }

    /**
     * Length of the real dumpsys section for the target display, captured as a raw observation
     * before the service starts. Kept as a plain count so no parsed meaning is invented.
     */
    private fun targetDisplaySectionLength(shell: (String) -> String, displayId: Int): Any {
        val dump = shell("dumpsys activity activities")
        val section = dump.substringAfter("Display: mDisplayId=$displayId ", "")
            .substringBefore("Display: mDisplayId=")
        return if (section.isBlank()) JSONObject.NULL else section.length
    }

    /** The newest real service-owned evidence directory, or null when none was observed. */
    private fun newestSessionEvidenceDir(app: MyApplication): File? =
        File(app.getExternalFilesDir(null), "pet_mvp_session")
            .listFiles()?.filter { it.isDirectory }?.maxByOrNull { it.lastModified() }
}
