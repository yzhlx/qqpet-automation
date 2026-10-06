# QQPET_PRODUCTIZATION_SLICE2

START_SHA: `af393ea68aec175c9ec10145a6b900f1fe01bf18`

## Audit and scope

`2dbc276… → af393ea…` adds one SessionRecovery / PetRecoveryBoundary, atomic private checkpoint, independent paused-input barrier and fault tests. This slice reuses those classes. Capture, Input, Virtual Display, TemplateMatcher, health digit templates and liveness implementations are not rewritten. `PetExperimentalSession` is retained unchanged.

User policy frozen for this slice: energy >=90 and cleanliness >=90 authorize study. Mood is observation only, including Unknown / Stale; no mood recovery action exists. Required health retains precise digit readings and the conservative bar boundary, never numeric defaults for Unknown.

## Extracted main-source path

- `PetAutomationService → PetSessionController` is the actual foreground Service entry.
- `PetCareFlow`: existing cookie / continuous soap gesture, fresh re-reading, positive-gain confirmation, bounded consumption attempts. Can run without the experimental Session.
- `PetStudyFlow`: pre-start validation, fixed course, start confirmation, encouragement, learning observation and result recognition.
- `PetNavigation`: measured health/food/bath/outdoor/academy/course/result/upgrade edges; no generic path finding.
- `PetFlowContext`: existing verified observations and checkpoints shared by these flows.
- `PetManagedRuntime`: immutable read-only StateFlow with sessionId, lifecycle, flow/step, rounds, start/confirmation time, elapsed duration, next step, pause reason and typed health observations aged to Stale.

## Recovery and duplicate protection

Same active coroutine resumes through the existing 3-attempt /120-second recovery. No restart of the entire Session, no replay of unconfirmed input. `PetRoundLedger` prevents duplicate starts and completions, caps encouragement at ten, and makes a pending action STOP instead of repeat.

Only an owned course in phase WAIT_RESULT may reconcile LEARNING to a positively matched result page. The ledger must confirm that this round was started and not completed. Other checkpoint mismatches remain STOP; 0.97 templates and 8-second page settling remain unchanged.

Health revalidation in the managed path uses only the two required attributes; the existing recovery class accepts that explicit validator rather than introducing a second recovery system. Default callers keep their existing validation behavior.

Encouragement still begins about 30 seconds after start and checks the learning page before/after each input. Ten verifications have a bounded 45-second burst budget; the old untested ten-second budget could not cover the measured capture/validation latency. INPUT_SENT does not claim that each game reward was confirmed.

Historical before/ready health is serialized before the course; it is not accessed later as a current fact. Live UI-facing health becomes Stale during the long learning wait and is re-read before the next course.

## Incremental evidence

1. Extracted care path compiled and passed all original unit tests plus four health-policy tests. Independent real health read/open/close probe `1791259472086` PASS: digits 76/73/76, conservative bars 76/72/76, physicalDelta=0, no consumables used.
2. Full core: unit 31/31, original device regression 9/9, capture-outage gate PASS, wrong-page STOP gate PASS, forced Hybrid Capture PASS. Evidence: `evidence/managed/`.
3. Actual foreground-Service three-round acceptance and a real pause/resume are required before this slice can be marked PASS. Acceptance checks 3 unique App course starts, 30 App encouragement inputs, 3 result acknowledgements, zero inputs while PAUSED/RESUMING and physicalDelta=0.

Device acceptance: pending explicit batch authorization and execution. No three-round PASS is claimed by the code/build/regression work alone.

## Debug trace

- Initial instrumentation compilation attempted to access private Service constants. Fixed by using the already public `startIntent` / `stopIntent`; no new dispatch mechanism.
- Standalone care originally inherited a Health wrapper requiring mood. Replaced only in the new path with an explicit two-attribute policy per the user's frozen-mood instruction. Legacy Session remains a regression reference.
- Checkpoint reconciliation is phase- and round-specific; an unrelated result page cannot authorize resume.

Final source SHA / artifacts / actual acceptance outcome will be recorded after device acceptance.

## Formal Service acceptance: current outcome (2026-10-06)

Tested source: `6dfa13a` (full SHA recorded in Git / artifact manifest). Three real failures were found and bounded fixes were committed:

