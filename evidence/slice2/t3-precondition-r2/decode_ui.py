import os
import xml.etree.ElementTree as ET

d = os.path.join("evidence", "slice2", "t3-precondition-r2")
txt = open(os.path.join(d, "ui4.xml"), "rb").read().decode("utf-8", "replace")
root = ET.fromstring(txt)
nodes = list(root.iter("node"))

out = []
out.append("UI dump: display 4, 136 nodes, package com.tencent.mobileqq")
out.append("")
out.append("=== CLICKABLE nodes (authoritative semantics) ===")
for n in nodes:
    if n.get("clickable") == "true":
        out.append("  %-24s %-40s click=1 rid=%-22s desc=%r text=%r" % (
            (n.get("class") or "").split(".")[-1], n.get("bounds"),
            (n.get("resource-id") or "").replace("com.tencent.mobileqq:id/", ""),
            (n.get("content-desc") or ""), (n.get("text") or "")))
out.append("")
out.append("=== text nodes ===")
for n in nodes:
    t = (n.get("text") or "").strip()
    if t:
        out.append("  %-24s %-40s click=%-5s text=%r" % (
            (n.get("class") or "").split(".")[-1], n.get("bounds"), n.get("clickable"), t))
out.append("")
out.append("=== content-desc nodes ===")
for n in nodes:
    cd = (n.get("content-desc") or "").strip()
    if cd:
        out.append("  %-24s %-40s click=%-5s desc=%r" % (
            (n.get("class") or "").split(".")[-1], n.get("bounds"), n.get("clickable"), cd))

open(os.path.join(d, "ui4-decoded.txt"), "w", encoding="utf-8").write("\n".join(out))
print("wrote ui4-decoded.txt", len(out), "lines")
