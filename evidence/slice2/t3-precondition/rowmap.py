import os
from PIL import Image, ImageDraw

d = os.path.join("evidence", "slice2", "t3-precondition")
im = Image.open(os.path.join(d, "before-20261007-125532.png")).convert("RGB")
print("frame size", im.size)

y0, y1 = 250, 1000
crop = im.crop((0, y0, 720, y1))
draw = ImageDraw.Draw(crop)
for y in range(y0, y1 + 1, 50):
    cy = y - y0
    draw.line([(0, cy), (719, cy)], fill=(255, 0, 0), width=1)
    draw.text((4, cy + 2), "y=%d" % y, fill=(255, 0, 0))
    draw.text((660, cy + 2), "y=%d" % y, fill=(255, 0, 0))
crop = crop.resize((crop.width, int(crop.height * 1.6)), Image.LANCZOS)
out = os.path.join(d, "list-ruler-250-1000.png")
crop.save(out)
print("saved", out, crop.size)
