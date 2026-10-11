import io, sys, os
from PIL import Image
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")
BASE = r"C:\Users\lu158\Desktop\dsh插件\养宠自动化"
SC = BASE + r"\VirtualDisplay\deliverables\takeover-runtime\three-course-live\single-regression\direct-one-round\ui-run\supply-course-7"
DARK = 100

# locate balance pill in soap dialog (title-row y855-905 per summary) and food dialog (y245-285)
def scan_dark_cols(fp, x0, y0, x1, y1, tag):
    im = Image.open(fp).convert("RGB"); px = im.load()
    cols = [x for x in range(x0, x1) if any(max(px[x, y]) < DARK for y in range(y0, y1))]
    print(f"{tag}: dark cols {cols[:6]}...{cols[-6:] if len(cols)>6 else ''} n={len(cols)}")
    if cols:
        rows = [y for y in range(y0, y1) if any(max(px[x, y]) < DARK for x in cols)]
        print(f"  rows {rows[0]}-{rows[-1]}")
        for y in range(rows[0], rows[-1] + 1):
            print(f"{y:4d} " + "".join("#" if max(px[x, y]) < DARK else "." for x in range(cols[0], min(cols[-1] + 2, x1))))

scan_dark_cols(SC + r"\soap-price.png", 500, 845, 720, 915, "soap-price balance row")
