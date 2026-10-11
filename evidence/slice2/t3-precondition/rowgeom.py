import os
from PIL import Image

d = os.path.join("evidence", "slice2", "t3-precondition")
im = Image.open(os.path.join(d, "before-20261007-125532.png")).convert("RGB")
px = im.load()

# Avatars are saturated/dark discs in the left column; list rows contain lots of
# near-white background. Count "strongly non-background" pixels per row in x=20..130.
def strong(p):
    r, g, b = p
    mx, mn = max(r, g, b), min(r, g, b)
    return (mx - mn) > 40 or mx < 200   # coloured OR clearly dark

rows = []
for y in range(380, 1000):
    n = sum(1 for x in range(20, 131) if strong(px[x, y]))
    rows.append((y, n))

runs = []
cur = None
gap = 0
for y, n in rows:
    if n >= 45:            # an avatar disc spans most of this 111px-wide band
        cur = [y, y] if cur is None else [cur[0], y]
        gap = 0
    elif cur is not None:
        gap += 1
        if gap > 4:
            runs.append(tuple(cur)); cur = None
if cur:
    runs.append(tuple(cur))

print("avatar-disc rows (x=20..130, >=45 strong px/row):")
for a, b in runs:
    print("  y=%d..%d height=%d centre=%.1f" % (a, b, b - a + 1, (a + b) / 2.0))

# tight bbox of run 1
if runs:
    a, b = runs[0]
    minx = maxx = miny = maxy = None
    for y in range(a, b + 1):
        for x in range(0, 200):
            if strong(px[x, y]):
                minx = x if minx is None else min(minx, x)
                maxx = x if maxx is None else max(maxx, x)
                miny = y if miny is None else min(miny, y)
                maxy = y if maxy is None else max(maxy, y)
    print("run1 tight bbox: (%d,%d)-(%d,%d) size %dx%d centre=(%.1f,%.1f)"
          % (minx, miny, maxx, maxy, maxx - minx + 1, maxy - miny + 1,
             (minx + maxx) / 2.0, (miny + maxy) / 2.0))
