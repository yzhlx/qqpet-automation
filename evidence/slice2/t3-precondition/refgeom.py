import os
from PIL import Image, ImageDraw

d = os.path.join("evidence", "slice2", "t3-precondition")
ref = "deliverables/gate-c/qq-home.png"
im = Image.open(ref).convert("RGB")
print("qq-home.png size", im.size)

# same ruler treatment for comparison with the 12:55:32 frame
y0, y1 = 250, 1000
crop = im.crop((0, y0, 720, y1))
draw = ImageDraw.Draw(crop)
for y in range(y0, y1 + 1, 50):
    cy = y - y0
    draw.line([(0, cy), (719, cy)], fill=(255, 0, 0), width=1)
    draw.text((4, cy + 2), "y=%d" % y, fill=(255, 0, 0))
    draw.text((660, cy + 2), "y=%d" % y, fill=(255, 0, 0))
out = os.path.join(d, "ref-qqhome-ruler-250-1000.png")
crop.save(out)
print("saved", out, crop.size)

# avatar-disc rows in the reference, same detector
px = im.load()
def strong(p):
    r, g, b = p
    mx, mn = max(r, g, b), min(r, g, b)
    return (mx - mn) > 40 or mx < 200

runs, cur, gap = [], None, 0
for y in range(380, 1000):
    n = sum(1 for x in range(20, 131) if strong(px[x, y]))
    if n >= 45:
        cur = [y, y] if cur is None else [cur[0], y]
        gap = 0
    elif cur is not None:
        gap += 1
        if gap > 4:
            runs.append(tuple(cur)); cur = None
if cur:
    runs.append(tuple(cur))
print("qq-home.png avatar-disc rows:")
for a, b in runs:
    print("  y=%d..%d height=%d centre=%.1f" % (a, b, b - a + 1, (a + b) / 2.0))
