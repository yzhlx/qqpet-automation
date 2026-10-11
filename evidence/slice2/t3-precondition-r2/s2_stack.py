# -*- coding: utf-8 -*-
"""t3 attempt-2 STEP 3 decisive read-only check (NO input).

Question: does the 名画之旅 QPublicFragmentActivity sit on top of a PET page (so that 返回
returns to the pet home / ROOM), or on top of the QQ chat (so that 返回 just goes back to
the chat and setup would be wasted)?

Read-only. Writes step3-stack.txt.
"""
import os
import re
import subprocess

HERE = os.path.dirname(os.path.abspath(__file__))
ADB = r"C:\Users\lu158\Desktop\dsh插件\养宠自动化\tools\android-build\sdk\platform-tools\adb.exe"
DEV = "DKS9K23420020242"
OUT = []


def log(s):
    OUT.append(s)
    print(s, flush=True)


def sh(*a):
    return subprocess.run([ADB, "-s", DEV, "shell"] + list(a),
                          capture_output=True).stdout.decode("utf-8", "replace")


act = sh("dumpsys", "activity", "activities")

# All ActivityRecords in task 2230 (or any task on display 4), in stack order.
log("=== TaskRecord t2230 / display-4 task contents ===")
m = re.search(r"TaskRecord\{[^}]*#2230[^\n]*\n", act)
if not m:
    m = re.search(r"Task\{[^\n]*#2230[^\n]*\n", act)
if m:
    log("  header: " + m.group(0).strip()[:200])
    start = m.end()
    seg = act[start:start + 8000]
    for line in seg.splitlines():
        s = line.strip()
        if re.match(r"\* ActivityRecord\{|ActivityRecord\{[0-9a-f]+ u0", s) or "Hist #" in s:
            log("    " + s[:200])
else:
    log("  task header not matched; dumping all ActivityRecord lines with task ids")

log("")
log("=== every ActivityRecord mentioning t2230 ===")
for line in act.splitlines():
    if "t2230" in line:
        log("  " + line.strip()[:200])

log("")
log("=== all ActivityRecords, with task id and state (first 80) ===")
seen = set()
for mm in re.finditer(r"ActivityRecord\{([0-9a-f]+) u0 ([^ ]+) t(\d+)([^}]*)\}", act):
    key = (mm.group(1), mm.group(2), mm.group(3))
    if key in seen:
        continue
    seen.add(key)
    if len(seen) <= 80:
        log("  tid=%s task=%s %s%s" % (mm.group(1), mm.group(3), mm.group(2), mm.group(4)[:60]))

log("")
log("=== windows on display 4 ===")
w = sh("dumpsys", "window", "windows")
cur = None
for line in w.splitlines():
    if "Window{" in line:
        cur = line.strip()
    if "mDisplayId=" in line and cur:
        d = re.search(r"mDisplayId=(\d+)", line)
        if d and d.group(1) == "4":
            log("  " + cur[:170])
            log("      " + line.strip()[:150])
        cur = None

open(os.path.join(HERE, "step3-stack.txt"), "w", encoding="utf-8").write("\n".join(OUT) + "\n")
log("wrote step3-stack.txt")
