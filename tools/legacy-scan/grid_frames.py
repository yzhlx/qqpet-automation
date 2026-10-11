import io, sys, os
from PIL import Image, ImageDraw
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")
BASE = r"C:\Users\lu158\Desktop\dsh插件\养宠自动化"
SC = BASE + r"\VirtualDisplay\deliverables\takeover-runtime\three-course-live\single-regression\direct-one-round\ui-run\supply-course-7"
im = Image.open(SC + r"\cookie-buy-one-confirm.png").convert("RGB")
im2 = im.resize((360, 800))
d = ImageDraw.Draw(im2)
for x in range(0, 360, 40):
    d.line([(x, 0), (x, 800)], fill=(255, 0, 0), width=1)
    d.text((x + 2, 2), str(x * 2), fill=(255, 0, 0))
for y in range(0, 800, 40):
    d.line([(0, y), (360, y)], fill=(255, 0, 0), width=1)
    d.text((2, y + 2), str(y * 2), fill=(255, 0, 0))
im2.save(BASE + r"\VirtualDisplay\work\dialog-rois\food-dialog-grid.png")
im3 = Image.open(SC + r"\soap-price.png").convert("RGB").resize((360, 800))
d = ImageDraw.Draw(im3)
for x in range(0, 360, 40):
    d.line([(x, 0), (x, 800)], fill=(255, 0, 0), width=1)
    d.text((x + 2, 2), str(x * 2), fill=(255, 0, 0))
for y in range(0, 800, 40):
    d.line([(0, y), (360, y)], fill=(255, 0, 0), width=1)
    d.text((2, y + 2), str(y * 2), fill=(255, 0, 0))
im3.save(BASE + r"\VirtualDisplay\work\dialog-rois\soap-dialog-grid.png")
print("ok")
