import os
from PIL import Image

d = os.path.join("evidence", "slice2", "t3-precondition-r2")
im = Image.open(os.path.join(d, "step1-after-20261007-132047.png")).convert("RGB")
for name, box in [
    ("zoom-card1.png", (110, 270, 620, 560)),
    ("zoom-card1-footer.png", (110, 480, 620, 560)),
]:
    c = im.crop(box)
    c = c.resize((c.width * 2, c.height * 2), Image.LANCZOS)
    c.save(os.path.join(d, name))
    print("saved", name, c.size)
