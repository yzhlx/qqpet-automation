# -*- coding: utf-8 -*-
"""Identify the clickable child that sits inside card C, and validate every box
against a FRESH frame by drawing them over it.

Read-only except for one screencap. No input.
"""
import os
import re
import subprocess
import xml.etree.ElementTree as ET

from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
ADB = r"C:\Users\lu158\Desktop\dsh插件\养宠自动化\tools\android-build\sdk\platform-tools\adb.exe"
DEV = "DKS9K23420020242"
BR = re.compile(r"\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]")
OUT = []

xml = open(os.path.join(HERE, "rehearsal.xml"), encoding="utf-8", errors="replace").read()
xml = xml[xml.index("<?xml"):]
root = ET.fromstring(xml)
nodes = list(root.iter("node"))
parent = {}
for p in nodes:
    for c in p:
        parent[c] = p


def b_of(n):
    m = BR.match(n.get("bounds") or "")
    return tuple(int(g) for g in m.groups()) if m else None


def line(n):
    rid = (n.get("resource-id") or "").replace("com.tencent.mobileqq:id/", "")
    return "%-14s %-22s click=%-5s rid=%-12s desc=%r text=%r" % (
        (n.get("class") or "").split(".")[-1], n.get("bounds"), n.get("clickable"),
        rid or "-", n.get("content-desc") or "", n.get("text") or "")


# 1. what text lives in [303,1170][543,1329] ?
OUT.append("=== nodes intersecting the clickable box [303,1170][543,1329] ===")
BOX = (303, 1170, 543, 1329)
for n in nodes:
    b = b_of(n)
    if not b:
        continue
    if b[0] < BOX[2] and b[2] > BOX[0] and b[1] < BOX[3] and b[3] > BOX[1]:
        OUT.append("  " + line(n))

# 2. any node whose text mentions the bubble words
OUT.append("")
OUT.append("=== nodes whose text/desc mention 隐藏 / 职业 / 解锁 ===")
for n in nodes:
    blob = (n.get("text") or "") + (n.get("content-desc") or "")
    if any(w in blob for w in ("\u9690\u85cf", "\u804c\u4e1a", "\u89e3\u9501")):
        OUT.append("  " + line(n))

# 3. identity + chain of the full-area clickable FrameLayout
OUT.append("")
OUT.append("=== full-area clickable FrameLayout [0,210][720,1600] ===")
for n in nodes:
    if b_of(n) == (0, 210, 720, 1600) and n.get("clickable") == "true":
        OUT.append("  SELF " + line(n))
        p = parent.get(n)
        while p is not None:
            sibs = list(parent[p]) if p in parent else []
            pos = sibs.index(p) if p in sibs else -1
            OUT.append("    parent childOrder=%d/%-2d %s" % (pos, len(sibs), line(p)))
            p = parent.get(p)

# 4. validate against a FRESH frame
frame_path = os.path.join(HERE, "fresh-validate.png")
raw = subprocess.run([ADB, "-s", DEV, "exec-out", "screencap", "-p", "-d", "4"],
                     capture_output=True).stdout
ok = raw[:8] == b"\x89PNG\r\n\x1a\n"
open(frame_path, "wb").write(raw)
OUT.append("")
OUT.append("=== fresh frame ===")
OUT.append("  exec-out bytes=%d png_signature=%s" % (len(raw), ok))
im = Image.open(frame_path).convert("RGB")
OUT.append("  size=%s" % (im.size,))

prev = Image.open(os.path.join(HERE, "state-now.png")).convert("RGB")
same = (im.size == prev.size) and not Image.new("1", im.size, 0).point(lambda v: 0)
OUT.append("  identical_to_state-now=%s" % same)

d = ImageDraw.Draw(im)
BOXES = [(122, 280, 590, 541, (255, 0, 0)), (122, 680, 590, 941, (255, 0, 0)),
         (122, 1080, 590, 1341, (255, 0, 0)),
         (303, 1170, 543, 1329, (0, 0, 255)), (183, 1093, 423, 1493, (255, 0, 255)),
         (303, 1093, 543, 1493, (255, 0, 255))]
for x1, y1, x2, y2, col in BOXES:
    d.rectangle([x1, y1, x2, y2], outline=col, width=2)
d.rectangle([247, 1112, 261, 1138], outline=(0, 200, 0), width=3)
im.save(os.path.join(HERE, "validate-overlay.png"))

# 5. pixel diff in the card-C region between fresh and the earlier frame
import itertools
reg = (122, 1080, 591, 1342)
a = prev.crop(reg)
b = im.crop(reg)
diff = sum(1 for pa, pb in zip(a.getdata(), b.getdata()) if pa != pb)
OUT.append("  card-C region %s differing pixels fresh-vs-state-now = %d / %d"
           % (reg, diff, a.width * a.height))

txt = "\n".join(OUT) + "\n"
open(os.path.join(HERE, "geom-validate.txt"), "w", encoding="utf-8").write(txt)
print(txt)
