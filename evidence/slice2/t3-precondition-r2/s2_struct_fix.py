# -*- coding: utf-8 -*-
"""READ-ONLY correction of the scheme-count error in step3-structural.txt.

The earlier s2_struct.py used the regex scheme="([^"]+)" against `dumpsys package`,
but the real line format is:   Scheme: "xxx"
so it necessarily matched 0 and produced a false "no schemes" conclusion.

This script re-derived the facts with an accepting regex. It sends NO input and
makes no state change; it only reads `dumpsys package`.
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


p = sh("dumpsys", "package", "com.tencent.mobileqq")

log("=== CORRECTION: scheme parsing ===")
bad = re.findall(r'scheme="([^"]+)"', p)
good = re.findall(r'Scheme:\s*"([^"]+)"', p)
log("  regex scheme=\"...\"        -> %d matches   (the WRONG regex used before)" % len(bad))
log("  regex Scheme:\\s*\"...\"     -> %d matches   (accepting regex)" % len(good))
uniq = sorted(set(good))
log("  distinct schemes: %d" % len(uniq))
log("  all distinct schemes: %s" % ", ".join(uniq))
petish = [s for s in uniq if re.search(r"pet|nourish|qqpet|dress|study", s, re.I)]
log("  pet-ish schemes: %s" % (petish or "NONE (with an ACCEPTING regex)"))

log("")
log("=== exported activities / aliases mentioning pet-ish words (accepting regex) ===")
blocks = re.split(r"\n(?=\s{4}(?:Activity|Activity Alias|Service|Receiver|Provider)\b)", p)
hits = []
for b in blocks:
    first = b.strip().splitlines()[0] if b.strip() else ""
    if re.search(r"pet|nourish|dress|qqpet|study", first, re.I):
        hits.append(first.strip()[:160])
log("  matches: %d" % len(hits))
for h in hits:
    log("    " + h)

log("")
log("=== how many Activity/Activity Alias blocks exist at all ===")
n_act = len(re.findall(r"\n\s{4}Activity:", p))
n_alias = len(re.findall(r"\n\s{4}Activity Alias:", p))
log("  Activity blocks: %d ; Activity Alias blocks: %d" % (n_act, n_alias))

log("")
log("=== SCOPE NOTE ===")
log("  This only corrects a parsing error and reports package metadata.")
log("  It does NOT establish that no native QQ pet entry exists, and it does NOT")
log("  test any clickable parent container. Those remain UNRESOLVED.")

open(os.path.join(HERE, "step3-structural-CORRECTION.txt"), "w", encoding="utf-8").write(
    "\n".join(OUT) + "\n")
print("wrote step3-structural-CORRECTION.txt")
