# -*- coding: utf-8 -*-
"""Focused read-only inventory of the QQ chat title bar (and every clickable node)
from the authoritative display-4 dump, to decide whether a *positively identified*
pet-game entry control exists other than the Ark promo card.

Writes titlebar.txt + zoom3-*.png. Takes no input.
"""
import os
import re
import xml.etree.ElementTree as ET

from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
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


def desc(n):
    rid = (n.get("resource-id") or "").replace("com.tencent.mobileqq:id/", "")
    return "%-14s %-22s click=%-5s lclick=%-5s rid=%-14s desc=%r text=%r" % (
        (n.get("class") or "").split(".")[-1], n.get("bounds"), n.get("clickable"),
        n.get("long-clickable"), rid or "-", n.get("content-desc") or "", n.get("text") or "")


def path(n):
    out = []
    p = parent.get(n)
    while p is not None:
        out.append("%s%s" % ((p.get("class") or "").split(".")[-1],
                             ("#" + (p.get("resource-id") or "").split("/")[-1]) if p.get("resource-id") else ""))
        p = parent.get(p)
    return " < ".join(out[:8])


OUT.append("=== all nodes whose bounds lie in the title band (y2 <= 260) ===")
for n in nodes:
    b = b_of(n)
    if b and b[3] <= 260:
        OUT.append("  " + desc(n))
        OUT.append("      path: %s" % path(n))

OUT.append("")
OUT.append("=== every clickable node in the window ===")
for n in nodes:
    if n.get("clickable") == "true":
        OUT.append("  " + desc(n))

OUT.append("")
OUT.append("=== node [568,136][616,184] and its subtree ===")
for n in nodes:
    if b_of(n) == (568, 136, 616, 184):
        OUT.append("  SELF " + desc(n))
        OUT.append("      path: %s" % path(n))
        for c in n.iter("node"):
            if c is not n:
                OUT.append("      child " + desc(c))
        # siblings
        p = parent.get(n)
        if p is not None:
            OUT.append("      siblings under %s:" % ((p.get("class") or "").split(".")[-1]))
            for s in p:
                OUT.append("        " + desc(s))

OUT.append("")
OUT.append("=== search whole tree for any node mentioning pet words ===")
PAT = re.compile(r"宠物|QQ宠物|Q\u5ba0|pet|Pet")
for n in nodes:
    blob = " ".join(filter(None, [n.get("content-desc") or "", n.get("text") or "",
                                  n.get("resource-id") or ""]))
    if PAT.search(blob):
        OUT.append("  " + desc(n))

txt = "\n".join(OUT) + "\n"
open(os.path.join(HERE, "titlebar.txt"), "w", encoding="utf-8").write(txt)

im = Image.open(os.path.join(HERE, "state-now.png")).convert("RGB")
for name, box, k in (("zoom3-icon1fv.png", (556, 124, 628, 196), 8),
                     ("zoom3-iconband.png", (500, 120, 700, 200), 4),
                     ("zoom3-catregion.png", (170, 1280, 560, 1500), 2)):
    c = im.crop(box)
    c = c.resize((c.width * k, c.height * k), Image.LANCZOS)
    c.save(os.path.join(HERE, name))
    print("%-22s %s x%d -> %s" % (name, box, k, c.size))

print(txt)
