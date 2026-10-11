import os
from PIL import Image

d = os.path.join("evidence", "slice2", "t3-precondition-r2")
im = Image.open(os.path.join(d, "step1-after-20261007-132047.png")).convert("RGB")
# cards: LinearLayout [122,280][590,541], [122,680][590,941], [122,1080][590,1341]
for i, (y0, y1) in enumerate([(270, 550), (670, 950), (1070, 1350)], start=1):
    box = (100, y0, 620, y1)
    c = im.crop(box).resize(((620 - 100) * 2, (y1 - y0) * 2), Image.LANCZOS)
    name = "zoom-card%d-full.png" % i
    c.save(os.path.join(d, name))
    print("saved", name, c.size, "box", box)
