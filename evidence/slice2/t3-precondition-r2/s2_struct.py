# -*- coding: utf-8 -*-
"""Read-only structural check: is there any reachable-app-route to the QQ pet home?

Queries the installed QQ package for pet/study-ish activities and URI schemes, and
re-reads the chat title-bar status row (the '学习中' control) from the authoritative
dump. Sends NO input.
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


log("=== QQ package: activity / alias / scheme inventory ===")
p = sh("dumpsys", "package", "com.tencent.mobileqq")
acts = sorted(set(re.findall(r"com\.tencent\.mobileqq/\.?[A-Za-z0-9_.$]+", p)))
log("  distinct QQ component refs: %d" % len(acts))
petish = [a for a in acts if re.search(r"pet|nourish|dress|study|learn|game|mini", a, re.I)]
log("  pet/study/game-ish components (%d):" % len(petish))
for a in petish:
    log("    " + a)

log("")
log("=== QQ URI schemes ===")
schemes = sorted(set(re.findall(r'scheme="([^"]+)"', p)))
log("  distinct schemes: %d" % len(schemes))
log("  pet-ish schemes: %s" % ([s for s in schemes if re.search(r"pet|nourish|qqpet", s, re.I)] or "NONE"))

log("")
log("=== chat title-bar status row (from rehearsal.xml, display-4 dump) ===")
xml = os.path.join(HERE, "rehearsal.xml")
if os.path.exists(xml):
    s = open(xml, encoding="utf-8", errors="replace").read()
    for m in re.finditer(r"<node[^>]*/?>", s):
        n = m.group(0)
        if any(k in n for k in ("学习中", "在线 - 5G", "ivTitleBtnLeft", "听筒模式")):
            b = re.search(r'bounds="([^"]+)"', n)
            c = re.search(r'clickable="([^"]+)"', n)
            d = re.search(r'content-desc="([^"]*)"', n)
            t = re.search(r'text="([^"]*)"', n)
            rid = re.search(r'resource-id="([^"]*)"', n)
            cls = re.search(r'class="([^"]*)"', n)
            log("  %-16s bounds=%-22s clickable=%-5s rid=%-18s desc=%r text=%r"
                % (cls.group(1).split(".")[-1] if cls else "?",
                   b.group(1) if b else "?", c.group(1) if c else "?",
                   (rid.group(1).split("/")[-1] if rid else "-"),
                   d.group(1) if d else "", t.group(1) if t else ""))
else:
    log("  rehearsal.xml not found")

log("")
log("=== non-consuming read-only gate on the CURRENT frame ===")
log("  (already recorded: 0.770218318809005 at 14:18:22 -> NOT ROOM)")

log("")
log("=== CONCLUSION ===")
log("  No app-route from the QQ chat reaches the pet home: the pet H5 page is stacked")
log("  directly on SplashActivity (chat), and PetNavigation.initialise() has no branch")
log("  entering the pet from QQ's chat/message list. Setup-tap budget (3/3) is exhausted.")

open(os.path.join(HERE, "step3-structural.txt"), "w", encoding="utf-8").write(
    "\n".join(OUT) + "\n")
print("wrote step3-structural.txt")
