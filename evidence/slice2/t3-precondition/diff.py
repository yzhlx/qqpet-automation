import sys
from PIL import Image, ImageChops

a = Image.open(sys.argv[1]).convert("RGB")
b = Image.open(sys.argv[2]).convert("RGB")
print("A", a.size, sys.argv[1])
print("B", b.size, sys.argv[2])
if a.size != b.size:
    print("SIZE MISMATCH - not comparable")
    sys.exit(0)

pa, pb = a.load(), b.load()
w, h = a.size
minx = maxx = miny = maxy = None
n = 0
for y in range(h):
    for x in range(w):
        ra, ga, ba = pa[x, y]
        rb, gb, bb = pb[x, y]
        if abs(ra - rb) + abs(ga - gb) + abs(ba - bb) > 12:
            n += 1
            if minx is None:
                minx = maxx = x; miny = maxy = y
            else:
                minx = min(minx, x); maxx = max(maxx, x)
                miny = min(miny, y); maxy = max(maxy, y)
pct = 100.0 * n / (w * h)
print("differing px (>12 L1):", n, "(%.3f%% of frame)" % pct)
if n:
    print("diff bbox:", (minx, miny), "-", (maxx, maxy))
else:
    print("IDENTICAL within threshold")
