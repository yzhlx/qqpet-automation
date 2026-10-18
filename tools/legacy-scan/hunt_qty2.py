import io, sys, os
from PIL import Image
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")
BASE = r"C:\Users\lu158\Desktop\dsh插件\养宠自动化"
SC = BASE + r"\VirtualDisplay\deliverables\takeover-runtime\three-course-live\single-regression\direct-one-round\ui-run\supply-course-7"
DARK = 100
QTY = (548, 1262, 622, 1318)

def groups(cols):
    out = []
    for x in cols:
        if not out or x > out[-1][-1] + 1:
            out.append([])
        out[-1].append(x)
    return out

CANDS = ["cookie-next-quantity.png", "resume9-soap-quantity-edit.png", "resume9-soap-next-confirm.png",
         "resume9-soap-price-current.png", "soap-buy-one-confirm.png", "cookie-price.png",
         "resume8-soap-current.png", "soap-before.png"]
for name in CANDS:
    fp = os.path.join(SC, name)
    im = Image.open(fp).convert("RGB"); px = im.load()
    cols = [x for x in range(QTY[0], QTY[2]) if any(max(px[x, y]) < DARK for y in range(QTY[1], QTY[3]))]
    gs = [g for g in groups(cols) if g[-1] - g[0] + 1 >= 4]
    # render
    if not gs:
        print(f"{name}: no qty ink"); continue
    allrows = [y for y in range(QTY[1], QTY[3]) if any(max(px[x, y]) < DARK for x in range(QTY[0], QTY[2]))]
    print(f"== {name}: {len(gs)} groups, rows {allrows[0]}-{allrows[-1]}")
    for y in range(allrows[0], allrows[-1] + 1):
        print(f"{y:4d} " + "".join("#" if max(px[x, y]) < DARK else "." for x in range(QTY[0], QTY[2])))
