# -*- coding: utf-8 -*-
"""Parse the VALIDATED display-4 dump of the 名画之旅 page for:
  - every clickable node in the top band (y2 <= 330);
  - any node whose text/desc mentions 返回/关闭/back/close/首页/宠物/房间;
  - all clickable nodes with 立即/购买/支付/装扮/充值/领取 (purchase/claim => forbidden);
  - the node under the coordinate (40,150) and (356,411).
Read-only. Writes step3-page-nodes.txt.
"""
import os
import re

HERE = os.path.dirname(os.path.abspath(__file__))
xml = open(os.path.join(HERE, "dump4-page.xml"), encoding="utf-8").read()

NODE = re.compile(r"<node\b[^>]*>")
attrs = {}


def A(tag, k):
    m = re.search(r'\b%s="([^"]*)"' % k, tag)
    return m.group(1) if m else ""


def B(tag):
    m = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', tag)
    return tuple(int(g) for g in m.groups()) if m else (0, 0, 0, 0)


nodes = []
for t in NODE.findall(xml):
    x1, y1, x2, y2 = B(t)
    nodes.append({
        "cls": A(t, "class").split(".")[-1],
        "text": A(t, "text"),
        "desc": A(t, "content-desc"),
        "res": A(t, "resource-id").split("/")[-1],
        "click": A(t, "clickable") == "true",
        "bounds": (x1, y1, x2, y2),
        "center": ((x1 + x2) // 2, (y1 + y2) // 2),
    })

L = []
L.append("dump4-page.xml nodes=%d" % len(nodes))
L.append("")
L.append("== A. clickable nodes in top band y2<=330 ==")
for n in nodes:
    if n["click"] and n["bounds"][3] <= 330:
        L.append("  %-14s %-10s click=%s %-28s %s center=%s"
                 % (n["cls"], n["res"][:10], n["click"], n["text"][:26], n["bounds"], n["center"]))
L.append("")
L.append("== B. any node mentioning return/close/home/pet ==")
KEY = ("返回", "关闭", "首页", "宠物", "房间", "back", "close", "home")
for n in nodes:
    if any(k in (n["text"] + n["desc"]) for k in KEY):
        L.append("  %-14s click=%-5s %-24s desc=%-18s %s center=%s"
                 % (n["cls"], n["click"], n["text"][:22], n["desc"][:16], n["bounds"], n["center"]))
L.append("")
L.append("== C. FORBIDDEN affordances (purchase/claim/pay/authorize) ==")
BAD = ("立即", "购买", "支付", "充值", "领取", "解锁", "开通", "授权")
hits = [n for n in nodes if any(k in (n["text"] + n["desc"]) for k in BAD)]
L.append("  count=%d" % len(hits))
for n in hits:
    L.append("  %-14s click=%-5s %-26s %s center=%s"
             % (n["cls"], n["click"], (n["text"] or n["desc"])[:24], n["bounds"], n["center"]))
L.append("")
L.append("== D. nodes containing (40,150) and (356,411) ==")
for pt in ((40, 150), (356, 411)):
    L.append("  point %s:" % str(pt))
    for n in nodes:
        x1, y1, x2, y2 = n["bounds"]
        if x1 <= pt[0] <= x2 and y1 <= pt[1] <= y2:
            L.append("    %-14s click=%-5s text=%-20s desc=%-14s %s"
                     % (n["cls"], n["click"], n["text"][:18], n["desc"][:12], n["bounds"]))
L.append("")
L.append("== E. all clickable nodes (inventory) ==")
for n in nodes:
    if n["click"]:
        L.append("  %-14s %-12s %-24s %s center=%s"
                 % (n["cls"], n["res"][:10], (n["text"] or n["desc"])[:22], n["bounds"], n["center"]))

open(os.path.join(HERE, "step3-page-nodes.txt"), "w", encoding="utf-8").write("\n".join(L) + "\n")
print("\n".join(L))
