# -*- coding: utf-8 -*-
"""READ-ONLY verification of the two facts the captain's correction rests on.

1. Is the parent of the non-clickable '学习中' TextView clickable?
2. Does the title-bar icon carry a resource ID, and is it clickable?

Parses the authoritative display-4 dump (rehearsal.xml) already on disk.
Sends NO input.
"""
import os
import re
import xml.etree.ElementTree as ET

HERE = os.path.dirname(os.path.abspath(__file__))
XML = os.path.join(HERE, "rehearsal.xml")
OUT = []


def log(s):
    OUT.append(s)
    print(s, flush=True)


tree = ET.parse(XML)
root = tree.getroot()
nodes = list(root.iter("node"))
log("=== authoritative dump: %s (%d nodes) ===" % (os.path.basename(XML), len(nodes)))
log("  root bounds=%s pkg=%s" % (root.get("bounds"), root.get("package")))

log("")
log("=== FACT 1: '学习中' node and its ancestors' clickability ===")
target = None
for n in nodes:
    if (n.get("text") or "") == "学习中":
        target = n
        break
if target is None:
    log("  '学习中' NOT FOUND")
else:
    log("  self: class=%s bounds=%s clickable=%s text=%r"
        % (target.get("class"), target.get("bounds"), target.get("clickable"), target.get("text")))


def parent_of(want):
    for p in nodes:
        for c in list(p):
            if c is want:
                return p
    return None


cur = target
depth = 0
while cur is not None and depth < 6:
    clk = cur.get("clickable")
    log("  [%d] %-16s bounds=%-22s clickable=%-5s rid=%-12s"
        % (depth, (cur.get("class") or "?").split(".")[-1], cur.get("bounds"), clk,
           (cur.get("resource-id") or "-").split("/")[-1]))
    if clk == "true":
        log("      ^^ CLICKABLE ANCESTOR FOUND -> the 'not clickable' child does NOT")
        log("         imply the entry is unclickable.")
        break
    cur = parent_of(cur)
    depth += 1

log("")
log("=== FACT 2: title-bar icons -- clickable + resource-id ===")
for n in nodes:
    b = n.get("bounds") or ""
    if n.get("class", "").endswith("ImageView") and re.match(r"\[\d+,\s*1\d\d\]", b):
        log("  %-16s bounds=%-22s clickable=%-5s rid=%-6s desc=%r text=%r"
            % (n.get("class").split(".")[-1], b, n.get("clickable"),
               (n.get("resource-id") or "-").split("/")[-1],
               n.get("content-desc") or "", n.get("text") or ""))

log("")
log("=== SCOPE ===")
log("  Facts only: clickability/ids as recorded in the dump. No input sent, no state change.")
log("  This does NOT prove what those controls do, nor that ROOM is reachable.")

open(os.path.join(HERE, "step3-parentcheck.txt"), "w", encoding="utf-8").write("\n".join(OUT) + "\n")
print("wrote step3-parentcheck.txt")
