"""Dump ALL attributes of every node in the three ark-card subtrees of ui4.xml."""
import os
import re
import xml.etree.ElementTree as ET

here = os.path.dirname(os.path.abspath(__file__))
root = ET.fromstring(open(os.path.join(here, "ui4.xml"), "rb").read().decode("utf-8", "replace"))

BR = re.compile(r"\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]")


def b_of(n):
    m = BR.match(n.get("bounds") or "")
    return tuple(int(g) for g in m.groups()) if m else None


lines = []

lines.append("=== ALL nodes whose bounds fall inside the three card bands ===")
bands = [(260, 587), (660, 987), (1060, 1387)]
for lo, hi in bands:
    lines.append("")
    lines.append("--- card band y=%d..%d ---" % (lo, hi))
    for n in root.iter("node"):
        b = b_of(n)
        if not b:
            continue
        if b[1] >= lo - 5 and b[3] <= hi + 5:
            attrs = ["%s=%r" % (k, v) for k, v in sorted(n.attrib.items()) if k != "bounds"]
            lines.append("  %-20s %-40s %s" % ((n.get("class") or "").split(".")[-1],
                                               n.get("bounds"), " ".join(attrs)))

lines.append("")
lines.append("=== ancestor chain of the newest card (deepest text node 宠物名画之旅) ===")
target = None
for n in root.iter("node"):
    if (n.get("text") or "").strip() == "\u5ba0\u7269\u540d\u753b\u4e4b\u65c5":
        b = b_of(n)
        if b and b[1] > 1000:
            target = n
parent = {c: p for p in root.iter("node") for c in p}
cur = target
while cur is not None:
    attrs = ["%s=%r" % (k, v) for k, v in sorted(cur.attrib.items()) if k != "bounds"]
    lines.append("  %-20s %-40s %s" % ((cur.get("class") or "").split(".")[-1],
                                       cur.get("bounds"), " ".join(attrs)))
    cur = parent.get(cur)

lines.append("")
lines.append("=== every distinct resource-id in the whole dump ===")
rids = sorted({(n.get("resource-id") or "") for n in root.iter("node")})
for r in rids:
    lines.append("  %s" % (r or "(none)"))

out = "\n".join(lines)
with open(os.path.join(here, "card-attrs.txt"), "w", encoding="utf-8") as f:
    f.write(out + "\n")
print(out[:2500])
