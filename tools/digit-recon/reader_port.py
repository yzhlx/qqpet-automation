"""Faithful port of HealthDigitReader.kt + TemplateMatcher.similarity for offline testing.

Reads templates from the real digits.txt and applies the EXACT algorithm:
  - ROI size gate 52x31
  - column projection to find digit groups
  - per-group geometry gate (t in 5..8, b in 22..24, width 5..16)
  - 24x32 normalized mask
  - bit-match confidence, gate >= 0.98 and margin >= 0.08
  - leading-zero and range gates

Nothing is hand-tuned here; any failure is the real algorithm's failure.
"""
import sys, os
from PIL import Image

ASSETS = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "assets")


def load_templates(path):
    tpl = []
    with open(path, "r", encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            d, mask = line.split(" ")
            assert len(mask) == 768, (d, len(mask))
            tpl.append((int(d), mask))
    digits = {d for d, _ in tpl}
    assert digits == set(range(10)), digits
    return tpl


def read_digits(img, x0, y0, w, h, dark_thresh=100, templates=None,
                max_digits=3, value_range=(0, 100), verbose=False):
    """Returns (status, detail). status is 'KNOWN' or the Unknown reason (mirrors Kotlin)."""
    if w != 52 or h != 31:
        return ("DIGIT_ROI_SIZE", None)

    def dark(x, y):
        p = img.getpixel((x0 + x, y0 + y))
        r, g, b = p[0], p[1], p[2]
        return max(r, g, b) < dark_thresh

    columns = [x for x in range(w) if any(dark(x, y) for y in range(h))]
    if not columns:
        return ("DIGITS_ABSENT", None)

    groups = []
    for x in columns:
        if not groups or x > groups[-1][-1] + 1:
            groups.append([])
        groups[-1].append(x)

    if not (1 <= len(groups) <= max_digits) or not (4 <= columns[0] <= 8):
        return ("DIGIT_LAYOUT_UNSUPPORTED", f"groups={len(groups)} firstcol={columns[0]}")

    text = ""
    confidence = 1.0
    detail = []
    for xs in groups:
        ys = [y for y in range(h) if any(dark(x, y) for x in xs)]
        l, r = xs[0], xs[-1]
        t, b = ys[0], ys[-1]
        if not (5 <= t <= 8 and 22 <= b <= 24 and 5 <= (r - l + 1) <= 16):
            return ("DIGIT_GEOMETRY_UNSUPPORTED",
                    f"t={t} b={b} w={r-l+1}")
        mask = ""
        for y in range(32):
            for x in range(24):
                px = l + x * (r - l + 1) // 24
                py = t + y * (b - t + 1) // 32
                mask += "1" if dark(px, py) else "0"
        cands = []
        for d in range(10):
            best = max(
                1.0 - sum(1 for i in range(768) if mask[i] != m[i]) / 768.0
                for dd, m in templates if dd == d
            )
            cands.append((d, best))
        cands.sort(key=lambda c: -c[1])
        if cands[0][1] < 0.98:
            return ("LOW_DIGIT_CONFIDENCE", f"digit@{l} best={cands[0][0]} score={cands[0][1]:.4f}")
        if cands[0][1] - cands[1][1] < 0.08:
            return ("AMBIGUOUS_DIGIT",
                    f"digit@{l} {cands[0][0]}={cands[0][1]:.4f} vs {cands[1][0]}={cands[1][1]:.4f}")
        confidence = min(confidence, cands[0][1])
        text += str(cands[0][0])
        detail.append(f"{cands[0][0]}@{l}-{r}:{cands[0][1]:.4f}")

    if len(text) > 1 and text.startswith("0"):
        return ("INVALID_LEADING_ZERO", text)
    value = int(text)
    lo, hi = value_range
    if not (lo <= value <= hi):
        return ("NUMBER_OUT_OF_RANGE", f"{value} not in {lo}..{hi}")
    return ("KNOWN", (value, confidence, text, detail))


def template_similarity(asset_path, img, x0, y0, w, h):
    """Port of TemplateMatcher.similarity(): scale both to <=24x24, sum abs RGB diff."""
    tpl = Image.open(asset_path).convert("RGB")
    live = img.crop((x0, y0, x0 + w, y0 + h))
    tw, th = min(24, w), min(24, h)
    a = live.resize((tw, th), Image.BILINEAR)
    b = tpl.resize((tw, th), Image.BILINEAR)
    diff = 0.0
    for y in range(th):
        for x in range(tw):
            p = a.getpixel((x, y)); q = b.getpixel((x, y))
            diff += abs(p[0]-q[0]) + abs(p[1]-q[1]) + abs(p[2]-q[2])
    mx = tw * th * 3.0 * 255.0
    return max(0.0, min(1.0, 1.0 - diff / mx))
