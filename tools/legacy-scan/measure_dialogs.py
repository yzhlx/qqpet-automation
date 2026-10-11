# Measure the soap exchange dialog geometry and reconcile with the food dialog.
# Purpose: confirm whether the legacy Agent tap sequence is reusable across both.
import sys, os
from PIL import Image

SOAP = sys.argv[1]   # soap-price.png (兑换洗护道具)
FOOD = sys.argv[2]   # p1-feed-after.png (兑换食物)

def load(p):
    im = Image.open(p).convert("RGB")
    return im, im.load()

def gold_runs(px, y0, y1, x0, x1):
    """columns of gold-ish text (coin numbers) in a band."""
    cols = []
    for x in range(x0, x1):
        hit = False
        for y in range(y0, y1):
            r, g, b = px[x, y]
            if r > 150 and 120 < g < 210 and b < 150 and (r - b) > 50:
                hit = True; break
        if hit: cols.append(x)
    runs = []
    for x in cols:
        if not runs or x > runs[-1][1] + 2: runs.append([x, x])
        else: runs[-1][1] = x
    return runs

def blue_button_top(px, x=360, y0=1350, y1=1500):
    for y in range(y0, y1):
        r, g, b = px[x, y]
        if b > 200 and r < 90 and 120 < g < 200:
            return y
    return None

def find_title_dark_band(px, y0, y1):
    """rows with lots of dark text (the title 兑换...道具 / 兑换食物)."""
    best = []
    for y in range(y0, y1):
        d = sum(1 for x in range(180, 540) if max(px[x, y]) < 120)
        best.append((d, y))
    best.sort(reverse=True)
    ys = sorted(y for d, y in best if d > 25)
    return (ys[0], ys[-1]) if ys else None

for label, path in [("SOAP 兑换洗护道具", SOAP), ("FOOD 兑换食物", FOOD)]:
    im, px = load(path)
    W, H = im.size
    print(f"=== {label}  ({W}x{H}) ===")
    tb = find_title_dark_band(px, 820, 960)
    print(f"  title dark band y: {tb}")
    bb = blue_button_top(px)
    print(f"  blue pay button top y: {bb}  (center ~ {bb+38 if bb else '?'})")
    # balance pill: gold runs near title row (right side)
    for yy0, yy1, tag in [(855, 905, "title-row"), (245, 285, "top-261")]:
        gr = gold_runs(px, yy0, yy1, 560, 700)
        print(f"  gold runs x>560 [{tag} y{yy0}..{yy1}]: {gr[:6]}")
    # stepper band
    sr = gold_runs(px, 1280, 1320, 540, 620)
    print(f"  gold runs stepper y1280..1320 x540..620: {sr[:6]}")
    print()
