# -*- coding: utf-8 -*-
"""Extract the entry-route description from the project completion report."""
import io
import os
import re

cands = [
    "当前完成内容报告.md",
    os.path.join("deliverables", "EXPERIMENTAL-3-ROUND-REPORT.md"),
]
here = os.path.dirname(os.path.abspath(__file__))
base = os.path.abspath(os.path.join(here, "..", "..", ".."))
out = []
for c in cands:
    p = os.path.join(base, c)
    if not os.path.exists(p):
        out.append("MISSING: %s" % p)
        continue
    txt = io.open(p, encoding="utf-8", errors="replace").read()
    lines = txt.splitlines()
    out.append("=" * 80)
    out.append("FILE %s (%d lines)" % (c, len(lines)))
    out.append("=" * 80)
    for i, l in enumerate(lines, 1):
        if re.search(r"宠物|入口|ROOM|room|学园|名画|点击|how|进入", l):
            out.append("%4d | %s" % (i, l.rstrip()))
open(os.path.join(here, "entry-route.txt"), "w", encoding="utf-8").write("\n".join(out) + "\n")
print("wrote entry-route.txt, %d lines" % len(out))
