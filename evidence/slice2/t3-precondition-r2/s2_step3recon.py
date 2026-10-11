# -*- coding: utf-8 -*-
"""t3 attempt-2 STEP 3 read-only reconnaissance (NO input).

Goal: decide whether the 名画之旅 activity page's 返回 control returns to the pet home
(ROOM) or merely to the QQ chat, before spending the last authorized setup tap.

Read-only: activity/window state on display 4 + logcat around the card tap at 14:10:34.
Writes step3-recon.txt.
"""
import os
import re
import subprocess
import time

HERE = os.path.dirname(os.path.abspath(__file__))
ADB = r"C:\Users\lu158\Desktop\dsh插件\养宠自动化\tools\android-build\sdk\platform-tools\adb.exe"
DEV = "DKS9K23420020242"
OUT = []


def log(s):
    OUT.append(s)
    print(s, flush=True)


def sh(*a):
    p = subprocess.run([ADB, "-s", DEV, "shell"] + list(a), capture_output=True)
    return p.stdout.decode("utf-8", "replace")


log("=== 1. display-4 focused activity / top activity ===")
for key in ("mCurrentFocus", "mFocusedApp", "topResumedActivity"):
    txt = sh("dumpsys", "window", "displays")
    break
for m in re.finditer(r"Display: mDisplayId=(\d+)[\s\S]{0,600}?mCurrentFocus=([^\n]*)", txt):
    log("  display %s focus=%s" % (m.group(1), m.group(2).strip()))

log("")
log("=== 2. activities on display 4 (dumpsys activity activities) ===")
act = sh("dumpsys", "activity", "activities")
for m in re.finditer(r"ActivityRecord\{[^}]*u0 ([^ \n]+) t\d+[^}]*\}", act):
    pass
blocks = re.split(r"\n\s+", act)
keep = [b for b in blocks if "displayId" in b or "mDisplayId" in b or "ActivityRecord{" in b]
for b in keep[:60]:
    if re.search(r"displayId=|mDisplayId=", b):
        log("  " + b.strip()[:200])

log("")
log("=== 3. which activities mention display 4 ===")
lines = act.splitlines()
for i, l in enumerate(lines):
    if "displayId" in l or "mDisplayId" in l:
        log("  L%d %s" % (i, l.strip()[:180]))
        for j in range(max(0, i - 3), i):
            if "ActivityRecord{" in lines[j]:
                log("      ctx L%d %s" % (j, lines[j].strip()[:180]))

log("")
log("=== 4. logcat around the 14:10:34 card tap (activity transitions) ===")
lc = sh("logcat", "-d", "-v", "time", "-t", "4000")
pat = re.compile(r"(Displayed|START u0|ActivityTaskManager|pet|Pet|QZone|cmshow|mini|Mini|名画|tianshu|QQP%E5%AE%A0)")
hits = [l for l in lc.splitlines() if pat.search(l)]
log("  total logcat lines=%d hits=%d" % (len(lc.splitlines()), len(hits)))
for l in hits[-60:]:
    log("  " + l.strip()[:220])

log("")
log("=== 5. current top activity of the QQ task stack ===")
act2 = sh("dumpsys", "activity", "lru")
for l in act2.splitlines()[:40]:
    if "Activity" in l or "com.tencent" in l:
        log("  " + l.strip()[:180])

open(os.path.join(HERE, "step3-recon.txt"), "w", encoding="utf-8").write("\n".join(OUT) + "\n")
log("wrote step3-recon.txt")
