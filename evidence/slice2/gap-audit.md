# Slice-2 Gap Audit — frozen v0.1 spec vs. code

- Repo root: `C:\Users\lu158\Desktop\dsh插件\养宠自动化\VirtualDisplay`
- Branch `feature/qqpet-mvp-session`, HEAD `327029bb94a17f10495f411017e5c7d5d52af8ca`
- Method: static read-only inspection of main source, unit tests, androidTest, evidence artifacts, run scripts and docs. **No device run was performed for this audit.**
- Scope of this document: the 10 requested items, then a severity-ordered MISSING/PARTIAL summary, then a list of capabilities absent from the codebase entirely.

## 0. Blocking limitation that affects items 3 and 4

**The frozen v0.1 spec text is not in this repository.** Exhaustive search for the spec's own vocabulary returned zero hits in code *and* in docs:

```
grep -r "运行记录|暂停次数|护理次数|需要护理|护理动作|护理结果确认|进入学院|开课确认|发送鼓励|完成课程|安全停止"
  → 0 hits in *.kt
  → "检查状态" only in deliverables/experimental/request.txt:824 and 调试过程与技术留痕报告.md:35
grep -r "pauseCount|pause_count|暂停" (main source)  → 0 hits
grep -r "totalDuration|session_total|run_total|round_total_ms|screenshots|manifest"
  → round_total_ms only in app/src/debug/.../PetExperimentalSession.kt:436
```

The only in-repo requirements document of this family, `deliverables/experimental/request.txt` (1021 lines), is a **different request** — a mood-override experiment. It explicitly says at L148: “mood 不作为本次实验的 blocking condition”, which contradicts the frozen spec's mood freeze. It is therefore *not* the frozen spec, and it is *not* evidence either way for items 3 and 4.

Consequence: for item 3 I can state exactly which events the code *does* emit (a closed set, enumerated below) and can state that none of the 14 named Chinese events exist as literals anywhere in the repo. Whether the code's English event kinds are an acceptable substitute for the 14 named Chinese events **cannot be determined from the code**: recorded as **UNKNOWN**. Same for the spec's exact run-record field list in item 4 — I rate what the code records, not what the spec demands.

---

## 1–10. Verdict table

