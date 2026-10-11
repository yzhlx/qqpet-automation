"""Resolve the tree context of the clickable FrameLayout [303,1170][543,1329] and crop
the pet/sprite area at high zoom, so the step-2 target can be identified semantically."""
import os
import re
import xml.etree.ElementTree as ET
from PIL import Image

here = os.path.dirname(os.path.abspath(__file__))
root = ET.fromstring(open(os.path.join(here, "ui4.xml"), "rb").read().decode("utf-8", "replace"))
parent = {c: p for p in root.iter("node") for c in p}

TARGET = "[303,1170][543,1329]"
lines = []


def brief(n):
    return "%-18s %-22s idx=%-3s click=%-5s desc=%r text=%r rid=%s" % (
        (n.get("class") or "").split(".")[-1], n.get("bounds"), n.get("index"),
        n.get("clickable"), (n.get("content-desc") or "")[:24], (n.get("text") or "")[:24],
        (n.get("resource-id") or "").replace("com.tencent.mobileqq:id/", "") or "-")


node = None
for n in root.iter("node"):
    if n.get("bounds") == TARGET:
        node = n
    if n.get("bounds") == "[303,1093][543,1493]" and (n.get("content-desc") or "").startswith("Q"):
        sprite = n

lines.append("=== target node ===")
lines.append(brief(node))
lines.append("")
lines.append("=== ancestor chain ===")
cur = parent.get(node)
while cur is not None:
    lines.append(brief(cur))
    cur = parent.get(cur)

lines.append("")
lines.append("=== siblings of the target ===")
for s in list(parent.get(node)):
    lines.append(brief(s))

lines.append("")
lines.append("=== sprite Q宠 node ===")
lines.append(brief(sprite))
lines.append("")
lines.append("=== sprite ancestors ===")
cur = parent.get(sprite)
while cur is not None:
    lines.append(brief(cur))
    cur = parent.get(cur)

out = "\n".join(lines)
open(os.path.join(here, "target-tree.txt"), "w", encoding="utf-8").write(out + "\n")
print(out)

# High-zoom crop of the clickable band and its neighbourhood.
im = Image.open(os.path.join(here, "rehearsal.png")).convert("RGB")
box = (280, 1080, 570, 1420)
c = im.crop(box)
c = c.resize((c.size[0] * 3, c.size[1] * 3), Image.NEAREST)
c.save(os.path.join(here, "crop-clickable-band.png"))
print("crop-clickable-band.png", box, "->", c.size)

box2 = (140, 1040, 620, 1420)
d = im.crop(box2)
d = d.resize((int(d.size[0] * 1.6), int(d.size[1] * 1.6)), Image.LANCZOS)
d.save(os.path.join(here, "crop-cardC-wide.png"))
print("crop-cardC-wide.png", box2, "->", d.size)
