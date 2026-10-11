# -*- coding: utf-8 -*-
"""Build the independent PRECONDITION_RESTORE record directory required by t3 criterion 6.

Creates VirtualDisplay/evidence/slice2/t3-precondition-r2/PRECONDITION_RESTORE/ with:
  setup-taps.json      - every setup tap, all mandated fields, cumulative count
  gate-scores.json     - every ROOM gate run + numeric score + frame/score binding status
  display-identity.txt - runtime display identity (copied from the probe run)
  manifest.txt         - sha256 + size of every artefact referenced by the report
Read-only w.r.t. the device.
"""
import hashlib
import json
import os
import shutil

HERE = os.path.dirname(os.path.abspath(__file__))
DST = os.path.join(HERE, "PRECONDITION_RESTORE")
os.makedirs(DST, exist_ok=True)

DEVICE = {
    "serial": "DKS9K23420020242",
    "model": "FOA-AL00",
    "android_release": "12",
    "sdk": 31,
    "build_display_id": "FOA-AL00 4.2.0.218(C00E182R3P3)",
}
DISPLAY4 = {
    "displayId": 4,
    "name": "Shizuku_VD_1791180519368",
    "type": "VIRTUAL",
    "flags": ["FLAG_PRESENTATION"],
    "owner": "com.android.shell (uid 2000)",
    "width": 720,
    "height": 1600,
    "densityDpi": 320,
    "orientation": 0,
    "real": "720 x 1600",
    "largest_app": "720 x 1600",
    "layerStack": 4,
    "state": "ON",
}
DECLARED = ("init=720x1600 320dpi cur=720x1600 app=720x1600 rng=720x720-1600x1600 "
            "(dumpsys window displays, display 4)")

taps = [
    {
        "step": 1,
        "purpose": "open the unique QQ conversation whose preview explicitly mentions 宠物名画之旅",
        "authorized_category": "(a) the explicit pet-related QQ conversation",
        "semantic_identity": "conversation-list row whose preview band contains the text 宠物名画之旅",
        "target_bounds_on_that_frame": [159, 423, 720, 517],
        "x": 360, "y": 470,
        "cmd": "adb -s DKS9K23420020242 shell input -d 4 tap 360 470",
        "source": "com.android.shell (NOT APP_AUTOMATION)",
        "displayId": 4,
        "capture_provenance": "exec-out screencap -p -d 4 -> step1-before-20261007-132007.png",
        "capture_begin_local": "2026-10-07 13:20:07.038",
        "input_local": "2026-10-07 13:20:36.948",
        "capture_to_input_ms": 29948,
        "latency_within_5000ms": False,
        "latency_violation_note": ("GATE VIOLATION, not merely a disclosed deviation: the contract "
                                   "requires REFUSING to send input once >5000 ms is anticipated. "
                                   "The correct action was to abandon the tap, NOT consume budget, "
                                   "and recapture/re-localize. Instead the input was sent first and "
                                   "the 29948 ms measured afterwards. Sending it at all is the "
                                   "violation; honest after-the-fact reporting does not cure it. "
                                   "The corrected pre-send rejection is under review in t8."),
        "outcome_observed": "QQ conversation opened; the promo card 宠物名画之旅 is present (3 instances)",
        "outcome_frame": "step1-after-20261007-132047.png",
        "outcome_frame_first_observed_local": "2026-10-07 13:20:48.549",
        "outcome_frame_is_first_rendered": False,
        "outcome_frame_note": "first-OBSERVED at +2 s; post-render earlier frames may exist and are not claimed",
        "cumulative_setup_count": 1,
    },
    {
        "step": 2,
        "purpose": "open the positively identified pet-game launch card 宠物名画之旅 in that conversation",
        "authorized_category": "(b) a clearly identified pet-game entry card/control",
        "semantic_identity": ("card instance 1 of 3: title 宠物名画之旅 + subtitle 旅行环线攻略已就位，"
                              "等你一起出发！ + footer 宠物 + Mona-Lisa/sunflower artwork identical to the "
                              "名画之旅 icon in VirtualDisplay/deliverables/gate-c/pet-entry.png; "
                              "dump clickable box [122,280][591,542]"),
        "target_bounds_on_that_frame": [122, 280, 591, 542],
        "x": 356, "y": 411,
        "cmd": "adb -s DKS9K23420020242 shell input -d 4 tap 356 411",
        "source": "com.android.shell (NOT APP_AUTOMATION)",
        "displayId": 4,
        "capture_provenance": "exec-out screencap -p -d 4 -> step2b-before-20261007-141033.png",
        "capture_begin_local": "2026-10-07 14:10:33",
        "input_local": "2026-10-07 14:10:34",
        "capture_to_input_ms": 861,
        "latency_within_5000ms": True,
        "localization": ("template re-match of the dump-validated card patch on the fresh frame: "
                         "MAE=0.000 at shift (0,0); nearest rival 12.00 @ (4,0) -> unique peak"),
        "occlusion_check": ("tap point (356,411) is outside both forbidden floating-sprite overlay "
                            "boxes [183,1093][423,1493] and [303,1093][543,1493]"),
        "outcome_observed": ("QQ pet 名画之旅 H5 activity page (com.tencent.mobileqq/.activity."
                            "QPublicFragmentActivity) -- an activity landing page with 立即装扮 "
                            "affordances, NOT ROOM"),
        "outcome_frame": "step2b-after-4-20261007-141033.png",
        "outcome_frame_first_observed_local": "2026-10-07 14:11:03",
        "outcome_frame_is_first_rendered": False,
        "outcome_frame_note": "first-OBSERVED at +30 s; earlier +2s/+6s/+12s frames retained too",
        "cumulative_setup_count": 2,
    },
    {
        "step": 3,
        "purpose": "use the explicitly identified non-consuming return control to leave the activity page",
        "authorized_category": "(c) an explicit non-consuming close/return-to-home control",
        "semantic_identity": ('content-desc="返回" at [0,110][117,210] (inner ImageView [33,140][71,178]) '
                             "from the validated display-4 dump dump4-page.xml"),
        "target_bounds_on_that_frame": [0, 110, 117, 210],
        "x": 58, "y": 160,
        "cmd": "adb -s DKS9K23420020242 shell input -d 4 tap 58 160",
        "source": "com.android.shell (NOT APP_AUTOMATION)",
        "displayId": 4,
        "capture_provenance": "exec-out screencap -p -d 4 -> step3b-before-20261007-141741.png",
        "capture_begin_local": "2026-10-07 14:17:41",
        "input_local": "2026-10-07 14:17:43",
        "capture_to_input_ms": 1459,
        "latency_within_5000ms": True,
        "localization": ("template re-match of the dump-validated control patch on the fresh frame: "
                         "MAE=0.000 at shift (0,0); nearest rival 6.25 @ (2,-5) -> unique peak"),
        "outcome_observed": "returned to the QQ chat conversation (SplashActivity); NOT the pet home",
        "outcome_frame": "step3b-after-3-20261007-141741.png",
        "outcome_frame_first_observed_local": "2026-10-07 14:18:06",
        "outcome_frame_is_first_rendered": False,
        "cumulative_setup_count": 3,
    },
]