1. `b323f46`: Service consumed CONNECTED before managed-display ownership was ready. Wait for both within the existing seven-second startup bound; refresh ownership when the service binder arrives. Never remove the display ownership guard. Test startup now has its own fifteen-second bound. No course or care action occurred in this failed attempt.
2. `fae88c5`: the second cookie triggered a Lv.2 care upgrade overlay, unlike the existing study-upgrade overlay. Two measured fixed ROIs positively identify it at unchanged 0.97, then dismiss via the visible blank-area instruction. Do not press reward/purchase. Re-read health after dismissal. The failed attempt consumed cookies but opened no course.
3. `6dfa13a`: health observation inside the bathroom incorrectly verified the bedroom header. Use the actual cookie/soap parent probe before opening health and after closing it. Thresholds remain unchanged. The failed attempt performed a soap gesture but opened no course.

Formal batch evidence: session `3a5cc23e-7aba-4c86-8489-cf5c707757f3`, acceptance `1791261203181`, capture directory `1791261204560`. Exact digits before start: energy 95 / cleanliness 100 / mood 81. Formal App started one course, sent ten encouragement taps, reached WAIT_RESULT, automatically PAUSED on manual lock. Unlock was not confirmed within the existing 120-second bound; STOP reason RECOVERY_WINDOW_EXHAUSTED. Recovery attempt count zero because the phone stayed locked. The course may continue inside the game independently of stopped automation. No RESUMING or successful three-round completion is claimed.

All twenty game inputs in this batch were APP_AUTOMATION on display 4. No input after PAUSED; physical-display touch delta zero. No PC/ADB supplementary taps. Initial navigation and observations account for the other ten inputs. The batch used no care items because fresh values were already ready; earlier failed attempts' consumptions are separate and must not be hidden.

Latest full unit run: 31 tests, zero failures; main and AndroidTest APK assembly successful. Original nine phone regressions, two deterministic faults and forced Hybrid gate passed on `a470ce0`; they have not been rerun on final care fixes. Real manual lock proves PAUSE plus bounded STOP, not successful RESUME. Final verdict currently REWORK, pending unlock and authorization for a new uninterrupted three-course acceptance.

Evidence: `evidence/managed/managed-session-events.jsonl`, acceptance status/verdict, individual build/test logs; raw before/after frames under `deliverables/managed-evidence/pet_mvp_session/`. APK `deliverables/qqpet-managed-debug.apk`.

## Reauthorized retry and final bounded verification fix

User explicitly reauthorized three courses and confirmed unlock. Retry `1791262031237`, session `a193fa1e-7757-42b0-84ef-be95129de020`, handled the previous A result without counting it as owned completion. Owned result assertions now count only inputs after the first owned COURSE_STARTED event (`4431dcd`). Health digits initially 90/98/81; conservative energy boundary required one cookie, then fresh ready digits 100/98/82. No soap gesture in this retry. One course started; eight encouragement taps were App-sent. The eighth post-action frame positively matched LEARNING; next pre-action frame did not, causing LEARNING_PAGE_LOST and immediate STOP. Subsequent read-only screenshot still showed learning. Physical delta zero, no PC/ADB supplementary taps.

That evidence does not conclusively prove vertical motion caused the mismatch: the exact failing frame was not saved. `daac818` reuses existing eight-second verifySettled before/after encouragement instead of the single-frame precondition; thresholds remain 0.97 and the finite ten-tap/45-second budget stays. A persistent unknown page still stops without further input. This fix has not yet passed another real course.

Final current package: full unit 31/31, original phone regression 9/9 rerun after this fix, both APKs assembled and installed. Deterministic capture/mismatch gates and forced Hybrid gate previously passed on `a470ce0`; latest encouragement change does not alter those implementations, but their final-package phone rerun remains pending. No successful real PAUSED->RESUMING->RUNNING claimed. Two distinct one-course failed batches remain recorded; neither counts as a continuous three-round acceptance. User has been asked for additional authorization because a further three-course retry increases actual starts beyond the last explicit batch.

Current verdict: REWORK. Next: upon fresh batch authorization and the active course naturally ending, run three uninterrupted formal courses with timely human lock/unlock. No new course is started while awaiting that approval.
