# -*- coding: utf-8 -*-
"""Step-2 decision evidence.

Settles, from the authoritative display-4 UI dump already on disk plus live
read-only device queries:
  1. the exact ancestor chains of the 宠物名画之旅 card's clickable box and of the
     Q宠 floating-sprite overlay, and which of them is painted later (on top);
  2. which nodes contain the card's clickable centre -> whether a centre tap would
     hit the forbidden floating-cat overlay;
  3. whether a FRESH display-4 semantic dump can still be obtained right now;
  4. live window stack on display 4.

Writes step2-occlusion.txt. Takes no input of any kind.
"""
import os
import re
import subprocess
import xml.etree.ElementTree as ET

HERE = os.path.dirname(os.path.abspath(__file__))
ADB = r"C:\Users\lu158\Desktop\dsh插件\养宠自动化\tools\android-build\sdk\platform-tools\adb.exe"
DEV = "DKS9K23420020242"
BR = re.compile(r"\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]")
TITLE = "\u5ba0\u7269\u540d\u753b\u4e4b\u65c5"      # 宠物名画之旅
CARD = (122, 1080, 590, 1341)
OUT = []


def sh(args):
    return subprocess.run([ADB, "-s", DEV] + args, capture_output=True).stdout.decode("utf-8", "replace")


def b_of(n):
    m = BR.match(n.get("bounds") or "")
    return tuple(int(g) for g in m.groups()) if m else None


def chain(node, parent, nodes):
    """Return list of (doc_index, depth, node) from the target up to the root."""
    out = []
    p = node
    while p is not None:
        out.append(p)
        p = parent.get(p)
    return out


def describe(n):
    rid = (n.get("resource-id") or "").replace("com.tencent.mobileqq:id/", "")
    return "%-16s %-22s idx=%-2s click=%-5s rid=%s" % (
        (n.get("class") or "").split(".")[-1], n.get("bounds"), n.get("index"),
        n.get("clickable"), rid or "-")


def load(fn):
    xml = open(os.path.join(HERE, fn), encoding="utf-8", errors="replace").read()
    xml = xml[xml.index("<?xml"):]
    root = ET.fromstring(xml)
    nodes = list(root.iter("node"))
    parent = {}
    for p in nodes:
        for c in p:
            parent[c] = p
    return nodes, parent


# ---------- 1. parse the authoritative display-4 dump ----------
src = None
for cand in ("rehearsal.xml", "ui4.xml"):
    fp = os.path.join(HERE, cand)
    if not os.path.exists(fp):
        continue
    txt = open(fp, encoding="utf-8", errors="replace").read()
    pkgs = set(re.findall(r'package="([^"]+)"', txt))
    OUT.append("%-14s bytes=%-7d pkgs=%s" % (cand, len(txt), sorted(pkgs)))
    if "com.tencent.mobileqq" in pkgs and src is None:
        src = cand
OUT.append("")
OUT.append("authoritative display-4 dump chosen: %s" % src)
nodes, parent = load(src)
OUT.append("nodes in dump: %d" % len(nodes))

sprites = [n for n in nodes if (n.get("content-desc") or "").startswith("Q\u5ba0")]
cards = [n for n in nodes if b_of(n) == CARD and n.get("clickable") == "true"]
out_child = [n for n in sprites if any(
    (b_of(c) == (303, 1170, 543, 1329) and c.get("clickable") == "true") for c in n)]

OUT.append("")
OUT.append("=== Qchong sprite overlay frames ===")
for n in sprites:
    OUT.append("  %s desc=%r" % (describe(n), n.get("content-desc")))

OUT.append("")
OUT.append("=== card clickable boxes ===")
card_nodes = [n for n in nodes if n.get("clickable") == "true" and (b_of(n) or (0,))[0] == 122]
for n in card_nodes:
    OUT.append("  %s" % describe(n))

