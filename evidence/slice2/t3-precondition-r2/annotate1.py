import os
from PIL import Image, ImageDraw

d = os.path.join("evidence", "slice2", "t3-precondition-r2")
f = "step1-before-20261007-132007.png"
im = Image.open(os.path.join(d, f)).convert("RGB")

# mark the measured row band and tap point
box = (0, 380, 720, 560)
crop = im.crop(box).copy()
draw = ImageDraw.Draw(crop)
a, b, cx, cy = 423, 517, 360, 470
draw.rectangle([0, a - box[1], 719, b - box[1]], outline=(255, 0, 0), width=3)
draw.line([(cx - 14, cy - box[1]), (cx + 14, cy - box[1])], fill=(0, 0, 255), width=4)
draw.line([(cx, cy - box[1] - 14), (cx, cy - box[1] + 14)], fill=(0, 0, 255), width=4)
crop = crop.resize((crop.width, int(crop.height * 2.0)), Image.LANCZOS)
out = os.path.join(d, "step1-target-annotated.png")
crop.save(out)
print("saved", out, crop.size, "-> row band y=423..517, tap (360,470)")
