import io, sys, os
from PIL import Image
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")
BASE = r"C:\Users\lu158\Desktop\dsh插件\养宠自动化"
SC = BASE + r"\VirtualDisplay\deliverables\takeover-runtime\three-course-live\single-regression\direct-one-round\ui-run\supply-course-7"
FILES = {
    "p1": BASE + r"\screenshots\v01\p1-feed-after.png",
    "ck1": SC + r"\cookie-buy-one-confirm.png",
    "ck4": SC + r"\cookie-buy-four-confirm.png",
    "sp10": SC + r"\soap-price.png",
    "sp8q": SC + r"\resume9-soap-buy-eight-confirm.png",
    "sp7q": SC + r"\resume9-soap-buy-seven-confirm.png",
    "q3": BASE + r"\.foreman\qqpet-three-round-benchmark\live-run-2\topup\live2-q3-175746.png",
    "s23": BASE + r"\.foreman\qqpet-three-round-2" if False else BASE + r"\.foreman\qqpet-three-round-benchmark\live-run-2\topup\live2-s23-183736.png",
}
# ground truth: frame -> (qty_digits, total_digits)  (edit-state frames carry a cursor column)
TRUTH = {
    "p1": ("5", "25"), "ck1": ("1", "5"), "ck4": ("4", "20"), "sp10": ("10", "20"),
    "sp8q": ("1", "2"), "sp7q": ("7", "14"), "q3": ("3", "15"), "s23": ("3", "6"),
}
QTY = (548, 1262, 622, 1318)
PAY = (400, 1370, 545, 1448)   # amount digits right of coin icon
DARK = 100

def strokes(img, box):
    px = img.load()
    x0, y0, x1, y1 = box
    cols = [x for x in range(x0, x1) if any(max(px[x, y]) < DARK for y in range(y0, y1))]
    if not cols:
        return [], []
    rows = [y for y in range(y0, y1) if any(max(px[x, y]) < DARK for x in cols)]
    return cols, rows

def groups(cols):
    out = []
    for x in cols:
        if not out or x > out[-1][-1] + 1:   # adjacent only: a 1-2px cursor gap must split
            out.append([])
        out[-1].append(x)
    return out

def mask_of(img, xs, ys):
    px = img.load()
    l, r, t, b = xs[0], xs[-1], ys[0], ys[-1]
    m = ""
    for yy in range(32):
        for xx in range(24):
            X = l + xx * (r - l + 1) // 24
            Y = t + yy * (b - t + 1) // 32
            m += "1" if max(px[X, Y]) < DARK else "0"
    return m

def sim(a, b):
    return 1 - sum(1 for i in range(768) if a[i] != b[i]) / 768.0

# qty digits per frame
qty_masks = {}   # (frame,idx) -> mask
total_masks = {}
for tag, fp in FILES.items():
    im = Image.open(fp).convert("RGB")
    cols, rows_ = strokes(im, QTY)
    gs = [g for g in groups(cols) if g[-1] - g[0] + 1 >= 4]  # drop cursor (1-2px)
    q_digits = TRUTH[tag][0]
    if len(gs) != len(q_digits):
        print(f"WARN {tag}: qty groups={len(gs)} expect {len(q_digits)}")
    for i, g in enumerate(gs[:len(q_digits)]):
        ys = [y for y in range(QTY[1], QTY[3]) if any(max(im.load()[x, y]) < DARK for x in g)]
        qty_masks[(tag, i)] = mask_of(im, g, ys)
    # pay: white digits on blue -> invert predicate
    px = im.load()
    x0, y0, x1, y1 = PAY
    pcols = [x for x in range(x0, x1) if any(max(px[x, y]) > 200 and px[x, y][2] > 150 and max(px[x, y]) - min(px[x, y]) < 60 for y in range(y0, y1))]
    pgs = [g for g in groups(pcols) if g[-1] - g[0] + 1 >= 4]
    t_digits = TRUTH[tag][1]
    if len(pgs) != len(t_digits):
        print(f"WARN {tag}: pay groups={len(pgs)} expect {len(t_digits)}")
    for i, g in enumerate(pgs[:len(t_digits)]):
        ys = [y for y in range(PAY[1], PAY[3]) if any(max(px[x, y]) > 200 and max(px[x, y]) - min(px[x, y]) < 60 for x in g)]
        total_masks[(tag, i)] = mask_of(px and im, g, ys)  # placeholder, replaced below

# NOTE: mask_of uses dark predicate; for pay digits (white on blue) we need bright predicate.
def mask_bright(img, xs, ys):
    px = img.load()
    l, r, t, b = xs[0], xs[-1], ys[0], ys[-1]
    m = ""
    for yy in range(32):
        for xx in range(24):
            X = l + xx * (r - l + 1) // 24
            Y = t + yy * (b - t + 1) / 32
            Y = int(t + yy * (b - t + 1) // 32)
            p = px[X, Y]
            m += "1" if (p[0] > 200 and p[1] > 200 and p[2] > 200) else "0"
    return m

for tag, fp in FILES.items():
    im = Image.open(fp).convert("RGB")
    px = im.load()
    x0, y0, x1, y1 = PAY
    pcols = [x for x in range(x0, x1) if any(px[x, y][0] > 200 and px[x, y][1] > 200 and px[x, y][2] > 200 for y in range(y0, y1))]
    pgs = [g for g in groups(pcols) if g[-1] - g[0] + 1 >= 4]
    t_digits = TRUTH[tag][1]
    if len(pgs) == len(t_digits):
        for i, g in enumerate(pgs):
            ys = [y for y in range(y0, y1) if any(px[x, y][0] > 200 and px[x, y][1] > 200 and px[x, y][2] > 200 for x in g)]
            total_masks[(tag, i)] = mask_bright(im, g, ys)

# cross-validate: every mask of digit d must match every other mask of d at >=0.95,
# and mismatch digits: report best confusion
from collections import defaultdict
buckets = defaultdict(list)
for (tag, i), m in qty_masks.items():
    buckets[TRUTH[tag][0][i]].append(("qty", tag, m))
for (tag, i), m in total_masks.items():
    buckets[TRUTH[tag][1][i]].append(("pay", tag, m))

print("digit coverage:", sorted(buckets.keys()))
confusions = []
for d, ms in sorted(buckets.items()):
    print(f"digit {d}: {len(ms)} samples")
    for a in range(len(ms)):
        for b in range(a + 1, len(ms)):
            s = sim(ms[a][2], ms[b][2])
            if s < 0.95:
                confusions.append((d, ms[a][:2], ms[b][:2], round(s, 4)))
for d, a, b, s in confusions:
    print(f"  LOW-SIM digit {d}: {a} vs {b} sim={s}")
# confusion across different digits
worst = []
ds = sorted(buckets)
for i, d1 in enumerate(ds):
    for d2 in ds[i+1:]:
        best = max((sim(a[2], b[2]) for a in buckets[d1] for b in buckets[d2]), default=0)
        worst.append((round(best, 4), d1, d2))
worst.sort(reverse=True)
print("cross-digit worst:", worst[:8])
