import os
import xml.etree.ElementTree as ET

d = os.path.join("evidence", "slice2", "t3-precondition-r2")
raw = open(os.path.join(d, "ui4.xml"), "rb").read()

for enc in ("gb18030", "utf-8"):
    try:
        txt = raw.decode(enc)
        ok = "宠物" in txt
        print("enc=%s decodes_ok_cjk=%s" % (enc, ok))
        if ok:
            break
    except Exception as e:
        print("enc=%s failed %s" % (enc, e))

root = ET.fromstring(txt)
nodes = list(root.iter("node"))
print("total nodes:", len(nodes))

def short(n):
    return "%s %s" % ((n.get("class") or "").split(".")[-1], n.get("bounds"))

print()
print("=== ALL clickable nodes ===")
for n in nodes:
    if n.get("clickable") == "true":
        print("  %-46s rid=%-28s desc=%r text=%r" % (
            short(n),
            (n.get("resource-id") or "").replace("com.tencent.mobileqq:id/", ""),
            (n.get("content-desc") or ""), (n.get("text") or "")))

print()
print("=== text-bearing nodes ===")
for n in nodes:
    t = (n.get("text") or "").strip()
    if t:
        print("  %-46s click=%-5s text=%r" % (short(n), n.get("clickable"), t))

print()
print("=== content-desc nodes ===")
for n in nodes:
    cd = (n.get("content-desc") or "").strip()
    if cd:
        print("  %-46s click=%-5s desc=%r" % (short(n), n.get("clickable"), cd))
