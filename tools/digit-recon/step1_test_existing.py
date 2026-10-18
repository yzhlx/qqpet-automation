"""Step 1: offline test of the EXISTING digit algorithm on the real exchange dialog frame.

Frame: screenshots/v01/p1-feed-after.png (720x1600), showing 兑换食物 with
  余额 437 (top right), 饼干 5 / 虾 20 (unit prices), 数量 5, 支付 25.

We first LOCATE the numbers by scanning for dark digit-like clusters, then run the
unmodified algorithm's gates. No manual value substitution anywhere.
"""
import os, sys
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from reader_port import load_templates, read_digits, ASSETS

ROOT = os.path.abspath(os.path.join(HERE, "..", ".."))
# Allow explicit paths to dodge Windows CJK argv/encoding mangling.
FRAME = sys.argv[1] if len(sys.argv) > 1 else os.path.join(ROOT, "screenshots", "v01", "p1-feed-after.png")
TPL = sys.argv[2] if len(sys.argv) > 2 else os.path.join(ASSETS, "pet_health", "digits.txt")

img = Image.open(FRAME).convert("RGB")
print(f"frame: {os.path.basename(FRAME)}  size={img.size}")
templates = load_templates(TPL)
print(f"templates loaded: {len(templates)} (digits {sorted({d for d,_ in templates})})")
print()


def dark_ratio(x0, y0, w, h, thresh=100):
    n = 0
    for y in range(y0, y0 + h):
        for x in range(x0, x0 + w):
            p = img.getpixel((x, y))
            if max(p[0], p[1], p[2]) < thresh:
                n += 1
    return n / (w * h)


# ---- 1. find candidate numeric text rows in the dialog area ----
print("=== scanning dialog region for dark digit clusters (thresh<100) ===")
rows = []
for y in range(820, 1460, 2):
    cnt = 0
    for x in range(40, 700, 2):
        p = img.getpixel((x, y))
        if max(p[0], p[1], p[2]) < 100:
            cnt += 1
    rows.append((y, cnt))

# group contiguous bands with meaningful dark pixels
bands = []
cur = None
for y, cnt in rows:
    if cnt >= 3:
        if cur is None:
            cur = [y, y]
        else:
            cur[1] = y
    else:
        if cur is not None:
            bands.append(tuple(cur)); cur = None
if cur:
    bands.append(tuple(cur))

print(f"found {len(bands)} dark bands (y ranges):")
for b in bands:
    print(f"   y {b[0]}..{b[1]}  height={b[1]-b[0]+1}")
print()

# ---- 2. report the known target ROIs and run the algorithm verbatim ----
print("=== running UNMODIFIED algorithm on candidate 52x31 ROIs ===")
print("(the Kotlin reader REQUIRES exactly 52x31; we test that contract first)")
print()

# The health reader's ROI convention: 52 wide, 31 tall, dark = max(RGB) < 100.
# Let's find where a 52x31 dark cluster exists near each expected number.
def probe_roi(label, cx, cy, w=52, h=31):
    x0 = cx - w // 2
    y0 = cy - h // 2
    if x0 < 0 or y0 < 0 or x0 + w > img.width or y0 + h > img.height:
        print(f"{label}: ROI {x0},{y0} out of bounds")
        return
    dr = dark_ratio(x0, y0, w, h)
    res = read_digits(img, x0, y0, w, h, templates=templates, max_digits=3,
                      value_range=(0, 100))
    print(f"{label:22s} ROI({x0:4d},{y0:4d},52x31) darkratio={dr:.3f} -> {res[0]} {res[1]}")
    return res


# Expected locations from visual inspection of the frame
probe_roi("coins 437 (topright)", 383, 261)
probe_roi("biscuit price 5", 103, 1190)
probe_roi("shrimp price 20", 313, 1190)
probe_roi("quantity 5", 586, 1298)
probe_roi("total 25", 500, 1424)
print()

# ---- 3. decisive check: the range gate ----
print("=== CRITICAL: range gate 0..100 (HealthDigitReader.kt:39) ===")
print("coins=437 > 100. Even with perfect digit recognition the reader returns")
print("HEALTH_NUMBER_OUT_OF_RANGE. Let's PROVE it by feeding a synthetic 437 ROI.")
print()

# Build a synthetic ROI containing '437' using the real templates, then read it.
def render_digits(text, tpl, gap=2):
    masks = {}
    for d, m in tpl:
        masks.setdefault(d, m)
    w_total = len(text) * 24 + (len(text) - 1) * gap
    canvas = Image.new("L", (w_total, 32), 255)
    px = canvas.load()
    for i, ch in enumerate(text):
        m = masks[int(ch)]
        ox = i * (24 + gap)
        for y in range(32):
            for x in range(24):
                if m[y * 24 + x] == '1':
                    px[ox + x, y] = 0
    return canvas


for val in ["89", "99", "437", "25", "5"]:
    g = render_digits(val, templates)
    # place into a 52x31 ROI as the Kotlin reader expects
    roi = Image.new("RGB", (52, 31), (255, 255, 255))
    # scale the rendered glyph strip to fit height 31 and center
    gw, gh = g.size
    scale = 31 / gh
    g2 = g.resize((max(1, int(gw * scale)), 31), Image.BILINEAR)
    ox = max(0, (52 - g2.width) // 2)
    roi.paste(Image.merge("RGB", (g2, g2, g2)), (min(ox, 52 - g2.width) if g2.width <= 52 else 0, 0))
    rr = read_digits(roi, 0, 0, 52, 31, templates=templates, max_digits=3,
                     value_range=(0, 100))
    rr_unbounded = read_digits(roi, 0, 0, 52, 31, templates=templates, max_digits=4,
                               value_range=(0, 99999))
    print(f"  input {val:>4s}: range(0..100) -> {rr[0]:24s} | range(0..99999) -> {rr_unbounded[0]} {rr_unbounded[1]}")
