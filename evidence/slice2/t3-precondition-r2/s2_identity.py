# -*- coding: utf-8 -*-
"""Collect the exact runtime display-4 identity required by t3 criterion 6.
Read-only. Writes display-identity.txt.
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


log("=== build / device ===")
for p in ("ro.product.model", "ro.build.version.release", "ro.build.version.sdk",
          "ro.build.display.id"):
    log("  %s=%s" % (p, sh("getprop", p).strip()))
log("  serial=%s" % subprocess.run([ADB, "-s", DEV, "get-serialno"],
                                  capture_output=True).stdout.decode().strip())

log("")
log("=== dumpsys display: DisplayDeviceInfo ===")
d = sh("dumpsys", "display")
for m in re.finditer(r'DisplayDeviceInfo\{"[^"]+"[^\n]*', d):
    line = m.group(0)
    if "displayId 4" in line or '"内置屏幕"' in line or 'displayId 0,' in line:
        log("  " + line.strip()[:400])

log("")
log("=== dumpsys window displays: display 4 block ===")
w = sh("dumpsys", "window", "displays")
blocks = re.split(r"\n\s*Display: ", w)
for b in blocks:
    if b.startswith("mDisplayId=4"):
        for line in b.splitlines():
            s = line.strip()
            if any(k in s for k in ("init=", "cur=", "app=", "mDisplayId=4", "density",
                                    "rotation", "DisplayInfo", "DisplayFrames", "mBaseDisplayInfo")):
                log("  " + s[:200])
        break

log("")
log("=== wm size / density per display ===")
for d_id in ("0", "4"):
    log("  --- display %s ---" % d_id)
    for sub in (["wm", "size", "--display", d_id], ["wm", "density", "--display", d_id]):
        out = sh(*sub).strip()
        log("    %s -> %s" % (" ".join(sub), out.replace("\n", " | ")[:200]))

log("")
log("=== physical input device -> display binding ===")
for line in sh("dumpsys", "input").splitlines():
    if "dispId" in line or "state(-1)" in line:
        log("  " + line.strip()[:200])

open(os.path.join(HERE, "display-identity.txt"), "w", encoding="utf-8").write("\n".join(OUT) + "\n")
log("wrote display-identity.txt")
