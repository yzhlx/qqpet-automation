# -*- coding: utf-8 -*-
"""Read-only: (a) enumerate the real window stack on display 4, (b) zoom the chat
title-bar icons and the card/overlay overlap region on a fresh frame.

No input, no taps. Writes win4.txt and zoom2-*.png.
"""
import os
import re
import subprocess

from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
ADB = r"C:\Users\lu158\Desktop\dsh插件\养宠自动化\tools\android-build\sdk\platform-tools\adb.exe"
DEV = "DKS9K23420020242"


def sh(args):
    return subprocess.run([ADB, "-s", DEV] + args, capture_output=True).stdout.decode("utf-8", "replace")


out = []

# ---- display 4 presence / state ----
d = sh(["shell", "dumpsys", "display"])
for block in d.split("Display "):
    if re.match(r"^\d+:", block.strip()):
        out.append("Display " + block.strip().splitlines()[0])
        for l in block.splitlines():
            if re.search(r"mDisplayId=|DisplayDeviceInfo|state |FLAG_|rotation|mState", l):
                out.append("    " + l.strip()[:150])

# ---- window stack per display ----
w = sh(["shell", "dumpsys", "window", "windows"])
blocks = re.split(r"\n\s*Window #", w)
out.append("")
out.append("total window blocks: %d" % (len(blocks) - 1))
for b in blocks[1:]:
    head = b.splitlines()[0] if b.strip() else ""
    if "mDisplayId=4" in b or "Shizuku_VD" in b:
        m = re.search(r"mDisplayId=(\d+)", b)
        title = re.search(r"\n\s*mAttrs=\{.*?title=([^\}]*)\}", b, re.S)
        uid = re.search(r"mOwnerUid=(\d+)", b)
        onscreen = re.search(r"isOnScreen=(\w+)", b)
        vis = re.search(r"isVisible=(\w+)", b)
        frame = re.search(r"mFrame=\[([^\]]*)\]", b)
        out.append("  --- %s" % head.strip()[:110])
        out.append("      mDisplayId=%s mOwnerUid=%s isOnScreen=%s isVisible=%s"
                   % (m.group(1) if m else "?", uid.group(1) if uid else "?",
                      onscreen.group(1) if onscreen else "?", vis.group(1) if vis else "?"))
        if title:
            out.append("      title=%s" % title.group(1).strip()[:90])
        if frame:
            out.append("      mFrame=[%s]" % frame.group(1))

# which display has which windows
per = {}
for b in blocks[1:]:
    m = re.search(r"mDisplayId=(\d+)", b)
    if m:
        per.setdefault(m.group(1), []).append(b.splitlines()[0].strip()[:60])
out.append("")
out.append("=== windows per display ===")
for k in sorted(per):
    out.append("  display %s: %d window(s)" % (k, len(per[k])))
    for h in per[k]:
        out.append("      %s" % h)

open(os.path.join(HERE, "win4.txt"), "w", encoding="utf-8").write("\n".join(out) + "\n")

# ---- zooms on the FRESH frame ----
src = os.path.join(HERE, "state-now.png")
im = Image.open(src).convert("RGB")
crops = {
    "zoom2-titlebar.png": (0, 100, 720, 235),
    "zoom2-topright.png": (540, 110, 710, 200),
    "zoom2-overlap.png": (100, 1060, 620, 1420),
    "zoom2-cardC.png": (110, 1070, 600, 1400),
}
for name, box in crops.items():
    c = im.crop(box)
    k = max(1, int(680 / c.width))
    c = c.resize((c.width * k, c.height * k), Image.NEAREST)
    c.save(os.path.join(HERE, name))
    print("%-22s src=%s x%d -> %s" % (name, box, k, c.size))

print("\n".join(out[:80]))