gates = [
    {"run": "attempt1", "local": "2026-10-07 ~12:58-13:00", "score": 0.8209808460421205,
     "threshold": 0.97, "room": False, "dir": "1791349244795",
     "binding": "frame<->score UNKNOWN (gate stores no frame)",
     "numeric_status": "KNOWN (printed in raw device output)"},
    {"run": "attempt2-pre", "local": "2026-10-07 13:59 (gate dir timestamp 1791351562289)",
     "score": 0.7670955882352941, "threshold": 0.97, "room": False, "dir": "1791351560832",
     "binding": "frame<->score UNKNOWN (gate stores no frame)",
     "numeric_status": "KNOWN (printed in raw device output)"},
    {"run": "attempt2-after-step2-card-tap", "local": "2026-10-07 14:16:17",
     "score": 0.8390681735657226, "threshold": 0.97, "room": False, "dir": "1791353772747",
     "binding": "frame<->score UNKNOWN (gate stores no frame)",
     "numeric_status": "KNOWN (printed in raw device output)"},
    {"run": "attempt2-after-step3-return-tap", "local": "2026-10-07 14:18:22",
     "score": 0.770218318809005, "threshold": 0.97, "room": False, "dir": "1791353897549",
     "binding": "frame<->score UNKNOWN (gate stores no frame)",
     "numeric_status": "KNOWN (printed in raw device output)"},
]

