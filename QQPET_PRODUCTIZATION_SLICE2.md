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
