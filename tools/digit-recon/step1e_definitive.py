"""Step 1e: DEFINITIVE test - run the existing digit algorithm against real ROIs
using BOTH the current dark gate and a colour-appropriate gate.

Findings so far (measured, not assumed):
  - coins 437  : gold text, darkest max(RGB)=178  -> fails dark gate (<100)
  - price 5    : gold text at x96..117            -> fails dark gate
  - price 20   : gold text at x297..318           -> fails dark gate
  - quantity 5 : DARK text (24,24,25) at x582..593 -> passes dark gate
  - total 25   : gold text at x361..392           -> fails dark gate

So the question is: given a CORRECT ROI and an appropriate darkness predicate,
do the EXISTING 10 templates recognise these digits?

We test that directly. The templates came from the health panel's font; if the
exchange dialog uses the same font, a gold-aware threshold should recover them.
"""
import sys, os
from PIL import Image
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from reader_port import load_templates

FRAME = sys.argv[1]
TPL = sys.argv[2]
img = Image.open(FRAME).convert("RGB")
px = img.load()
templates = load_templates(TPL)
W, H = img.size


def make_pred(kind):
    if kind == "dark":          # current Kotlin behaviour: max(RGB) < 100
        return lambda x, y: max(px[x, y]) < 100
    if kind == "gold":          # gold text on light tile: R high, B low, saturated
        def f(x, y):
            r, g, b = px[x, y]
            return r > 120 and (r - b) > 50 and g < r
        return f
    if kind == "notlight":      # anything clearly darker than the tile background
        return lambda x, y: sum(px[x, y]) / 3 < 200
    raise ValueError(kind)


def read_with(pred, x0, y0, w=52, h=31, max_digits=4, rng=(0, 99999)):
    """Same algorithm as HealthDigitReader.read but with an injectable predicate."""
    if not any(pred(x0 + x, y0 + y) for x in range(w) for y in range(h)):
        return ("DIGITS_ABSENT", None)
    columns = [x for x in range(w) if any(pred(x0 + x, y0 + y) for y in range(h))]
    if not columns:
        return ("DIGITS_ABSENT", None)
    groups = []
    for x in columns:
        if not groups or x > groups[-1][-1] + 1:
            groups.append([])
        groups[-1].append(x)
    if not (1 <= len(groups) <= max_digits) or not (4 <= columns[0] <= 8):
        return ("DIGIT_LAYOUT_UNSUPPORTED", f"groups={len(groups)} firstcol={columns[0]}")
    text, conf, detail = "", 1.0, []
    for xs in groups:
        ys = [y for y in range(h) if any(pred(x0 + x, y0 + y) for x in xs)]
        l, r, t, b = xs[0], xs[-1], ys[0], ys[-1]
        if not (5 <= t <= 8 and 22 <= b <= 24 and 5 <= (r - l + 1) <= 16):
            return ("DIGIT_GEOMETRY_UNSUPPORTED", f"t={t} b={b} w={r-l+1}")
        mask = ""
        for y in range(32):
            for x in range(24):
                xx = l + x * (r - l + 1) // 24
                yy = t + y * (b - t + 1) // 32
                mask += "1" if pred(x0 + xx, y0 + yy) else "0"
        cands = sorted(
            ((d, max(1.0 - sum(1 for i in range(768) if mask[i] != m[i]) / 768.0
                     for dd, m in templates if dd == d)) for d in range(10)),
            key=lambda c: -c[1])
        if cands[0][1] < 0.98:
            return ("LOW_DIGIT_CONFIDENCE", f"best={cands[0][0]} {cands[0][1]:.4f}")
        if cands[0][1] - cands[1][1] < 0.08:
            return ("AMBIGUOUS_DIGIT", f"{cands[0][0]}={cands[0][1]:.4f} vs {cands[1][0]}={cands[1][1]:.4f}")
        conf = min(conf, cands[0][1])
        text += str(cands[0][0])
        detail.append(f"{cands[0][0]}:{cands[0][1]:.4f}")
    if len(text) > 1 and text.startswith("0"):
        return ("INVALID_LEADING_ZERO", text)
    v = int(text)
    if not (rng[0] <= v <= rng[1]):
        return ("NUMBER_OUT_OF_RANGE", v)
    return ("KNOWN", (v, conf, text, detail))


def sweep(label, xs, ys, pred_kind):
    print(f"--- {label}   (predicate={pred_kind})")
    pred = make_pred(pred_kind)
    hits = []
    for y0 in ys:
        for x0 in xs:
            if x0 < 0 or y0 < 0 or x0 + 52 > W or y0 + 31 > H:
                continue
            st, det = read_with(pred, x0, y0)
            if st == "KNOWN":
                hits.append((x0, y0, det))
    if hits:
        for x0, y0, det in hits[:6]:
            v, conf, text, detail = det
            print(f"    KNOWN at ({x0},{y0}) -> {v} conf={conf:.4f} [{text}] {detail}")
        print(f"    total matches: {len(hits)}")
    else:
        print("    no KNOWN result in sweep")
    print()


# Real measured digit spans
sweep("coins 437 (gold x~340..400, y~250..275)",
      range(330, 360), range(246, 262), "gold")
sweep("biscuit price 5 (gold x96..117, y~1158..1178)",
      range(80, 105), range(1150, 1166), "gold")
sweep("shrimp price 20 (gold x297..318)",
      range(280, 306), range(1150, 1166), "gold")
sweep("quantity 5 (dark x582..593)",
      range(570, 585), range(1282, 1298), "dark")
sweep("total 25 (gold x361..392)",
      range(345, 375), range(1408, 1424), "gold")

print("=== CONTROL: same sweep on the HEALTH panel (known-good, dark text) ===")
print("health digits are at y=228/304/380, x from 256, dark gate")
for y0 in (220, 296, 372):
    for x0 in (252, 256):
        st, det = read_with(make_pred("dark"), x0, y0, max_digits=3, rng=(0, 100))
        print(f"    y={y0} x={x0}: {st} {det}")
