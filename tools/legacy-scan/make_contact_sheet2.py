import io, sys, os
from PIL import Image, ImageDraw
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")
BASE = r"C:\Users\lu158\Desktop\dsh插件\养宠自动化"
FRAMES = [
    ("p1-qty5", BASE + r"\screenshots\v01\p1-feed-after.png"),
    ("ck1", BASE + r"\VirtualDisplay\deliverables\takeover-runtime\three-course-live\single-regression\direct-one-round\ui-run\supply-course-7\cookie-buy-one-confirm.png"),
    ("ck4", BASE + r"\VirtualDisplay\deliverables\takeover-runtime\three-course-live\single-regression\direct-one-round\ui-run\supply-course-7\cookie-buy-four-confirm.png"),
    ("sp10", BASE + r"\VirtualDisplay\deliverables\takeover-runtime\three-course-live\single-regression\direct-one-round\ui-run\supply-course-7\soap-price.png"),
    ("sp8q", BASE + r"\VirtualDisplay\deliverables\takeover-runtime\three-course-live\single-regression\direct-one-round\ui-run\supply-course-7\resume9-soap-buy-eight-confirm.png"),
    ("sp7q", BASE + r"\VirtualDisplay\deliverables\takeover-runtime\three-course-live\single-regression\direct-one-round\ui-run\supply-course-7\resume9-soap-buy-seven-confirm.png"),
    ("q3", BASE + r"\.foreman\qqpet-three-round-benchmark\live-run-2\topup\live2-q3-175746.png"),
    ("s23", BASE + r"\.foreman\qqpet-three-round-benchmark\live-run-2\topup\live2-s23-183736.png"),
]
OUT = BASE + r"\VirtualDisplay\work\dialog-rois"
QTY = (548, 1262, 622, 1318)
PAY = (355, 1368, 545, 1450)
rows = []
for tag, fp in FRAMES:
    im = Image.open(fp).convert("RGB")
    q = im.crop(QTY); p = im.crop(PAY)
    q = q.resize((q.width * 5, q.height * 5), Image.NEAREST)
    p = p.resize((p.width * 3, p.height * 3), Image.NEAREST)
    col = Image.new("RGB", (max(q.width, p.width) + 8, q.height + p.height + 70), (40, 40, 40))
    d = ImageDraw.Draw(col)
    d.text((6, 2), tag + " QTY(548,1262,622,1318)", fill=(255, 255, 0))
    col.paste(q, (4, 18))
    d.text((6, q.height + 22), tag + " PAY(355,1368,545,1450)", fill=(0, 255, 255))
    col.paste(p, (4, q.height + 40))
    rows.append(col)
W = max(c.width for c in rows) + 8
H = sum(c.height for c in rows) + 8 * len(rows)
sheet = Image.new("RGB", (W, H), (40, 40, 40))
y = 0
for c in rows:
    sheet.paste(c, (4, y)); y += c.height + 8
sheet.save(OUT + r"\contact-sheet2.png")
print("saved", sheet.size)
