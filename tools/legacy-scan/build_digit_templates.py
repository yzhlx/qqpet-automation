import io, sys, os, json
from PIL import Image
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")
BASE = r"C:\Users\lu158\Desktop\dsh插件\养宠自动化"
SC = BASE + r"\VirtualDisplay\deliverables\takeover-runtime\three-course-live\single-regression\direct-one-round\ui-run\supply-course-7"
DARK = 100
QTY = (548, 1262, 622, 1318)
PAY = (400, 1370, 545, 1448)

def groups(cols):
    out = []
    for x in cols:
        if not out or x > out[-1][-1] + 1:
            out.append([])
        out[-1].append(x)
    return out

def group_dark(im, box, g):
    px = im.load(); x0, y0, x1, y1 = box
    xs = [x for x in range(g[0], g[-1] + 1)]
    ys = [y for y in range(y0, y1) if any(max(px[x, y]) < DARK for x in xs)]
    return xs, ys

def mask(im, xs, ys):
    px = im.load(); l, r, t, b = xs[0], xs[-1], ys[0], ys[-1]
    return "".join("1" if max(px[l + xx * (r - l + 1) // 24, t + yy * (b - t + 1) // 32]) < DARK else "0"
                   for yy in range(32) for xx in range(24))

def mask_bright(im, xs, ys):
    px = im.load(); l, r, t, b = xs[0], xs[-1], ys[0], ys[-1]
    return "".join("1" if all(c > 200 for c in px[l + xx * (r - l + 1) // 24, t + yy * (b - t + 1) // 32]) else "0"
                   for yy in range(32) for xx in range(24))

def sim(a, b):
    return 1 - sum(1 for i in range(768) if a[i] != b[i]) / 768.0

TRUTH = {"p1": ("5", "25"), "ck1": ("1", "5"), "ck4": ("4", "20"), "sp10": ("10", "20"),
         "sp8q": ("1", "2"), "sp7q": ("7", "14"), "q3": ("3", "15"), "s23": ("3", "6")}
FILES = {
    "p1": BASE + r"\screenshots\v01\p1-feed-after.png",
    "ck1": SC + r"\cookie-buy-one-confirm.png",
    "ck4": SC + r"\cookie-buy-four-confirm.png",
    "sp10": SC + r"\soap-price.png",
    "sp8q": SC + r"\resume9-soap-buy-eight-confirm.png",
    "sp7q": SC + r"\resume9-soap-buy-seven-confirm.png",
    "q3": BASE + r"\.foreman\qqpet-three-round-benchmark\live-run-2\topup\live2-q3-175746.png",
    "s23": BASE + r"\.foreman\qqpet-three-round-benchmark\live-run-2\topup\live2-s23-183736.png",
}

qty_bank, pay_bank = {}, {}   # digit -> list of masks
for tag, fp in FILES.items():
    im = Image.open(fp).convert("RGB"); px = im.load()
    # qty (dark on light)
    cols = [x for x in range(QTY[0], QTY[2]) if any(max(px[x, y]) < DARK for y in range(QTY[1], QTY[3]))]
    gs = [g for g in groups(cols) if g[-1] - g[0] + 1 >= 4]
    q = TRUTH[tag][0]
    assert len(gs) == len(q), f"{tag} qty {len(gs)} vs {q}"
    for d, g in zip(q, gs):
        xs, ys = group_dark(im, QTY, g)
        qty_bank.setdefault(d, []).append(mask(im, xs, ys))
    # pay (bright on blue)
    cols = [x for x in range(PAY[0], PAY[2]) if any(all(c > 200 for c in px[x, y]) for y in range(PAY[1], PAY[3]))]
    gs = [g for g in groups(cols) if g[-1] - g[0] + 1 >= 4]
    t = TRUTH[tag][1]
    assert len(gs) == len(t), f"{tag} pay {len(gs)} vs {t}"
    for d, g in zip(t, gs):
        xs = [x for x in range(g[0], g[-1] + 1)]
        ys = [y for y in range(PAY[1], PAY[3]) if any(all(c > 200 for c in px[x, y]) for x in xs)]
        pay_bank.setdefault(d, []).append(mask_bright(im, xs, ys))

# balance 638 from soap-price (dark on light, same font as qty)
im = Image.open(SC + r"\soap-price.png").convert("RGB"); px = im.load()
BAL = (620, 868, 675, 896)
cols = [x for x in range(BAL[0], BAL[2]) if any(max(px[x, y]) < DARK for y in range(BAL[1], BAL[3]))]
gs = [g for g in groups(cols) if g[-1] - g[0] + 1 >= 4]
assert len(gs) == 3, gs
for d, g in zip("638", gs):
    xs, ys = group_dark(im, BAL, g)
    qty_bank.setdefault(d, []).append(mask(im, xs, ys))

print("qty digits:", {d: len(m) for d, m in sorted(qty_bank.items())})
print("pay digits:", {d: len(m) for d, m in sorted(pay_bank.items())})

# intra-context consistency check
def check(bank, name):
    for d, ms in sorted(bank.items()):
        for i in range(len(ms)):
            for j in range(i + 1, len(ms)):
                s = sim(ms[i], ms[j])
                if s < 0.93:
                    print(f"  {name} intra {d}: {i}vs{j} sim={s:.4f}")
check(qty_bank, "qty"); check(pay_bank, "pay")

# build template files: pick medoid per digit (mask with max min-sim to others in bank)
def medoid(ms):
    if len(ms) == 1: return ms[0]
    return max(range(len(ms)), key=lambda i: min(sim(ms[i], ms[j]) for j in range(len(ms)) if j != i))
# emit ALL samples as templates (multi-template per digit, reader takes best score)
out = {"qty": {}, "pay": {}}
for d in sorted(qty_bank):
    out["qty"][d] = qty_bank[d]
for d in sorted(pay_bank):
    out["pay"][d] = pay_bank[d]

ASSETS = BASE + r"\VirtualDisplay\app\src\main\assets\pet_exchange"
os.makedirs(ASSETS, exist_ok=True)
with open(ASSETS + r"\qty_digits.txt", "w") as f:
    for d, ms in out["qty"].items():
        for m in ms:
            f.write(f"{d} {m}\n")
with open(ASSETS + r"\pay_digits.txt", "w") as f:
    for d, ms in out["pay"].items():
        for m in ms:
            f.write(f"{d} {m}\n")

# full replay self-check: every frame re-read with reader algorithm vs TRUTH
def read_digits(im, box, bright, templates):
    px = im.load(); x0, y0, x1, y1 = box
    def ink(x, y):
        p = px[x, y]
        return all(c > 200 for c in p) if bright else max(p) < DARK
    cols = [x for x in range(x0, x1) if any(ink(x, y) for y in range(y0, y1))]
    gs = [g for g in groups(cols) if g[-1] - g[0] + 1 >= 4]
    if not gs: return None, "EMPTY"
    text = ""; conf = 1.0
    for g in gs:
        xs = [x for x in range(g[0], g[-1] + 1)]
        ys = [y for y in range(y0, y1) if any(ink(x, y) for x in xs)]
        m = (mask_bright if bright else mask)(im, xs, ys)
        scored = sorted(((max(1 - sum(1 for i in range(768) if m[i] != tm[i]) / 768.0 for tm in tms), d)
                         for d, tms in templates.items()), reverse=True)
        if scored[0][0] < 0.90 or scored[0][1] is None: return None, f"LOW:{scored[0][0]:.3f}"
        if len(scored) > 1 and scored[0][0] - scored[1][0] < 0.06: return None, f"AMBIG:{scored[0][1]}~{scored[1][1]}"
        conf = min(conf, scored[0][0]); text += scored[0][1]
    return (text, conf)

fails = 0
for tag, fp in FILES.items():
    im = Image.open(fp).convert("RGB")
    qt, qc = read_digits(im, QTY, False, out["qty"])
    pt, pc = read_digits(im, PAY, True, out["pay"])
    ok = qt == TRUTH[tag][0] and pt == TRUTH[tag][1]
    if not ok: fails += 1
    print(f"{tag}: qty={qt}({qc}) total={pt}({pc}) expect={TRUTH[tag]} {'OK' if ok else 'FAIL'}")
# balance 638 replay
im = Image.open(SC + r"\soap-price.png").convert("RGB")
bt, bc = read_digits(im, BAL, False, out["qty"])
print(f"balance638: {bt} ({bc}) {'OK' if bt == '638' else 'FAIL'}")
print("fails:", fails)
# missing digits report
print("missing qty digits:", sorted(set("0123456789") - set(out["qty"])))
print("missing pay digits:", sorted(set("0123456789") - set(out["pay"])))