OUT.append("")
OUT.append("=== ancestor chains (target -> root) ===")
for label, cap in (("CARD", card_nodes[-1] if card_nodes else None),
                   ("SPRITE-OVERLAY", sprites[0] if sprites else None)):
    if cap is None:
        OUT.append("  %s: NOT FOUND" % label)
        continue
    OUT.append("  --- %s: %s" % (label, describe(cap)))
    ch = chain(cap, parent, nodes)
    for i, n in enumerate(ch):
        sibs = list(parent[n]) if n in parent else []
        pos = sibs.index(n) if n in sibs else -1
        OUT.append("    [%d] depth-%-2d childOrder=%d/%-2d  %s" % (i, len(ch) - i - 1, pos, len(sibs), describe(n)))

OUT.append("")
OUT.append("=== which nodes contain the CARD's clickable centre (356, 1210) ===")
cx, cy = (CARD[0] + CARD[2]) / 2, (CARD[1] + CARD[3]) / 2
for n in nodes:
    b = b_of(n)
    if b and b[0] <= cx <= b[2] and b[1] <= cy <= b[3] and n.get("clickable") == "true":
        OUT.append("  clickable %s desc=%r" % (describe(n), n.get("content-desc")))

# sibling order between the sprite overlay subtree and the card subtree
def top_ancestor_under(node, anc):
    p = node
    while parent.get(p) is not None and parent.get(p) is not anc:
        p = parent[p]
    return p


card = card_nodes[-1]
sprite = sprites[0]
common = None
cs = set(chain(card, parent, nodes))
for n in chain(sprite, parent, nodes):
    if n in cs:
        common = n
        break
OUT.append("")
OUT.append("=== paint order (later sibling index = painted on top) ===")
if common is not None:
    OUT.append("  lowest common ancestor: %s" % describe(common))
    a = top_ancestor_under(card, common)
    b = top_ancestor_under(sprite, common)
    sibs = list(common)
    pa, pb = sibs.index(a), sibs.index(b)
    OUT.append("  card subtree   childOrder=%d  %s" % (pa, describe(a)))
    OUT.append("  sprite subtree childOrder=%d  %s" % (pb, describe(b)))
    OUT.append("  => %s is painted ON TOP of %s"
               % ("SPRITE" if pb > pa else "CARD", "CARD" if pb > pa else "SPRITE"))
else:
    OUT.append("  no common ancestor found")

# ---------- 3. can we still get a FRESH display-4 semantic dump? ----------
OUT.append("")
OUT.append("=== FRESH display-4 dump attempts (live) ===")
for i in range(3):
    sh(["shell", "rm", "-f", "/sdcard/_f4.xml"])
    err = sh(["shell", "uiautomator", "dump", "--display", "4", "/sdcard/_f4.xml"]).strip()
    xml = sh(["shell", "cat", "/sdcard/_f4.xml"])
    i0 = xml.find("<?xml")
    if i0 < 0:
        OUT.append("  attempt%d: NO XML  stderr=%r" % (i + 1, err[:120]))
        continue
    xml = xml[i0:]
    root_b = re.match(r'.*?bounds="(\[[0-9,]+\]\[[0-9,]+\])"', xml)
    pk = sorted(set(re.findall(r'package="([^"]+)"', xml)))
    n_qq = len(re.findall(r'package="com\.tencent\.mobileqq"', xml))
    OUT.append("  attempt%d: bytes=%-6d nodes=%-4d rootBounds=%-22s pkgs=%-28s qqNodes=%d"
               % (i + 1, len(xml), xml.count("<node"), root_b.group(1) if root_b else "?",
                  ",".join(p.split(".")[-1] for p in pk), n_qq))
    OUT.append("            stderr=%r" % err[:120])

# ---------- 4. live window stack on display 4 ----------
OUT.append("")
OUT.append("=== display-4 windows (live) ===")
w = sh(["shell", "dumpsys", "window", "windows"])
disp = -1
for line in w.splitlines():
    m = re.search(r"Display: mDisplayId=(\d+)", line)
    if m:
        disp = int(m.group(1))
        continue
    if disp == 4 and re.search(r"Window\{|mOwnerUid|isOnScreen|mFrame=", line.strip()):
        OUT.append("  " + line.strip()[:150])

txt = "\n".join(OUT) + "\n"
open(os.path.join(HERE, "step2-occlusion.txt"), "w", encoding="utf-8").write(txt)
print(txt)
