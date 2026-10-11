"""Full flat inventory of every node in ui4.xml: bounds, class, clickable, rid, desc, text.
Also reports which nodes overlap the three ark-card bands and which are overlay sprites."""
import os
import re
import xml.etree.ElementTree as ET

here = os.path.dirname(os.path.abspath(__file__))
root = ET.fromstring(open(os.path.join(here, "ui4.xml"), "rb").read().decode("utf-8", "replace"))
BR = re.compile(r"\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]")

lines = []


def b_of(n):
    m = BR.match(n.get("bounds") or "")
    return tuple(int(g) for g in m.groups()) if m else None


lines.append("%-3s %-16s %-22s %-6s %-26s %-22s %s" % (
    "#", "class", "bounds", "click", "resource-id", "desc", "text"))
lines.append("-" * 150)
nodes = list(root.iter("node"))
for i, n in enumerate(nodes):
    rid = (n.get("resource-id") or "").replace("com.tencent.mobileqq:id/", "").replace("android:id/", "A:")
    lines.append("%-3d %-16s %-22s %-6s %-26s %-22s %s" % (
        i, (n.get("class") or "").split(".")[-1], n.get("bounds"), n.get("clickable"),
        rid or "-", (n.get("content-desc") or "")[:22], (n.get("text") or "")[:30]))

lines.append("")
lines.append("=== clickable nodes sorted by y ===")
cl = [n for n in nodes if n.get("clickable") == "true"]
for n in sorted(cl, key=lambda n: b_of(n)[1]):
    b = b_of(n)
    lines.append("  y=%-5d h=%-5d %-18s %-22s rid=%-14s desc=%r text=%r" % (
        b[1], b[3] - b[1], (n.get("class") or "").split(".")[-1], n.get("bounds"),
        ((n.get("resource-id") or "").replace("com.tencent.mobileqq:id/", "") or "-"),
        (n.get("content-desc") or "")[:24], (n.get("text") or "")[:24]))

# Overlap analysis: sprites vs card bands
lines.append("")
lines.append("=== overlap: sprite band y=1093..1493 vs card bands ===")
for lo, hi, name in ((260, 587, "A"), (660, 987, "B"), (1060, 1387, "C")):
    lines.append("  card %s y=%d..%d : sprite overlap = %s" % (
        name, lo, hi, "YES" if hi > 1093 else "no"))

out = "\n".join(lines)
open(os.path.join(here, "allnodes.txt"), "w", encoding="utf-8").write(out + "\n")
print("nodes=%d clickable=%d -> allnodes.txt" % (len(nodes), len(cl)))
