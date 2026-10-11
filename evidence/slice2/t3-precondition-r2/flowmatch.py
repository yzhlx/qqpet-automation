import os, glob
from PIL import Image

root = os.path.join("deliverables", "gate-c")
cur = Image.open(os.path.join("evidence", "slice2", "t3-precondition-r2",
                              "step1-after-20261007-132047.png")).convert("RGB")
cw, ch = cur.size
# coarse 36x80 signature
SIG = (36, 80)
a = list(cur.resize(SIG, Image.BILINEAR).getdata())

def sig(im):
    return list(im.convert("RGB").resize(SIG, Image.BILINEAR).getdata())

def dist(a, b):
    s = 0
    for (r1, g1, b1), (r2, g2, b2) in zip(a, b):
        s += abs(r1 - r2) + abs(g1 - g2) + abs(b1 - b2)
    return s / (len(a) * 3 * 255.0)

rows = []
for p in sorted(glob.glob(os.path.join(root, "*.png"))):
    try:
        im = Image.open(p)
    except Exception as e:
        print("skip", p, e)
        continue
    if im.size != (cw, ch):
        rows.append((None, os.path.basename(p), "size %s" % (im.size,)))
        continue
    d = dist(a, sig(im))
    rows.append((d, os.path.basename(p), ""))

rows.sort(key=lambda r: (r[0] is None, r[0]))
print("current frame: step1-after-20261007-132047.png  %s" % (cur.size,))
print("%-8s %s" % ("diff", "file"))
for d, name, note in rows[:14]:
    print("%-8s %-34s %s" % ("%.4f" % d if d is not None else "-", name, note))
