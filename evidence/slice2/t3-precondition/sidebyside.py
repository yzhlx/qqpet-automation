import os
from PIL import Image

d = os.path.join("evidence", "slice2", "t3-precondition")
b = Image.open(os.path.join(d, "before-20261007-125532.png")).convert("RGB")
a = Image.open(os.path.join(d, "after-2-settled.png")).convert("RGB")
box = (420, 1360, 550, 1510)
W = (box[2] - box[0]) * 3
H = (box[3] - box[1]) * 3
lb = b.crop(box).resize((W, H), Image.LANCZOS)
la = a.crop(box).resize((W, H), Image.LANCZOS)
canvas = Image.new("RGB", (W * 2 + 12, H), (255, 255, 255))
canvas.paste(lb, (0, 0))
canvas.paste(la, (W + 12, 0))
out = os.path.join(d, "entry-tap-before-vs-after.png")
canvas.save(out)
print("saved", out, canvas.size)