| # | Item | Verdict | Key evidence (`file:line`) | One-line justification |
|---|------|---------|---------------------------|------------------------|
| 1 | Lifecycle states (incl. COMPLETED/FAILED) | **MISSING** | `SessionRecovery.kt:9`; `PetSessionController.kt:107`; `PetManagedRuntime.kt:10-11`; `PetRuntime.kt:12` | `SessionRunState` has only `RUNNING, PAUSED, RESUMING, STOPPED`; success, stop and failure all collapse to `STOPPED` via an unconditional `finally`; no COMPLETED/FAILED exists; IDLE is only default strings, never a state. |
| 2 | Read-only runtime state + CONFIRMED/UNKNOWN/STALE | **PARTIAL** | `PetManagedRuntime.kt:7-27`; `ObservedValue.kt:16-34`; `PetSessionController.kt:43-49`; `PetMvpScreen.kt:31` | Every field exists and health is properly typed `Known/Unknown/Stale/Unsupported` with `.at()` ageing and a status field; but `object PetManagedRuntime` has exactly one consumer, `publish()` — no UI reads it, the screen binds legacy `PetRuntime`, so "UI shows runtime state" is unwired. |
| 3 | Runtime log distinguishing app / game-confirmed / unknown | **PARTIAL** (spec vocabulary **UNKNOWN**) | `SessionEvent.kt:7-9,46-48`; `AutomationFacts.kt:34-42`; `PetFlowContext.kt:50-55`; `PetStudyFlow.kt:44-45` | Sources correctly separate `APP_AUTOMATION / GAME_OBSERVATION / USER / DEBUG_ADB` and unknown exists as `HEALTH_UNKNOWN` + `Unknown(...)`; but **0 of the 14 named Chinese events appear as literals**, and whether the English equivalents satisfy the spec is UNKNOWN because the spec text is absent (see §0). |
| 4 | Run record (spec #14) | **PARTIAL** | `PetSessionController.kt:88-92`; `PetSessionController.kt:43-49`; `PetAutomationService.kt:94-97`; `PetCareFlow.kt:37-39`; `PetStudyFlow.kt:128-129` | Per-round before/ready/care/after/course/result and course timing are recorded; **no pause counter exists anywhere**, no per-action confirmed-effectiveness field, no consolidated run record, and `careStatistics` is reassigned per `recover()` so it is not a per-run total. Exact JSON keys listed in §4 below. |
| 5 | Duplicate-execution protection | **PARTIAL** | `FileCheckpointStore.kt:11-17`; `SessionEvent.kt:46-48`; `PetManagedRuntime.kt:38-41`; `PetNavigation.kt:110`; `SessionRecovery.kt:56`; `PetSessionController.kt:84` | Checkpoint (AtomicFile) and journal (`fd.sync()`) are durable; but `PetRoundLedger` is purely in-memory and there is **no resume-after-process-death** — a restart yields a new sessionId and fails closed (`ACTIVE_COURSE_WITHOUT_OWNED_CHECKPOINT`, `CHECKPOINT_IDENTITY_MISMATCH`). Duplication is prevented by refusing to continue, not by resuming; residual gap: the orphaned in-game course is never counted in the budget (`PetAutomationService.kt:88`). |
| 6 | Navigation centralisation + typed page identity | **PARTIAL** | `PetNavigation.kt:88-96`; `PetRecoveryBoundary.kt:68-69,73-74`; `PetFlowContext.kt:42,82`; `SessionRecovery.kt:38` | Navigation *is* centralised in `PetNavigation`, but page identity is bare strings with **four different encodings of the same pages** (`"HEALTH"` vs `"health"`; `"course_list"/"course_detail"/"COURSE_LIST"/"COURSE_DETAIL"`; `"LEARNING"` uppercase vs lowercase probe names). No enum, no typed set, no single source of truth. |
| 7 | Safety / fail-closed guards | **SATISFIED** (one off-path caveat) | `PetProfile.kt:22`+`PetFlowContext.kt:81`; `FrameLiveness.kt:48`; `ObservedValue.kt:22-27`; `SessionRecovery.kt:25,64,78,93` | 0.97 threshold enforced on every page verify, `FrameTrustGate(3)` inhibits input on the first invalid frame and latches at three, `requireCurrent` throws rather than defaulting, recovery is bounded to 3 attempts / 120 s, and checkpoint page-unknown/mismatch STOP. No UNKNOWN-as-success on the formal path. Caveat: mood still gates `HealthDecision.ready` (`HealthObservations.kt:22`), reachable only from the debug path. |
| 8 | Device fault tests, 4 required classes | **PARTIAL** | `PetRecoveryGateTest.kt:49-52,106,128`; `PetRuntimeFactsTest.kt:39-41,91,105`; `SessionRecoveryTest.kt` (unit only) | page-mismatch ✅ and capture-anomaly ✅ have device tests; recovery-failure has **unit tests only** (`repeatedFailureStopsAfterThree`, `permanentlyLockedHasFiniteDeadline`), no device test; unknown/indeterminate state has **no dedicated device test** (only unit `unknownPageInvalidDisplayAndInvalidFrameNeverResume` and the lock mode of `faultGate`). |
| 9 | Device regression suite of exactly 9 checks | **SATISFIED** | `evidence/managed/managed-final-regression.txt:4,134,136`; classes named in §9 | Exactly 9 tests across 5 androidTest classes, artifact ends `OK (9 tests)`, all 9 have `INSTRUMENTATION_STATUS_CODE: 0` and no `ASSUMPTION`/`-3` markers. Caveat: several gates self-skip via `assumeTrue(...)` when not authorised, so an "OK" run does not on its own prove those bodies executed; and **no script reproduces the 9-test set** (`run-mvp-gates.ps1:10` runs only `PetDisplayGateTest`). |
| 10 | Experimental-path isolation | **SATISFIED** | `PetSessionController.kt:9`; `PetFlowContext.kt:43`; grep result in §10 | Zero main-source references to `PetExperimentalSession` (only `app/src/debug/.../PetExperimentalSession.kt` and `PetExperimentalSessionTest.kt`); the formal path uses `PetStudyHealthPolicy::requireReady`, never the mood-gated `PetHealthCheck.forBusiness`. |

---

## 2. Item 2 in detail — what exists and what is unwired

`ManagedRuntimeState` (`PetManagedRuntime.kt:7-27`) carries every requested field: `sessionId, runState, currentFlow="IDLE", currentStep="IDLE", round, totalRounds, completedRounds, startedAt, startedElapsedMs, lastConfirmedAt, nextStep="等待启动", pauseReason, runtimeMs, energy/cleanliness/mood` as `ObservedValue<Int>` defaulting to `Unknown("NOT_OBSERVED", 0, ...)`. `at()` (L25-26) ages health past 60000 ms into `Stale` and recomputes `runtimeMs` — it does **not** invent numbers.

The gap is consumption, not definition: `PetManagedRuntime` is read in exactly one place, `publish()` at `PetSessionController.kt:41`, which serialises it to `runtime-state.json`. The only UI (`PetMvpScreen.kt:31`) collects `PetRuntime.state` — the legacy snapshot (`PetRuntime.kt:12`, `status="未运行"`). So the typed, confirmed/unknown/stale-aware runtime state is written to a file and never displayed. Whether the spec's "read-only UI runtime state" means the file or the screen **cannot be determined from the code**: the file form is SATISFIED, the screen form is MISSING.

## 3. Item 3 in detail — the closed set of emitted events

`EventSource` (`SessionEvent.kt:7`): `APP_AUTOMATION, GAME_OBSERVATION, USER, DEBUG_ADB` — the app/game/user three-way distinction is real. `EventKind` (`SessionEvent.kt:8-9`) is a closed enum of 11 values: `HEALTH_OBSERVED, HEALTH_UNKNOWN, FRAME_VALID, FRAME_INVALID, INPUT_SENT, COURSE_STARTED, LEARNING_CONFIRMED, ERROR, SESSION_CONFIG, SESSION_STATE, CHECKPOINT`.

Ground truth from a real recorded session, `evidence/managed/managed-session-events.jsonl` (its `"kind"` histogram):

```
90 FRAME_VALID    20 INPUT_SENT    2 HEALTH_OBSERVED    1 LEARNING_CONFIRMED
73 CHECKPOINT       4 SESSION_STATE 1 ERROR              1 SESSION_CONFIG    1 COURSE_STARTED
```

`INPUT_SENT` actions observed: 20 × `TAP` (`PetInputController.kt:53`), 1 × `SESSION_STOP`; `CONTINUOUS_DRAG` (`PetInputController.kt:86`) is implemented but absent from that journal. Note what this run *did not* record: `HEALTH_UNKNOWN` (0) and `FRAME_INVALID` (0).

Answers:
- **App-did vs. game-confirmed vs. unknown are distinguishable** — by `source` on every event, and by `HEALTH_UNKNOWN`/`Unknown` for the third category. SATISFIED at the mechanism level.
- **Which of the 14 named Chinese log events are emitted: none as literals.** `grep` for all 14 names returns 0 code hits. Nearest functional equivalents: 开课 ≈ `INPUT_SENT` + `COURSE_STARTED` (`PetStudyFlow.kt:26,44`); 开课确认 ≈ `LEARNING_CONFIRMED` (`PetStudyFlow.kt:45`); 暂停/恢复/安全停止 ≈ `SESSION_STATE` payloads (`PetRecoveryBoundary.kt:22-23`) plus the Chinese `stopReason` strings (`PetAutomationService.kt:36,49,52,68,69,103,191`). Whether these substitutions satisfy the spec is **UNKNOWN** — the spec text naming those 14 events does not exist in this repo (§0).
- **One genuine honesty defect:** the encouragement burst logs `result="INPUT_SENT_BUSINESS_UNCONFIRMED"` (`PetStudyFlow.kt:85`) and `high_five_result` = `SENT_UNCONFIRMED` (L93) with `high_five_success_count=JSONObject.NULL` (L90) — i.e. the code records that it *sent* taps but never that encouragement *worked*. That is correctly conservative, but it also means no confirmed encouragement success is ever recorded.

## 4. Item 4 in detail — exact files and JSON keys

| Record | File / producer | Keys |
|---|---|---|
| Session runtime | `runtime-state.json` ← `PetSessionController.kt:43-49` | `sessionId, runState, flow, step, round, totalRounds, completedRounds, startedAt, lastConfirmedAt, nextStep, pauseReason, runtimeMs, energy, cleanliness, mood` (health fields via `ObservedValue.fields()`, `ObservedValue.kt:29-34`: `status/…evidence/source`) |
| Per round | `round-N.json` ← `PetSessionController.kt:88-92` | `round, before, ready, care, course, result, after, appEncouragementSent` |
| Course | inside `course` ← `PetStudyFlow.kt:46-47,90-93` | `name, courseStartElapsedRealtime, start_ts, enter_course_ms, high_five_sent_count, high_five_success_count, high_five_first_offset_ms, high_five_last_offset_ms, high_five_duration_ms, high_five_match_score, high_five_result` |
| Result | inside `result` ← `PetStudyFlow.kt:128-129` | `grade, course_ms, result_wait_ms` |
| Care | `PetCareFlow.kt:37-39`, per-action payload `:54-56,76-78` | `feed_count, feed_duration_ms, clean_count, clean_duration_ms`; per action `feed_start_ts, feed_end_ts, stamina_before, stamina_after, inventory_before="UNKNOWN", inventory_after="UNKNOWN"` and `cleanliness_before/after` |
| Verdict | `verdict.json` ← `PetAutomationService.kt:94-97` | `verdict, completed, requested, stopReason, captureStatistics` |

**Missing from the record:** total run duration as a single field (only per-call `runtimeMs` and per-round implied spans — no `totalDuration`/`run_total_ms` exists; `round_total_ms` exists only in the debug path, `PetExperimentalSession.kt:436`); **暂停次数 — zero hits for `pauseCount`/`pause_count`/`暂停` in main source**; per-action *confirmed* effectiveness (care actions record before/after health, which is the nearest thing, but the encouragement action explicitly records `SENT_UNCONFIRMED`); any screenshot/evidence path list per round (frames are saved ad hoc, e.g. `PetStudyFlow.kt:68` `save(f,"before-high-five")`, with no manifest); and any consolidated single-run record joining the above.

## 5. Item 5 in detail — durability boundary

**Durable:** the checkpoint via `AtomicFile` (`FileCheckpointStore.kt:11-17`, keys `sessionId, round, phase, trustedPage, health, observedAt, healthRequired, actionPending`, round-tripping `KNOWN/UNKNOWN/UNSUPPORTED/STALE` at L22-33, else `CHECKPOINT_OBSERVATION_INVALID`); the event journal via append + `stream.fd.sync()` (`SessionEvent.kt:46-48`).

**In-memory only:** `PetRoundLedger` (`PetManagedRuntime.kt:37-50`) — `mutableSetOf`/`mutableMapOf` guarding `DUPLICATE_COURSE_START`, `DUPLICATE_START_CONFIRMATION`, `COURSE_NOT_STARTED`, `ENCOURAGEMENT_LIMIT`. Plus `PetSessionController.kt:84` throws `DUPLICATE_ROUND_COMPLETION`.

**Consequence for crash/process death:** `AutomationFacts.kt:18` creates the store with `UUID.randomUUID()` and the checkpoint path embeds that sessionId (`PetRecoveryBoundary.kt:18`). After a restart the new session owns a different checkpoint file, so continuation fails closed rather than duplicating: `PetNavigation.kt:110` `ACTIVE_COURSE_WITHOUT_OWNED_CHECKPOINT`, `PetRecoveryBoundary.kt:65` `CHECKPOINT_PAGE_UNSUPPORTED`, `SessionRecovery.kt:55-56` `CHECKPOINT_MISSING` / `CHECKPOINT_IDENTITY_MISMATCH`. The recorded evidence agrees: `evidence/managed/managed-retry-verdict.json` ends `STOPPED ... LEARNING_PAGE_LOST`, `managed-three-rounds-verdict.json` ends `STOPPED ... RECOVERY_WINDOW_EXHAUSTED`, and `evidence/managed/artifact-manifest.json` carries `"verdict": "REWORK"` with `"reason": "Three uninterrupted rounds and successful real pause/resume remain unverified"`.

So: **no duplicated course start, no double count, no double result claim** — because nothing resumes. The residual, narrower gap is accounting: `onRoundCompleted` adds 10 minutes (`PetAutomationService.kt:88`) only when a round completes in-process, so a course started before a crash is never charged to `DailyBudgetStore`, which itself documents that manual study is invisible (`DailyBudgetStore.kt` doc-comment; surfaced in UI at `PetMvpScreen.kt:140`).

## 6. Item 6 in detail — every bare page literal

`PetNavigation.kt:88-96`: `"cookie"`, `"soap"`, `"result"`, `"LEARNING"`, `"room"`, `"course_list"`, `"academy"`, `"outdoor"`, `"UNKNOWN"`.
`PetFlowContext.kt:27-38` probe names → `"pet_experimental/<name>.png"`: `home, room, health, cookie, soap` (`pet_mvp/soap.png`), `outdoor, academy, high_five, grade_a, upgrade, care_upgrade_hint, care_upgrade_reward`; page map built from probe names + `mapOf("LEARNING" to profile.learningProbes)` (L40-42) and verified with the same literal at L82.
`PetRecoveryBoundary.kt:68` `cp.trustedPage!="health" && != "HEALTH"`; L69 `setOf("course_list","course_detail","COURSE_LIST","COURSE_DETAIL")`; L73 `"HEALTH"`; L74 `"result"`.
`PetSessionController.kt`: page identity only through `nextStep` strings, `:53,64,73,76,78,81,86,99`.
`SessionRecovery.kt:37-38` `confirm()` rejects blank and the literal `"UNKNOWN"`; `SessionRecoveryTest.kt:51` supplies `"RESULT"` (uppercase) as a fixture, a fifth casing in circulation.

No enum, no sealed page type, no shared constant — the same logical page is spelled four different ways, and every spelling is a string that a typo would silently pass. **Single source of truth: NO.** (Per instruction, no path-finder recommendation is made; the spec forbids it.)

## 7. Item 7 in detail — guards and the one off-path caveat

Present and enforced: match threshold `0.97` (`PetProfile.kt:22`) checked on every `verify()` with `PAGE_MISMATCH:$state:$name:$s` (`PetFlowContext.kt:81`) and an 8 s bounded `verifySettled` (`:86-104`); `FrameTrustGate(3)` inhibits input on the first invalid frame and latches STOP at three, re-armed only for controlled RESUMING (`FrameLiveness.kt:38-51`, `AutomationFacts.kt:23-25`); `ObservedValue.requireCurrent` throws `HEALTH_UNKNOWN/HEALTH_STALE/HEALTH_UNSUPPORTED` (`ObservedValue.kt:22-27`); recovery bounded to `maxAttempts=3, windowMs=120000` with a constructor `require` (`SessionRecovery.kt:25,29`) and exhaustion STOPs (`:64,78,93`); checkpoint page unknown/mismatch STOPs (`:71-73`).

**No UNKNOWN-as-success found on the formal path.** Unknowns either STOP or are recorded as unconfirmed (`SENT_UNCONFIRMED`, `high_five_success_count=null`).

Caveat, off the formal path only: `HealthDecision.ready` still requires `mood>=90` (`HealthObservations.kt:22`) and `HealthObservations.fields()` publishes mood. Per the mood freeze this must not gate starts, and it does not on the formal path — `PetFlowContext.kt:43` injects `PetStudyHealthPolicy::requireReady`, whose `Reading.ready = energy>=90 && cleanliness>=90` (`PetStudyHealthPolicy.kt:6`) is mood-agnostic, proven by `PetStudyHealthPolicyTest.lowUnknownStaleMoodDoesNotAuthorizeOrPreventStudy` (`:11-15`). The mood-gated decision is reachable only from `PetExperimentalSession.kt:158` (debug) and asserted in three tests (`PetHealthReadingTest.kt:47`, `PetRecordedStateTest.kt:62`, `PetStudyHealthPolicyTest.kt:11`). It is dead weight, not a violation — but it is a loaded gun if the formal path is ever rewired to `PetHealthCheck.forBusiness`. No mood-restore action exists anywhere.

## 8. Item 8 in detail — device fault coverage

Required class → test method → injected fault:

1. **Page mismatch — COVERED (device).** `PetRecoveryGateTest.faultGate` with arg `recovery_gate=mismatch`: injects `TEST_PAGE_FIXTURE` health bitmap (`PetRecoveryGateTest.kt:50-52`), asserts `RECOVERY_PAGE_MISMATCH` (`:106`). Requires `display_id==4`; evidence `evidence/recovery/recovery-mismatch.txt`, `evidence/managed/managed-fault-mismatch.txt` each `OK (1 test)`.
2. **Capture anomaly — COVERED (device).** `PetRecoveryGateTest.faultGate` arg `recovery_gate=capture`: injects `INJECTED_CAPTURE_FAILURE` (`:49`); asserts **no** `"kind":"INPUT_SENT"` appears in the journal (`:128`). Plus `PetRuntimeFactsTest` injects `TEST_DECODED_BITMAP` black/blue frames and a `TEST_PAGE` no-op (`:39-41,91,105`). Evidence: `managed-fault-capture.txt`, `recovery-capture.txt`.
3. **Unknown / indeterminate state — NOT COVERED on device.** The only coverage is the unit test `SessionRecoveryTest.unknownPageInvalidDisplayAndInvalidFrameNeverResume`, plus `faultGate`'s `lock` mode (`recovery-lock.txt`). No androidTest injects an indeterminate page observation as such.
4. **Recovery failure — NOT COVERED on device.** Unit only: `SessionRecoveryTest.repeatedFailureStopsAfterThree`, `permanentlyLockedHasFiniteDeadline`, `outageRecoversWithBoundedAttemptsAndNoInput`. No device test drives attempt exhaustion or window exhaustion to a STOP.

Also present but not one of the four required classes: `PetDisplayGateTest.headlessThenSingleScopedTap` (display-scoped input), `PetHealthReadingTest.heldoutRealHealthDigits` (57 held-out values) and `realPanelObservationsSeparateExactAndConservativeAndExpire` (`assertFalse(values.current(at).ready)` at `:47`), `PetActiveCaptureTest.captureGate` (static/transition/hybrid), `PetCalibrationGateTest.authorizedSoapGesture`, `PetExperimentalSessionTest`, `PetRecordedStateTest` (3 tests, all `assumeTrue(fixtures.exists())`).

Unit-test baseline for reference (from the recorded XML, not re-run): `ExampleUnitTest` 1, `ObservationFactsTest` 13, `PetManagedReconcileTest` 1, `PetManagedRuntimeTest` 3, `PetStudyHealthPolicyTest` 4, `SessionRecoveryTest` 9 — **31 total, 0 failures/errors/skips**, matching the documented 31/31 claim.

## 9. Item 9 in detail — the 9 checks and where they are defined

Defined by the test classes; enumerated by the artifacts. Artifact `evidence/managed/managed-final-regression.txt` declares `numtests=9` (L4) and ends `Time: 5.743` / `OK (9 tests)` (L134/136).

| # | Check | Defining file |
|---|---|---|
| 1 | `PetDisplayGateTest.headlessThenSingleScopedTap` | `app/src/androidTest/java/com/ynk/virtualdisplay/PetDisplayGateTest.kt` |
| 2 | `PetRecoveryGateTest.checkpointRoundTrip` | `.../PetRecoveryGateTest.kt` |
| 3 | `PetRecoveryGateTest.faultGate` | `.../PetRecoveryGateTest.kt` |
| 4 | `PetHealthReadingTest.heldoutRealHealthDigits` | `.../PetHealthReadingTest.kt` |
| 5 | `PetHealthReadingTest.realPanelObservationsSeparateExactAndConservativeAndExpire` | `.../PetHealthReadingTest.kt` |
| 6 | `PetRecordedStateTest` #1 | `.../PetRecordedStateTest.kt` |
| 7 | `PetRecordedStateTest` #2 | `.../PetRecordedStateTest.kt` |
| 8 | `PetRecordedStateTest` #3 | `.../PetRecordedStateTest.kt` |
| 9 | `PetRuntimeFactsTest` (2 methods → the 9th slot) | `.../PetRuntimeFactsTest.kt` |

Identical 9-test artifacts exist four times (SHA-256 verified identical to their `.log` twins): `evidence/managed/managed-final-regression.txt` `FB0AE2FD…`, `evidence/managed/managed-regression.txt` `AEF96BCE…`, `evidence/managed/managed-settle-regression.txt` `5290C15E…`, `evidence/recovery/recovery-regression.txt` `E5E64363…`. The three managed files differ from each other by content (different runs) but each reports the same 9-test set.

**Automation gap:** `run-mvp-gates.ps1` installs the APKs and runs only `PetDisplayGateTest` (L10), asserting `OK (1 test)` (L13); `run-recorded-checks.ps1:20` runs only `PetRecordedStateTest`; `run-experimental-session.ps1` runs only `PetExperimentalSessionTest`. **No script in the repo reproduces the 9-test suite**; the four artifacts above were produced by ad-hoc invocations.

## 10. Item 10 in detail — isolation evidence

Grep for `PetExperimentalSession` across all `*.kt` outside `build/` returns only two files: `app/src/debug/java/com/ynk/virtualdisplay/automation/PetExperimentalSession.kt` (the debug source set) and `app/src/androidTest/java/com/ynk/virtualdisplay/PetExperimentalSessionTest.kt`. Zero main-source references, zero main-source imports.

The isolation is also stated as a contract in code: `PetSessionController.kt:9` — “Finite main-source controller. No dependency on PetExperimentalSession or test APIs.” The formal health gate is injected rather than inherited (`PetFlowContext.kt:39-43`, `validateHealth=PetStudyHealthPolicy::requireReady`), so the mood-gated `PetHealthCheck.forBusiness` from the debug path is structurally unreachable from production.

---

## MISSING / PARTIAL SUMMARY (severity order)

**S1 — MISSING, breaks the frozen scope**

1. **Lifecycle has no terminal success/failure state.** `SessionRunState` = `RUNNING, PAUSED, RESUMING, STOPPED` only (`SessionRecovery.kt:9`); `finally { PetManagedRuntime.update { it.copy(runState = STOPPED) } }` (`PetSessionController.kt:106-108`) makes a clean 3-round finish, a user stop and an exception **indistinguishable**. A run record cannot state whether the run succeeded.
2. **No pause accounting.** `pauseCount`/`pause_count`/`暂停` have **zero hits in main source**; `pauseReason` (`PetManagedRuntime.kt:20`) is the only pause fact recorded, and it is last-write-wins. Spec #14's 暂停次数 is unimplementable from the current record.
3. **No consolidated run record.** The run's facts are scattered across `runtime-state.json`, `round-N.json`, `verdict.json` and the JSONL journal; nothing joins them, and total run duration exists only as per-call `runtimeMs`.
4. **No resume-after-process-death.** Checkpoint and journal are durable, but the sessionId is regenerated per process (`AutomationFacts.kt:18`), so recovery always fails closed on identity (`SessionRecovery.kt:56`) or ownership (`PetNavigation.kt:110`). Consequence recorded in `evidence/managed/artifact-manifest.json`: `"verdict": "REWORK"`.

**S2 — PARTIAL, spec-visible**

5. **Runtime state is written but never shown.** `PetManagedRuntime`'s only consumer is `publish()` (`PetSessionController.kt:41`); `PetMvpScreen.kt:31` still binds legacy `PetRuntime`. Typed CONFIRMED/UNKNOWN/STALE exists on disk and nowhere on screen.
6. **Page identity is four-way-stale bare strings.** `"HEALTH"` vs `"health"` (`PetRecoveryBoundary.kt:68,73`), the four-element `setOf("course_list","course_detail","COURSE_LIST","COURSE_DETAIL")` (`:69`), `"LEARNING"` vs lowercase probes (`PetFlowContext.kt:42,82`), `"RESULT"` in test fixtures (`SessionRecoveryTest.kt:51`). No typed single source of truth.
7. **None of the 14 named Chinese log events exist as literals** (0 hits, whole repo). The journal's own vocabulary is the 11-value `EventKind` enum (`SessionEvent.kt:8-9`), whose real observed distribution is FRAME_VALID 90 / CHECKPOINT 73 / INPUT_SENT 20 / SESSION_STATE 4 / HEALTH_OBSERVED 2 / LEARNING_CONFIRMED 1 / ERROR 1 / SESSION_CONFIG 1 / COURSE_STARTED 1.
8. **Encouragement is never confirmed.** `high_five_result="SENT_UNCONFIRMED"`, `high_five_success_count=null`, `result="INPUT_SENT_BUSINESS_UNCONFIRMED"` (`PetStudyFlow.kt:85,90,93`). Honest, but the spec's “auto-encourage → handle result” has no confirmed link.
9. **Per-action effectiveness has no confirmed field.** Care records before/after health deltas only; the gain checks (`FEED_NO_EXPECTED_GAIN_OR_EMPTY` `PetCareFlow.kt:50`, `CLEAN_NO_GAIN_OR_EMPTY` `:72`) throw rather than record an effectiveness value. `inventory_before/after` are hard-coded `"UNKNOWN"` (`:54-56`).
10. **Two of the four required fault classes lack device tests** — unknown/indeterminate state and recovery-failure exhaustion exist only as unit tests (§8 items 3–4).
11. **`careStatistics` is per-`recover()`-call, not per run** (`PetCareFlow.kt:37-39` reassigns a fresh `JSONObject`), so 护理次数 across a 3-round session is not a single number.

**S3 — hygiene / false confidence**

12. **No script reproduces the 9-check regression suite.** `run-mvp-gates.ps1:10` runs exactly one of the nine.
13. **`assumeTrue`-gated device gates**: `PetManagedSessionTest` (`managed_authorized=="yes"`), `PetCalibrationGateTest` (`soap_authorized`), `PetExperimentalSessionTest` (`experimental_authorized`), `PetRecordedStateTest` (fixtures exist). An “OK (N tests)” line therefore does not by itself prove the gated bodies ran. (The specific 9-test artifact was checked and contains no ASSUMPTION/-3 markers; the concern applies to re-runs, not to that recorded artifact.)
14. **`PetStateMachine.kt` is dead code** — declared at `:8`, referenced nowhere outside its own file.
15. **Budget ledger provably incomplete**, by its own admission: `DailyBudgetStore` cannot see manual study, surfaced to the user at `PetMvpScreen.kt:140`.

---

## Capabilities MISSING from the codebase entirely

Each of these has **no implementation anywhere** in main source:

| Capability | Proof of absence |
|---|---|
| Run/lifecycle COMPLETED or FAILED state | `SessionRecovery.kt:9` — enum has 4 members, none terminal-success/failure |
| Pause counter / 暂停次数 | 0 grep hits for `pauseCount`, `pause_count`, `暂停` in `app/src/main` |
| Total run duration as one recorded field | 0 hits for `totalDuration`, `session_total`, `run_total_ms`; `round_total_ms` only in debug (`PetExperimentalSession.kt:436`) |
| Consolidated single-run record | No producer; four disjoint outputs (§4 table) |
| Per-action confirmed effectiveness metric | Care records before/after only; encouragement explicitly `SENT_UNCONFIRMED` (`PetStudyFlow.kt:93`) |
| Screenshot/evidence manifest per round | Frames saved ad hoc (`PetStudyFlow.kt:68`), no list, no manifest |
| Typed page identity (enum/sealed class) | All page names are `String` literals (§6) |
| Resume-after-process-death | sessionId regenerated per process (`AutomationFacts.kt:18`); recovery always fails closed |
| Device test for unknown/indeterminate page state | No androidTest injects one (§8.3) |
| Device test for recovery-failure exhaustion | Unit tests only (§8.4) |
| The 14 named Chinese runtime-log events | 0 literal hits repo-wide |
| Spec #14 run-record section as specified | Spec text absent from repo; matching field list absent from code |
| UI display of `PetManagedRuntime` | `PetMvpScreen.kt:31` binds `PetRuntime`; only consumer of the managed state is `publish()` |
| Reproduction script for the 9-check suite | No script enumerates all 9 (`run-mvp-gates.ps1:10` = 1 test) |
| Mood-free implementation of `HealthDecision.ready` | `HealthObservations.kt:22` still requires `mood>=90` (off formal path) |

---

### UNKNOWNs and why

- **Item 3, spec conformance of the event vocabulary** — the 14 named Chinese events are not in the repo, so equivalence cannot be judged from code (§0).
- **Item 4, completeness against spec #14** — the spec's field list is not in the repo; only the code's actual keys can be reported (§4).
- **Item 1/2, whether lifecycle and UI-state requirements are met** — rated on the code's own terms; the spec's exact wording for the required states and for the read-only UI surface is not in the repo.
- **Item 9, whether the recorded 9-test artifacts still reproduce** — no device run was performed in this audit; the artifacts and their hashes were verified as files, not re-executed.
- **Item 8, runtime behaviour of any device test** — several gates self-skip via `assumeTrue`; static inspection cannot show which assertions executed in past runs beyond the artifact markers checked.
