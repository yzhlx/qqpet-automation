import io, sys, os
from PIL import Image, ImageDraw
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")
BASE = r"C:\Users\lu158\Desktop\dsh插件\养宠自动化"
FRAMES = [
    ("p1", BASE + r"\screenshots\v01\p1-feed-after.png", "expect qty=5 total=25"),
    ("ck1", BASE + r"\VirtualDisplay\deliverables\takeover-runtime\three-course-live\single-regression\direct-one-round\ui-run\supply-course-7\cookie-price.png", "expect qty=1 total=5"),
    ("sp10", BASE + r"\VirtualDisplay\deliverables\takeover-runtime\three-course-live\single-regression\direct-one-round\ui-run\supply-course-7\soap-price.png", "expect qty=10 total=20"),
    ("ck4", BASE + r"\VirtualDisplay\deliverables\takeover-runtime\three-course-live\single-regression\direct-one-round\ui-run\supply-course-7\cookie-buy-four-confirm.png", "expect qty=4 total=20"),
    ("sp8", BASE + r"\VirtualDisplay\deliverables\takeover-runtime\three-course-live\single-regression\direct-one-round\ui-run\supply-course-7\resume9-soap-buy-eight-confirm.png", "expect qty=8 total=16"),
    ("sp7", BASE + r"\VirtualDisplay\deliverables\takeover-runtime\three-course-live\single-regression\direct-one-round\ui-run\supply-course-7\resume9-soap-buy-seven-confirm.png", "expect qty=7 total=14"),
    ("q3", BASE + r"\.foreman\qqpet-three-round-benchmark\live-run-2\topup\live2-q3-175746.png", "expect qty=3 total=15"),
    ("s23", BASE + r"\.foreman\qqpet-three-round-benchmark\live-run-2\topup\live2-s23-183736.png", "expect qty=3 total=6"),
]
OUT = BASE + r"\VirtualDisplay\work\dialog-rois"
os.makedirs(OUT, exist_ok=True)
QTY = (530, 1268, 625, 1318)     # generous qty ROI
PAY = (290, 1368, 450, 1450)     # generous pay-button ROI
rows_q, rows_p = [], []
for tag, fp, note in FRAMES:
    im = Image.open(fp).convert("RGB")
    q = im.crop(QTY); p = im.crop(PAY)
    q = q.resize((q.width * 4, q.height * 4), Image.NEAREST)
    p = p.resize((p.width * 4, p.height * 4), Image.NEAREST)
    lab = Image.new("RGB", (q.width, 26), (255, 255, 0))
    d = ImageDraw.Draw(lab); d.text((4, 4), f"{tag} {note}", fill=(0, 0, 0))
    col = Image.new("RGB", (q.width, q.height + p.height + 56), (255, 255, 255))
    col.paste(lab, (0, 0)); col.paste(q, (0, 26))
    lab2 = Image.new("RGB", (p.width, 26), (0, 255, 255))
    d2 = ImageDraw.Draw(lab2); d2.text((4, 4), tag, fill=(0, 0, 0))
    col.paste(lab2, (0, q.height + 30)); col.paste(p, (0, q.height + 56))
    rows_q.append(col)
W = max(c.width for c in rows_q) + 8
H = sum(c.height for c in rows_q) + 8 * len(rows_q)
sheet = Image.new("RGB", (W, H), (40, 40, 40))
y = 0
for c in rows_q:
    sheet.paste(c, (4, y)); y += c.height + 8
sheet.save(OUT + r"\contact-sheet.png")
print("saved", sheet.size, "->", OUT + r"\contact-sheet.png")
