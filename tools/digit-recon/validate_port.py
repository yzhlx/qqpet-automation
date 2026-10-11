"""Validate the Python port against known-good ground truth.

round-1-before.png (from the successful 3/3 session) shows the health panel.
The Kotlin reader read energy/cleanliness/mood from y=228/304/380 at x=256.
If this port reproduces those values, the port is faithful and any negative
result on the exchange dialog can be trusted.
"""
import sys, os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from PIL import Image
from reader_port import load_templates, read_digits

FRAME = sys.argv[1]
TPL = sys.argv[2]
img = Image.open(FRAME).convert("RGB")
templates = load_templates(TPL)
print(f"frame {os.path.basename(FRAME)} size={img.size}")

anchors = [("energy", 228), ("cleanliness", 304), ("mood", 380)]
print("\n=== health-panel read at x=256, 52x31, dark gate (<100) ===")
for label, y in anchors:
    res = read_digits(img, 256, y, 52, 31, dark_thresh=100,
                      templates=templates, max_digits=3, value_range=(0, 100))
    print(f"  {label:12s} y={y}: {res[0]}  {res[1]}")

print("\n=== also sweep nearby x/y to find the best alignment ===")
for label, y in anchors:
    found = []
    for yy in range(y - 6, y + 7):
        for xx in range(248, 268):
            if xx < 0 or yy < 0:
                continue
            r = read_digits(img, xx, yy, 52, 31, templates=templates,
                            max_digits=3, value_range=(0, 100))
            if r[0] == "KNOWN":
                found.append((xx, yy, r[1]))
    print(f"  {label:12s}: {len(found)} KNOWN results")
    for xx, yy, det in found[:5]:
        v, conf, text, detail = det
        print(f"      ({xx},{yy}) -> {v} conf={conf:.4f} {detail}")
