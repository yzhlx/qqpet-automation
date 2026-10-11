"""Step 1b: locate the REAL digit ROIs in the exchange dialog, then run the
unmodified algorithm against them.

Key insight from the Kotlin reader (PetHealthCheck.kt:28):
  reader.read(52, 31, {x,j -> frame.getPixel(256+x, y+j)}, ...) with y in 228/304/380
So ROIs are anchored at a fixed left edge (256) and fixed widths (52).
The health digits are DARK text (max(RGB) < 100).

In the exchange dialog the numbers are:
  437  (coins, top-right)  - gold text on light pill
  5    (biscuit price)     - dark text
  20   (shrimp price)      - dark text
  5    (quantity)          - dark text
  25   (total, on blue)    - white text on blue button

We must find where a 52x31 window actually contains dark digit strokes and
whether the leading column falls in 4..8 (the reader's layout contract).
"""
import os, sys
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from reader_port import load_templates, read_digits

FRAME = sys.argv[1]
TPL = sys.argv[2]

img = Image.open(FRAME).convert("RGB")
templates = load_templates(TPL)
px = img.load()


def dark(x, y, t=100):
    r, g, b = px[x, y]
    return max(r, g, b) < t


def digit_columns(y0, y1, x0=0, x1=720, t=100):
    """Columns containing dark pixels in a y-band -> (start,end) runs."""
    cols = [x for x in range(x0, x1) if any(dark(x, y, t) for y in range(y0, y1))]
    runs = []
    for x in cols:
        if not runs or x > runs[-1][1] + 1:
            runs.append([x, x])
        else:
            runs[-1][1] = x
    return runs


print("=== locating real numeric text in the exchange dialog ===")
print()

# Focus on regions where numbers actually are, per the frame.
regions = [
    ("coins 437 (top-right pill)", 300, 420, 245, 285),
    ("biscuit price 5 (under tile)", 70, 140, 1180, 1215),
    ("shrimp price 20 (under tile)", 280, 350, 1180, 1215),
    ("quantity 5 (stepper)", 560, 615, 1280, 1320),
    ("total 25 (pay button)", 460, 545, 1405, 1445),
]

for label, x0, x1, y0, y1 in regions:
    runs = digit_columns(y0, y1, x0, x1)
    print(f"{label}")
    print(f"   search box x{x0}..{x1} y{y0}..{y1}")
    if not runs:
        print("   -> no dark strokes found (thresh<100)")
    for r in runs:
        print(f"   stroke run x {r[0]}..{r[1]}  (w={r[1]-r[0]+1})")
    print()

print("=== now test: does each region look like the reader's expected layout? ===")
print("reader contract: 52 wide window, first dark column must be at x offset 4..8")
print()

for label, x0, x1, y0, y1 in regions:
    runs = digit_columns(y0, y1, x0, x1)
    if not runs:
        print(f"{label:32s} SKIP (no strokes)")
        continue
    first = runs[0][0]
    last = runs[-1][1]
    total_w = last - first + 1
    print(f"{label:32s} digits span {total_w}px, first col at {first}")
    # Try a 52-wide window whose left edge puts first stroke at offset 4..8
    for left in range(first - 8, first - 3):
        if left < 0 or left + 52 > 720:
            continue
        # vertical: center the band
        for top in range(y0 - 8, y0 + 4):
            if top < 0 or top + 31 > 1600:
                continue
            res = read_digits(img, left, top, 52, 31, templates=templates,
                              max_digits=4, value_range=(0, 99999))
            if res[0] == "KNOWN":
                v, conf, text, detail = res[1]
                print(f"      MATCH left={left} top={top} -> {v} (conf {conf:.4f}) "
                      f"[{text}] {detail}")
