import os
from PIL import Image

d = os.path.join("evidence", "slice2", "t3-precondition-r2")
im = Image.open(os.path.join(d, "step1-after-20261007-132047.png")).convert("RGB")
for name, box, scale in [
    ("zoom-topbar.png", (0, 90, 720, 250), 2),
    ("zoom-bottom.png", (0, 1280, 720, 1520), 2),
    ("zoom-bottom2.png", (0, 1500, 720, 1600), 2),
]:
    c = im.crop(box)
    c = c.resize((c.width * scale, c.height * scale), Image.LANCZOS)
    c.save(os.path.join(d, name))
    print("saved", name, c.size, "box", box)
