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
        if not out or x > out[-1][-1] + 2:
            out.append([])
        out[-1].append(x)
    return out

def show(fp, tag):
    im = Image.open(fp).convert("RGB"); px = im.load()
    cols = [x for x in range(QTY[0], QTY[2]) if any(max(px[x, y]) < DARK for y in range(QTY[1], QTY[3]))]
    gs = groups(cols)
    print(f"== {tag}: groups={[(g[0], g[-1]) for g in gs]}")
    # render each group's rows
    rows = [y for y in range(QTY[1], QTY[3]) if any(max(px[x, y]) < DARK for x in cols)]
    print("rows:", rows[0], "-", rows[-1])
    for y in range(rows[0], rows[-1] + 1, 1):
        line = ""
        for x in range(QTY[0], QTY[2]):
            line += "#" if max(px[x, y]) < DARK else "."
        print(f"{y:4d} {line}")

show(BASE + r"\.foreman\qqpet-three-round-benchmark\live-run-2\topup\live2-q3-175746.png", "q3")
show(BASE + r"\.foreman\qqpet-three-round-benchmark\live-run-2\topup\live2-s23-183736.png", "s23")
