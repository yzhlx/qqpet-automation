# -*- coding: utf-8 -*-
"""Run the installed, reviewed read-only ROOM gate (PetRecoveryGateTest#faultGate,
recovery_gate=capture, display_id=4, reset_home=no) and report the raw public-matcher
score. Takes NO game input and NO setup tap (reset_home=no skips PetNavigation.initialise).

Usage: s2_gate.py <label>
Writes gate-<label>-output.txt and gate-<label>-verdict.txt.
"""
import os
import subprocess
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
ADB = r"C:\Users\lu158\Desktop\dsh插件\养宠自动化\tools\android-build\sdk\platform-tools\adb.exe"
DEV = "DKS9K23420020242"
label = sys.argv[1] if len(sys.argv) > 1 else "now"


def sh(*a):
    return subprocess.run([ADB, "-s", DEV, "shell"] + list(a),
                          capture_output=True).stdout.decode("utf-8", "replace")


before = set(sh("ls", "/sdcard/Android/data/com.ynk.virtualdisplay/files/pet_recovery_gate").split())
t0 = time.time()
cmd = ["am", "instrument", "-w", "-r",
       "-e", "class", "com.ynk.virtualdisplay.PetRecoveryGateTest#faultGate",
       "-e", "recovery_gate", "capture", "-e", "display_id", "4", "-e", "reset_home", "no",
       "com.ynk.virtualdisplay.test/androidx.test.runner.AndroidJUnitRunner"]
out = sh(*cmd)
t1 = time.time()

open(os.path.join(HERE, "gate-%s-output.txt" % label), "w", encoding="utf-8").write(out)
print("=== gate label=%s  local=%s  took=%.1fs ==="
      % (label, time.strftime("%H:%M:%S"), t1 - t0))
for line in out.splitlines():
    s = line.strip()
    if any(k in s for k in ("INITIAL_PAGE_UNKNOWN", "Tests run", "OK (", "FAILURES",
                            "Exception", "verdict", "PASS", "STOPPED", "check", "Cursor")):
        print("  " + s[:240])

after = set(sh("ls", "/sdcard/Android/data/com.ynk.virtualdisplay/files/pet_recovery_gate").split())
new = sorted(after - before)
print("  new gate dirs: %s" % new)
lines = ["label=%s local=%s took=%.1fs" % (label, time.strftime("%Y-%m-%d %H:%M:%S"), t1 - t0)]
for d in new:
    d = d.strip()
    if not d:
        continue
    v = sh("cat", "/sdcard/Android/data/com.ynk.virtualdisplay/files/pet_recovery_gate/%s/verdict.json" % d)
    s = sh("cat", "/sdcard/Android/data/com.ynk.virtualdisplay/files/pet_recovery_gate/%s/status.json" % d)
    print("  dir=%s" % d)
    print("    verdict.json=%s" % v.strip()[:400])
    print("    status.json=%s" % s.strip()[:200])
    lines.append("dir=%s\n  verdict=%s\n  status=%s" % (d, v.strip(), s.strip()))
open(os.path.join(HERE, "gate-%s-verdict.txt" % label), "w", encoding="utf-8").write("\n".join(lines) + "\n")
import re
m = re.search(r"INITIAL_PAGE_UNKNOWN:([0-9.]+)", out)
print("  => ROOM score = %s  (threshold 0.97)  ROOM=%s"
      % (m.group(1) if m else "?", "YES" if m and float(m.group(1)) >= 0.97 else "NO"))