record = {
    "label": "PRECONDITION_RESTORE attempt-2 (t3 revision 2)",
    "device": DEVICE,
    "display": DISPLAY4,
    "display_declared_by_window_manager": DECLARED,
    "gate": {
        "test": "com.ynk.virtualdisplay.PetRecoveryGateTest#faultGate",
        "args": "recovery_gate capture -e display_id 4 -e reset_home no",
        "runner": "com.ynk.virtualdisplay.test/androidx.test.runner.AndroidJUnitRunner",
        "cmd": ("adb -s DKS9K23420020242 shell am instrument -w -r "
                "-e class com.ynk.virtualdisplay.PetRecoveryGateTest#faultGate "
                "-e recovery_gate capture -e display_id 4 -e reset_home no "
                "com.ynk.virtualdisplay.test/androidx.test.runner.AndroidJUnitRunner"),
        "threshold": 0.97,
        "public_matcher": "com.ynk.virtualdisplay.automation.TemplateMatcher.match",
        "room_probe": "HOME Rect(320,1340,405,1465) pet_experimental/room.png",
        "game_input": 0,
        "setup_taps": 0,
    },
    "setup_tap_budget": {"authorized_max": 3, "used": 3, "remaining": 0},
    "setup_taps": taps,
    "gate_runs": gates,
    "final_state": {
        "room_confirmed": False,
        "final_room_score": 0.770218318809005,
        "final_screen": "QQ chat conversation (com.tencent.mobileqq/.activity.SplashActivity)",
        "managed_batch_started": False,
        "human_pause_performed": False,
        "automatic_repeat_batch": False,
    },
    "physical_touch_delta": {
        "value": "UNKNOWN",
        "why": ("No counting baseline and no end value were taken for this setup phase, so a delta "
                "cannot be computed. The earlier inference 'delta = 0' from display 4 having "
                "port=<none> is WITHDRAWN: a missing registered input port is not a touch count."),
        "what_is_established": ("Every recorded shell target in this setup phase used -d 4 "
                                "(display 4); see each tap's displayId field. This says nothing "
                                "about touches on the physical display (displayId 0)."),
        "batch_criterion_touched": False,
        "batch_criterion_note": ("The delta==0 criterion belongs to the managed batch, which was "
                                 "never started."),
    },
    "reachability_claims_withdrawn": {
        "note": ("The earlier 'structurally impossible to reach ROOM' and 'must be tapped by the "
                 "user' claims were overreach and are withdrawn. What is supported is only that "
                 "the TESTED route failed."),
        "withdrawn": [
            "structural impossibility of reaching ROOM",
            "it must be the user who hand-taps the entry",
            "QQ has no pet URI scheme (this was a regex artifact: 0 matches for scheme=\"...\" "
            "vs 34 schemes under the accepting regex Scheme:\\s*\"...\")",
            "the pet/game-ish components prove no native pet entry exists (they are a "
            "classification impression, not a proof; exported-component counts are unresolved)",
        ],
        "unresolved_and_untested": [
            "the clickable parent LinearLayout [129,175][371,199] of the non-clickable "
            "学习中 TextView -- never exercised",
            "the title-bar ImageView rid=1fv [568,136][616,184], which IS clickable and DOES "
            "carry a resource ID -- never resolved",
            "whether any native QQ entry into the pet product exists at all",
        ],
    },
    "attempt1_separately_preserved": {
        "location": "VirtualDisplay/evidence/slice2/t3-precondition/",
        "rewritten_as_success": False,
        "cat_interaction_tap_disclosed": True,
    },
}
open(os.path.join(DST, "setup-taps.json"), "w", encoding="utf-8").write(
    json.dumps(record, ensure_ascii=False, indent=1) + "\n")
open(os.path.join(DST, "gate-scores.json"), "w", encoding="utf-8").write(
    json.dumps({"threshold": 0.97, "runs": gates, "final": 0.770218318809005,
                "note": ("All four numbers are KNOWN numeric values printed in raw device output. "
                         "Only the frame<->score association is UNKNOWN, because the gate stores no "
                         "frame; that is distinct from an unknown numeric value.")},
               ensure_ascii=False, indent=1) + "\n")
shutil.copyfile(os.path.join(HERE, "display-identity.txt"),
                os.path.join(DST, "display-identity.txt"))

# manifest of every artefact referenced
names = sorted(f for f in os.listdir(HERE) if os.path.isfile(os.path.join(HERE, f)))
lines = []
for f in names:
    p = os.path.join(HERE, f)
    h = hashlib.sha256(open(p, "rb").read()).hexdigest()
    lines.append("%s  %10d  %s" % (h, os.path.getsize(p), f))
for f in sorted(os.listdir(DST)):
    p = os.path.join(DST, f)
    if os.path.isfile(p) and f != "manifest.txt":
        h = hashlib.sha256(open(p, "rb").read()).hexdigest()
        lines.append("%s  %10d  PRECONDITION_RESTORE/%s" % (h, os.path.getsize(p), f))
open(os.path.join(DST, "manifest.txt"), "w", encoding="utf-8").write("\n".join(lines) + "\n")
print("wrote %s" % DST)
print("files: %d artefacts in manifest" % len(lines))
for f in sorted(os.listdir(DST)):
    print("  PRECONDITION_RESTORE/%s" % f)
