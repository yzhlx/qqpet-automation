"""Step 1d: find the ACTUAL locations of the price numbers by scanning broadly.

My earlier boxes were guesses. This scans the whole dialog for dark/gold text
clusters and reports where numbers really are, then tests the reader on them.
"""
import sys, os
from PIL import Image

FRAME = sys.argv[1]
img = Image.open(FRAME).convert("RGB")
px = img.load()
W, H = img.size


def classify(x, y):
    r, g, b = px[x, y]
    m = max(r, g, b)
    if m < 100:
        return "dark"
    # gold: high R, mid G, low B
    if r > 140 and 90 < g < 200 and b < 150 and (r - b) > 60:
        return "gold"
    # blue (button): B dominant
    if b > 180 and r < 120:
        return "blue"
    return None


print("=== scanning dialog band y 1000..1470 for text clusters ===")
print()

# density map per row for each class
for label, y0, y1 in [("tile prices row", 1170, 1220),
                      ("stepper row", 1280, 1320),
                      ("pay button row", 1405, 1445),
                      ("coins row", 245, 285)]:
    print(f"--- {label} (y{y0}..{y1})")
    for cls in ("dark", "gold"):
        cols = [x for x in range(W) if any(classify(x, y) == cls for y in range(y0, y1))]
        runs = []
        for x in cols:
            if not runs or x > runs[-1][1] + 1:
                runs.append([x, x])
            else:
                runs[-1][1] = x
        runs = [r for r in runs if r[1] - r[0] >= 2]
        if runs:
            print(f"    {cls:5s} runs: " + ", ".join(f"{r[0]}-{r[1]}" for r in runs))
        else:
            print(f"    {cls:5s} runs: none")
    print()

# Where exactly is the biscuit price? scan a tall column band under the tile
print("=== vertical scan under biscuit tile (x 60..150) ===")
for y in range(1150, 1240, 4):
    d = sum(1 for x in range(60, 150) if classify(x, y) == "dark")
    g = sum(1 for x in range(60, 150) if classify(x, y) == "gold")
    if d or g:
        print(f"   y={y:4d}  dark={d:3d}  gold={g:3d}")
