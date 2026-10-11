"""Crop regions of interest from the current rehearsal frame for visual inspection."""
import os
from PIL import Image

here = os.path.dirname(os.path.abspath(__file__))
src = os.path.join(here, "rehearsal.png")
im = Image.open(src).convert("RGB")
print("frame", im.size)

# Card C (bottom / newest): dump bounds [122,1060][590,1387]
regions = {
    "crop-cardC.png": (100, 1040, 620, 1410),
    "crop-cardC-title.png": (130, 1090, 600, 1160),
    "crop-sprite-left.png": (120, 1080, 200, 1350),
}
for name, box in regions.items():
    c = im.crop(box)
    scale = max(1, int(420 / max(1, c.size[0])))
    if scale > 1:
        c = c.resize((c.size[0] * scale, c.size[1] * scale), Image.NEAREST)
    c.save(os.path.join(here, name))
    print(name, box, "->", c.size)

# Sample pixels along the title row of card C to see if a sprite overlays it.
row = 1124
px = im.load()
print("row y=%d samples:" % row)
for x in range(130, 601, 20):
    print("  x=%3d %s" % (x, px[x, row]))
