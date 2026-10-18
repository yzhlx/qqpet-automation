"""Step 1c: measure the ACTUAL pixel colors of the dialog numbers.

The reader's darkness gate is max(R,G,B) < 100. If the dialog numbers are gold
(437), grey (5/20/25) or white-on-blue, they never satisfy it.
This measures real values instead of assuming.
"""
import sys, os
from PIL import Image

FRAME = sys.argv[1]
img = Image.open(FRAME).convert("RGB")
px = img.load()


def region_stats(label, x0, x1, y0, y1):
    print(f"--- {label}  (x{x0}..{x1}, y{y0}..{y1})")
    # darkest pixel = most likely digit stroke color
    best = None
    hist = {}
    for y in range(y0, y1):
        for x in range(x0, x1):
            r, g, b = px[x, y]
            key = (r // 16 * 16, g // 16 * 16, b // 16 * 16)
            hist[key] = hist.get(key, 0) + 1
            m = max(r, g, b)
            if best is None or m < best[0]:
                best = (m, (r, g, b), (x, y))
    top = sorted(hist.items(), key=lambda kv: -kv[1])[:4]
    print(f"    darkest pixel: max(RGB)={best[0]} rgb={best[1]} at {best[2]}")
    print(f"    passes dark gate (<100)? {best[0] < 100}")
    print(f"    most common colors (bucketed):")
    for c, n in top:
        print(f"       rgb~{c}  count={n}")
    print()


# The dialog numbers, generously boxed
region_stats("coins 437 (top-right)", 340, 420, 248, 278)
region_stats("biscuit price 5", 88, 122, 1183, 1210)
region_stats("shrimp price 20", 296, 332, 1183, 1210)
region_stats("quantity 5 (stepper)", 575, 600, 1285, 1315)
region_stats("total 25 (pay button)", 470, 540, 1410, 1440)

print("=== summary vs reader contract (dark = max(RGB) < 100) ===")
for label, box in [
    ("coins 437", (340, 420, 248, 278)),
    ("price 5", (88, 122, 1183, 1210)),
    ("price 20", (296, 332, 1183, 1210)),
    ("quantity 5", (575, 600, 1285, 1315)),
    ("total 25", (470, 540, 1410, 1440)),
]:
    x0, x1, y0, y1 = box
    mn = 999
    for y in range(y0, y1):
        for x in range(x0, x1):
            r, g, b = px[x, y]
            mn = min(mn, max(r, g, b))
    print(f"  {label:12s} darkest max(RGB)={mn:3d}  -> dark gate {'PASS' if mn < 100 else 'FAIL'}")
