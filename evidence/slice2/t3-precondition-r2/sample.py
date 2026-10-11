import os
from PIL import Image

d = os.path.join("evidence", "slice2", "t3-precondition-r2")
im = Image.open(os.path.join(d, "step1-after-20261007-132047.png")).convert("RGB")
px = im.load()

print("vertical samples at x=350 (inside card column):")
for y in range(200, 1300, 25):
    print("  y=%4d  %s" % (y, px[350, y]))

print()
print("horizontal samples at y=340 (inside first card):")
for x in range(60, 700, 40):
    print("  x=%4d  %s" % (x, px[x, 340]))

print()
print("background sample (between cards) x=350 y=600:", px[350, 600])
print("page bg x=20 y=340:", px[20, 340])
