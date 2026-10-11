# -*- coding: utf-8 -*-
"""Device helper: dump UI on display 4, screencap display 4, list adb devices.
Usage:
  py dev.py dump [out.xml]
  py dev.py shot <out.png>
  py dev.py info
"""
import os
import re
import subprocess
import sys
import time

ADB = r"C:\Users\lu158\Desktop\dsh插件\养宠自动化\tools\android-build\sdk\platform-tools\adb.exe"
DEV = "DKS9K23420020242"


def sh(args, binary=False):
    p = subprocess.run([ADB, "-s", DEV] + args, capture_output=True)
    return p.stdout if binary else p.stdout.decode("utf-8", "replace")


def main():
    cmd = sys.argv[1] if len(sys.argv) > 1 else "info"
    if cmd == "dump":
        out = sys.argv[2] if len(sys.argv) > 2 else "dump4.xml"
        sh(["shell", "rm", "-f", "/sdcard/_d4.xml"])
        err = sh(["shell", "uiautomator", "dump", "--display", "4", "/sdcard/_d4.xml"])
        xml = sh(["shell", "cat", "/sdcard/_d4.xml"])
        i = xml.find("<?xml")
        if i < 0:
            print("DUMP FAILED: %r" % err[:300])
            print("stdout head: %r" % xml[:300])
            return 1
        xml = xml[i:]
        open(out, "w", encoding="utf-8").write(xml)
        root_tag = re.search(r"bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"", xml)
        pkgs = sorted(set(re.findall(r"package=\"([^\"]+)\"", xml)))
        nodes = xml.count("<node")
        print("wrote %s  bytes=%d nodes=%d" % (out, len(xml), nodes))
        print("root bounds = %s" % (root_tag.group(0) if root_tag else None))
        print("packages = %s" % pkgs)
        print("dump stderr = %r" % err.strip()[:200])
        return 0
    if cmd == "shot":
        out = sys.argv[2] if len(sys.argv) > 2 else "shot.png"
        t0 = time.time()
        sh(["shell", "screencap", "-p", "-d", "4", "/sdcard/_s4.png"])
        data = sh(["exec-out", "cat", "/sdcard/_s4.png"], binary=True)
        open(out, "wb").write(data)
        print("wrote %s bytes=%d elapsed=%.2fs" % (out, len(data), time.time() - t0))
        return 0
    print(sh(["shell", "dumpsys", "activity", "activities"]).split("Display #4")[-1][:600])
    return 0


if __name__ == "__main__":
    sys.exit(main())
