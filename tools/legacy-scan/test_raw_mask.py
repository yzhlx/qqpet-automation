# Decisive test: with geometry gate relaxed, do the EXISTING templates match the
# dialog digits at >=0.98? Print raw per-group best-match confidence for ground-truth
# values 437/638/663 (balance), 5/10/1 (quantity).
import sys
from PIL import Image
sys.path.insert(0, r"C:\Users\lu158\Desktop\dsh插件\养宠自动化\VirtualDisplay\tools\digit-recon")
from reader_port import load_templates

TPL = r"C:\Users\lu158\Desktop\dsh插件\养宠自动化\VirtualDisplay\app\src\main\assets\pet_health\digits.txt"
templates = load_templates(TPL)

FRAMES = {
    "p1-feed-after balance=437 qty=5": (r"C:\Users\lu158\Desktop\dsh插件\养宠自动化\screenshots\v01\p1-feed-after.png",
                                        (624, 664, 870, 890), (582, 593, 1289, 1308)),
    "soap-price balance=638 qty=10": (r"C:\Users\lu158\Desktop\dsh插件\养宠自动化\VirtualDisplay\deliverables\takeover-runtime\three-course-live\single-regression\direct-one-round\ui-run\supply-course-7\soap-price.png",
                                      (625, 664, 872, 891), (575, 602, 1288, 1308)),
    "cookie-price balance=663 qty=1": (r"C:\Users\lu158\Desktop\dsh插件\养宠自动化\VirtualDisplay\deliverables\takeover-runtime\three-course-live\single-regression\direct-one-round\ui-run\supply-course-7\cookie-price.png",
                                       (625, 664, 870, 890), (582, 593, 1289, 1308)),
}

def match_groups(img, sx0, sx1, sy0, sy1, dark=100):
    """Group columns into digit groups, build 24x32 masks, score vs templates."""
    px = img.load()
    cols = [x for x in range(sx0, sx1 + 1) if any(max(px[x, y]) < dark for y in range(sy0, sy1 + 1))]
    groups = []
    for x in cols:
        if not groups or x > groups[-1][-1] + 1:
            groups.append([])
        groups[-1].append(x)
    out = []
    for xs in groups:
        ys = [y for y in range(sy0, sy1 + 1) if any(max(px[x, y]) < dark for x in xs)]
        l, r, t, b = xs[0], xs[-1], min(ys), max(ys)
        mask = ""
        for yy in range(32):
            for xx in range(24):
                X = l + xx * (r - l + 1) // 24
                Y = t + yy * (b - t + 1) // 32
                mask += "1" if max(px[X, Y]) < dark else "0"
        scores = []
        for d, m in templates:
            diff = sum(1 for i in range(768) if mask[i] != m[i])
            scores.append((1.0 - diff / 768.0, d))
        scores.sort(reverse=True)
        out.append((f"{l}-{r}x{t}-{b}", scores[0], scores[1]))
    return out

for label, (path, bal, qty) in FRAMES.items():
    img = Image.open(path).convert("RGB")
    print(f"=== {label} ===")
    print("  BALANCE digit groups (expect 4,3,7 / 6,3,8 / 6,6,3):")
    for g, best, second in match_groups(img, *bal):
        print(f"    {g}: best={best[1]} conf={best[0]:.4f}  2nd={second[1]} {second[0]:.4f}")
    print("  QUANTITY digit groups:")
    for g, best, second in match_groups(img, *qty):
        print(f"    {g}: best={best[1]} conf={best[0]:.4f}  2nd={second[1]} {second[0]:.4f}")
    print()
