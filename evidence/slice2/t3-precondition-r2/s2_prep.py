# -*- coding: utf-8 -*-
"""Step-2 geometry: fresh dump + tree depth + annotated boxes to find a card tap point
that is provably clear of the floating Q宠 overlay children."""
import os
import re
import subprocess
import xml.etree.ElementTree as ET
from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
ADB = r"C:\Users\lu158\Desktop\dsh插件\养宠自动化\tools\android-build\sdk\platform-tools\adb.exe"
DEV = "DKS9K23420020242"
BR = re.compile(r"\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]")


def sh(args):
    return subprocess.run([ADB, "-s", DEV] + args, capture_output=True).stdout


def b_of(n):
    m = BR.match(n.get("bounds") or "")
    return tuple(int(g) for g in m.groups()) if m else None


# 1) fresh dump
sh(["shell", "uiautomator", "dump", "--display", "4", "/sdcard/s2.xml"])
xml = sh(["shell", "cat", "/sdcard/s2.xml"]).decode("utf-8", "replace")
xml = xml[xml.index("<?xml"):]
open(os.path.join(HERE, "step2-now.xml"), "w", encoding="utf-8").write(xml)
root = ET.fromstring(xml)

# 2) tree with depth, and parent links
lines = ["=== tree with depth (nodes overlapping card-C band y 1060..1387) ==="]
parent = {}
for p in root.iter("node"):
    for c in p:
        parent[c] = p


def depth(n):
    d = 0
    while n in parent:
        n = parent[n]
        d += 1
    return d


def desc(n):
    b = b_of(n)
    return "%-16s %-22s click=%-5s desc=%r text=%r" % (
        (n.get("class") or "").split(".")[-1], n.get("bounds"), n.get("clickable"),
        (n.get("content-desc") or "")[:26], (n.get("text") or "")[:22])


def overlaps(n, lo, hi):
    b = b_of(n)
    return b and not (b[3] <= lo or b[1] >= hi)


for n in root.iter("node"):
    b = b_of(n)
    if b and b[3] > 1040 and b[1] < 1500 and n.get("class"):
        lines.append("%s%-3d %s" % ("  " * depth(n), depth(n), desc(n)))

# 3) z-order: document order among the top overlay container and the list container
lines.append("")
lines.append("=== document order: which subtree contains the sprites vs the list ===")
nodes = list(root.iter("node"))
sprite_i = [i for i, n in enumerate(nodes) if (n.get("content-desc") or "").startswith("Q宠")]
list_i = [i for i, n in enumerate(nodes) if (n.get("resource-id") or "").endswith(":id/mh2")]
lines.append("  sprite nodes at doc index: %s" % sprite_i)
lines.append("  list container mh2 at doc index: %s" % list_i)
for i in sprite_i + list_i:
    n = nodes[i]
    chain = []
    p = n
    while p in parent:
        p = parent[p]
        chain.append((p.get("resource-id") or "-").replace("com.tencent.mobileqq:id/", ""))
    lines.append("  idx=%-3d %-22s ancestors(top->) = %s" % (i, b_of(n), " < ".join(chain[:6])))

# 4) candidate tap points inside the newest card's clickable box, avoiding overlay children
CARD = (122, 1080, 590, 1341)
OVERLAYS = [(183, 1093, 423, 1493), (303, 1093, 543, 1493)]   # Q宠-1000001 / Q宠-1000006
CHILDREN = [(303, 1170, 543, 1329)]                            # clickable child of Q宠-1000006
TITLE = (146, 1104, 566, 1144)


def inside(pt, r):
    return r[0] <= pt[0] <= r[2] and r[1] <= pt[1] <= r[3]


def hits_any(pt, rects):
    return any(inside(pt, r) for r in rects)


cands = []
# a) band of the card above the overlay clickable child
for y in range(1081, 1170, 2):
    for x in range(123, 590, 2):
        pt = (x, y)
        if hits_any(pt, CHILDREN):
            continue
        cands.append(pt)
# b) left strip of the card, left of both overlay boxes
for y in range(1081, 1341, 2):
    for x in range(123, 183, 2):
        pt = (x, y)
        if hits_any(pt, CHILDREN):
            continue
        cands.append(pt)

lines.append("")
lines.append("=== card-C clickable box %s ; overlay children %s ===" % (CARD, CHILDREN))
lines.append("  card centre = (%.1f, %.1f)  -> inside overlay clickable child? %s"
             % ((CARD[0] + CARD[2]) / 2, (CARD[1] + CARD[3]) / 2,
                hits_any(((CARD[0] + CARD[2]) / 2, (CARD[1] + CARD[3]) / 2), CHILDREN)))
lines.append("  card title box %s centre=(356.0,1124.0) inside child? %s"
             % (TITLE, hits_any((356, 1124), CHILDREN)))
lines.append("  safe candidate count = %d" % len(cands))
if cands:
    xs = [p[0] for p in cands]
    ys = [p[1] for p in cands]
    lines.append("  safe region x %d..%d  y %d..%d" % (min(xs), max(xs), min(ys), max(ys)))
# pick: deepest point of the card that is still above the overlay child, near title centre
best = max([p for p in cands if p[1] < 1170], key=lambda p: (p[1], -abs(p[0] - 356)))
lines.append("  chosen = %s  (inside card %s, inside overlay-child %s)"
             % (best, inside(best, CARD), hits_any(best, CHILDREN)))
open(os.path.join(HERE, "step2-geom.txt"), "w", encoding="utf-8").write("\n".join(lines) + "\n")

# 5) annotate a FRESH frame
sh(["shell", "screencap", "-p", "-d", "4", "/sdcard/s2b.png"])
img = sh(["exec-out", "cat", "/sdcard/s2b.png"])
fp = os.path.join(HERE, "step2-geom-frame.png")
open(fp, "wb").write(img)
im = Image.open(fp).convert("RGB")
dr = ImageDraw.Draw(im)


def box(r, colour, label):
    dr.rectangle([r[0], r[1], r[2] - 1, r[3] - 1], outline=colour, width=3)
    dr.text((r[0] + 3, r[1] + 3), label, fill=colour)


box(CARD, (255, 0, 0), "CARD-C clickable")
box(OVERLAYS[0], (0, 200, 0), "Qchong-1000001")
box(OVERLAYS[1], (0, 120, 255), "Qchong-1000006")
box(CHILDREN[0], (255, 0, 255), "overlay clickable child")
box(TITLE, (255, 200, 0), "card title")
dr.ellipse([best[0] - 12, best[1] - 12, best[0] + 12, best[1] + 12], outline=(255, 255, 255), width=4)
dr.line([best[0] - 24, best[1], best[0] + 24, best[1]], fill=(255, 255, 255), width=2)
dr.line([best[0], best[1] - 24, best[0], best[1] + 24], fill=(255, 255, 255), width=2)
im.save(os.path.join(HERE, "step2-boxes.png"))
print("\n".join(lines))
print("\nchosen=%s  frame=%s (%.1f KB)" % (best, fp, len(img) / 1024))
